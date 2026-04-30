/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrService {
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  suspend fun extractBlocks(bitmap: Bitmap): List<SettleTextBlock> {
    val image = InputImage.fromBitmap(bitmap, 0) // bitmap already rotated in Slice 1
    val result = recognizer.process(image).await()
    var nextId = 1
    return result.textBlocks.mapNotNull { block ->
      val rect = block.boundingBox ?: return@mapNotNull null
      val text = block.text
      if (!isLikelyClause(text)) return@mapNotNull null
      SettleTextBlock(id = nextId++, text = text, boundingBox = rect)
    }
  }

  /**
   * Heuristic: only treat a text block as a clause if it looks like prose — long enough, contains
   * sentence-ending punctuation, has more than a couple of words, and isn't a shouting heading. This
   * keeps titles, subtitles, page numbers, signature placeholders, and field labels out of the
   * highlight + classification pipeline so the model never has to "summarize" a two-word heading.
   */
  private fun isLikelyClause(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty()) return false

    // Sentence-like prose tends to be at least this long.
    if (text.length < 40) return false

    val wordCount = text.split(Regex("\\s+")).size
    if (wordCount < 6) return false

    // A genuine clause has at least one sentence terminator somewhere in it.
    val hasSentenceTerminator = text.any { it == '.' || it == '!' || it == '?' }
    if (!hasSentenceTerminator) return false

    // ALL-CAPS short blocks are almost always headings/section labels.
    val letterPortion = text.filter { it.isLetter() }
    if (letterPortion.isNotEmpty()) {
      val upperRatio = letterPortion.count { it.isUpperCase() }.toFloat() / letterPortion.length
      if (upperRatio > 0.85f && wordCount < 12) return false
    }

    return true
  }
}
