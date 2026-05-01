/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

object FileLoader {

  internal const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  internal const val MIME_ODT  = "application/vnd.oasis.opendocument.text"

  private val TEXT_MIME_TYPES = setOf(
    "text/plain",
    "application/rtf",
    "text/rtf",
    "text/richtext",
    MIME_DOCX,
    MIME_ODT,
    "application/msword",
  )

  suspend fun loadBitmap(context: Context, uri: Uri, mimeType: String?): Bitmap =
    withContext(Dispatchers.IO) {
      when {
        mimeType == "application/pdf" -> renderPdf(context, uri)
        mimeType != null && mimeType in TEXT_MIME_TYPES -> {
          val text = extractText(context, uri, mimeType)
          renderTextToBitmap(text)
        }
        else -> decodeImage(context, uri)
      }
    }

  // ── PDF ──────────────────────────────────────────────────────────────────

  /**
   * Renders PDF pages into a single stitched bitmap.
   * @param pageLimit Maximum number of pages to render to avoid massive bitmaps.
   */
  fun renderPdf(context: Context, uri: Uri, pageLimit: Int = Int.MAX_VALUE): Bitmap {
    val fd = context.contentResolver.openFileDescriptor(uri, "r")
      ?: error("Cannot open PDF: $uri")
    // PdfRenderer takes ownership of fd and closes it — do not wrap fd in use{} too.
    return PdfRenderer(fd).use { renderer ->
      // Render at 2× to give ML Kit OCR enough pixel density.
      val scale = 2f
      val pageCount = renderer.pageCount.coerceAtMost(pageLimit)
      val pages = (0 until pageCount).map { i ->
        val page = renderer.openPage(i)
        page.use {
          val w = (page.width * scale).toInt()
          val h = (page.height * scale).toInt()
          val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
          bmp.eraseColor(Color.WHITE)
          page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bmp
        }
      }
      val totalH = pages.sumOf { it.height }
      val width = pages.maxOf { it.width }
      val stitched = createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(stitched)
      var y = 0
      pages.forEach { page ->
        canvas.drawBitmap(page, 0f, y.toFloat(), null)
        y += page.height
        page.recycle()
      }
      stitched
    }
  }

  /** Opens a PdfRenderer for the given URI. */
  fun openPdfRenderer(context: Context, uri: Uri): PdfRenderer {
    val fd = context.contentResolver.openFileDescriptor(uri, "r")
      ?: error("Cannot open PDF: $uri")
    return PdfRenderer(fd)
  }

  /** Renders a single page from a PdfRenderer. */
  fun renderPdfPage(renderer: PdfRenderer, pageIndex: Int): Bitmap {
    val scale = 2f
    val page = renderer.openPage(pageIndex)
    return page.use {
      val w = (page.width * scale).toInt()
      val h = (page.height * scale).toInt()
      val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
      bmp.eraseColor(Color.WHITE)
      page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      bmp
    }
  }

  // ── Image ─────────────────────────────────────────────────────────────────

  private fun decodeImage(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
  }

  // ── Text extraction ───────────────────────────────────────────────────────

  private fun extractText(context: Context, uri: Uri, mimeType: String): String {
    val stream = context.contentResolver.openInputStream(uri)
      ?: error("Cannot open file: $uri")
    return stream.use { input ->
      when (mimeType) {
        MIME_DOCX -> extractDocx(input)
        MIME_ODT  -> extractOdt(input)
        "application/rtf", "text/rtf", "text/richtext" ->
          extractRtf(input)
        else -> input.readBytes().toString(Charsets.UTF_8)
      }
    }
  }

  /** DOCX is a ZIP; pull text from word/document.xml `w:t` nodes. */
  private fun extractDocx(stream: InputStream): String {
    val zip = ZipInputStream(stream)
    var entry = zip.nextEntry
    while (entry != null) {
      if (entry.name == "word/document.xml") {
        return parseXmlText(zip, textTags = setOf("w:t"), paragraphTag = "w:p")
      }
      entry = zip.nextEntry
    }
    return ""
  }

