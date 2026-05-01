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
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

private const val TAG = "SettleScreen"
private const val MODEL_FILENAME = "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
private const val DISCLAIMER_TEXT = "Settle helps spot risks. This is not legal advice."

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
  var capturedPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
  var blocks by remember { mutableStateOf<List<SettleTextBlock>>(emptyList()) }
  var results by remember { mutableStateOf<List<ClauseResult>>(emptyList()) }
  var ocrComplete by remember { mutableStateOf(false) }
  var classifying by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(false) }
  var selectedClause by
    remember { mutableStateOf<Pair<SettleTextBlock, ClauseResult?>?>(null) }
  val controller = remember { CameraController() }
  val ocr = remember { OcrService() }
  val analyzer = remember { SettleAnalyzer(context) }
  var analyzerReady by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val uploadLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val mimeType = context.contentResolver.getType(uri)
      loading = true
      scope.launch {
        try {
          if (mimeType == "application/pdf") {
            // Process PDF page by page to avoid huge bitmaps
            val renderer = FileLoader.openPdfRenderer(context, uri)
            renderer.use { r ->
              val allBlocks = mutableListOf<SettleTextBlock>()
              val allPages = mutableListOf<Bitmap>()
              var currentId = 1
              for (i in 0 until r.pageCount) {
                val pageBmp = FileLoader.renderPdfPage(r, i)
                allPages.add(pageBmp)
                
                val (pageBlocks, nextId) = ocr.extractBlocks(pageBmp, pageIndex = i, startId = currentId)
                allBlocks.addAll(pageBlocks)
                currentId = nextId
              }
              capturedPages = allPages
              blocks = allBlocks
              ocrComplete = true
            }
          } else {
            val bmp = FileLoader.loadBitmap(context, uri, mimeType)
            capturedPages = listOf(bmp)
          }
        } catch (e: Exception) {
          Log.e(TAG, "File load failed", e)
        } finally {
          loading = false
        }
      }
    }

  LaunchedEffect(Unit) {
    val modelFile = File(context.getExternalFilesDir(null), "settle/$MODEL_FILENAME")
    if (!modelFile.exists()) {
      Log.w(TAG, "Model file missing at ${modelFile.absolutePath}; using keyword fallback only")
      return@LaunchedEffect
    }
    try {
      analyzer.initialize(modelFile.absolutePath)
      analyzerReady = true
      Log.i(TAG, "Analyzer ready")
    } catch (e: Exception) {
      Log.e(TAG, "Analyzer init failed", e)
    }
  }

  DisposableEffect(Unit) { onDispose { analyzer.close() } }

  LaunchedEffect(capturedPages) {
    if (capturedPages.isEmpty()) return@LaunchedEffect
    // If it's not a PDF (captured from camera or single image upload), we still need OCR.
    // For PDF upload, OCR is already done in the launcher.
    if (!ocrComplete) {
      val (allBlocks, _) = ocr.extractBlocks(capturedPages.first())
      blocks = allBlocks
      ocrComplete = true
    }
    loading = false
  }

  LaunchedEffect(blocks) {
    if (blocks.isEmpty()) {
      results = emptyList()
      classifying = false
      return@LaunchedEffect
    }
    classifying = true
    results =
      if (analyzerReady) analyzer.classifyBlocks(blocks) else KeywordClassifier.classify(blocks)
    classifying = false
  }

  fun resetCapture() {
    capturedPages.forEach { it.recycle() }
    capturedPages = emptyList()
    blocks = emptyList()
    results = emptyList()
    ocrComplete = false
    classifying = false
    loading = false
    selectedClause = null
  }

  Box(modifier = Modifier.fillMaxSize()) {
    if (capturedPages.isEmpty()) {
      CameraPreview(modifier = Modifier.fillMaxSize(), controller = controller)
      // Center the Analyze button vertically inside a fixed-height bottom band so it sits in
      // the middle of the margin below the camera preview rather than pinned to the screen edge.
      Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            enabled = !loading,
            onClick = {
              loading = true
              controller.takePicture(
                context = context,
                onCapture = { capturedPages = listOf(it) },
                onError = { Log.e(TAG, "Capture failed", it); loading = false },
              )
            }
          ) {
            Text("Scan")
          }
          Button(
            enabled = !loading,
            onClick = {
              uploadLauncher.launch(
                arrayOf(
                  "application/pdf",
                  "image/*",
                  "text/plain",
                  "application/rtf",
                  "text/rtf",
                  FileLoader.MIME_DOCX,
                  FileLoader.MIME_ODT,
                  "application/msword",
                )
              )
            }
          ) {
            Text("Upload")
          }
        }
      }
      // Loading overlay: covers camera preview and blocks interaction while processing.
      if (loading) {
        Box(
          modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            CircularProgressIndicator(color = Color.White)
            Text(
              text = "Processing…",
              color = Color.White,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    } else {
      val resultById = remember(results) { results.associateBy { it.id } }
      
      // Scrollable list of pages
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        capturedPages.forEachIndexed { index, bitmap ->
          Box(modifier = Modifier.fillMaxWidth()) {
            AnalyzedImage(
              bitmap = bitmap,
              blocks = blocks.filter { it.pageIndex == index },
              results = results,
              modifier = Modifier.fillMaxWidth(),
              onBlockTap = { block -> selectedClause = block to resultById[block.id] },
            )
          }
        }
        // Spacer for the bottom button
        Spacer(Modifier.height(220.dp))
      }

      // Edge case: OCR ran but found nothing meaningful in the photo.
      if (ocrComplete && blocks.isEmpty()) {
        EmptyTextOverlay(
          onBack = ::resetCapture,
          modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
        )
      } else if (classifying) {
        // While the LLM works, sweep a horizontal scan line across the captured frame.
        ScanLineOverlay(modifier = Modifier.fillMaxSize())
      }
      Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center,
      ) {
        Button(onClick = ::resetCapture) { Text("Back") }
      }
    }

    // "Not legal advice" disclaimer banner pinned to the top of the screen so it's always visible
    // regardless of which stage we're in (camera preview, analyzing, or analyzed image).
    DisclaimerBanner(modifier = Modifier.align(Alignment.TopCenter))
  }

  selectedClause?.let { (block, result) ->
    ClauseDetailSheet(
      block = block,
      result = result,
      analyzer = analyzer,
      analyzerReady = analyzerReady,
      onDismiss = { selectedClause = null },
    )
  }
}

