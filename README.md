# Settle

**Point your camera at a contract. See where the risk lives.**

Settle is an Android app that uses the device camera to surface risky clauses in everyday legal documents — leases, terms of service, employment offers, gym waivers, credit agreements — and explains them in plain English. Risky language gets a colored highlight, and tapping it opens a panel with what the clause actually means, what to watch for, and a chat where you can ask follow‑up questions about that specific clause. Every model call runs **on‑device** on the Hexagon NPU; nothing about your document leaves the phone.

> ⚠️ Settle helps spot risks. It is **not** legal advice and is not a substitute for talking to a lawyer.

---

## Why this exists

Most people sign documents they don't fully read. The problematic parts — binding arbitration clauses, perpetual content licenses, auto‑renewal at higher rates, broad indemnification, hidden fees — are usually buried in dense legalese that's deliberately hard to parse. People who *would* read carefully often can't tell which sentences actually carry risk and which are routine boilerplate.

Settle is a fast, private way to triage a document the moment it's in front of you. You don't have to upload it anywhere, scan a PDF, or paste text into a third‑party AI website. Take a picture, see the heat, tap to understand.

## What it does

1. **Live camera preview with pinch‑to‑zoom.** Frame the page; pinch to zoom in on small print.
2. **Capture & OCR.** Tap *Analyze* to capture a still. ML Kit's Latin text recognizer extracts blocks of text with their on‑page coordinates.
3. **Heading filter.** Page numbers, signature lines, ALL‑CAPS section headers, and other non‑prose blocks are filtered out so they aren't classified or highlighted.
4. **Risk classification on the NPU.** The remaining clauses are sent to **Gemma 4 E2B** running on the Snapdragon 8 Elite Hexagon NPU through LiteRT‑LM. The model returns one of `red` / `yellow` per clause along with a plain‑English summary and an actionable note ("ask whether arbitration is opt‑in", "set a calendar reminder for the cancellation window", etc.).
5. **Color‑coded overlays.** Red and yellow clauses get translucent highlights with crisp outlines, fading in over the captured frame. 
6. **Tap a highlight → bottom sheet.** A modal sheet slides up showing the risk badge, *What this means* (paraphrase), *Why this matters* (one‑sentence guidance), and a collapsible *Original text* section.
7. **Ask follow‑up questions.** Beneath the static fields is *Ask about this clause* — a chat input where you can ask the LLM specific questions about that clause. Answers stream in turn‑by‑turn; the model keeps prior turns in context, so you can drill down ("what about California?", "expand on the previous answer").

The OCR coordinates are preserved through the whole pipeline, so highlights line up exactly with the underlying text on the captured photo.

## Demo flow

```
camera preview  →  Analyze (capture)  →  scan‑line "Analyzing…" overlay
                                       →  red/yellow highlights fade in
                                       →  tap highlight  →  bottom sheet
                                                        →  Ask about this clause
                                                        →  streamed answer
                                       →  Scan again  →  back to live preview
```

## Hardware target

Settle was built and tested on:

- **Samsung Galaxy S25 Ultra** (SM‑S938U)
- **Snapdragon 8 Elite (SM8750)** with the **Hexagon NPU v79**
- Android 14+

