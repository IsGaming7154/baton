# Numbers, and where each one comes from

| Number | Source |
|---|---|
| 459 test executions, 0 failures, 0 errors, 0 Kotlin warnings | Run in this repository, full task rerun |
| APK 32,194,832 bytes after `./gradlew clean` — the last few bytes move between builds because the zip carries its own timestamps, and an incremental build lands around 400 KB larger | `app/build/outputs/apk/debug/app-debug.apk` |
| Demo clip: 88.6 s, 854×356 H.264, AAC 48 kHz stereo | `ffprobe` |
| Demo clip: −30.3 LUFS integrated, LRA 20.0 LU | `ffmpeg -af ebur128` |
| −23.0 / −33.0 LUFS on EBU Tech 3341 signals | `:loudness` tests, run here |
| 154 ms decision-to-audible for the output-stage duck | Measured on a Fire TV Stick (AFTSSS, Fire OS 7.7.1.5, Android 9 / API 28) |
| The duck arrives as commanded: 0.997 dB per dB over 343 settled samples, residual RMS 0.64 dB | Same device, 2026-09-20, `OutputMeter` against a −23.2 LUFS pink-noise reference |
| Voice 12.1 LU above the ducked bed, where the rule asks for 12.5 — 15.1 before the mono-to-stereo correction | Same device and session; friction-log entry 13 |
| A mono track is duplicated to both output channels with no pan law: 0.18 dB between a mono file and the same file already copied into both channels | Same device and session |
| Cold start 20.8 s to the film; 50 rendered files reused in 183 ms on the next | Same device, 2026-09-22, signed release build, `am start -W` and the run's own log |
| Warm start 2.96 s to the film against 3.94 s before, median of five each; audio ready at 0.92 s against 3.91 s | Same device, 2026-09-28, signed release builds A/B in one session, the run's own log |
| `underrunCount=0` on both the old `MODE_STREAM` build and `MODE_STATIC` — so it is not evidence for the switch: a static track always reports 0 (`StaticAudioTrackClientProxy::getUnderrunCount`, AOSP `AudioTrackShared.h`) | Same device; Android 9 audio framework source |
| 918 ms duration divergence between the two installed TTS engines | Same device; friction-log entry 9 |
| Mix periods 16 / 20 / 32 ms | `dumpsys media.audio_flinger` on the same device; friction-log entry 12 |
| Meter cross-check −21.751 vs ffmpeg −21.8 LUFS | Friction-log entry 10 |
| Band importance 10.2% / 6.7% / 0.84% at 1.6 / 4 / 8 kHz | ANSI S3.5-1997, cited in `SpeechClarity.kt` |

Numbers attributed to the device were measured on hardware and are recorded in [`FRICTION_LOG.md`](../FRICTION_LOG.md).
