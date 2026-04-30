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
    val conv = conversation ?: return KeywordClassifier.classify(blocks)
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
    val capped = blocks.take(25) // protect KV cache
    val numbered = capped.joinToString("\n") { "[${it.id}] ${it.text.replace("\n", " ").take(300)}" }
    return """
You are a legal risk classifier. Classify each numbered clause from a contract or terms-of-service document.

Risk levels:
- "red": binding arbitration, class action waivers, auto-renewal traps, large or unusual fees, broad liability waivers, indemnification clauses
- "yellow": notice periods, security deposits, standard late fees, termination conditions
- "green": routine boilerplate (definitions, governing law, severability, headings)

Respond with ONLY valid JSON. No preamble, no markdown, no code fences. Schema:
{"clauses":[{"id":<int>,"risk":"red|yellow|green","plain":"<one sentence>","why":"<one sentence>"}]}

Example input:
[1] Tenant agrees to binding arbitration and waives the right to a jury trial.
[2] Rent is due on the first of each month.

Example output:
{"clauses":[{"id":1,"risk":"red","plain":"You give up the right to sue in court if there is a dispute.","why":"Binding arbitration plus a jury trial waiver removes your strongest legal protections."},{"id":2,"risk":"green","plain":"Rent is due monthly on the 1st.","why":"Standard payment term in any lease."}]}

Now classify these clauses:
$numbered
""".trimIndent()
  }

  private fun parseResponse(raw: String, blocks: List<SettleTextBlock>): List<ClauseResult> {
    return try {
      val json = extractFirstJsonObject(raw) ?: return KeywordClassifier.classify(blocks)
      val arr = JSONObject(json).getJSONArray("clauses")
      val results = mutableListOf<ClauseResult>()
      for (i in 0 until arr.length()) {
        val item = arr.getJSONObject(i)
        results +=
          ClauseResult(
            id = item.getInt("id"),
            risk =
              when (item.optString("risk").lowercase()) {
                "red" -> Risk.RED
                "yellow" -> Risk.YELLOW
                "green" -> Risk.GREEN
                else -> Risk.UNKNOWN
              },
            plain = item.optString("plain", ""),
            why = item.optString("why", ""),
          )
      }
      val seen = results.map { it.id }.toSet()
      results + KeywordClassifier.classify(blocks.filter { it.id !in seen })
    } catch (e: Exception) {
      Log.e(TAG, "Parse failed: $raw", e)
      KeywordClassifier.classify(blocks)
    }
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
