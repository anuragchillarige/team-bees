/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.graphics.Rect

data class SettleTextBlock(
  val id: Int, // 1-indexed; used as LLM reference
  val text: String,
  val boundingBox: Rect, // pixel coordinates in source bitmap
  val pageIndex: Int = 0,
)

enum class Risk {
  RED,
  YELLOW,
  GREEN,
  UNKNOWN,
}

data class ClauseResult(
  val id: Int, // matches SettleTextBlock.id
  val risk: Risk,
  val plain: String, // one-sentence summary in plain English
  val why: String, // one-sentence rationale
)

/** One question-and-answer pair within the follow-up history of a single tapped clause. */
data class FollowUpExchange(
  val question: String,
  val answer: String, // empty while streaming; appended as chunks arrive
  val isLoading: Boolean, // true while the LLM is still generating
)