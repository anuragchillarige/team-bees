/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.ai.edge.gallery.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shown inside the clause detail bottom sheet (Slice 5.5). Lets the user ask free-text follow-up
 * questions about the currently-selected clause; answers stream in chunk-by-chunk from the same
 * on-device LLM used for classification. State is keyed on [clauseId] so opening a different
 * clause's panel resets input + history.
 */
@Composable
fun FollowUpSection(
  clauseText: String,
  analyzer: SettleAnalyzer,
  analyzerReady: Boolean,
  clauseId: Int,
  modifier: Modifier = Modifier,
) {
  var input by remember(clauseId) { mutableStateOf("") }
  var exchanges by remember(clauseId) { mutableStateOf<List<FollowUpExchange>>(emptyList()) }
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val streaming = exchanges.any { it.isLoading }

  Column(modifier = modifier.fillMaxWidth()) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Spacer(Modifier.height(16.dp))
    Text(
      text = "Ask about this clause",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))

    exchanges.forEach { exchange ->
      FollowUpBubble(exchange)
      Spacer(Modifier.height(8.dp))
    }

    Row(verticalAlignment = Alignment.Bottom) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text("e.g. What if I break this early?") },
        modifier = Modifier.weight(1f),
        enabled = analyzerReady && !streaming,
        singleLine = false,
        maxLines = 3,
      )
      Spacer(Modifier.width(8.dp))
      IconButton(
        onClick = {
          val q = input.trim()
          if (q.isEmpty() || !analyzerReady || streaming) return@IconButton
          input = ""
          keyboard?.hide()

          val newExchange = FollowUpExchange(question = q, answer = "", isLoading = true)
          exchanges = exchanges + newExchange
          val index = exchanges.lastIndex

          scope.launch {
            val buffer = StringBuilder()
            try {
              analyzer.askFollowUp(clauseId, clauseText, q).collect { chunk ->
                buffer.append(chunk)
                exchanges =
                  exchanges.toMutableList().also {
                    if (index < it.size) it[index] = it[index].copy(answer = buffer.toString())
                  }
              }
            } finally {
              exchanges =
                exchanges.toMutableList().also {
                  if (index < it.size) it[index] = it[index].copy(isLoading = false)
                }
            }
          }
        },
        enabled = input.isNotBlank() && analyzerReady && !streaming,
      ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
      }
    }

    if (!analyzerReady) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = "AI not ready yet — follow-up questions will be available once the model loads.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun FollowUpBubble(exchange: FollowUpExchange) {
  Column(modifier = Modifier.fillMaxWidth()) {
    // User question — right-aligned bubble.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 280.dp),
      ) {
        Text(
          text = exchange.question,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    // Model answer — left-aligned bubble.
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (exchange.answer.isEmpty() && exchange.isLoading) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("Thinking…", style = MaterialTheme.typography.bodySmall)
        } else {
          Text(text = exchange.answer, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}