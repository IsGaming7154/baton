# Architecture

```
  Android                          pure JVM
  ─────────────────────────────    ─────────────────────────────

  :app ──┬──► :player ──┬────────► :ports ──────────► :director
         │              │                                 ▲
         └──► :speech ──┤                                 │
                        └────────► :loudness              │
                                                          │
                                             :pipeline ───┘
```

Seven modules. Four of them — `:ports`, `:director`, `:loudness` and `:pipeline` — have no Android on their classpath at all.

**`:director` is pure JVM.** No Android dependency, no framework dependency, no production dependency beyond the Kotlin standard library and one serialization library. It exposes `advance(nowMs, inputs) → Directive` and owns the entire arbitration policy. This is what makes the product's central claim provable: 124 tests, including a property test over randomised timelines, run on any machine with no device attached. The one dependency it does carry is `kotlinx-serialization`, because the `DescriptionTrack` codec has to live beside the model it encodes and neither `:app` nor `:pipeline` may depend on the other.

**`:ports` is interfaces and nothing else.** `PlaybackClock`, `ContentGain`, `ProgrammeLoudness`, `Speaker`, `SpeechSynth`. It exists so that neither side can reach through the seam: the director never learns that gain is a Media3 `AudioProcessor`, and the player never learns what a cue is. It is the reason the arbitration policy can be driven by a fake speaker in a unit test and by an `AudioTrack` on the device without either knowing the difference.

**`:player` and `:speech` are adapters and hold no policy.** `BatonPlayer` can tell you where the content is and how loud it may be; it cannot tell you whether a cue may speak. `Tracer` in `:app` is the loop that turns a `Directive` into things happening, and it too contains no decisions — it reads the clock, hands the director what has happened, and does what it is told.

**`:loudness` is a separate module for a specific reason.** Three consumers need the same measurement and none may depend on another. `:speech` normalises a rendered utterance to the delivery target on the device; `:player` meters the content bed live in the render chain; `:pipeline` will measure programme loudness on a workstation. `:speech` and `:player` are Android libraries and `:pipeline` is a build-time tool that must never reach an APK — so the arrow can only point down into a module all three can see.

**`:pipeline` must never become a dependency of `:app`.** It is a workstation tool. The live slice adds the AWS SDKs to it, which have no business inside a 1 GB Fire TV Stick's APK. The dependency arrow points one way: `:pipeline → :director`.

The result of pointing the arrows this way is that the correctness surface of the product — arbitration, admission, loudness — is testable without hardware, and the parts that need hardware contain nothing to get wrong.
## APIs used

Every entry below is used in the shipped code.

### AndroidX Media3 / ExoPlayer 1.4.1 — `:player`

- **`ExoPlayer`, `ExoPlayer.Builder`** — the playback engine.
- **`DefaultRenderersFactory.buildAudioSink`** overridden, and **`DefaultAudioSink.Builder.setAudioProcessors`** — building the audio sink by hand is the only way to get a processor into the render chain.
- **`androidx.media3.common.audio.AudioProcessor` / `BaseAudioProcessor`** (`onConfigure`, `queueInput`, `onFlush`, `onReset`) — `DuckingAudioProcessor`, which sees the content's decoded PCM in order at a known rate. This is where the BS.1770 meter lives, and it reads the processor's *input* so the duck never feeds back into its own measurement.
- **`ExoPlayer.setVolume`** — the active duck. It applies *after* `DefaultAudioSink`'s internal queue rather than before it, which is the difference between a duck that is audible in 154 ms and one that is inaudible for most of a second.
- **`Player.Listener.onPositionDiscontinuity`** with **`DISCONTINUITY_REASON_SEEK`** — what the director rebaselines on.
- **`Player.isPlaying`, `playWhenReady`, `playbackSuppressionReason`, `PLAYBACK_SUPPRESSION_REASON_NONE`, `STATE_BUFFERING`** — pulled apart deliberately. `isPlaying` is a conjunction of three facts that answers "is the position advancing" and throws away which of the three failed; Baton needs to distinguish a viewer's pause from a buffering stall, and this is the only place in the system that can.
- **`androidx.media3.ui.PlayerView`** — the picture and the subtitles, hosted through Compose interop, with its built-in controller off (`useController = false`). The controller's buttons are Views that VoiceView would announce and the director would never hear, so the transport controls are Compose.

