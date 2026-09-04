# Audio-Reactive Hyperion TV — implementation guide

This document describes the current source tree at `org.hyperion.audioreactive`. It is a source-level guide, not device acceptance evidence. Building or testing the project must not install or launch the APK, and it must not contact a TV, WLED, Hyperion, MQTT, or Home Assistant.

## Product boundary

The application is an Android TV app that can capture user-approved playback audio and/or a MediaProjection video surface, produce bounded RGB24 frames, and send them only through one explicitly selected output route:

- **Hyperion** — one freshly revalidated Hyperion endpoint;
- **WLED** — one or more freshly revalidated WLED endpoints.

The two output modes are exclusive. Discovery and preflight are read-only. The app does not modify WLED controller settings such as LED count, segments, presets, brightness, power, or realtime configuration.

MediaProjection is never started by a remote command. A visible UI flow requests `RECORD_AUDIO` when audio is needed and then asks Android for MediaProjection consent. A selected route must be revalidated and bound exactly once before the foreground capture service consumes it.

## TV UI and D-pad contract

`MainActivity` contains exactly two ordinary focusable, styled text tabs rather than `TabHost`, `TabWidget`, or a dependency-provided tab control:

1. **Керування**
   - capture enable/disable control (the first interactive control);
   - audio and video checkboxes, resolved into the canonical `Аудіо`, `Відео`, or `Аудіо+відео` mode;
   - one compatible effect selector;
   - a local **Rainbow screen-capture visual source** and a local detailed-status dialog.
2. **Додатково**
   - video source quality and a shared FPS value;
   - sensitivity, brightness, base video colour treatment, saturation, and live effect parameters;
   - exclusive output choice, explicit local discovery, target selection, WLED source zones, and per-device calibration.

Both fixed content panels are individual vertical `ScrollView` containers with `isFillViewport = true` and descendant-first focus. The tab strip has explicit left/right/down focus targets, and exactly one panel is visible at a time.

The rainbow action is deliberately local only: it animates the current activity background. It does not request a permission, start capture, invoke route preflight, open a socket, or emit network output. The separately implemented diagnostic frame seam is not exposed by this visual-source control.

## Capture modes and mutable settings

The canonical render modes are defined by `RenderMode`:

- `AUDIO` captures playback audio and renders the selected audio-reactive strip effect;
- `VIDEO` captures a video surface and applies base image treatment only;
- `VIDEO_AUDIO` retains source-video hue/chroma and adds a bounded, non-negative audio-derived brightness accent.

At least one of the two capture-mode checkboxes must stay selected. While capture is active, route-affecting settings are locked: capture mode, output mode, target selections, discovery, video quality, FPS, source zones, video treatment/saturation, and calibration. Effect selection and effect-local parameters intentionally remain live. Their changes are held in `LiveRendererSettings`, so they change only renderer-local state for the running session and do not mutate routing, permissions, endpoint data, or persisted capture settings.

## Source-frame dimensions, quality, and cadence

`VideoCapturePolicy` provides one shared FPS setting for every mode. The permitted values are **5, 10, 12, 15, 20, 24, and 30 FPS**. Legacy `video_fps_v2` is read only as a migration fallback; saving current settings removes it.

The current bounded video-quality presets are:

| Preset | RGB24 dimensions | Frame payload |
| --- | ---: | ---: |
| `VERY_LOW` | 64 × 36 | 6,912 bytes |
| `ECONOMY` | 80 × 45 | 10,800 bytes |
| `LOW` | 96 × 54 | 15,552 bytes |
| `BALANCED` | 112 × 63 | 21,168 bytes |
| `STANDARD` | 128 × 72 | 27,648 bytes |
| `HIGH` | 160 × 90 | 43,200 bytes |

The virtual display is created at 320 × 180. `VideoFrameProcessor` downsamples into a preallocated RGB24 source frame corresponding to the selected quality. Audio-only Hyperion uses a 16 × 1 source frame. Direct WLED audio uses its separately configured bounded source-zone count; video capture continues to use the native selected video frame so WLED perimeter calibration can sample screen edges before any legacy zone reduction.

## Rendering model

### Audio-only

`EffectFrameRenderer` consumes analysed audio features and renders reusable RGB24 buffers. Brightness accepts the bounded 0–100% range; 0% produces an immediate black frame. Effect parameters are separately bounded in `EffectParameters`.

### Video-only

`VideoFrameProcessor` copies image pixels to a fixed RGB24 buffer and applies the selected base `VideoEffect`:

- `NORMAL` — source RGB values;
- `SATURATION` — channel distance from the pixel average, controlled by a 0–200% saturation value;
- `CONTRAST` — channel distance from the midtone, controlled by the same bounded value.

### Video plus audio

For `VIDEO_AUDIO`, the video image remains the colour source. When audio has a signal, the chosen `VideoAudioEffect` contributes only a clamped positive gain. Supported modulation styles are Brightness pulse, Beat pulse, EQ, Comet, Ripple, and Bass sweep. On silence, the compositor emits the video-only result.

The capture loops reuse processing buffers and use `RgbFrameSmoother` to reduce flicker. Sustained black or protected/unavailable video is treated as a failure path: the app invokes its route cleanup and terminates capture rather than retaining the last coloured output.

## Output-route lifecycle

A user-selectable output is not automatically usable merely because it is persisted. The app follows this route lifecycle:

1. The user explicitly selects Hyperion or WLED and selects saved/discovered targets.
2. Discovery is local and read-only. Fresh results are merged with prior known devices by stable identity, preserving temporarily offline records and selections.
3. Immediately before capture or an internal diagnostic action, preflight validates the current selected route and creates a one-shot route binding.
4. The service validates that binding against the frozen settings snapshot and consumes it exactly once before marking itself alive.
5. `OutputRouter` owns the activated route for the session.
6. On stop, error, projection revocation, or route loss, the service closes only its own resources. Hyperion clears only an app-owned priority after successful registration; direct WLED sends an app-owned blackout/close sequence.

No TV, output device, broker, or Home Assistant state is contacted by local unit tests or by `assembleDebug`.

## WLED screen calibration

Calibration is an app-side mapping keyed by the validated WLED stable identity and its physical LED count. It persists start pixel, clockwise/counter-clockwise direction, edge allocations, per-edge insets, sample depth, samples per edge, gamma, and an application brightness cap. These settings do not alter controller configuration.

The mapping is valid only if allocations cover the physical LED count. The calibration dialog edits a local draft and writes preferences only through its Save action. Capture locks calibration edits. The perimeter mapper samples the native captured RGB frame around all four configured edges and writes into reusable output buffers.

## Persistence and diagnostics

`SharedPreferencesAudioSettingsStore` persists validated `AudioSettings`, output inventories/selections, effect parameters, and calibration records. Invalid values are rejected by `AudioSettings.valid()` and invalid persisted data falls back to defaults.

`MqttContract` can represent a read-only diagnostic state for Home Assistant when the MQTT control service is configured and connected, but this document makes no claim of broker or Home Assistant runtime verification. Local UI/settings changes notify the in-process runtime listener; they do not themselves prove any external publication succeeded.

## Local verification gate

Run the following from the repository root:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

For this source tree, build outputs may be redirected to a temporary writable directory with the supplied `.tmp-build-dir.init.gradle` and `HERMES_GRADLE_BUILD_DIR`. The local gate is limited to JVM unit tests and debug APK assembly. It must not include installation, ADB, activity launch, Android permission dialogs, or any network/device integration.

A successful debug assembly is build evidence only. It is not a release artifact, does not prove device behaviour, and does not prove end-to-end output.