/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Settle's monochromatic, typography-first palette. Off-white canvas, near-black headings,
 * charcoal body text, mid-grey for secondary copy. Risk colors (red/yellow/green) live in the
 * highlight/badge code itself — the rest of the UI stays neutral so the colors carry signal.
 */
private val SettleColors =
  lightColorScheme(
    background = Color(0xFFFAFAF7), // warm off-white canvas
    onBackground = Color(0xFF0F0F0F), // near-black for primary text
    surface = Color(0xFFFAFAF7),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFE8E6E0), // muted off-white for original-text card
    onSurfaceVariant = Color(0xFF4A4A4A), // mid-grey for secondary copy
    primary = Color(0xFF0F0F0F), // near-black FABs / accent
    onPrimary = Color(0xFFFAFAF7),
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color(0xFFFAFAF7),
    secondaryContainer = Color(0xFFEFEFEC),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF4A4A4A),
    tertiaryContainer = Color(0xFFE8E6E0), // disclaimer banner background
    onTertiaryContainer = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF1A1A1A), // user question bubble fill
    onPrimaryContainer = Color(0xFFFAFAF7),
    outline = Color(0xFF8A8A85),
    outlineVariant = Color(0xFFD8D6D0),
  )

// Sans-serif base with slightly tightened tracking and refined weights to feel closer to
// Helvetica's typographic personality on a stack that doesn't actually ship Helvetica.
private val SansBase = FontFamily.SansSerif

private val SettleTypography =
  Typography(
    titleLarge =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
        lineHeight = 28.sp,
      ),
    titleMedium =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.1).sp,
        lineHeight = 22.sp,
      ),
    titleSmall =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
        lineHeight = 20.sp,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
        lineHeight = 24.sp,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
        lineHeight = 20.sp,
      ),
    bodySmall =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
        lineHeight = 16.sp,
      ),
    labelLarge =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        lineHeight = 16.sp,
      ),
    labelMedium =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
        lineHeight = 14.sp,
      ),
    labelSmall =
      TextStyle(
        fontFamily = SansBase,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        lineHeight = 14.sp,
      ),
  )

@Composable
fun SettleTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = SettleColors, typography = SettleTypography, content = content)
}