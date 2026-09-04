# Independent pre-deployment review — 2026-09-01 (historical snapshot; superseded)

> This is an immutable review record of an earlier candidate. It is not the current feature specification: the current supported FPS contract is **5–30 FPS**, documented in `docs/HYPERION_PROTOCOL.md` and enforced by source/tests.

## Verdict

**BLOCK release deployment.** The generated APK is structurally valid and technically installable, but it is a debug build signed with the standard `CN=Android Debug` certificate. It is not a production deployment artifact. Create and verify a release-signed APK/AAB before deploying as a final build.

A secondary release-quality gate is the build-tool warning: Android Gradle Plugin 8.7.3 is tested only through compileSdk 35 while this app compiles/targets API 36. Upgrade AGP (or otherwise validate the supported Android 16 toolchain) before treating the artifact as final.

## Verified implementation behavior

- At the time of this historical snapshot, persisted FPS had an upper bound of 60. That earlier behavior is superseded: the current supported contract is **5–30 FPS**, with migration/fallback enforced by the current source and tests.
- Manifest disables backups (`allowBackup=false`); both backup rule files exclude all root data. The store contains only benign display/capture settings and no MQTT or device credentials.
- The activity's first interactive control is exactly `Увімкнути аудіоефекти`; only that UI path leads through runtime `RECORD_AUDIO` permission and Android MediaProjection consent before it starts `AudioReactiveService`.
- Audio capture uses `EffectFrameRenderer`'s reusable 48-byte 16×1 RGB24 buffer. Every non-monochrome effect has position-dependent palette/wave output; the unit test confirms more than one simultaneous RGB triplet for all nine non-monochrome effects. Monochrome deliberately emits uniform grayscale.
- RawImage uses the retained local schema: `Request` union Image=2, `Image` union RawImage=1, `RawImage { data:[ubyte], width, height }`, `duration=-1`; payload framing is a four-byte big-endian length. The independent test decodes generated FlatBuffers and checks table fields, vector contents, dimensions, tags, and framing. Dimensions are restricted to 1..16 × 1; bytes must be exactly width×height×3 and at most 48. The RGB channel order is documented locally as RGB24 and renderer writes R, then G, then B per pixel.
- Register and Clear use priority 101. Clear is encoded with its single priority field and only called through the active capture client's teardown path. Cleanup is correctly stated as best effort, bounded to two fresh-socket attempts, with no server acknowledgement claim.
- MQTT uses one `MqttControlService` owner, is `START_NOT_STICKY`, has no boot receiver, rejects retained/malformed/off-topic commands, and only allows bounded settings. MQTT `ON` deliberately performs no capture/permission/service start. `OFF` only stops an already-existing audio service. Settings snapshots use the same `RuntimeSettings` state as UI and capture.

## Test/build evidence

Fresh local, non-deploy command completed: `./gradlew clean testDebugUnitTest lintDebug assembleDebug`.

- 20 JVM tests passed: AudioCore 9, RuntimeSettings 3, MQTT contract 8; zero failures/errors/skips.
- Lint: 0 errors, 13 warnings: one TV banner vector-size warning, four stale AndroidX dependency notices, and eight hard-coded UI-string localization warnings.
- Generated APK: `app/build/outputs/apk/debug/app-debug.apk`, 10,898,911 bytes, SHA-256 `ed530aff29d9dc916f9f08b211d8e9bcbe9c3671670a061fca78ecc2fdc5d33c`.
- `apksigner verify --verbose` passed v2 signature verification. The sole signer is `C=US, O=Android, CN=Android Debug` (debug certificate), which causes the release-deployment block.

## Additional non-blocking code-quality notes

- `README.md` line 3 still says the app “renders a single derived color,” which contradicts the implemented/documented 16-pixel spatial RGB image. Correct this release documentation.
- Protocol image dimensions and renderer storage are bounded, and no retained resource leak was found. However, the capture loop still allocates a RawImage `ByteBuffer`, length-framed byte array, and a new `BufferedOutputStream` per frame. This is bounded per iteration and not a retained leak, but should be reused for sustained 60-FPS operation.
