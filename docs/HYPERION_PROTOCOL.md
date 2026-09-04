# Hyperion FlatBuffers protocol evidence

The implementation follows the retained official `hyperion-project/hyperion.ng` schema from commit `9818abe25b3ae51f65a180a5ba0c5706a13b2e70`, copied verbatim at `app/src/main/assets/hyperion_request.fbs`.

* `Register { origin:string; priority:int; }`, `Clear { priority:int; }`, and `Request { command:Command }` use the schema's documented union tags: Image=2, Clear=3, Register=4.
* `Image { data:ImageType; duration:int=-1 }` carries `RawImage { data:[ubyte]; width:int=-1; height:int=-1 }`; the app supplies exactly RGB24 bytes, three bytes per pixel.
* Frames have the official FlatBuffer endpoint envelope: four-byte big-endian payload length, followed by one FlatBuffer `Request` payload.
* Registration and cleanup use priority **101**, inside the server's documented registration range 100..199. The `android-tv-audio-reactive` origin is registered once per active capture connection.

Capture submits bounded 16×1 RGB24 images (48 bytes maximum) at the selected 5–30 FPS. Each frame analyzes 1,024 PCM samples at 48 kHz, and deadline-aware pacing sleeps only for the remaining portion of the selected period. Each non-monochrome effect creates a spatial palette/wave, so a nonzero frame can carry distinct RGB pixels simultaneously. Monochrome deliberately fills the image with one grayscale color. `EffectFrameRenderer` reuses its 48-byte pixel buffer in the capture loop.

Local JVM tests independently decode the generated tables and assert request union tags, dimensions, RGB vector bytes, framing, registration origin/priority, and Clear. They also assert that all non-monochrome effects produce multiple pixel colors for typical audio features and that Monochrome is uniform.

## Response and cleanup limit

The retained source evidence specifies client-to-server request framing but no response/ack schema. A successful socket write is local transport success only. On stop, projection revocation, capture/output failure, and destruction, the app attempts `Clear(101)` using a fresh socket at most twice. This remains bounded best effort, not server-acknowledged cleanup.
