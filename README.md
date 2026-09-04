# Audio-Reactive Hyperion TV

Android TV app for user-approved playback capture. `MainActivity` is exported and accepts the exact remote actions `org.hyperion.audioreactive.action.TOGGLE`, `org.hyperion.audioreactive.action.ON`, and `org.hyperion.audioreactive.action.OFF`. The visible exported components `RemoteOnActivity`, `RemoteOffActivity`, and `RemoteToggleActivity` each forward only their corresponding fixed action to `MainActivity` and then finish. `OFF` stops only an existing app-owned service (otherwise it is a no-op); `TOGGLE` stops an active service or otherwise returns to the visible capture-consent flow; `ON` is a no-op while active or otherwise returns to that visible flow. No remote action directly starts capture, reuses a token, preflights a route, or controls output. Only the visible flow can request `RECORD_AUDIO` and MediaProjection. Internal `RainbowVisualActivity`, `CaptureToggleActivity`, `AudioReactiveService`, and `MqttControlService` are non-exported.

## UI and capture contract

The D-pad UI uses two ordinary focusable `Button` tabs (not `TabHost`, `TabWidget`, or a library tab widget): **Керування** and **Додатково**. Each has an explicit down-focus target and exactly one fixed panel is visible. The first interactive control remains capture; capture-mode checkboxes and the compatible effect selector are on the control tab. Only effect selection and its parameters remain live during capture; routes, output, quality, source zones, video saturation, calibration, and shared FPS lock.

One 5–30 FPS setting applies to AUDIO, VIDEO, and VIDEO_AUDIO. Old `video_fps_v2` is read only as a migration fallback and is removed at the next save. VIDEO has Normal (baseline), Saturation (the inactive-only 0–200%, default 125% slider), and Contrast (midtone contrast) treatments. VIDEO_AUDIO leaves video hue/chroma at its source baseline and adds only a bounded nonnegative brightness accent, with distinct Brightness pulse/Beat pulse/EQ/Comet/Ripple/Bass sweep modulation. Bounded reusable smoothing reduces flicker; black/protected video blackouts app-owned output immediately.

**Rainbow screen-capture visual source** changes only the app's full-screen local background. It does not request or own MediaProjection, preflight a route, open a socket, or emit output. The existing direct route diagnostic remains an internal tested seam and is not presented as this visual source.

## Outputs, discovery, and calibration

Users explicitly choose Hyperion *or* WLED and explicitly initiate bounded, read-only local discovery for the selected route. Hyperion discovery is restricted to the reviewed cleartext endpoint, accepts immutable UUID identity from `serverinfo` or the exact `sysinfo.info.hyperion.id` fallback, and independently verifies the data port; it never writes a setting or frame. No LAN discovery was run for this delivery.

Before capture, the selected route is freshly revalidated and handed to the service once. No output is emitted before successful preflight and activation. On stop/failure/revocation, the app clears only a successfully activated Hyperion priority; it blackouts only activated WLED sockets and never changes controller power, LEDs, segments, presets, brightness, or configuration.

WLED calibration is app-side and keyed by stable MAC plus read-only physical LED count. Perimeter mapping samples all four inset/depth strips at native capture dimensions before legacy source-zone reduction, works for portrait/landscape-safe dimensions, and reuses its mapping/packet buffers. Edge allocations are edited independently; the wizard displays the remaining count and Save is enabled only when their sum equals physical LEDs. Direction and start pixel are persisted and affect packet mapping.

## Local verification

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

This command performs no installation, launch, ADB, TV, WLED, Hyperion, MQTT, or Home Assistant action. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
