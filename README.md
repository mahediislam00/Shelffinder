# ShelfFinder Live

ShelfFinder Live is a real-world **Ctrl+F**. Point the phone at a pharmacy shelf and type a medicine name. Every matching pack lights up on the live camera view as you move the phone.

It is the live-camera version of the ShelfFinder web demo. The web demo searches one photo that was OCR'd in advance. This app reads text from every camera frame on the phone itself. It works offline and uploads nothing.

## What it does

| Feature | How |
|---|---|
| Live text detection | Google ML Kit Text Recognition v2. The model is bundled in the app, so it works offline with no API key. It reads frames at up to 1920×1440. |
| Fuzzy matching | Forgives OCR mistakes (0/O, 1/I, 5/S, 8/B) and typos. "pantonics" still finds **Pantonix**. Three levels: Exact, Normal, Loose. |
| Works like Ctrl+F | Finds the query inside longer words ("Itra" inside "Itranova") with a tight box around the matching letters. Also finds names that OCR split in two ("PANTO NIX") and two-word brands ("Bisopro Plus"). |
| Several names at once | Separate names with commas: `valex, secrin, pantonix`. Each name gets its own colour. |
| Stable highlights | Boxes glide between frames, stay up through brief OCR misses, then fade out. A pulse and a short vibration mark each new find. |
| Spotlight | Dims everything except the matches. |
| Show all text | Draws a box around every word the camera can read. Tap any word to search for it. |
| Pause | Freezes the frame so you can keep the phone still. New searches run against the paused frame. |
| Camera controls | Flashlight, zoom slider, pinch to zoom, tap to focus. |
| Voice search and autocomplete | Mic button. The dropdown suggests 226 brand names taken from the web demo's index, and recent searches show as chips. |

**Tip:** To read text printed vertically on a pack, turn the phone sideways. The app stays in portrait, so sideways text reaches the OCR as horizontal text.

## Build it

### Option A: Android Studio
1. Install Android Studio: https://developer.android.com/studio
2. **File → Open** this folder. Let Gradle sync (first sync downloads about 1 GB of SDK files and libraries).
3. Turn on USB debugging on the phone: https://developer.android.com/studio/debug/dev-options
4. Plug in the phone and press **Run ▶**: https://developer.android.com/studio/run/device
5. To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The file lands in `app/build/outputs/apk/debug/`.

### Option B: GitHub Actions (no Android Studio)
1. Push this folder to a GitHub repo: https://docs.github.com/en/get-started/importing-your-projects-to-github/importing-source-code-to-github/adding-locally-hosted-code-to-github
2. The workflow in `.github/workflows/build-apk.yml` runs on every push to `main`. You can also start it by hand from the **Actions** tab.
3. Download the APK from the run's **Artifacts** section: https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-workflow-runs/downloading-workflow-artifacts
4. Install it on the phone. You need to allow "install unknown apps": https://support.google.com/android/answer/7391672

### Command line
```bash
./gradlew assembleDebug        # needs JDK 17+ and the Android SDK (ANDROID_HOME)
```

## Requirements
- Android 8.0 (API 26) or newer, with a rear camera
- Toolchain: AGP 8.9.1, Kotlin 2.1.21, Gradle 8.14.3, compileSdk 35

## Code map
```
app/src/main/java/app/shelffinder/live/
  MainActivity.kt   camera (CameraX LifecycleCameraController + MlKitAnalyzer), search UI, controls
  MatchCore.kt      normalisation + semi-global Levenshtein (pure Kotlin, unit-testable)
  TextMatcher.kt    turns ML Kit Text into per-character boxes and finds every term
  MatchTracker.kt   smooths per-frame hits into stable, animated highlights
  OverlayView.kt    draws boxes, labels, spotlight, word-tap handling
app/src/main/assets/brands.txt   autocomplete list (edit freely; any word can be searched)
```

The analyzer uses `COORDINATE_SYSTEM_VIEW_REFERENCED`, so ML Kit returns boxes already in on-screen pixels. Rotation, cropping and scaling need no hand-written mapping.

## Notes
- The release build is signed with the debug key so it can be side-loaded. Set up your own signing before publishing to Play: https://developer.android.com/studio/publish/app-signing
- ML Kit reads Latin script. Bangla text on packs won't match. ML Kit has a Devanagari model but no Bangla model.
