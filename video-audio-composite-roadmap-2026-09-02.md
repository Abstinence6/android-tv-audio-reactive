# Video + Audio Composite Mode — roadmap

## Product decision
Primary backend will be implemented inside `org.hyperion.audioreactive` with a mode selector in both app UI and Home Assistant:

- **Output routing** — exactly two exclusive top-level modes: **Hyperion** or **WLED**. In WLED mode, the app discovers and validates local WLED endpoints; the user can check multiple discovered WLED targets, and the persisted selection is revalidated against stable WLED identity before direct output. Hyperion and direct WLED are not enabled together.
- **Аудіо** — existing audio-reactive renderer and its audio-effect catalog.
- **Відео** — video colors/base luminance only.
- **Аудіо+відео** — video colors/base luminance plus a selected audio modulation effect.

The available effect list is mode-aware: only materially applicable effects are offered for the selected mode. The catalog will be broad but curated: no duplicate aliases presented as separate effects.

### Audio contribution in `Аудіо+відео`
Video supplies every zone’s spatial RGB color and baseline luminance. Audio never substitutes an audio-generated color; it adds only a bounded non-negative brightness accent:

```text
accent = clamp(audioEffect(zone, time, bands) × userIntensity, 0, MAX_ACCENT)
finalBrightness = clamp(videoLuminance + accent, 0, 1)
finalColor = videoColor × finalBrightness
```

Initial distinct audio-brightness modifiers: **Загальна яскравість** (global RMS/spectral accent), **Пульс/beat**, **16-зонний еквалайзер**, **Комети** (moving bright trails), **Ripple/хвиля**, and **Bass sweep**. All use reusable buffers/state, clamp their output, preserve video hue/chroma, and reduce to video-only output on silence.

```
One user-approved MediaProjection
├── VirtualDisplay → ImageReader → video zone colors
└── AudioPlaybackCapture → RMS/onset/beat → bounded additive brightness coefficient

video colors × (base video brightness + audio coefficient) → one bounded RGB zone frame
                             → selected Hyperion and/or direct WLED routes
```

UAL-style on-device scrcpy is explicitly **not** part of the first implementation. It remains a later fallback only if MediaProjection video is unusable for a verified non-DRM scenario. It requires separate ADB authorization, a pinned scrcpy-server binary, decoder lifecycle, separate priority/origin, and its own test gate.

## Implementation order
1. Finish current HA always-online lifecycle, effects refresh, and remote-toggle Activity release.
2. Add a `VIDEO_AUDIO` capture mode behind the existing user-approved MediaProjection flow.
3. Reuse the existing projection token to create a bounded `VirtualDisplay` targeting an `ImageReader` surface, initially 320×180 and 10–15 FPS.
4. Downsample video to an aspect-preserving full RGB24 image; UAL-compatible baseline for this 1920×1080 RPi target is **128×72 at target 30 FPS**, with configurable bounded quality/FPS presets. Do not reduce video to audio LED zones.
5. Combine with the existing audio features using a bounded brightness accent: `finalRgb = videoRgb × boundedAudioGain`.
6. Send the full image in the Hyperion FlatBuffers `RawImage` contract (big-endian framed TCP request to `192.168.1.158:19400`, RGB24, persistent duration `-1`) under this app’s own video origin and priority. Hyperion instance 0 performs LED mapping; do not reuse UAL’s origin or priority.
7. Detect repeated black/near-black video frames and surface a truthful public status (e.g. `video_unavailable_or_protected`); never pretend DRM content is captured.

## Non-negotiable constraints
- Manual Android MediaProjection consent remains mandatory; no auto approval.
- No per-frame object/array allocations in video conversion, compositor, or output path.
- Preserve 5–30 FPS audio contract; video cadence stays independently bounded.
- Projection revoke/lock/device stop tears down ImageReader, VirtualDisplay, AudioRecord, router, and only app-owned output resources.
- Existing Audio-only mode stays available and behavior-compatible.
- UAL and TV configuration are untouched.

## Acceptance evidence
- Unit/protocol tests: RGB bounds, zone mapping, brightness gain, black-frame detector, frame ownership/cleanup, no output before sources start.
- Device: MediaProjection approved; capture remains stable while SmartTube is foreground; confirm non-DRM video changes colors, bass changes brightness, and output routes only to selected targets.
- Verify WLED realtime `live` and Hyperion app input separately; verify cleanup after stop.
- Explicitly record DRM/black-frame results rather than claiming support.