@Composable
private fun DisclaimerBanner(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
  ) {
    Text(
      text = DISCLAIMER_TEXT,
      style = MaterialTheme.typography.bodySmall,
      textAlign = TextAlign.Center,
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(top = 24.dp),
    )
  }
}

@Composable
private fun EmptyTextOverlay(onBack: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = Color.Black.copy(alpha = 0.7f),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "No text detected",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "Try a different file or a clearer scan.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
      Button(onClick = onBack) { Text("Back") }
    }
  }
}

@Composable
private fun ScanLineOverlay(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "scanline")
  val progress by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1500, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "scanline-progress",
    )
  val density = LocalDensity.current
  BoxWithConstraints(modifier = modifier) {
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    val bandHeightPx = with(density) { 56.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
      val centerY = progress * heightPx
      val top = (centerY - bandHeightPx / 2f).coerceAtLeast(0f)
      val brush =
        Brush.verticalGradient(
          colors =
            listOf(
              Color.Transparent,
              Color(0x4DFFFFFF), // 30% white core
              Color.Transparent,
            ),
          startY = top,
          endY = top + bandHeightPx,
        )
      drawRect(brush = brush, topLeft = Offset(0f, top), size = Size(widthPx, bandHeightPx))
    }
    Surface(
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
      shape = RoundedCornerShape(16.dp),
      color = Color.Black.copy(alpha = 0.55f),
      contentColor = Color.White,
    ) {
      Text(
        text = "Analyzing…",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClauseDetailSheet(
  block: SettleTextBlock,
  result: ClauseResult?,
  analyzer: SettleAnalyzer,
  analyzerReady: Boolean,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showOriginal by remember(block.id) { mutableStateOf(false) }
  val risk = result?.risk ?: Risk.UNKNOWN
  val (badgeColor, badgeLabel) =
    when (risk) {
      Risk.RED -> Color(0xFFC62828) to "HIGH RISK"
      Risk.YELLOW -> Color(0xFFEF6C00) to "CAUTION"
      Risk.GREEN -> Color(0xFF2E7D32) to "STANDARD"
      Risk.UNKNOWN -> Color(0xFF555555) to "UNKNOWN"
    }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
      // Risk badge — filled Surface with rounded corners and contrasting text.
      Surface(
        color = badgeColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
          Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
          Spacer(Modifier.width(8.dp))
          Text(
            text = badgeLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
      Spacer(Modifier.height(20.dp))
      Text(
        text = "What this means",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = result?.plain?.takeIf { it.isNotBlank() } ?: "No summary available.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(20.dp))
      Text(
        text = "Why this matters",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = result?.why?.takeIf { it.isNotBlank() } ?: "No rationale available.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(20.dp))
      // Slice 5.5: free-text follow-up questions about the tapped clause. Streams answers from
      // the same on-device LLM. Sits between "Why this matters" and the collapsible original.
      FollowUpSection(
        clauseText = block.text,
        analyzer = analyzer,
        analyzerReady = analyzerReady,
        clauseId = block.id,
      )
      Spacer(Modifier.height(20.dp))
      // Original text collapsed by default per polish guidelines.
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
      Spacer(Modifier.height(20.dp))
      Text(
        text = DISCLAIMER_TEXT,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(20.dp))
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
