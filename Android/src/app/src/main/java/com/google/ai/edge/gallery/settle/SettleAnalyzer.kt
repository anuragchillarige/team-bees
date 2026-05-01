/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "SettleAnalyzer"

/**
 * Wraps the LiteRT-LM Engine + Conversation lifecycle for Settle. The public surface mirrors the
 * design in PROMPT.md but the underlying API is callback-based (MessageCallback), so
 * [classifyBlocks] suspends inside [suspendCancellableCoroutine] until the LLM signals onDone.
 *
 * Any exception path falls back to [KeywordClassifier] so the UI never sees an empty result.
 */
@OptIn(ExperimentalApi::class)
class SettleAnalyzer(private val context: Context) {
  private var engine: Engine? = null
  private var conversation: Conversation? = null
  // The clause id that owns the current conversation's history (set by askFollowUp). Cleared on
  // any reset (classification or first-turn follow-up). When a follow-up arrives whose clauseId
  // matches this value, we reuse the conversation so the model can build on prior turns; otherwise
  // we reset and re-inject the clause context as the first user message.
  private var followUpClauseId: Int? = null

  suspend fun initialize(modelPath: String) =
    withContext(Dispatchers.IO) {
      if (engine != null) return@withContext
      val cfg =
        EngineConfig(
          modelPath = modelPath,
          backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
          visionBackend = null,
          audioBackend = null,
          maxNumTokens = 4096,
          cacheDir = context.getExternalFilesDir(null)?.absolutePath,
        )
      val e = Engine(cfg)
      e.initialize()
      val conv =
        e.createConversation(
          ConversationConfig(
            samplerConfig = null, // NPU backend ignores sampler params
            systemInstruction = null,
            tools = emptyList(),
          )
        )
      engine = e
      conversation = conv
      Log.i(TAG, "Engine initialized with modelPath=$modelPath")
    }

  suspend fun classifyBlocks(blocks: List<SettleTextBlock>): List<ClauseResult> {
    if (blocks.isEmpty()) return emptyList()
    val eng = engine ?: return KeywordClassifier.classify(blocks)
    // Each scan starts a brand-new conversation so the model can't be biased by the JSON it
    // returned for the previous document — that residual chat state was causing the LLM to echo
    // the new clauses verbatim on the second and later scans.
    val conv =
      try {
        resetConversation(eng)
      } catch (e: Exception) {
        Log.e(TAG, "resetConversation failed", e)
        return KeywordClassifier.classify(blocks)
      }
    val prompt = buildPrompt(blocks)
    return try {
      val raw = sendAndCollect(conv, prompt)
      Log.d(TAG, "LLM raw response: $raw")
      parseResponse(raw, blocks)
    } catch (e: Exception) {
      Log.e(TAG, "LLM classification failed", e)
      KeywordClassifier.classify(blocks)
    }
  }

  private suspend fun resetConversation(eng: Engine): Conversation =
    withContext(Dispatchers.IO) {
      // Cancel any in-flight inference first so close() doesn't race with active streaming.
      try {
        conversation?.cancelProcess()
      } catch (e: Throwable) {
        Log.w(TAG, "old conversation cancelProcess failed", e)
      }
      try {
        conversation?.close()
      } catch (e: Throwable) {
        Log.w(TAG, "old conversation close failed", e)
      }
      val fresh =
        eng.createConversation(
          ConversationConfig(
            samplerConfig = null,
            systemInstruction = null,
            tools = emptyList(),
          )
        )
      conversation = fresh
      // Whatever owned the previous conversation no longer does. The next follow-up will need
      // to re-inject clause context.
      followUpClauseId = null
      fresh
    }

  private suspend fun sendAndCollect(conv: Conversation, prompt: String): String =
    suspendCancellableCoroutine { continuation ->
      val buffer = StringBuilder()
      val callback =
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            // Mirror the helper: skip control tokens that aren't meaningful text.
            if (!text.startsWith("<ctrl")) buffer.append(text)
          }

          override fun onDone() {
            if (continuation.isActive) continuation.resume(buffer.toString())
          }

