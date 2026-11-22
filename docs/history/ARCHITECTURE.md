# ARCHITECTURE.md history

Old sections moved out of [ARCHITECTURE.md](../../ARCHITECTURE.md) on 22 November 2026, unchanged apart from relative links. They describe
the state on their own dates and may no longer be true. Use the main file for current information.

## Reviewed playback integration (22 October 2026)

`VodSources` retains at most two short-lived sessions of up to five cached candidates, identified by random intent tokens. Manual Sources and AutoPlayback pass the selected file and remaining cached candidates. Alternative direct links resolve on demand through the existing repository; the session holds no Activity. A process restart loses alternatives and safely retains ordinary single-source history playback.

`VodSourceFailover` owns plain-Java thresholds and source exhaustion. `VodPlayback` owns asynchronous resolution, cancellation generations, a 45-second deadline per candidate, and a muted second VLC player using the film engine. The original player continues until video decodes within two seconds of its position. Replacement uses a TextureView so staging can be transparent on API 28; initial VOD and shared Live TV retain their existing SurfaceView path. Insufficient decoder resources may prevent a replacement: failure keeps the original player. Live thresholds and the shared live instance are independent.
