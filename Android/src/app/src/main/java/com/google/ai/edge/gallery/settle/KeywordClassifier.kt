/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

object KeywordClassifier {
  private val redKeywords =
    listOf(
      "arbitration",
      "class action",
      "waive",
      "waiver",
      "auto-renew",
      "automatic renewal",
      "non-refundable",
      "liquidated damages",
      "indemnify",
      "indemnification",
      "perpetual",
      "irrevocable",
      "binding",
    )
  private val yellowKeywords =
    listOf(
      "deposit",
      "notice",
      "late fee",
      "termination",
      "penalty",
      "interest",
      "default",
      "breach",
    )

  fun classify(blocks: List<SettleTextBlock>): List<ClauseResult> {
    return blocks.map { block ->
      val lower = block.text.lowercase()
      val risk =
        when {
          redKeywords.any { lower.contains(it) } -> Risk.RED
          yellowKeywords.any { lower.contains(it) } -> Risk.YELLOW
          else -> Risk.GREEN
        }
      val matched = (redKeywords + yellowKeywords).firstOrNull { lower.contains(it) }
      ClauseResult(
        id = block.id,
        risk = risk,
        plain = block.text.take(120) + if (block.text.length > 120) "…" else "",
        why =
          matched?.let {
            "Contains \"$it\", which often signals a clause limiting your rights or imposing obligations."
          } ?: "Standard clause; no risk keywords detected.",
      )
    }
  }
}
