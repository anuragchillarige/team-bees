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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
      AnalyzedImage(
        bitmap = bitmap,
        blocks = blocks,
        modifier = Modifier.fillMaxSize(),
      )
      Button(
        onClick = {
          capturedBitmap = null
          blocks = emptyList()
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
      ) {
        Text("Scan again")
      }
    }
  }
}

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