### Android platform — `:speech`

- **`android.speech.tts.TextToSpeech`**, three-argument constructor with an explicit engine package. Never the two-argument form, which resolves to the platform default and on this hardware is a shell with no voice data.
- **`TextToSpeech.getVoices()`, `getAvailableLanguages()`, `setLanguage()`, `synthesizeToFile()`** — engines are qualified by voice count, not by init status.
- **`TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE`** with **`PackageManager.queryIntentServices`**, plus a `<queries>` element in the manifest — without the manifest declaration, a build against a newer platform sees an empty device and speaks nothing, with no error.
- **`android.speech.tts.UtteranceProgressListener`** — both `onError` overloads implemented, including the deprecated `onError(String)`, because on the failing path only the deprecated one fires.
- **`android.media.AudioTrack`** in **`MODE_STATIC`**, built with `AudioTrack.Builder`, `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY`, `CONTENT_TYPE_SPEECH`, `AudioFormat.ENCODING_PCM_16BIT`. Static rather than streaming because the whole WAV is already in memory, so there is no periodic feed left to starve — an underrun is not merely unlikely, it is structurally impossible.
- **`AudioTrack.getPlaybackHeadPosition()`** — how the speaker knows the audio actually stopped. The director releases the floor on an observation, never on a timer.

### Android TV / Compose — `:app`

- **`androidx.tv:tv-material:1.0.0`** (`androidx.tv.material3.Surface`, `Button`, `Text`), Compose BOM 2024.06.00, `androidx.activity.compose.setContent`.
- **`Modifier.onFocusChanged`** — turns each focus landing on a transport button into a `UI_NARRATION` request to the director. **`Modifier.semantics { contentDescription; invisibleToUser() }`** carries the narration text for the composition-tree test and hides the node from VoiceView; **`FocusRequester`** puts initial focus on Play/Pause.
- **`Activity.dispatchKeyEvent`** — the one key the app binds, Play/Pause. See "Remote bindings".
- Tests: **`androidx.compose.ui:ui-test-junit4`** over **Robolectric 4.13** at `sdk = 28`, run on the JUnit Platform beside kotest through **`junit-vintage-engine`**.
- Manifest: **`android.software.leanback`** and **`LEANBACK_LAUNCHER`**, which is what puts the app on the Fire TV home row.

### Implemented in-house because the platform offers nothing — `:loudness`

- **ITU-R BS.1770-4**: K-weighting (high shelf plus RLB high-pass), 400 ms gating blocks at 75% overlap, the −70 LUFS absolute gate and the −10 LU relative gate, and the −0.691 dB calibration offset. Plus a sliding short-term meter and a 4× polyphase true-peak meter following BS.1770-4 Annex 2.
- Filter coefficients are **derived from the analogue prototype with a `tan()` prewarp per sample rate**, because the standard prints its table for 48 kHz and the speech this measures is not at 48 kHz — IVONA renders at 24 kHz, SVOX Pico at 16 kHz. Fed 48 kHz, the derivation reproduces the standard's printed table.

Friction-log entries 10 and 11 are the record of why this had to be written by hand: `LoudnessEnhancer` is an effect and not a meter, `MediaFormat.KEY_AAC_DRC_OUTPUT_LOUDNESS` arrives at API 30, `LoudnessCodecController` is API 35, `Visualizer` needs `RECORD_AUDIO` to observe your own audio and returns 8-bit samples, and Media3's own AC-3 parser reads straight past the `dialnorm` field that the mastering engineer already put in the bitstream.

### Designed against, not yet called — `:pipeline`

`:pipeline`'s ports name **Amazon Transcribe** (word-level timings), **Amazon Bedrock / Nova** (video understanding and drafting) and **Amazon Polly** (synthesis), and its fixtures are stored in Transcribe's own response shape so that the parser exercised by the tests is the parser the live slice needs. The Polly and Transcribe ports have live adapters on the AWS SDK for Java v2 (Polly's billed characters settle the spend guard's estimate); they are exercised against fake clients. Nova has one too (`NovaDrafting`, on Bedrock `Converse`, whose billed tokens settle the estimate the same way). All three are tested against fake clients; Nova's has not yet made a live call, and `GeminiDrafting` drafts in its place. Treat this as direction, not as a running integration.
