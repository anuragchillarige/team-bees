# Settle — Implementation Plan for Claude Code

## Project context

You are building an Android app called **Settle** during a 24-hour hackathon. The app uses the device camera to identify risky clauses in legal documents (leases, terms of service) and highlights them with color-coded overlays. Tapping a highlight shows a plain-English explanation in a bottom sheet.

**Hardware**: Samsung Galaxy S25 Ultra with Snapdragon 8 Elite (SM8750), Hexagon NPU v79.

**Starting point**: The Google AI Edge Gallery app has been cloned and successfully built/installed on the device. Repo root is the gallery clone; Android project is at `Android/src/`. Gemma3 1B INT4 NPU has been verified working through the gallery's existing chat UI.

**Stack**:
- Kotlin + Jetpack Compose
- CameraX for camera preview and capture
- ML Kit Text Recognition for OCR
- LiteRT-LM with Gemma 4 E2B NPU build (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`)
- Existing reference: `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt`

**Working directory for new code**: `app/src/main/java/com/google/ai/edge/gallery/settle/`

### Execution rules

1. **One slice at a time.** Complete the current slice fully — including its verification checklist — then **stop and wait for explicit approval from the user before starting the next slice.** Do not begin Slice N+1 on your own initiative, even if you believe Slice N is complete.

2. **End-of-slice protocol.** When you believe a slice is done:
    - Run `./gradlew installDebug` and confirm it succeeds.
    - Commit your work with the specified commit message.
    - Post a brief summary including: (a) what was built, (b) any deviations from the plan and why, (c) the verification steps the user should perform on the device, (d) the literal text "**Slice N complete. Ready for verification. Awaiting approval to proceed to Slice N+1.**"
    - Then **stop**. Do not write more code. Do not preview the next slice. Do not speculate about what's next.

3. **Resuming.** Only begin the next slice when the user replies with explicit approval (e.g., "ok proceed to slice N+1", "go ahead", "next"). If the user reports a bug instead of approving, fix the bug within the current slice before asking again.

4. **Commit after every slice.** Use the specified commit message format (`slice N: <description>`). Never combine commits across slices.

5. **Verify on device, not assumptions.** Run `./gradlew installDebug` and confirm behavior on the connected S25 Ultra (`adb devices` shows it as authorized) before declaring a slice done.

6. **Read before writing.** When the plan references existing files (e.g., `LlmChatModelHelper.kt`), read them first with the `view` tool before writing code that depends on them. APIs in this codebase may differ from generic examples.

7. **Preserve gallery code.** Do not modify gallery code outside `app/src/main/java/com/google/ai/edge/gallery/settle/` unless the plan explicitly authorizes it (Slice 1 modifies `MainActivity.kt` and `AndroidManifest.xml` — that's the only exception).

8. **Defensive integration.** Every external call (OCR, LLM) must have a fallback path so the app never shows an empty screen on failure.

9. **Stop and ask immediately** (don't continue and ask later) if: the LiteRT-LM API in the codebase differs materially from what's described here, an NPU library is missing, the model file path cannot be located, or you need to make a scope tradeoff not covered by the plan.


## Shared data model

Define these once in Slice 2 and use them everywhere downstream. **Do not change these signatures in later slices** — they are the contract between OCR, classifier, and UI.

```kotlin
// app/src/main/java/com/google/ai/edge/gallery/settle/Models.kt
package com.google.ai.edge.gallery.settle

import android.graphics.Rect

data class SettleTextBlock(
    val id: Int,            // 1-indexed; used as LLM reference
    val text: String,
    val boundingBox: Rect   // pixel coordinates in source bitmap
)

enum class Risk { RED, YELLOW, GREEN, UNKNOWN }

data class ClauseResult(
    val id: Int,            // matches SettleTextBlock.id
    val risk: Risk,
    val plain: String,      // one-sentence summary in plain English
    val why: String         // one-sentence rationale
)
```

---

## Slice 0 — Verify NPU pipeline (status: COMPLETE)

Already done by the human. Gemma-4-E2B-it NPU runs in the gallery's chat UI. No action needed; this slice exists for context only.

---

## Slice 1 — Camera capture skeleton

**Objective**: A standalone screen that shows a live camera preview with an "Analyze" button. Tapping captures a still bitmap and shows it frozen on screen. A "Scan again" button returns to live preview.

**Why**: Visual skeleton for the entire app. Debug camera lifecycle and permissions in isolation before adding ML.

### Tasks

1. **Add CameraX dependencies** to `app/build.gradle.kts`. Check existing dependencies first; only add what's missing:
   ```kotlin
   implementation("androidx.camera:camera-core:1.3.4")
   implementation("androidx.camera:camera-camera2:1.3.4")
   implementation("androidx.camera:camera-lifecycle:1.3.4")
   implementation("androidx.camera:camera-view:1.3.4")
   ```

2. **Add camera permission** to `app/src/main/AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.CAMERA" />
   <uses-feature android:name="android.hardware.camera" android:required="true" />
   ```

3. **Replace the launcher activity.** Read `MainActivity.kt` first to understand its structure. Then either:
    - Modify it to launch directly into `SettleScreen`, OR
    - Create a new `SettleActivity.kt` and set it as the `MAIN`/`LAUNCHER` activity in the manifest, demoting the gallery's MainActivity.

   Pick the approach that requires fewer changes to existing gallery code. Comment out (don't delete) the original launcher intent filter so it can be restored.

4. **Create `settle/SettleScreen.kt`** with this structure:
    - A `Box` filling the screen.
    - State: `var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }`.
    - When `capturedBitmap == null`: show `CameraPreview` composable + an "Analyze" button at `Alignment.BottomCenter`.
    - When `capturedBitmap != null`: show the bitmap as an `Image` with `ContentScale.Fit` + a "Scan again" button that sets `capturedBitmap = null`.

5. **Implement `CameraPreview`** as a separate composable:
    - Use `AndroidView` wrapping `PreviewView`.
    - Bind a CameraX `Preview` use case + `ImageCapture` use case to the `LifecycleOwner`.
    - Expose an `onCapture: (Bitmap) -> Unit` callback.
    - On Analyze tap, call `imageCapture.takePicture(...)` with `OnImageCapturedCallback`, convert the resulting `ImageProxy` to a Bitmap, **rotate it according to `imageInfo.rotationDegrees`**, and invoke the callback.
    - The bitmap rotation is critical — if you skip it, OCR coordinates in Slice 2 will be wrong.

6. **Permission handling**: Use `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`. If denied, render a centered "Camera permission required" message with a retry button.

### Verification before commit

- App launches into the camera preview (no gallery task list visible).
- Tapping Analyze freezes the frame; the frozen image matches what was on screen.
- Tapping Scan again returns to live preview.
- Cycle works repeatedly without crashing.
- Rotating the device or backgrounding/foregrounding the app does not crash.

**End-of-slice action**:
1. Run `./gradlew installDebug` and confirm success.
2. Commit: `git commit -am "slice N: <description>"`
3. Post the slice-completion summary (per Execution Rule 2) and **stop**. Do not start Slice N+1.

**Commit message**: `slice 1: camera capture working`

---

## Slice 2 — OCR with bounding box overlay

**Objective**: After capture, run ML Kit text recognition on the bitmap and draw thin gray rectangles around every detected text block, perfectly aligned with the underlying text.

**Why**: Slice 4 will draw colored highlights using these same boxes. Coordinate alignment is the highest-risk technical detail in the project — debug it in isolation now.

### Tasks

1. **Add dependencies** to `app/build.gradle.kts`:
   ```kotlin
   implementation("com.google.mlkit:text-recognition:16.0.1")
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
   ```

2. **Create `settle/Models.kt`** with the data classes from the "Shared data model" section above. **Do not modify these in later slices.**

3. **Create `settle/OcrService.kt`**:
   ```kotlin
   class OcrService {
       private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

       suspend fun extractBlocks(bitmap: Bitmap): List<SettleTextBlock> {
           val image = InputImage.fromBitmap(bitmap, 0)  // bitmap already rotated in Slice 1
           val result = recognizer.process(image).await()
           return result.textBlocks.mapIndexedNotNull { index, block ->
               val rect = block.boundingBox ?: return@mapIndexedNotNull null
               SettleTextBlock(id = index + 1, text = block.text, boundingBox = rect)
           }
       }
   }
   ```
   Use `kotlinx.coroutines.tasks.await` for the `.await()` extension.

4. **Wire into `SettleScreen`**:
    - Add state: `var blocks by remember { mutableStateOf<List<SettleTextBlock>>(emptyList()) }`.
    - Add `LaunchedEffect(capturedBitmap)` that runs OCR when a bitmap is captured.
    - When `capturedBitmap = null`, also reset `blocks = emptyList()`.

5. **Create `settle/AnalyzedImage.kt`** — a composable that draws the bitmap and overlays bounding boxes. This is the file that gets extended in Slice 3 and 4. Initial signature:
   ```kotlin
   @Composable
   fun AnalyzedImage(
       bitmap: Bitmap,
       blocks: List<SettleTextBlock>,
       boxColor: (SettleTextBlock) -> Color = { Color.Gray },
       onBlockTap: ((SettleTextBlock) -> Unit)? = null
   )
   ```

6. **Coordinate math** — this is where bugs live. Inside the composable use `BoxWithConstraints` to get view dimensions, then compute:
   ```kotlin
   val scale = minOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
   val offsetX = (viewWidth - bitmap.width * scale) / 2f
   val offsetY = (viewHeight - bitmap.height * scale) / 2f
   ```
   For each block, draw a `Stroke`-style rect at `(rect.left * scale + offsetX, rect.top * scale + offsetY)` with size `(rect.width() * scale, rect.height() * scale)`. The `Image` composable must use `ContentScale.Fit` and `Modifier.fillMaxSize()` for these calculations to be correct.

7. **Replace the frozen-bitmap `Image`** in `SettleScreen` with `AnalyzedImage` passing `blocks`.

### Verification before commit

- Capture three different documents (a printed page, a menu, a screenshot of any text). On all three, gray boxes hug the text precisely.
- Boxes do not drift, scale incorrectly, or appear rotated relative to the text.
- If alignment is off, **fix it before committing**. Do not move to Slice 3 with broken coordinates.

**End-of-slice action**:
1. Run `./gradlew installDebug` and confirm success.
2. Commit: `git commit -am "slice N: <description>"`
3. Post the slice-completion summary (per Execution Rule 2) and **stop**. Do not start Slice N+1.

**Commit message**: `slice 2: OCR with aligned bounding boxes`

---

## Slice 3 — Keyword classifier and full UI

**Objective**: Complete, demoable app. Keyword-based risk classification, color-coded highlights, tappable boxes, bottom sheet with details. **No LLM in this slice.**

**Why**: At the end of this slice the app is shippable. Slice 4 only upgrades the brain inside `classify()`. If Slice 4 fails, this is what gets demoed.

### Tasks

1. **Create `settle/KeywordClassifier.kt`**:
   ```kotlin
   object KeywordClassifier {
       private val redKeywords = listOf(
           "arbitration", "class action", "waive", "waiver",
           "auto-renew", "automatic renewal", "non-refundable",
           "liquidated damages", "indemnify", "indemnification",
           "perpetual", "irrevocable", "binding"
       )
       private val yellowKeywords = listOf(
           "deposit", "notice", "late fee", "termination",
           "penalty", "interest", "default", "breach"
       )

       fun classify(blocks: List<SettleTextBlock>): List<ClauseResult> {
           return blocks.map { block ->
               val lower = block.text.lowercase()
               val risk = when {
                   redKeywords.any { lower.contains(it) } -> Risk.RED
                   yellowKeywords.any { lower.contains(it) } -> Risk.YELLOW
                   else -> Risk.GREEN
               }
               val matched = (redKeywords + yellowKeywords).firstOrNull { lower.contains(it) }
               ClauseResult(
                   id = block.id,
                   risk = risk,
                   plain = block.text.take(120) + if (block.text.length > 120) "…" else "",
                   why = matched?.let {
                       "Contains \"$it\", which often signals a clause limiting your rights or imposing obligations."
                   } ?: "Standard clause; no risk keywords detected."
               )
           }
       }
   }
   ```

2. **Wire into `SettleScreen`**:
    - Add state: `var results by remember { mutableStateOf<List<ClauseResult>>(emptyList()) }`.
    - Add `LaunchedEffect(blocks)` that calls `KeywordClassifier.classify(blocks)` when blocks change and are non-empty.

3. **Extend `AnalyzedImage`** to color-code highlights and handle taps:
    - Accept `results: List<ClauseResult>` as a new parameter.
    - Build `val resultById = results.associateBy { it.id }`.
    - In the Canvas, for each block, look up its result and pick a color:
        - `Risk.RED` → `Color.Red.copy(alpha = 0.3f)`
        - `Risk.YELLOW` → `Color(0xFFFFC107).copy(alpha = 0.3f)`
        - `Risk.GREEN` → `Color(0xFF4CAF50).copy(alpha = 0.3f)`
        - `Risk.UNKNOWN` → `Color.Gray.copy(alpha = 0.3f)`
    - Draw a filled rect (no Stroke style) using this color, then a stroked rect on top with `alpha = 1f` for definition.
    - Add `Modifier.pointerInput(blocks) { detectTapGestures { tap -> ... } }` to the Canvas. Convert `tap.x, tap.y` back to bitmap coordinates by reversing the scale/offset math, then find the block whose `boundingBox.contains(bitmapX, bitmapY)`. Invoke the tap callback with that block.

4. **Add bottom sheet to `SettleScreen`**:
   ```kotlin
   var selectedClause by remember { mutableStateOf<Pair<SettleTextBlock, ClauseResult>?>(null) }
   ```
   When a block is tapped, set `selectedClause` to `(block, resultById[block.id])`. Render a `ModalBottomSheet` (Material 3) when non-null with:
    - Risk badge: a colored circle + label ("HIGH RISK" / "CAUTION" / "STANDARD").
    - Section "Plain English" with `result.plain`.
    - Section "Why this matters" with `result.why`.
    - Collapsible section "Original text" showing `block.text` in smaller, muted typography.
    - Dismissal sets `selectedClause = null`.

### Verification before commit

- Capture a document with at least one obviously red clause (e.g., a printed page containing the word "arbitration") and one neutral clause.
- Red highlight appears over the arbitration clause, green over the neutral clause.
- Tapping the red highlight opens the bottom sheet with the matching original text and a "HIGH RISK" badge.
- Tapping outside dismisses the sheet.
- Capturing a new document refreshes the highlights.

**This is your demo safety net.** If Slice 4 fails, you ship this version.

**End-of-slice action**:
1. Run `./gradlew installDebug` and confirm success.
2. Commit: `git commit -am "slice N: <description>"`
3. Post the slice-completion summary (per Execution Rule 2) and **stop**. Do not start Slice N+1.

**Commit message**: `slice 3: full app with keyword classifier — DEMO READY`

---

## Slice 4 — LiteRT-LM Gemma 4 E2B integration

**Objective**: Replace `KeywordClassifier.classify()` with calls to Gemma 4 E2B running on the NPU. Same input/output types; smarter results. Keyword classifier remains as fallback for any LLM failure.

**Why this is the longest slice**: Three things at once — LiteRT-LM API integration, prompt engineering, defensive JSON parsing. Each takes time.

### Tasks

 1. **Read these files in full before writing any code.** They are the authoritative reference for the LiteRT-LM Kotlin API as used in this codebase. Do not invent class names, method signatures, or import paths — copy them from these files:
    - `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt`
    - `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt` (if it exists)
    - Any file the above two import from `com.google.ai.edge.litertlm` or similar
    - `app/build.gradle.kts` to confirm which LiteRT-LM Maven coordinate and version are in use

 Confirm the actual class names for: engine creation, conversation creation, message sending, response streaming. If they differ from the names used elsewhere in this plan (e.g., `Engine`, `Conversation`, `EngineConfig`, `Backend.NPU`, `Message.user`, `Contents.of`, `Content.Text`), use the codebase names. Report any deviations in your end-of-slice summary.


   The plan below uses the API as described in the developer guide. **If the codebase uses different class names or signatures, follow the codebase, not the plan.**

2. **Verify the model is available.** Check:
    - Is `gemma-4-E2B-it_qualcomm_sm8750.litertlm` listed as a downloadable model in the gallery's UI? If yes, document its on-device path (typically under `/data/data/com.google.ai.edge.gallery/files/...`).
    - Are the NPU `.so` libraries in `app/src/main/jniLibs/arm64-v8a/`? Required: `libLiteRtDispatch_Qualcomm.so`, `libQnnHtp.so`, `libQnnHtpV79Skel.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`, `libGemmaModelConstraintProvider.so`.
    - **If the model is not available**, fall back to Gemma 3 1B INT4 NPU (`Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm`) — this was verified working in Slice 0. Update the model path constant accordingly. **Stop and report this to the user before continuing**, because output quality will differ.

3. **Create `settle/SettleAnalyzer.kt`**:
   ```kotlin
   class SettleAnalyzer(private val context: Context) {
       private var engine: Engine? = null
       private var conversation: Conversation? = null

       suspend fun initialize(modelPath: String) { /* see step 4 */ }
       suspend fun classifyBlocks(blocks: List<SettleTextBlock>): List<ClauseResult> { /* see step 5 */ }
       fun close() { /* release engine + conversation */ }
   }
   ```

4. **Implement `initialize`**: build `EngineConfig` with `Backend.NPU(context.applicationInfo.nativeLibraryDir)` and the model path. Call `Engine(config).initialize()`. Create a conversation with `engine.createConversation()`. Adapt to the actual API found in `LlmChatModelHelper.kt`.

5. **Implement `classifyBlocks`**:
    - Build the prompt via `buildPrompt(blocks)` (see step 6).
    - Send via `conversation.sendMessageAsync(Message.user(Contents.of(Content.Text(prompt))))`.
    - `.collect` the flow, accumulating all `Content.Text` chunks into a `StringBuilder`.
    - When complete, call `parseResponse(buffer.toString(), blocks)`.
    - Wrap the entire send+collect in try/catch. On any exception, log and return `KeywordClassifier.classify(blocks)`.

6. **The prompt** — define `buildPrompt`:
   ```kotlin
   private fun buildPrompt(blocks: List<SettleTextBlock>): String {
       val capped = blocks.take(25)  // protect KV cache
       val numbered = capped.joinToString("\n") {
           "[${it.id}] ${it.text.replace("\n", " ").take(300)}"
       }
       return """
   You are a legal risk classifier. Classify each numbered clause from a contract or terms-of-service document.

   Risk levels:
   - "red": binding arbitration, class action waivers, auto-renewal traps, large or unusual fees, broad liability waivers, indemnification clauses
   - "yellow": notice periods, security deposits, standard late fees, termination conditions
   - "green": routine boilerplate (definitions, governing law, severability, headings)

   Respond with ONLY valid JSON. No preamble, no markdown, no code fences. Schema:
   {"clauses":[{"id":<int>,"risk":"red|yellow|green","plain":"<one sentence>","why":"<one sentence>"}]}

   Example input:
   [1] Tenant agrees to binding arbitration and waives the right to a jury trial.
   [2] Rent is due on the first of each month.

   Example output:
   {"clauses":[{"id":1,"risk":"red","plain":"You give up the right to sue in court if there is a dispute.","why":"Binding arbitration plus a jury trial waiver removes your strongest legal protections."},{"id":2,"risk":"green","plain":"Rent is due monthly on the 1st.","why":"Standard payment term in any lease."}]}

   Now classify these clauses:
   $numbered
   """.trimIndent()
   }
   ```

7. **Defensive JSON parsing** — implement `parseResponse`:
   ```kotlin
   private fun parseResponse(raw: String, blocks: List<SettleTextBlock>): List<ClauseResult> {
       return try {
           val start = raw.indexOf("{")
           val end = raw.lastIndexOf("}")
           if (start == -1 || end <= start) return KeywordClassifier.classify(blocks)
           val json = raw.substring(start, end + 1)
           val arr = JSONObject(json).getJSONArray("clauses")
           val results = mutableListOf<ClauseResult>()
           for (i in 0 until arr.length()) {
               val item = arr.getJSONObject(i)
               results += ClauseResult(
                   id = item.getInt("id"),
                   risk = when (item.optString("risk").lowercase()) {
                       "red" -> Risk.RED
                       "yellow" -> Risk.YELLOW
                       "green" -> Risk.GREEN
                       else -> Risk.UNKNOWN
                   },
                   plain = item.optString("plain", ""),
                   why = item.optString("why", "")
               )
           }
           // Backfill any IDs the LLM dropped
           val seen = results.map { it.id }.toSet()
           results + KeywordClassifier.classify(blocks.filter { it.id !in seen })
       } catch (e: Exception) {
           Log.e("SettleAnalyzer", "Parse failed: $raw", e)
           KeywordClassifier.classify(blocks)
       }
   }
   ```

8. **Wire into `SettleScreen`**:
   ```kotlin
   val context = LocalContext.current
   val analyzer = remember { SettleAnalyzer(context) }
   var analyzerReady by remember { mutableStateOf(false) }

   LaunchedEffect(Unit) {
       try {
           analyzer.initialize(MODEL_PATH)
           analyzerReady = true
       } catch (e: Exception) {
           Log.e("SettleScreen", "Analyzer init failed", e)
           // analyzerReady stays false; classification will use keyword fallback
       }
   }

   DisposableEffect(Unit) { onDispose { analyzer.close() } }

   LaunchedEffect(blocks) {
       if (blocks.isEmpty()) return@LaunchedEffect
       results = if (analyzerReady) analyzer.classifyBlocks(blocks)
                 else KeywordClassifier.classify(blocks)
   }
   ```

9. **Loading state**: when `capturedBitmap != null && results.isEmpty()`, overlay a centered `CircularProgressIndicator` with "Analyzing…" text.

10. **Hour-16 stop rule**: if the LLM is producing unparseable output or wrong classifications by hour 16 of the hackathon and prompt iteration is not converging, stop. The keyword fallback already produces acceptable demo behavior. **Tell the user before reverting.**

### Verification before commit

- Print or display two real test documents: a residential lease page and a digital subscription ToS page. Capture both.
- For at least one of them, the LLM (not the keyword fallback) returns parseable JSON.
- Obvious red clauses (containing "arbitration", "auto-renew") are correctly classified red.
- Bottom-sheet `plain` and `why` fields contain LLM-generated text, not the keyword classifier's templated text.
- Forcing a failure (kill the LLM mid-call, or pass malformed input) gracefully falls back to keyword classification — no crashes, no empty screens.

**End-of-slice action**:
1. Run `./gradlew installDebug` and confirm success.
2. Commit: `git commit -am "slice N: <description>"`
3. Post the slice-completion summary (per Execution Rule 2) and **stop**. Do not start Slice N+1.

**Commit message**: `slice 4: Gemma 4 E2B classification with keyword fallback`

---

## Slice 5 — Polish

**Objective**: The app exists and works. Make it feel intentional.

### Tasks

1. **Highlight fade-in animation**. In `AnalyzedImage`, add an `Animatable(0f)` that animates to `1f` over 600ms (`tween(600)`) keyed on `results`. Multiply each box's alpha by this progress value. Reset to 0 when results change.

2. **Loading-state polish**. Replace the spinner with a custom "scan line" effect: a horizontal line that animates top-to-bottom across the frozen image while results are pending. Use `rememberInfiniteTransition` with a 1.5s duration.

3. **Bottom sheet typography pass**:
    - Risk badge: filled `Surface` with rounded corners, contrasting text color.
    - "Plain English" / "Why this matters" as `MaterialTheme.typography.titleMedium`.
    - Body text as `bodyMedium`.
    - "Original text" collapsed by default behind an `ExpandMore` icon.

4. **"Not legal advice" disclaimer**: small banner at the top of `SettleScreen` (`Surface` with `tertiaryContainer` color), short text "Settle helps spot risks. This is not legal advice." Also at the bottom of the bottom sheet in muted typography.

5. **Edge cases**:
    - `blocks.isEmpty()` after OCR completes (no text detected): show a centered "No text detected. Try better lighting or move closer." with a Scan again button.
    - Block count > 25: in `buildPrompt`, sort by `boundingBox.width() * boundingBox.height()` descending and take top 25 (substantive clauses are larger). Already partially handled by `.take(25)` — replace with sorted version.

6. **App identity**:
    - Change `app_name` in `app/src/main/res/values/strings.xml` to "Settle".
    - Replace `ic_launcher` with a simple custom icon. If no time, change the background color of the adaptive icon to a brand color via `ic_launcher_background.xml`.

7. **Haptic feedback**: in `AnalyzedImage`'s tap handler, before invoking the callback, call `view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)`.

### Verification before commit

- Capture → highlights fade in smoothly.
- Bottom sheet looks polished, not boilerplate.
- Disclaimer is visible without obstructing the camera.
- Empty-text edge case shows the helpful message.
- App icon and name on the home screen say "Settle", not "Edge Gallery".

**End-of-slice action**:
1. Run `./gradlew installDebug` and confirm success.
2. Commit: `git commit -am "slice N: <description>"`
3. Post the slice-completion summary (per Execution Rule 2) and **stop**. Do not start Slice N+1.

**Commit message**: `slice 5: polish pass`

---

## Slice 6 — Demo prep

**Objective**: Eliminate demo-day surprises.

### Tasks

1. **Add a demo escape hatch**. Place 2-3 baked-in document images in `app/src/main/assets/` (e.g., `demo_lease.jpg`, `demo_tos.jpg`). In `SettleScreen`, change the Analyze button to support long-press:
   ```kotlin
   Modifier.combinedClickable(
       onClick = { triggerCapture() },
       onLongClick = {
           val asset = context.assets.open("demo_lease.jpg")
           capturedBitmap = BitmapFactory.decodeStream(asset)
       }
   )
   ```
   Do not surface this in the UI. It exists only to rescue the demo if live capture misbehaves.

2. **Stress test**. Capture each demo document 10 times. Confirm:
    - OCR finds the same blocks reliably.
    - LLM classification is consistent across runs (same red clauses flagged red).
    - No crashes.

   If any document fails more than once in 10 tries, replace it.

3. **Demo-day device prep** (these are runtime steps, not code — leave a NOTES.md in the repo root with this checklist):
    - Charge to 100%.
    - Disable auto-rotate.
    - Enable airplane mode.
    - Set screen brightness to max.
    - Close all background apps.

4. **Final regression test**. Run through the full demo flow three times in a row without restarting the app:
    - Capture demo_lease.jpg via long-press → highlights → tap red → bottom sheet → dismiss → Scan again.
    - Capture demo_tos.jpg via long-press → highlights → tap red → bottom sheet → dismiss → Scan again.
    - Capture a live document → highlights → tap → bottom sheet.

   Any crash or visual glitch must be fixed before declaring done.

**Commit message**: `slice 6: demo ready`

---

### When to stop mid-slice and report

In addition to the end-of-slice stop protocol above, **stop and report mid-slice** (don't keep grinding) if:

- A verification step fails twice and you cannot identify the cause.
- The LiteRT-LM API in `LlmChatModelHelper.kt` cannot be adapted to the `SettleAnalyzer` design (different class names, missing methods, etc.).
- Gemma 4 E2B is not available and you need to fall back to Gemma 3 1B.
- NPU libraries are missing from `jniLibs/arm64-v8a/`.
- You have made 3 consecutive unsuccessful attempts at the same sub-task.
- The user's instructions in this thread conflict with the written plan.

When reporting mid-slice, include: the slice you're in, the specific error or unexpected behavior, what you've already tried, and your proposed next step. Then wait for guidance.