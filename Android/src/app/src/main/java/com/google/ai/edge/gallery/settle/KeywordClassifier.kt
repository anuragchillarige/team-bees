/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

/**
 * Conservative keyword-based fallback used only when the LLM is unavailable or its response can't
 * be parsed. The "why" text is phrased as actionable guidance, not as a definition of the matched
 * keyword, so the user gets useful direction even when the model is offline.
 */
object KeywordClassifier {

  // Keyword → actionable guidance for the "why" field.
  private val redGuidance: List<Pair<String, String>> =
    listOf(
      "binding arbitration" to
        "Confirm whether arbitration is mandatory or optional and ask who pays the arbitrator's fees before signing.",
      "arbitration" to
        "Check whether arbitration is required or just permitted; mandatory binding arbitration removes your right to sue in court.",
      "class action" to
        "If you waive class-action rights you must pursue any dispute alone — weigh that against the cost of individual arbitration.",
      "waive" to
        "Identify exactly which right is being waived and ask whether it can be struck before signing.",
      "waiver" to
        "Identify exactly which right is being waived and ask whether it can be struck before signing.",
      "auto-renew" to
        "Note the renewal date and the cancellation window; set a reminder so you can cancel before automatic renewal.",
      "automatic renewal" to
        "Note the renewal date and the cancellation window; set a reminder so you can cancel before automatic renewal.",
      "non-refundable" to
        "Confirm what triggers forfeiture of this fee and whether any portion can be refunded under specific conditions.",
      "liquidated damages" to
        "Compare the stated damages amount to the realistic harm — courts may strike clauses that look like a penalty.",
      "indemnify" to
        "Push back on indemnifying the other party for their own negligence; this is often negotiable.",
      "indemnification" to
        "Push back on indemnifying the other party for their own negligence; this is often negotiable.",
      "perpetual" to
        "A perpetual right cannot be revoked later — make sure you are comfortable granting it forever.",
      "irrevocable" to
        "Once granted you cannot take this right back; confirm the scope is exactly what you intend.",
    )

  private val yellowGuidance: List<Pair<String, String>> =
    listOf(
      "deposit" to
        "Confirm the deposit is refundable, the timeline for return after move-out, and that you'll receive an itemized statement of any deductions.",
      "notice" to
        "Note the required notice period and the delivery method (email, certified mail) so you don't accidentally miss it.",
      "late fee" to
        "Check that the late fee amount and grace period are reasonable for your jurisdiction.",
      "termination" to
        "Read the termination conditions carefully so you know what triggers them and what penalties apply.",
      "penalty" to
        "Identify exactly what triggers the penalty and whether the amount is proportionate to the actual harm.",
      "interest" to
        "Verify the interest rate and how it compounds — small rates add up over long terms.",
      "default" to
        "Understand exactly what counts as a default and whether you get a cure period to fix it before consequences apply.",
      "breach" to
        "Note what counts as a breach and what remedies the other party can pursue if it occurs.",
    )

  fun classify(blocks: List<SettleTextBlock>): List<ClauseResult> {
    return blocks.map { block ->
      val lower = block.text.lowercase()
      val redMatch = redGuidance.firstOrNull { (kw, _) -> lower.contains(kw) }
      val yellowMatch = yellowGuidance.firstOrNull { (kw, _) -> lower.contains(kw) }
      val (risk, why) =
        when {
          redMatch != null -> Risk.RED to redMatch.second
          yellowMatch != null -> Risk.YELLOW to yellowMatch.second
          else -> Risk.GREEN to "Standard clause, no action needed."
        }
      ClauseResult(
        id = block.id,
        risk = risk,
        plain = block.text,
        why = why,
      )
    }
  }
}