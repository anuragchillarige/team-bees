/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat

private const val TAG = "SettleScreen"

@Composable
fun SettleScreen() {
  val context = LocalContext.current
  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasPermission = granted
    }

  LaunchedEffect(Unit) {
    if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  Box(modifier = Modifier.fillMaxSize().padding(0.dp)) {
    if (!hasPermission) {
      PermissionRequired(onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) })
    } else {
      CameraStage()
    }
  }
}

@Composable
private fun CameraStage() {
  val context = LocalContext.current
  var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var blocks by remember { mutableStateOf<List<SettleTextBlock>>(emptyList()) }
  var results by remember { mutableStateOf<List<ClauseResult>>(emptyList()) }
  var selectedClause by
    remember { mutableStateOf<Pair<SettleTextBlock, ClauseResult?>?>(null) }
  val controller = remember { CameraController() }
  val ocr = remember { OcrService() }

  LaunchedEffect(capturedBitmap) {
    val bmp = capturedBitmap ?: return@LaunchedEffect
    blocks =
      try {
        ocr.extractBlocks(bmp)
      } catch (e: Exception) {
        Log.e(TAG, "OCR failed", e)
        emptyList()
      }
  }

  LaunchedEffect(blocks) {
    results = if (blocks.isEmpty()) emptyList() else KeywordClassifier.classify(blocks)
  }

  Box(modifier = Modifier.fillMaxSize()) {
    val bitmap = capturedBitmap
    if (bitmap == null) {
      CameraPreview(modifier = Modifier.fillMaxSize(), controller = controller)
      Button(
        onClick = {
          controller.takePicture(
            context = context,
            onCapture = { capturedBitmap = it },
            onError = { Log.e(TAG, "Capture failed", it) },
          )
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
      ) {
        Text("Analyze")
      }
    } else {
      val resultById = remember(results) { results.associateBy { it.id } }
      AnalyzedImage(
        bitmap = bitmap,
        blocks = blocks,
        results = results,
        modifier = Modifier.fillMaxSize(),
        onBlockTap = { block ->
          selectedClause = block to resultById[block.id]
        },
      )
      Button(
        onClick = {
          capturedBitmap = null
          blocks = emptyList()
          results = emptyList()
          selectedClause = null
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
      ) {
        Text("Scan again")
      }
    }
  }

  selectedClause?.let { (block, result) ->
    ClauseDetailSheet(
      block = block,
      result = result,
      onDismiss = { selectedClause = null },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClauseDetailSheet(
  block: SettleTextBlock,
  result: ClauseResult?,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showOriginal by remember(block.id) { mutableStateOf(false) }
  val risk = result?.risk ?: Risk.UNKNOWN
  val (badgeColor, badgeLabel) =
    when (risk) {
      Risk.RED -> Color.Red to "HIGH RISK"
      Risk.YELLOW -> Color(0xFFFFC107) to "CAUTION"
      Risk.GREEN -> Color(0xFF4CAF50) to "STANDARD"
      Risk.UNKNOWN -> Color.Gray to "UNKNOWN"
    }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(14.dp).background(badgeColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
          text = badgeLabel,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
      }
      Spacer(Modifier.height(16.dp))
      Text(
        text = "Plain English",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = result?.plain?.takeIf { it.isNotBlank() } ?: "No summary available.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      Text(
        text = "Why this matters",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = result?.why?.takeIf { it.isNotBlank() } ?: "No rationale available.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      TextButton(onClick = { showOriginal = !showOriginal }, contentPadding = PaddingZero) {
        Text(if (showOriginal) "Hide original text" else "Show original text")
      }
      if (showOriginal) {
        Spacer(Modifier.height(4.dp))
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
          )
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

private val PaddingZero = androidx.compose.foundation.layout.PaddingValues(0.dp)

@Composable
private fun PermissionRequired(onRetry: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "Camera permission required",
      color = Color.Unspecified,
      textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Grant permission") }
  }
}