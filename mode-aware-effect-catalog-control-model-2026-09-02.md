# Mode-aware effect catalog and control model

## Capture modes

The app exposes user-selected audio, video, and combined audio/video capture modes. Every capture session requires the user-driven `RECORD_AUDIO` permission when audio is selected and user MediaProjection consent before the service starts.

A selected output is exclusive: **Hyperion** or **WLED**. The output router is constructed only from a freshly revalidated, one-shot route binding after consent. It never changes a device configuration.

## UI contract

The D-pad UI has three tabs:

1. **Керування** — capture toggle and a manual test image action.
2. **Режими й ефекти** — audio/video mode, effects, and bounded quality controls.
3. **Виходи** — exclusive output selection and user-initiated WLED/Hyperion LAN discovery.

The test image does not request permissions or start capture. It revalidates the chosen target and sends one diagnostic frame only through the selected route. Mode, output, and discovery controls are disabled while capture is active.

## Rendering and cleanup

Hyperion receives the selected RGB frame through the app-owned route and priority. Direct WLED output resamples the bounded source frame to each revalidated target's physical LED count using preallocated realtime packets.

No output is sent before router activation. On stop, error, or projection revocation, the router clears Hyperion only if it was activated, sends one blackout frame to each activated WLED target, and closes app-owned sockets.
