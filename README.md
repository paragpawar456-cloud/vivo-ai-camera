# ProLens AI — an AI photographer for the Vivo X300 Pro

Native Android camera app (Kotlin, Jetpack Compose, CameraX + Camera2, on-device ML Kit).
It reads the scene live and sets the camera the way a professional would, then finishes the photo
with a pro colour grade and DSLR-style background blur.

## What it does

**AI scene detection (on-device, offline)**
Detects Portrait, Food, Landscape, Golden Hour/Sunset, Night, Macro/Flowers, Pets, City/Architecture and Action
from ML Kit image labels, face detection, measured scene brightness (EV), colour cast and focus distance.
Decisions are smoothed so the style doesn't flicker while you frame.

**Automatic camera settings per scene ("recipes")**
For each scene the AI picks: shutter-priority vs. low-ISO strategy, ISO ceiling, slowest safe handheld shutter,
exposure bias (e.g. −0.7 EV for sunsets to keep colour in the sky), white balance, focus strategy
(hyperfocal for landscapes, face/eye-priority AF for portraits), ideal focal length (85mm for portraits, 50mm for food),
virtual aperture for background blur, colour look, and the best camera angle.

| Scene | Exposure logic | Look | Blur | Angle coach |
|---|---|---|---|---|
| Portrait | 1/200s, auto-ISO ≤1600, +0.3 EV, eye-AF | Portra 400 | f/1.8 person-aware | Eye level |
| Food | Base ISO, ≥1/30s, +0.3 EV, warm | Food Fresh | f/2.8 | 45° or 90° overhead |
| Landscape | Base ISO, −0.3 EV, daylight WB, hyperfocal | Vivid Nature | none (f/11 look) | Level horizon |
| Golden hour | Base ISO, −0.7 EV, cloudy WB | Golden Hour | none | Level horizon |
| Night | Up to 1/8s handheld, ISO ≤3200, −0.5 EV | Original | none | Brace & timer |
| Macro | ≥1/60s, base ISO | Velvia 50 | f/2.8 | Parallel to subject |
| Pet | 1/500s | Original warm | f/2.0 | Their eye level |
| City | Base ISO, −0.3 EV | Leica Classic | none | Phone vertical |
| Action | 1/1000s, ISO ≤6400 | Velvia 50 | none | Pan with subject |

When the phone exposes manual sensor control (Camera2 `MANUAL_SENSOR`), ProLens runs its **own closed-loop
exposure**: it meters the frame (centre-weighted, highlight-protecting) and splits the exposure between ISO
and shutter exactly as the recipe prefers. Otherwise it steers the phone's auto-exposure with EV compensation.

**AI Coach panel** — what was detected, the DSLR-equivalent settings (e.g. `≈ 85mm · f/1.8 · 1/200 · ISO 100`),
a live camera-angle meter that turns green at the right angle, a one-tap "use the 85mm lens" suggestion, pro tips,
a brightness slider, and an **Auto-set / Suggest-only** switch.

**Pro (manual) mode** — ISO, shutter speed, EV, white balance (Daylight/Cloudy/Shade/Tungsten/Fluorescent/Twilight),
manual focus (∞ → macro), real aperture (only if the lens has a variable iris) and virtual aperture.
Set only shutter for shutter-priority with auto-ISO, or only ISO for auto-shutter, like a DSLR.

**DSLR tools** — rule-of-thirds / golden-ratio / crosshair grid, spirit level, live histogram with clipping
warnings, exposure meter, face-detect AF boxes, tap-to-focus/meter, pinch zoom and lens buttons
(0.6× / 1× / 2× / 3.5× / 10×, built from what the phone reports), flash/torch, 3s/10s timer, 4:3 / 16:9.

**Looks (filters)** — Leica Classic, Portra 400, Velvia 50, Cinematic (teal & orange), Noir, Golden Hour, Matte Mood,
Food Fresh, Vivid Nature, Night City, Silver. Shown live on the viewfinder and rendered at full quality
(colour matrix + film tone curves + split toning + vignette + grain) on the saved photo. "AI Pick" lets the AI choose.

**Vivo engine** — in Portrait, Night and Landscape modes, if Vivo exposes its own CameraX vendor extensions
(Bokeh, Night, HDR), a "Vivo engine" switch appears so you can use Vivo's multi-frame processing.

Photos are saved to `DCIM/ProLens AI` with EXIF (scene, look, settings). Untouched shots are saved byte-for-byte.

## Honest limits

- **Aperture:** phone lenses have a fixed iris (around f/1.6–f/2.0). ProLens can't change it physically; it
  shows the real value and recreates DSLR depth of field on the finished photo (person segmentation for
  portraits, a focus-point falloff for food/macro/pets). If a lens ever reports multiple apertures, the real control appears.
- Manual ISO/shutter/focus work only where Vivo exposes them to third-party apps through Camera2.
  If a lens doesn't, those dials fall back to smart auto and the app tells you.
- Vivo's own camera app uses private processing (and Zeiss tuning) that third-party apps can't access unless Vivo
  publishes it as a CameraX extension.
- Very large captures are rendered up to 8192 px on the long edge to keep memory safe.

## Build

**Android Studio (easiest):** open this folder, let Gradle sync, plug in the phone (USB debugging on), press Run.

**Command line:** JDK 17 + Android SDK (platform 35):

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**GitHub:** push this folder to a GitHub repo; `.github/workflows/build-apk.yml` builds the APK on every push and
attaches it to the workflow run as `ProLensAI-debug-apk`.

Requirements: Android 10+ (minSdk 29). Tuned for the Vivo X300 Pro; works on any Camera2-capable phone.

## Code map

```
app/src/main/java/com/parag/prolens/
  MainActivity.kt          permission + entry
  CameraViewModel.kt       camera engine: binding, Camera2 manual controls, AI exposure loop, capture
  ai/Scene.kt              scenes and shooting modes
  ai/SceneAnalyzer.kt      per-frame metering + ML Kit labels/faces
  ai/SceneClassifier.kt    scene decision with smoothing/hysteresis
  ai/Photographer.kt       the "photographer": recipes, ISO/shutter split, lens choice
  camera/CameraCaps.kt     what the lens supports, WB presets, stop tables
  processing/Look.kt       colour looks (film stocks / cinema grades)
  processing/PhotoPipeline.kt  full-res darkroom: grade, bokeh, vignette, grain, save
  sensors/LevelSensor.kt   spirit level + camera angle
  ui/...                   Compose UI (viewfinder, overlays, panels, controls)
```
