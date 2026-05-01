/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@Composable
fun AnalyzedImage(
  bitmap: Bitmap,
  blocks: List<SettleTextBlock>,
  results: List<ClauseResult> = emptyList(),
  modifier: Modifier = Modifier,
  onBlockTap: ((SettleTextBlock) -> Unit)? = null,
) {
  val resultById = remember(results) { results.associateBy { it.id } }
  val haptics = LocalHapticFeedback.current

  // 600ms fade-in keyed on results so highlights ease in after each classification rather than
  // popping. Resets to 0 whenever the result set changes (new scan, fallback re-classification).
  val fadeIn = remember { Animatable(0f) }
  LaunchedEffect(results) {
    fadeIn.snapTo(0f)
    if (results.isNotEmpty()) {
      fadeIn.animateTo(1f, animationSpec = tween(durationMillis = 600))
    }
  }

  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val viewWidthPx = with(density) { maxWidth.toPx() }
    val bmpWidth = bitmap.width.toFloat()
    val bmpHeight = bitmap.height.toFloat()
    
    // Calculate scale and height based on the width to preserve aspect ratio
    val scale = if (bmpWidth > 0f) viewWidthPx / bmpWidth else 1f
    val heightPx = bmpHeight * scale
    val heightDp = with(density) { heightPx.toDp() }

    Box(
      modifier = Modifier
        .width(maxWidth)
        .height(heightDp)
    ) {
      val strokeWidthPx = with(density) { 1.5.dp.toPx() }

      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Captured document",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillWidth,
      )

      // Token-budget optimization: only blocks the LLM actually returned (red/yellow) are
      // renderable. Greens and other omitted ids are skipped from both drawing AND tap detection.
      val renderable = blocks.filter { resultById[it.id]?.risk.let { r -> r != null && r != Risk.GREEN } }

      Canvas(
        modifier =
          Modifier.fillMaxSize().pointerInput(renderable, results) {
            detectTapGestures { tap ->
              val callback = onBlockTap ?: return@detectTapGestures
              val bmpX = tap.x / scale
              val bmpY = tap.y / scale
              // Walk in reverse so a smaller block on top of a larger one wins.
              val hit =
                renderable.lastOrNull { block ->
                  val r = block.boundingBox
                  bmpX >= r.left && bmpX <= r.right && bmpY >= r.top && bmpY <= r.bottom
                }
              if (hit != null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                callback(hit)
              }
            }
          }
      ) {
        val progress = fadeIn.value
        renderable.forEach { block ->
          val risk = resultById[block.id]?.risk ?: Risk.UNKNOWN
          val rect = block.boundingBox
          val left = rect.left * scale
          val top = rect.top * scale
          val width = rect.width() * scale
          val height = rect.height() * scale
          val topLeft = Offset(left, top)
          val size = Size(width, height)
          val baseColor =
            when (risk) {
              Risk.RED -> Color.Red
              Risk.YELLOW -> Color(0xFFFFC107)
              Risk.GREEN -> Color(0xFF4CAF50)
              Risk.UNKNOWN -> Color.Gray
            }
          drawRect(color = baseColor.copy(alpha = 0.3f * progress), topLeft = topLeft, size = size)
          drawRect(
            color = baseColor.copy(alpha = progress),
            topLeft = topLeft,
            size = size,
            style = Stroke(width = strokeWidthPx),
          )
        }
      }
    }
  }
}