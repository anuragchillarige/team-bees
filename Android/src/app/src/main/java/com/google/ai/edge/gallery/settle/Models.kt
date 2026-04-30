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