The NPU‑specific Gemma 4 E2B build (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`) is required for the LLM path. On hardware without that NPU, the engine init will fail and the app falls back to a keyword classifier that still produces colored highlights but with templated guidance. Follow‑up Q&A requires the NPU model.

## How it's built

Settle is implemented as a **camera‑first activity layered on top of the [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) sample app**. The gallery's architecture — its `litertlm-android` integration, model‑download / native‑library plumbing, theming, and Hilt scaffolding — is what made it possible to build a full NPU‑backed product over a hackathon weekend without rewriting the runtime layer. We deliberately kept the gallery's source intact and added a self‑contained `settle/` package alongside it.

Concretely:

- The **launcher activity** is now `SettleActivity` (camera UI), with the gallery's original `MainActivity` demoted (its `MAIN`/`LAUNCHER` intent filter is commented out, not deleted, so it can be restored).
- All Settle code lives under `Android/src/app/src/main/java/com/google/ai/edge/gallery/settle/`. We did not modify the rest of the gallery codebase except for the manifest entry and a small `extractNativeLibs="true"` flag needed to make the QNN dispatch libraries `dlopen`‑able at runtime.
- The `LiteRT‑LM` API used here (Engine / Conversation / MessageCallback / Backend.NPU) is the same one the gallery's chat surface uses; we read `LlmChatModelHelper.kt` to learn the actual callback‑based API and wrapped it for Settle.

### Settle module layout

```
app/src/main/java/com/google/ai/edge/gallery/settle/
├── SettleActivity.kt        Launcher activity, hosts SettleScreen in GalleryTheme
├── SettleScreen.kt          Camera/capture stage, OCR + classification orchestration,
│                            disclaimer banner, scan‑line overlay, empty‑text edge case,
│                            modal bottom sheet
├── CameraPreview.kt         CameraX PreviewView + ImageCapture, white bezels,
│                            pinch‑to‑zoom transform gesture
├── OcrService.kt            ML Kit text recognizer + heading/short‑fragment filter
├── AnalyzedImage.kt         Bitmap + Canvas overlay; coordinate math for highlights;
│                            fade‑in animation; tap detection with haptic feedback
├── KeywordClassifier.kt     Conservative keyword fallback (only fires if LLM unavailable)
├── SettleAnalyzer.kt        LiteRT‑LM Engine/Conversation lifecycle, classification
│                            prompt + lenient JSON parser, follow‑up Q&A streaming
├── FollowUpSection.kt       Bottom‑sheet chat UI for follow‑up questions
└── Models.kt                SettleTextBlock, ClauseResult, FollowUpExchange, Risk enum
```

### Pipeline at runtime

```
CameraX                                     ML Kit                  LiteRT‑LM (NPU)
┌──────────────┐  ImageCapture  ┌─────────────────┐  blocks   ┌──────────────────────┐
│ PreviewView  │ ─────────────→ │ TextRecognition │ ────────→ │ Gemma 4 E2B          │
│ (FIT_CENTER, │   rotated      │ + heading filter│           │ classification prompt│
│  white bg)   │   bitmap       │                 │           │ → JSON {clauses:[…]} │
└──────────────┘                └─────────────────┘           └──────────┬───────────┘
                                                                         │ red/yellow only
                                                                         ▼
                                                          ┌──────────────────────────┐
                                                          │ Lenient extractor (handles│
                                                          │ Gemma's unescaped quotes) │
                                                          └──────────┬───────────────┘
                                                                     ▼
                                ┌─────────────────────────────────────────────────────┐
                                │ AnalyzedImage: bitmap + coordinate‑aligned overlays │
                                │ Tap → bottom sheet → FollowUpSection                │
                                │       → Conversation reuse for multi‑turn Q&A       │
                                └─────────────────────────────────────────────────────┘
```

## Tech stack

- **UI:** Kotlin + Jetpack Compose, Material 3, custom `ModalBottomSheet`
- **Camera:** CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) with `Preview` + `ImageCapture` use cases, pinch‑to‑zoom via `detectTransformGestures`
- **OCR:** ML Kit Text Recognition (`com.google.mlkit:text-recognition`) on the rotated bitmap
- **LLM runtime:** [LiteRT‑LM](https://github.com/google-ai-edge/LiteRT-LM) (`com.google.ai.edge.litertlm:litertlm-android:0.11.0-rc1`) with `Backend.NPU(nativeLibraryDir = …)`
- **Model:** Gemma 4 E2B IT, Qualcomm SM8750 NPU build (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`, ~3 GB) from [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- **Native libs (`jniLibs/arm64-v8a/`):** `libLiteRtDispatch_Qualcomm.so`, `libQnnHtp.so`, `libQnnHtpV79Skel.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`, `libGemmaModelConstraintProvider.so` from the [`litert-samples`](https://github.com/google-ai-edge/litert-samples) NPU sample / [`LiteRT‑LM`](https://github.com/google-ai-edge/LiteRT-LM) prebuilts
- **Concurrency:** Kotlin coroutines (`kotlinx-coroutines-play-services`, `callbackFlow`, `suspendCancellableCoroutine`) to bridge the callback‑based `MessageCallback` API into suspend / `Flow<String>` shapes

## Build & run

### Prerequisites

- Android Studio (Iguana or newer recommended)
- A device with a Snapdragon 8 Elite (SM8750) Hexagon NPU v79 — the **Galaxy S25 Ultra** is the reference device. Other NPUs will require a different `.litertlm` artifact and may need different QNN dispatch libs.
- ~5 GB free on `/sdcard` for the model file

### 1. Clone and build

```bash
git clone <this repo>
cd google_hackathon/Android/src
./gradlew installDebug
```

The build extracts the bundled QNN native libraries to the device's app `lib/` directory (the manifest sets `android:extractNativeLibs="true"` so the dispatch loader can `dlopen` them by path).

### 2. Push the NPU model file

The Gemma 4 E2B NPU model is too large to include in the APK and isn't part of the gallery's allowlist. Download it once from Hugging Face:

```bash
curl -L -o /tmp/gemma-4-E2B-it_qualcomm_sm8750.litertlm \
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm
```

Then push it to the path Settle expects:

```bash
adb shell mkdir -p /sdcard/Android/data/com.google.aiedge.gallery/files/settle/
adb push /tmp/gemma-4-E2B-it_qualcomm_sm8750.litertlm \
  /sdcard/Android/data/com.google.aiedge.gallery/files/settle/gemma-4-E2B-it_qualcomm_sm8750.litertlm
```

If the file is missing at launch, Settle logs a warning and falls back to the keyword classifier; classification highlights still appear but the bottom sheet's *What this means* / *Why this matters* will be templated and follow‑up Q&A will be disabled.

### 3. Launch

```bash
adb shell am start -n com.google.aiedge.gallery/com.google.ai.edge.gallery.settle.SettleActivity
```

Or just tap the **Settle** icon on the launcher.

## Privacy

- Every model call (OCR + classification + follow‑up Q&A) runs on the device's NPU/CPU. Nothing about the document or your questions is sent to a network.
- The captured bitmap lives in memory for the duration of one analysis and is dropped on *Scan again*; nothing is written to disk.
- The model file itself sits at the path above and can be deleted at any time.

## Acknowledgments

Built on top of the **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)** sample app. The gallery's runtime, theming, native‑library wiring, and overall architecture are what made an on‑device NPU product feasible in a hackathon timeframe. The `LlmChatModelHelper.kt` file in the gallery was the primary reference for adapting LiteRT‑LM's callback‑based API into Settle's suspend / Flow surface.

Other components used:

- **Gemma 4 E2B** from the LiteRT community
- **LiteRT‑LM** runtime
- **ML Kit** for on‑device text recognition
- **Qualcomm QNN HTP** dispatch libraries (via the `litert-samples` repo)

## License

Apache 2.0 — see [LICENSE](LICENSE).