  /** ODT is a ZIP; pull text from content.xml `text:p` / `text:span` nodes. */
  private fun extractOdt(stream: InputStream): String {
    val zip = ZipInputStream(stream)
    var entry = zip.nextEntry
    while (entry != null) {
      if (entry.name == "content.xml") {
        return parseXmlText(zip, textTags = setOf("text:p", "text:span", "text:s"), paragraphTag = "text:p")
      }
      entry = zip.nextEntry
    }
    return ""
  }

  private fun parseXmlText(stream: InputStream, textTags: Set<String>, paragraphTag: String): String {
    val sb = StringBuilder()
    val factory = SAXParserFactory.newInstance()
    val parser = factory.newSAXParser()
    parser.parse(stream, object : DefaultHandler() {
      private var capture = false
      override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
        capture = qName in textTags
      }
      override fun endElement(uri: String, local: String, qName: String) {
        if (qName == paragraphTag) sb.append("\n\n")
        capture = false
      }
      override fun characters(ch: CharArray, start: Int, length: Int) {
        if (capture) sb.appendRange(ch, start, start + length)
      }
    })
    return sb.toString().trim()
  }

  /**
   * RTF: strip control words, groups, and escape sequences with a simple pass.
   * Covers the vast majority of real-world RTF lease documents without a full parser.
   */
  private fun extractRtf(stream: InputStream): String {
    val raw = stream.readBytes().toString(Charsets.ISO_8859_1)
    val sb = StringBuilder()
    var i = 0
    var depth = 0
    while (i < raw.length) {
      when {
        raw[i] == '{' -> { depth++; i++ }
        raw[i] == '}' -> { depth--; i++ }
        raw[i] == '\\' && i + 1 < raw.length -> {
          i++ // skip backslash
          when {
            raw[i] == '\'' && i + 2 < raw.length -> {
              // Hex-encoded character: backslash-quote followed by two hex digits
              val hex = raw.substring(i + 1, i + 3)
              val code = hex.toIntOrNull(16)
              if (code != null) sb.append(code.toChar())
              i += 3
            }
            raw[i] == '\\' || raw[i] == '{' || raw[i] == '}' -> {
              sb.append(raw[i]); i++
            }
            raw[i] == 'n' -> { sb.append('\n'); i++ }
            raw[i] == 't' -> { sb.append('\t'); i++ }
            raw[i] == '\n' || raw[i] == '\r' -> {
              sb.append('\n'); i++
            }
            else -> {
              // Skip control word (letters) and optional numeric parameter
              while (i < raw.length && raw[i].isLetter()) i++
              if (i < raw.length && (raw[i] == '-' || raw[i].isDigit())) {
                while (i < raw.length && (raw[i] == '-' || raw[i].isDigit())) i++
              }
              if (i < raw.length && raw[i] == ' ') i++ // consume delimiter space
              // \par / \line produce paragraph breaks
            }
          }
        }
        raw[i] == '\n' || raw[i] == '\r' -> { sb.append('\n'); i++ }
        depth == 0 || depth == 1 -> { sb.append(raw[i]); i++ }
        else -> i++
      }
    }
    // Collapse excessive blank lines
    return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
  }

  // ── Text → Bitmap renderer ────────────────────────────────────────────────

  /**
   * Renders plain text onto a white A4-proportioned bitmap at enough DPI
   * that ML Kit OCR can extract clean blocks with accurate bounding boxes.
   */
  private fun renderTextToBitmap(text: String): Bitmap {
    val pageWidth = 1240   // ~A4 at 150 dpi
    val pageHeight = 1754
    val margin = 80
    val textWidth = pageWidth - margin * 2
    val textSize = 28f

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      this.textSize = textSize
      typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    val layout = StaticLayout.Builder
      .obtain(text, 0, text.length, paint, textWidth)
      .setAlignment(Layout.Alignment.ALIGN_NORMAL)
      .setLineSpacing(4f, 1f)
      .setIncludePad(false)
      .build()

    // Figure out how many A4 pages we need
    val contentHeight = layout.height
    val usableH = pageHeight - margin * 2
    val pageCount = ((contentHeight + usableH - 1) / usableH).coerceAtLeast(1)
    val totalHeight = pageCount * pageHeight

    val bmp = createBitmap(pageWidth, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)
    canvas.withTranslation(margin.toFloat(), margin.toFloat()) { layout.draw(this) }

    return bmp
  }
}