          override fun onError(throwable: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(throwable))
          }
        }
      try {
        conv.sendMessageAsync(
          Contents.of(listOf(Content.Text(prompt))),
          callback,
          emptyMap(),
        )
      } catch (e: Throwable) {
        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
      }
      continuation.invokeOnCancellation {
        try {
          conv.cancelProcess()
        } catch (e: Throwable) {
          Log.w(TAG, "cancelProcess threw", e)
        }
      }
    }

  private fun buildPrompt(blocks: List<SettleTextBlock>): String {
    // Pick the 25 largest blocks (by bounding-box area) to protect the KV cache. Larger blocks
    // are far more likely to contain substantive prose; small blocks tend to be page numbers,
    // section labels, or trailing fragments that survived the OCR-side heading filter.
    val capped =
      blocks.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(25)
    val numbered = capped.joinToString("\n") { "[${it.id}] ${it.text.replace("\n", " ").take(300)}" }
    return """
You are a careful legal-risk advisor. For each numbered clause, output an object with: id, risk, plain, why.

ABSOLUTE RULE FOR "plain":
The "plain" value MUST be your own paraphrase in everyday English. NEVER copy, quote, or echo the clause's wording. NEVER lightly reword it. If your "plain" sentence shares more than a couple of consecutive words with the original clause, you are violating this rule and must rewrite. Speak directly to the reader ("you ...") about what the clause actually does to them.

WRONG (echoes the clause): clause = "Tenant agrees to binding arbitration and waives the right to a jury trial."  →  plain = "Tenant agrees to binding arbitration and waives the right to a jury trial."
WRONG (light reword):       clause = "Tenant agrees to binding arbitration and waives the right to a jury trial."  →  plain = "The tenant agrees to arbitration and waives jury trial rights."
RIGHT (true paraphrase):    clause = "Tenant agrees to binding arbitration and waives the right to a jury trial."  →  plain = "You give up the right to take any dispute to court and must instead use a private arbitrator."

Risk levels — judge in CONTEXT, keyword presence alone is NOT enough. Default to "green" unless the clause genuinely shifts rights, money, or obligations against the reader.
- "red": materially harms the reader. Examples: mandatory binding arbitration combined with class-action waiver; perpetual or irrevocable license to reader's content; automatic renewal at a higher rate without clear opt-out; one-sided indemnification of the other party including their own negligence; broad release of liability; large or unusual fees and penalties.
- "yellow": worth noticing but normal in scope. Examples: standard security deposit, written-notice requirements, late fees in normal range, ordinary termination conditions, capped liability limits.
- "green": routine boilerplate (definitions, governing law, severability) OR clauses mentioning risky-sounding words that do not actually impose risk. Distinguish: "the parties may agree to arbitration" is GREEN; "binding arbitration is required" is RED.

OUTPUT RULE (token-budget optimization — remove these two lines to re-enable green entries):
OMIT any clause you would have classified as "green" from the JSON output. Only emit entries for "red" and "yellow". If nothing is red or yellow, return {"clauses":[]}.

For "why": one sentence of ACTIONABLE guidance — what specifically should the reader check, ask, negotiate, or watch out for. For green clauses, write "Standard term, no action needed."

Respond with ONLY valid JSON. No preamble, no markdown, no code fences. Schema:
{"clauses":[{"id":<int>,"risk":"red|yellow|green","plain":"<your paraphrase, never the original wording>","why":"<one sentence>"}]}

Example input:
[1] Tenant agrees to binding arbitration and waives the right to a jury trial for any dispute arising under this lease.
[2] Rent is due on the first of each month.
[3] The Landlord may, at its discretion, choose to mediate disputes informally before filing suit.
[4] Tenant agrees to indemnify and hold Landlord harmless from any claim, including those arising from Landlord's own negligence.

Example output:
{"clauses":[{"id":1,"risk":"red","plain":"You give up the right to take any dispute to court and must instead use a private arbitrator.","why":"Ask whether arbitration is opt-in or mandatory, who selects the arbitrator, and who pays the fees — these terms are often negotiable before signing."},{"id":2,"risk":"green","plain":"Your rent payment is owed at the start of every month.","why":"Standard term, no action needed."},{"id":3,"risk":"green","plain":"The landlord can choose to try mediation first but does not have to.","why":"Standard term, no action needed."},{"id":4,"risk":"red","plain":"You promise to pay the landlord's legal costs and damages, even when the landlord caused the problem.","why":"Push back on indemnifying the other party for their own negligence — most jurisdictions treat this as overreach and it can be removed."}]}

Now classify these clauses:
$numbered
""".trimIndent()
  }

  private fun parseResponse(raw: String, blocks: List<SettleTextBlock>): List<ClauseResult> {
    // Try strict JSON first (cleanest path when the model emits well-formed output).
    val strict =
      try {
        val json = extractFirstJsonObject(raw) ?: ""
        if (json.isNotEmpty()) {
          val arr = JSONObject(json).getJSONArray("clauses")
          val out = mutableListOf<ClauseResult>()
          for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            out += clauseFrom(item.getInt("id"), item.optString("risk"), item.optString("plain"), item.optString("why"))
          }
          out
        } else emptyList()
      } catch (e: Exception) {
        Log.w(TAG, "Strict JSON parse failed, will try lenient extractor", e)
        emptyList<ClauseResult>()
      }

    // Lenient pass: pull each clause via field-marker regex. Gemma frequently emits unescaped
    // double quotes inside string values (e.g. ourselves as "we" or "our") which org.json rejects
    // even though the per-clause shape is recognizable. Anchor on field names rather than treating
    // double quotes as string boundaries.
    val lenient = if (strict.isEmpty()) extractClausesLenient(raw) else strict

    // Token-budget optimization: the prompt instructs the LLM to OMIT green clauses, but it
    // sometimes emits them anyway. Drop green / unknown so the UI only highlights red/yellow.
    val filtered = lenient.filter { it.risk == Risk.RED || it.risk == Risk.YELLOW }

    // If we still have nothing the model said anything about, fall back so the user sees *some*
    // signal instead of an unannotated page.
    if (filtered.isEmpty() && lenient.isEmpty()) {
      Log.e(TAG, "Parse produced no clauses, falling back to keyword classifier. Raw: $raw")
      return KeywordClassifier.classify(blocks)
    }
    return filtered
  }

  private fun clauseFrom(id: Int, risk: String, plain: String, why: String): ClauseResult =
    ClauseResult(
      id = id,
      risk =
        when (risk.lowercase().trim()) {
          "red" -> Risk.RED
          "yellow" -> Risk.YELLOW
          "green" -> Risk.GREEN
          else -> Risk.UNKNOWN
        },
      plain = plain,
      why = why,
    )

  /**
   * Hand-rolled lenient extractor: anchor on `"id":N`, `"risk":"…"`, `"plain":"…` and `"why":"…`
   * markers and slice values between them. Tolerates unescaped double quotes inside the plain/why
   * field values (a common Gemma quirk — e.g. plain text containing "we" or "our") that strict
   * JSON would reject.
   */
  private fun extractClausesLenient(raw: String): List<ClauseResult> {
    val out = mutableListOf<ClauseResult>()
    val idMatches = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(raw).toList()
    for (i in idMatches.indices) {
      val start = idMatches[i].range.first
      val end = if (i + 1 < idMatches.size) idMatches[i + 1].range.first else raw.length
      val chunk = raw.substring(start, end)
      val id = idMatches[i].groupValues[1].toIntOrNull() ?: continue
      val risk =
        Regex("\"risk\"\\s*:\\s*\"(red|yellow|green)\"", RegexOption.IGNORE_CASE)
          .find(chunk)
          ?.groupValues
          ?.getOrNull(1) ?: ""
      val plain = sliceValue(chunk, "\"plain\":\"", listOf("\",\"why\"", "\",\"id\"", "\"}")) ?: ""
      val why = sliceValue(chunk, "\"why\":\"", listOf("\"}", "\",\"id\"")) ?: ""
      out += clauseFrom(id, risk, plain, why)
    }
    return out
  }

  private fun sliceValue(chunk: String, startMarker: String, endMarkers: List<String>): String? {
    val s = chunk.indexOf(startMarker)
    if (s == -1) return null
    val valueStart = s + startMarker.length
    val nearestEnd =
      endMarkers.mapNotNull { m -> chunk.indexOf(m, valueStart).takeIf { it >= 0 } }.minOrNull()
        ?: return chunk.substring(valueStart)
    return chunk.substring(valueStart, nearestEnd)
  }

  /**
   * Walk balanced braces from the first `{` so we ignore any text the LLM emits past the closing
   * brace (extra `]}`, repeated objects, control tokens, etc.). Tracks string state so braces
   * inside JSON strings don't confuse the depth counter.
   */
  private fun extractFirstJsonObject(s: String): String? {
    val startIdx = s.indexOf('{')
    if (startIdx == -1) return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in startIdx until s.length) {
      val c = s[i]
      if (escape) {
        escape = false
        continue
      }
      if (inString) {
        when (c) {
          '\\' -> escape = true
          '"' -> inString = false
        }
        continue
      }
      when (c) {
        '"' -> inString = true
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return s.substring(startIdx, i + 1)
        }
      }
    }
    return null
  }

  /**
   * Stream a free-text answer to a follow-up question grounded in [clauseText]. Each call creates
   * its own [Conversation] so questions don't bleed context between clauses, and the
   * conversation is closed after the answer completes (or fails). The send button in the UI must
   * stay disabled while a stream is in flight — only one inference at a time.
   */
  fun askFollowUp(clauseId: Int, clauseText: String, question: String): Flow<String> =
    callbackFlow {
      val eng = engine
      if (eng == null) {
        trySend("[AI not ready yet — try again once the model has loaded.]")
        close()
        return@callbackFlow
      }
      // Reuse the conversation if this is a continuation of the same clause's Q&A — that way the
      // model retains prior turns and can answer "what about my previous question?" properly.
      // Reset the slot when the clause changes (or anything else has reset it, e.g. a fresh
      // classification). The engine only allows one Conversation at a time, so we always go
      // through the analyzer's single slot.
      val sameClauseContinuation = (followUpClauseId == clauseId) && (conversation != null)
      val conv =
        if (sameClauseContinuation) {
          conversation!!
        } else {
          try {
            resetConversation(eng).also { followUpClauseId = clauseId }
          } catch (e: Exception) {
            Log.e(TAG, "follow-up resetConversation failed", e)
            trySend("\n\n[Unable to answer right now. Please try again.]")
            close()
            return@callbackFlow
          }
        }

    val callback =
      object : MessageCallback {
        override fun onMessage(message: Message) {
          val text = message.toString()
          if (!text.startsWith("<ctrl")) trySend(text)
        }

        override fun onDone() {
          close()
        }

        override fun onError(throwable: Throwable) {
          Log.e(TAG, "follow-up error", throwable)
          trySend("\n\n[Unable to answer right now. Please try again.]")
          close()
        }
      }

      // First turn for this clause: include the clause + answering rules. Subsequent turns: just
      // send the question; the model already has the clause and rules in conversation history.
      val prompt =
        if (sameClauseContinuation) buildFollowUpContinuationPrompt(question)
        else buildFollowUpPrompt(clauseText, question)
      try {
        conv.sendMessageAsync(
          Contents.of(listOf(Content.Text(prompt))),
          callback,
          emptyMap(),
        )
      } catch (e: Throwable) {
        Log.e(TAG, "follow-up sendMessageAsync threw", e)
        trySend("\n\n[Unable to answer right now. Please try again.]")
        close()
      }

      awaitClose {
        // Don't close the conversation here — it's owned by the analyzer's slot and will be reset
        // by the next classification or follow-up call. Closing it here would race with future
        // resetConversation calls.
      }
    }

  private fun buildFollowUpContinuationPrompt(question: String): String =
    "Continue answering follow-up questions about the same clause, following the same rules. " +
      "Use prior turns in this conversation as context.\n\nThe user's next question:\n$question\n\nYour answer:"

  private fun buildFollowUpPrompt(clauseText: String, question: String): String {
    return """
You are explaining a contract clause to a non-lawyer in plain English. Answer the user's specific question about the clause shown below. Keep your answer to 2–3 sentences.

How to answer:
- TRY YOUR BEST to give a useful answer. Use the clause text plus your general knowledge of how contracts of this type usually work. Reasonable inference is welcome.
- Do NOT refuse just because the clause text alone doesn't spell something out — fill in with what is typical for this kind of contract.
- Do not invent specific facts the clause cannot support (concrete dollar amounts, deadlines, named jurisdictions, etc.). If the question is truly unanswerable without information that only the document's author or counterparty would know (e.g., "what was their intent", "will they actually enforce this"), end your answer with: "For specifics, contact the person or company who gave you this document."
- Do not speculate about jurisdictions unless the user names one. Do not give formal legal advice (the user already sees a 'not legal advice' disclaimer).

The clause:
"${clauseText.replace("\n", " ").take(600)}"

The user's question:
$question

Your answer:
"""
      .trimIndent()
  }

  fun close() {
    try {
      conversation?.close()
    } catch (e: Throwable) {
      Log.w(TAG, "conversation close failed", e)
    }
    try {
      engine?.close()
    } catch (e: Throwable) {
      Log.w(TAG, "engine close failed", e)
    }
    conversation = null
    engine = null
  }
}
