/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

private const val TAG = "SettleCameraPreview"

class CameraController {
  internal var imageCapture: ImageCapture? = null

  fun takePicture(context: Context, onCapture: (Bitmap) -> Unit, onError: (Throwable) -> Unit) {
    val capture = imageCapture ?: run {
      onError(IllegalStateException("ImageCapture not bound yet"))
      return
    }
    capture.takePicture(
      ContextCompat.getMainExecutor(context),
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          try {
            val rotated = image.toRotatedBitmap()
            onCapture(rotated)
          } catch (e: Throwable) {
            onError(e)
          } finally {
            image.close()
          }
        }

        override fun onError(exception: ImageCaptureException) {
          Log.e(TAG, "takePicture failed", exception)
          onError(exception)
        }
      },
    )
  }
}

@Composable
fun CameraPreview(modifier: Modifier = Modifier, controller: CameraController) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val previewView = remember {
    PreviewView(context).apply {
      // FIT_CENTER so the preview shows the entire sensor frame the same way the
      // captured bitmap will be displayed afterwards. Otherwise the preview crops to
      // fill the view and the captured image looks "wider" than what was on-screen.
      scaleType = PreviewView.ScaleType.FIT_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { previewView },
    update = { view ->
      val providerFuture = ProcessCameraProvider.getInstance(context)
      providerFuture.addListener(
        {
          try {
            val cameraProvider = providerFuture.get()
            val preview =
              Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val imageCapture =
              ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            controller.imageCapture = imageCapture
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
              lifecycleOwner,
              CameraSelector.DEFAULT_BACK_CAMERA,
              preview,
              imageCapture,
            )
          } catch (e: Throwable) {
            Log.e(TAG, "Camera bind failed", e)
          }
        },
        ContextCompat.getMainExecutor(context),
      )
    },
  )
}

private fun ImageProxy.toRotatedBitmap(): Bitmap {
  val buffer: ByteBuffer = planes[0].buffer
  val bytes = ByteArray(buffer.remaining())
  buffer.get(bytes)
  val raw =
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      ?: throw IllegalStateException("Failed to decode captured image")
  val rotation = imageInfo.rotationDegrees
  if (rotation == 0) return raw
  val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
  val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
  if (rotated !== raw) raw.recycle()
  return rotated
}