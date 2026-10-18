# Lumora — Premium Native Android Photo Editor

A professional-grade, non-destructive photo editing application for Android,
built with Kotlin and Jetpack Compose. Inspired by Adobe Lightroom's workflow,
designed with an original premium dark identity.

## Features

### Editing Engine (GPU-accelerated, OpenGL ES 3.0)
- **Light**: Exposure, contrast, highlights, shadows, whites, blacks, tone curve
- **Color**: White balance (temp/tint), vibrance, saturation, HSL color mixer (8 channels)
- **Color Grading**: 3-way color wheels (shadows/midtones/highlights) + global
- **Detail**: Clarity, texture, dehaze, sharpening, noise reduction
- **Optics**: Lens distortion correction, defringe, vignette, grain
- **Geometry**: Crop, rotate, flip, perspective (keystone) correction
- **Masking**: Brush, linear gradient, radial gradient with add/subtract/intersect
- **Retouch**: Heal/clone, skin smoothing, eye enhancement, teeth whitening
- **Presets**: 9 built-in professional presets + custom preset creation with strength control

### Non-Destructive Workflow
- All edits stored as parameters (JSON sidecars), never baked into pixels
- Full undo/redo (60 steps), edit history, session autosave & recovery
- Before/after comparison, histogram, clipping warnings

### Import & Export
- Android Photo Picker and Storage Access Framework import
- Full-resolution GPU export with EXIF preservation
- JPEG/PNG/WebP, quality control, resize options, cancellation support
- Direct share to other apps

### Library
- MediaStore integration, albums, favorites, ratings, search
- Edit history per photo

## Building

### Prerequisites
- JDK 17
- Android SDK (API 35, Build Tools 35.0.0)

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Run Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```

## Architecture
- **MVVM** with Jetpack Compose UI
- **Coroutines + Flow** for async operations
- **Room** for library database
- **OpenGL ES 3.0** for GPU-accelerated preview and export
- **MediaPipe Tasks Vision** for on-device AI segmentation (person mask)

## Project Structure
```
app/src/main/java/com/lumora/photoeditor/
├── engine/       # Image processing: EditParams, ImageMath, Shaders, GlEngine,
│                 #   Geometry, MaskEngine, AiSegment, RetouchEngine
├── data/         # Room database, repositories, presets
├── ui/           # Compose UI: theme, screens, panels, components
│   ├── theme/    # Lumora design system
│   ├── screens/  # Library, Editor, Export, Settings
│   ├── panels/   # Light, Color, Grade, Detail, Optics, Geometry, Mask, Retouch, Preset
│   └── state/    # ViewModels
├── export/       # ExportManager, GL host
└── core/         # AppContainer, AppSettings
```

## Known Limitations
- AI person segmentation: MediaPipe 0.10.14's `categoryMask()` returns an
  `MPImage` whose pixel buffer is not exposed via public API. The model loads
  and runs, but mask pixel extraction is not yet implemented. The UI honestly
  reports "AI unavailable" and falls back to the fully working manual masks
  (brush/linear/radial gradients). AI is never faked.
- Sky selection uses an honest heuristic (labeled "Auto" in UI), not AI.
- Release build: minification disabled; see `app/build.gradle.kts` for signing config.

## Quality Report
See `QUALITY_REPORT.md` for test results, build verification, and honest
assessment of completion status.
