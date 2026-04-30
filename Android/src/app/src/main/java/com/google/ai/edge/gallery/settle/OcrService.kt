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
    return result.textBlocks.mapIndexedNotNull { index, block ->
      val rect = block.boundingBox ?: return@mapIndexedNotNull null
      SettleTextBlock(id = index + 1, text = block.text, boundingBox = rect)
    }
  }
}