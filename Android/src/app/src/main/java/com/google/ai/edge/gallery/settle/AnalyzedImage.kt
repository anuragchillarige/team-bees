/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun AnalyzedImage(
  bitmap: Bitmap,
  blocks: List<SettleTextBlock>,
  modifier: Modifier = Modifier,
  boxColor: (SettleTextBlock) -> Color = { Color.Gray },
  onBlockTap: ((SettleTextBlock) -> Unit)? = null,
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val viewWidthPx = with(density) { maxWidth.toPx() }
    val viewHeightPx = with(density) { maxHeight.toPx() }
    val bmpWidth = bitmap.width.toFloat()
    val bmpHeight = bitmap.height.toFloat()
    val scale =
      if (bmpWidth > 0f && bmpHeight > 0f) {
        minOf(viewWidthPx / bmpWidth, viewHeightPx / bmpHeight)
      } else {
        1f
      }
    val offsetX = (viewWidthPx - bmpWidth * scale) / 2f
    val offsetY = (viewHeightPx - bmpHeight * scale) / 2f

    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = "Captured document",
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Fit,
    )

    val strokeWidthPx = with(density) { 1.5.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
      blocks.forEach { block ->
        val rect = block.boundingBox
        val left = rect.left * scale + offsetX
        val top = rect.top * scale + offsetY
        val width = rect.width() * scale
        val height = rect.height() * scale
        drawRect(
          color = boxColor(block),
          topLeft = Offset(left, top),
          size = Size(width, height),
          style = Stroke(width = strokeWidthPx),
        )
      }
    }
  }
}