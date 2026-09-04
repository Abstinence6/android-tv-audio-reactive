# Pinned screen-capture dependencies

## scrcpy server
- Upstream: https://github.com/Genymobile/scrcpy
- Tag / commit: `v3.3.4` / `fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3`
- Artifact: official GitHub release `scrcpy-server-v3.3.4`
- SHA-256: `8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e`
- License: Apache-2.0; `app/src/main/assets/NOTICE-scrcpy-APACHE-2.0.txt` reproduces the upstream license.

This app does not use UAL's asset or source. The asset is checked before each extraction and only pushed to the app-owned `/data/local/tmp/org.hyperion.audioreactive-scrcpy-server` path after an explicit local user start.

## Hyperion FlatBuffers schema
- Upstream: https://github.com/hyperion-project/hyperion.ng
- Commit: `9818abe25b3ae51f65a180a5ba0c5706a13b2e70`
- File: `libsrc/flatbufserver/hyperion_request.fbs`
- SHA-256: `63213a82d8c813556573ab13ead1549d5686627ce5aa5568617fddb37ffde721`
- Generator: upstream FlatBuffers `flatc v25.2.10`; generated sources are `app/src/main/java/hyperionnet/*.kt`.
- License: Hyperion NG is MIT licensed; schema is retained verbatim in assets.

Raw RGB channel ordering is intentionally conservative: generated schema serialization is tested offline, but device color ordering remains a separately authorized device-E2E check.
