# Baton

**One voice at a time.**

Build, Ship, Shape: Amazon Developer Hackathon · Fire TV track. Target device: Fire TV Stick HD, Fire OS 7 / Android 9 / API 28.

A blind viewer puts on a foreign-language film. To watch it they need three things switched on at once: audio description, so they know what is happening on screen; spoken subtitles, because they cannot read the translation; and the VoiceView screen reader, because that is how they operate the television at all.

All three speak. They speak simultaneously, on top of the actors, and nothing on the platform decides who gets the room. The result is not degraded — it is unusable. Amazon's own documented advice is to switch VoiceView off, which trades the ability to hear the television for the ability to operate it.

Fire OS has no hierarchy of auditory focus. There is no arbitration layer, and no API through which one service can even learn that another is speaking.

**Baton is that missing layer, built inside one application as a reference for what the platform API should look like.** It is a Fire TV video player that owns every voice the viewer can hear. It speaks audio description into the gaps in the dialogue, ducks the film underneath while it speaks — by an amount derived from how loud the film actually is at that moment, not a fixed number — and never lets two voices talk at once. That last part is an invariant with a property test behind it, not a best effort.

It also writes the description. An offline pipeline transcribes the audio, derives an authoritative map of dialogue and silence from the word timings, drafts a description for each silence, synthesises it, measures the resulting audio, and shortens anything that will not fit. Every cue reaches the runtime with a duration that was measured off real bytes rather than estimated from a character count.

The case for the platform API is in [`FRICTION_LOG.md`](FRICTION_LOG.md) entry 1. The other seventeen entries are what the platform cost on the way there.

---

## What is actually built, and what is not

This is a hackathon project. The honest scope:

**Built**

- `:director` — the arbitration state machine. Complete: two priority classes, preemption, the fit-or-drop admission rule, guard interval, seek rebaselining, pause-versus-stall handling, floor timeouts, adaptive duck depth. 75 tests, including property tests over 1,300 randomised timelines.
- `:player` — ExoPlayer adapter. Plays the clip, reports position and playback state, ducks the content, and meters the content's loudness live in the render chain.
- `:speech` — text-to-speech engine selection, rendering to WAV, the intelligibility chain, loudness normalisation, and `AudioTrack` playback with observed (not timed) completion.
- `:loudness` — ITU-R BS.1770-4 in full: K-weighting, gated integrated LUFS, a sliding short-term meter, a 4× polyphase true-peak meter.
- `:app` — the tracer. One bundled clip, one bundled description track of six description cues, a bundled English subtitle sidecar parsed into spoken-subtitle cues and rendered on-screen through Media3, the whole timeline logged under a single tag. A transport bar — Rewind, Play/Pause, Fast-forward — is the whole UI: each focus change is spoken as `UI_NARRATION` through the director, ducking the film, and is cut off by the next press.
- `:pipeline` — the offline track builder, complete and exercised end to end against recorded fixtures shaped like real Amazon Transcribe, Nova and Polly responses.

The tracer has been run on a Fire TV Stick, and the device measurements quoted throughout this document come from those runs; they are labelled where they appear. Everything else here was verified from the repository itself.

**Designed, specified, not built**

- The live AWS pipeline. `:pipeline`'s ports are defined against Transcribe, Bedrock Nova and Polly, and its fixtures are stored in those services' own response shapes so the parsers are the real ones — but no AWS SDK is on the classpath and nothing in the repository makes a network call. This is deliberate: the loop that has to be right is the one that runs for free.
- Reactive speech beyond menu narration. The director arbitrates `VQA_ANSWER` today, and its preemption behaviour is tested, but nothing in the app raises one yet. There is no question ring and no visual question answering.
- Spoken subtitles from a *live* subtitle track. The kind exists and outranks description in the policy, and the app now parses and speaks a bundled subtitle sidecar (`SubtitleFile.kt`, `TrackPreparation.kt`) the same way it speaks the bundled description track; what remains unbuilt is deriving that subtitle track from the live pipeline, rather than authoring it in advance.
- The accessible UI: screen curtain, the access-services row, per-title description state, density and length settings. Specified in [`PRD.md`](PRD.md); the app currently shows the video and the transport bar and nothing else.

**Not possible, and why it is worth saying**

Baton arbitrates the voices it owns. It cannot arbitrate against VoiceView, because VoiceView runs outside the process and exposes no signal that it is speaking. That is precisely the gap the feature request in friction-log entry 1 asks Fire OS to close.

---

## Build and run

### Prerequisites

- **JDK 17 or newer.** Verified here on Adoptium JDK 21.0.11. If `java -version` on your `PATH` reports anything older, set `JAVA_HOME` explicitly — the Android Gradle Plugin's failure message does not point at a `PATH`/`JAVA_HOME` mismatch.
- **The Android SDK.** Command-line tools are enough; Android Studio is not required. Either set `ANDROID_HOME` to the SDK root, or create a `local.properties` at the repository root containing `sdk.dir=/path/to/Android/Sdk`. (`local.properties` is git-ignored, so a fresh clone will not have one.)
- **`adb`**, from the SDK's `platform-tools`, if you want to put it on a device. It is not on `PATH` by default on any platform.

Gradle 8.7 comes from the wrapper. AGP 8.6.1, Kotlin 1.9.24 and every dependency are pinned in the build files.

### Verify it

```sh
./gradlew test :speech:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest assembleDebug
```

This is the command that is known to work. Run here with `--rerun-tasks`, so nothing was taken from the cache, it ends in `BUILD SUCCESSFUL`. Counting the JUnit XML it leaves behind under each module's `build/test-results`:

**318 test executions, 0 failures, 0 errors, 0 skipped — and 0 Kotlin compiler warnings.**

That is 253 distinct tests, by classname and name; the Android modules run their suites for both the debug and release variants (`ShellNarrationTest` runs on debug only), which accounts for 64 of the other 65 executions — the last is `DescriptionTrackBuilderTest`'s two independent tests that happen to share the display name "golden file". The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, 32,122,988 bytes.

Nothing here needs a device, a network, an emulator, or an AWS account. `:director`, `:loudness` and `:pipeline` are pure-JVM modules and their tests are plain JUnit.

### `:player:lintDebug` and Media3's `@UnstableApi` surface

Much of what this project needs from Media3 — `DefaultAudioSink.Builder`, `AudioProcessor`/`BaseAudioProcessor`, `DefaultRenderersFactory.buildAudioSink`, and `PlayerView`'s control-visibility setters — is annotated `@UnstableApi`, and Android lint treats an unannotated use of it as an error rather than a warning: unannotated, `:player:lintDebug` reports 37 `UnsafeOptInUsageError` errors, every one of them the same rule. The three declarations that actually reach those APIs — the class `BatonPlayer`, the class `DuckingAudioProcessor`, and the function `BatonPlayerSurface` — now each carry `@OptIn(UnstableApi::class)` at that scope, which is the acknowledgment Media3 asks for rather than a suppression of the rule. `:player:lintDebug` passes clean.

### Install it on a Fire TV

On the device, once: **Settings → My Fire TV → Developer Options → ADB Debugging** on, and **Settings → My Fire TV → About → Network** for its IP address. The first `adb connect` puts an authorisation prompt on the television that has to be accepted with the remote.

```sh
adb connect <device-ip>:5555
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.baton.tv/.MainActivity
adb logcat -s Baton:V
```

The app also appears on the Fire TV home row with its own banner: it declares `android.software.leanback` and a `LEANBACK_LAUNCHER` category. `minSdk` is 28, which is what governs installability on a Fire TV Stick HD; `compileSdk` is 35 for an unrelated reason explained in friction-log entry 8.

Remove it with `adb uninstall dev.baton.tv`.

### One thing that will make it silent

Baton needs a text-to-speech engine that actually has a voice. On a Fire TV Stick where VoiceView has never been enabled, the platform default engine reports `SUCCESS` from both `onInit` and `synthesizeToFile` and then produces no audio at all, failing asynchronously through a deprecated callback with no error code. Baton detects this — it selects an engine by voice count rather than by init status, and falls back to SVOX Pico — but if no engine on the device reports a usable voice, it withholds the cues rather than schedule audio that does not exist, and says so in the log:

```
ERROR no text-to-speech engine reported a usable voice; withholding 6 cues
rather than scheduling audio that does not exist
```

Enabling VoiceView once, anywhere, permanently provisions the better voice. The whole investigation is friction-log entry 9.

### Reading a run

Everything logs under one tag, and the run reads end to end:

```
adb logcat -s Baton:V
```

These are the line shapes it emits, not a transcript of a particular run: which engines are installed and which was chosen and why, the frame count and measured duration of every rendered variant, every duck decision beside the loudness it was computed from, every cue starting, ending and being dropped, and every piece of UI narration the viewer asked for.

```
track loaded asset=… source=sintel-01m41s-de-dub cues=6 gaps=11 synthetic=true
variant measured cue=d3 variant=0 engine=… frames=… measuredMs=… authoredMs=1968
narration measured phrase="Play, button" engine=… frames=… rate=…Hz measuredMs=… file=narration1.wav
duck target=…dB programmeLufs=…
cue start id=cue:d3 kind=AUDIO_DESCRIPTION cue=d3 variant=0 atPositionMs=… measuredMs=… endsAtMs=…
floor released id=cue:d3 by=speaker
reactive requested id=ui-3 kind=UI_NARRATION phrase="Fast forward, button"
cue start id=ui-3 kind=UI_NARRATION atPositionMs=…
floor released id=ui-3 by=preempted
reactive unavailable kind=UI_NARRATION phrase="Play, button" reason=not-prepared
cue dropped id=… kind=… atPositionMs=… reason=NoVariantFits(…)
state positionMs=… playback=… volume=… gain=… sinkLatencyMs=… programmeLufs=…
```

`duck target=` is logged next to the loudness measurement it was derived from, because a depth on its own is unreadable — −6 dB is either right or badly wrong depending on how loud the film was. A floor released by timeout rather than by the speaker is logged as `ANOMALY`, and so is a stall long enough to be worth seeing. `reactive unavailable … reason=not-prepared` is expected for any focus change before the track has finished preparing: until then there is no director to speak through.

---

## Remote bindings

VoiceView, Fire TV's screen reader, owns five remote gestures. Baton binds none of them, so using the app never toggles Review Mode, the text banner or the magnifier (PRD.md, "Remote control mapping"):

| Gesture | VoiceView uses it for |
|---|---|
| Menu, long press | Review Mode |
| Back + Menu | VoiceView on and off |
| Menu + Select | Exploration Mode |
| Rewind + Fast-Forward | Text Banner |
| Back + Fast-Forward | Screen Magnifier |

What the app does with the remote:

| Key | Does | Bound by |
|---|---|---|
| D-pad left / right | Moves focus along the transport bar; each landing is announced, e.g. "Play, button" | Compose focus, not the app |
| Select, short press | Activates the focused button | tv-material `Button`, not the app |
| Play/Pause, short press | Toggles playback and announces "Playing" or "Paused" | `RemoteBindings.togglesPlayback`, the only key the app binds itself |
| Back | Leaves the app | Nothing; the system default |

Left unbound on purpose:

- **Menu, pressed or held.** A long press is VoiceView's Review Mode and Menu is half of two of its chords. A short press is reserved for the question ring (issue #9).
- **The Rewind and Fast-Forward keys.** Bound a key at a time, as Media3's own controller binds them, both would fire on the Rewind + Fast-Forward chord VoiceView uses for the text banner. The on-screen Rewind and Fast-forward buttons seek instead, by Media3's default 5 s back and 15 s forward.
- **Every long press and every chord.** A held key repeats, and `RemoteBindings` acts only on the first key down.

`RemoteBindingsTest` checks the mapping on the JVM.

---

## Architecture

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

**`:director` is pure JVM.** No Android dependency, no framework dependency, no production dependency beyond the Kotlin standard library and one serialization library. It exposes `advance(nowMs, inputs) → Directive` and owns the entire arbitration policy. This is what makes the product's central claim provable: 75 tests, including a property test over randomised timelines, run on any machine with no device attached. The one dependency it does carry is `kotlinx-serialization`, because the `DescriptionTrack` codec has to live beside the model it encodes and neither `:app` nor `:pipeline` may depend on the other.

**`:ports` is interfaces and nothing else.** `PlaybackClock`, `ContentGain`, `ProgrammeLoudness`, `Speaker`, `SpeechSynth`. It exists so that neither side can reach through the seam: the director never learns that gain is a Media3 `AudioProcessor`, and the player never learns what a cue is. It is the reason the arbitration policy can be driven by a fake speaker in a unit test and by an `AudioTrack` on the device without either knowing the difference.

**`:player` and `:speech` are adapters and hold no policy.** `BatonPlayer` can tell you where the content is and how loud it may be; it cannot tell you whether a cue may speak. `Tracer` in `:app` is the loop that turns a `Directive` into things happening, and it too contains no decisions — it reads the clock, hands the director what has happened, and does what it is told.

**`:loudness` is a separate module for a specific reason.** Three consumers need the same measurement and none may depend on another. `:speech` normalises a rendered utterance to the delivery target on the device; `:player` meters the content bed live in the render chain; `:pipeline` will measure programme loudness on a workstation. `:speech` and `:player` are Android libraries and `:pipeline` is a build-time tool that must never reach an APK — so the arrow can only point down into a module all three can see.

**`:pipeline` must never become a dependency of `:app`.** It is a workstation tool. The live slice adds the AWS SDKs to it, which have no business inside a 1 GB Fire TV Stick's APK. The dependency arrow points one way: `:pipeline → :director`.

The result of pointing the arrows this way is that the correctness surface of the product — arbitration, admission, loudness — is testable without hardware, and the parts that need hardware contain nothing to get wrong.

---

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

`:pipeline`'s ports name **Amazon Transcribe** (word-level timings), **Amazon Bedrock / Nova** (video understanding and drafting) and **Amazon Polly** (synthesis), and its fixtures are stored in Transcribe's own response shape so that the parser exercised by the tests is the parser the live slice needs. No AWS SDK is on the classpath. Treat this as direction, not as a running integration.

---

## What is novel here

### The arbitration state machine, and its invariant

"Exactly one voice at a time" has two halves. The easy half — never two of *our* voices — is settled by the type: a `Directive` has room for one utterance and no representation of two. The half that has to be proven is that a scheduled voice never lands on top of the voice already in the film.

A cue may start only when

```
now >= cue.startMs  &&  now + variant.measuredDurationMs + guardMs <= nextBlockingEventMs

nextBlockingEventMs = min(endOfItsSilenceWindow, nextBookedSubtitleStartMs)
```

Spoken subtitles are known in advance, so they are booked against the timeline before descriptions are placed: a description, or the synthetic-speech disclosure, that would still be speaking when a subtitle is due is not started, even where the film is silent, and a description dropped for that is reported as `RunsIntoBookedSubtitle`, naming the subtitle.

The longest variant that satisfies it wins; if none does, the cue is **dropped and the drop is reported with a reason** rather than played late or played over. `guardMs` is 250.

`AudioDirectorInvariantTest` drives 1,000 randomised timelines through this — randomised cue placement, dialogue onsets, durations, button presses, seeks, pauses and stalls — asserting at every tick that no two voices are ever active, then repeats it 300 times under a deliberately hostile configuration. It also asserts that the generators keep *reaching* the hard states, so the suite cannot quietly go vacuous.

Three readings of the policy are worth pulling out because they were decisions, not omissions:

- **The floor is released by observation, not by prediction.** The measured duration says whether a cue may *start*. It does not decide when it has finished — the speaker reporting does. Answering the second question with arithmetic is how two voices end up in the room on a memory-constrained device, and it only ever reproduces on real hardware. The duration is demoted to a backstop, and a backstop that fires is reported as an anomaly.
- **A stall is not a pause.** Both stop the picture. A pause is a decision and is obeyed; a stall is an interruption and is waited out, with the floor and the duck held. Collapsing them cost a cut-in-half sentence every time a stream hiccupped — and reported nothing, because from the director's seat somebody had simply paused.
- **Scheduled does not preempt scheduled.** A truncated sentence is a worse artefact than a reported drop.

### An adaptive duck derived from measured loudness

The standard answer is −12 dB. The research is blunt about what that number is: a starting position a professional then rides the fader away from, cue by cue. Left static it is wrong in both directions at once — under an explosion 12 dB does not uncover the narrator, and under quiet room tone it tears an audible hole in the soundscape.

What has to be held constant is not the dip but the **loudness difference** between narration and bed. Since `SpeechClarity` renders every utterance to a known −27 LKFS, the dip is arithmetic:

```
dip = narrationLkfs − targetLoudnessDifferenceLu − programmeLufs
```

clamped so it never boosts and never digs past −19 dB, and held through a 1 dB deadband so a wobbling meter does not restart the fade thirteen times a second.

This is only possible because the app measures the programme itself. On the bundled demo clip, `ffmpeg`'s `ebur128` reports −30.3 LUFS integrated with a loudness range of 20.0 LU — LRA low −47.9, high −27.9. Put those two ends through the rule and it asks for no dip at all at the quiet end — at −47.9 LUFS the rule has already reached zero — and about −12 dB at the loud end. That is arithmetic from a measured programme loudness, not a hardware observation; what it establishes is that on this material the duck has something to adapt to.

The same body of research publishes a three-band table — −19 / −9 / −3 dB, and no ducking below −48 LUFS. At the default 12.5 LU the rule lands on those three values at −20.5, −30.5 and −36.5 LUFS, and reaches zero dip on its own at −39.5 LUFS without needing the near-silent threshold as a separate constant. The source comment is careful to call that a **range check and not a reproduction**, because the study does not publish where its band boundaries fall.

### An in-house BS.1770 meter, because there is no other way to get the number

Documented as friction-log entry 10. Every platform route to programme loudness on API 28 fails for a different reason, and on Fire TV two of them are structurally blind: under Widevine L1 the decrypted PCM lives in a TEE the application cannot address, and under HDMI passthrough what travels the pipeline is an IEC61937 payload of encoded packets rather than decoded samples.

So `:loudness` measures the samples the application can still see, in its own render chain. Its tests pin the EBU Tech 3341 compliance signals to their published values — a 1 kHz stereo sine at −23 dBFS reads −23.0 LUFS and at −33 dBFS reads −33.0 — which only comes out right if the K-weighting shelf, the RLB high-pass, the channel summation and the calibration offset are *all* correct at once. Friction-log entry 10 additionally records a cross-check against `ffmpeg`'s `ebur128` on decoded content: −21.751 LUFS against ffmpeg's −21.8, and −11.631 dBTP against −11.6.

Two things the tests pin because they are silent failures otherwise: the same signal must measure the same at 16, 24 and 48 kHz, and a mono stream must be measured as mono. Loudness sums over channels, so **the same sine in one channel reads 3.01 LU below the same sine in two** — measure a mono utterance as stereo and it reads 3 dB hot, which would have put every rendered utterance at the wrong end of the ±2 LU delivery window while every stereo test still passed.

### A speech chain tuned against measured intelligibility, not by ear

The only voice this hardware will provision is IVONA `kajal`, whose usable energy stops around 8 kHz in a 24 kHz container. The chain that fixes it is: high-pass at 110 Hz → −4 dB bell at 420 Hz → 3:1 compression with an 8 ms attack → **+6.5 dB presence at 1600 Hz, Q 0.4** → a small harmonic exciter at 6% mix → normalise to **−27 LKFS with a −2 dBTP ceiling**, which is the delivery specification Amazon Prime Video and Netflix both enforce for description narration.

The presence lift is at 1.6 kHz and the exciter is nearly off, and both look wrong to anyone who has reached for a presence control. They come from ANSI S3.5-1997's Band Importance Functions, which measure the share of intelligibility each band carries: it peaks at **1600 Hz (10.2%)**, is **6.7% at 4 kHz**, and **0.84% at 8 kHz**. The band an engineer reaches for to make a voice sound clear is not the band that carries whether the words are understood.

`SpeechClarity`'s own source comment records the wrong turn that led there: an earlier version put +6 dB at 3.8 kHz with a much larger exciter and won a listening comparison — auditioned dry, in silence, a condition this voice never occurs in. Scored with ESTOI against the earlier Tears of Steel demo bed at the signal-to-noise ratios the product actually sees, that old tuning does beat doing nothing at fixed SNR — +0.054 to +0.067 ESTOI over unprocessed at +6/0/−6 dB — but under the director's own duck, where the bed is already pulled down to protect the voice, its edge collapses to +0.005 and is positive in only 23 of 36 utterance/cue pairs: not a result. The shipped chain holds up better ducked — +0.069 to +0.082 over unprocessed at fixed SNR, +0.015 and 27 of 36 pairs once ducked — and moving the lift alone from 3.8 kHz to 1600 Hz, everything else unchanged, is worth a further +0.006 to +0.008 at fixed SNR and +0.005 under the duck. Speech that will always be heard against something else has to be tuned against something else.

One cross-module hazard worth naming, since nothing else would catch it: `SpeechClarity.Settings.targetLkfs` and `DirectorConfig.DEFAULT_NARRATION_LKFS` are the same number in two modules, and if they drift every duck in the product is wrong by the difference while nothing fails. A test pins them together.

### Netflix's ducking mandate, and the platform's answer to it

Netflix's Audio Description Style Guide mandates a ducking attack of **no less than 2 ms and no more than 15 ms**. Baton asks for 10 ms on the attack and 150 ms on the release, split by direction of travel — a duck that deepens mid-utterance attacks, one that lightens releases — with the release sized to fit inside the director's own 250 ms guard.

The mandate cannot actually be met on this hardware, and friction-log entry 12 works out why from the AOSP source and then measures it: `AudioTrack.setVolume` never reaches the mixer when it is called, AudioFlinger reads the value once per mix buffer and ramps across the whole of it, and `dumpsys media.audio_flinger` on the Fire TV Stick reports mix periods of 16, 20 and 32 ms. The shortest available is 16 ms against a ceiling of 15, and no amount of application-side work closes that gap. `OutputStageContentGain` still commands the 10 ms attack; what actually lands is the mixer's own ramp, 16 ms at best on this device.

That gap between commanded and delivered attack turns out not to cost anything downstream. Measured on the device rather than modelled: the duck is fully applied ~24 ms after the director's decision, and speech starts ~75 ms after that — comfortably ahead of the narrator's first word, with no pre-roll and nothing reserved for it in the admission rule.

The feature request writes itself, because Amazon has already built it. `@amazon-devices/keplerscript-audio-lib`, the audio library for Vega OS, exposes `duckVolumeAsync(mode, value, rampDurationMs)` alongside a `StreamDuckingPolicy` that distinguishes system ducking from `EXPLICIT` application-owned ducking. That is precisely the surface Baton needs and precisely the surface Fire OS 7 withholds — Baton arrived at the same three primitives independently and had to build them by hand. The ask is that they reach API 28's installed base, which will outlive the migration by years.

---

## Numbers, and where each one comes from

| Number | Source |
|---|---|
| 318 test executions, 0 failures, 0 errors, 0 Kotlin warnings | Run in this repository, full task rerun |
| APK 32,122,988 bytes | `app/build/outputs/apk/debug/app-debug.apk` |
| Demo clip: 88.6 s, 854×356 H.264, AAC 48 kHz stereo | `ffprobe` |
| Demo clip: −30.3 LUFS integrated, LRA 20.0 LU | `ffmpeg -af ebur128` |
| −23.0 / −33.0 LUFS on EBU Tech 3341 signals | `:loudness` tests, run here |
| 154 ms decision-to-audible for the output-stage duck | Measured on a Fire TV Stick (AFTSSS, Fire OS 7.7.1.5, Android 9 / API 28) |
| `underrunCount=0` on both the old `MODE_STREAM` build and `MODE_STATIC` — so it is not evidence for the switch: a static track always reports 0 (`StaticAudioTrackClientProxy::getUnderrunCount`, AOSP `AudioTrackShared.h`) | Same device; Android 9 audio framework source |
| 918 ms duration divergence between the two installed TTS engines | Same device; friction-log entry 9 |
| Mix periods 16 / 20 / 32 ms | `dumpsys media.audio_flinger` on the same device; friction-log entry 12 |
| Meter cross-check −21.751 vs ffmpeg −21.8 LUFS | Friction-log entry 10 |
| Band importance 10.2% / 6.7% / 0.84% at 1.6 / 4 / 8 kHz | ANSI S3.5-1997, cited in `SpeechClarity.kt` |

Numbers attributed to the device were measured on hardware in an earlier session and are recorded in `FRICTION_LOG.md` and `HANDOFF.md`; no hardware was used in preparing this document.

---

## Known gaps

Open, and not hidden:

- **An overrun is bounded, not eliminated.** The director withdraws the floor from an utterance that has run past the silence window it was granted, or past its speaker deadline -- modelled as `Speaking.Withdrawn` carrying a `Withdrawal` whose `WithdrawalReason` is `OutOfSilence`, `SpeakerOverdue`, or `NeverMeasured` (`Directive.kt`). `Tracer` acts on it by calling `speaker.stop(id)` and logs an `ANOMALY floor withdrawn` line carrying both `measuredMs` and `heldForMs`, so the price of the policy is legible afterwards. What is not bounded is the withdrawal's own latency: the overrun is the 50 ms director tick plus however long the platform's `AudioTrack` takes to actually go quiet after `stop()` -- and that second term has never been measured on the device. Baton does not claim a zero-overlap guarantee. It claims a bounded and reported one.
- **VoiceView's silence inside Baton is not device-verified.** The transport buttons are `invisibleToUser`, so VoiceView has no node of its own to read out, but Compose 1.6.8 still sends `TYPE_VIEW_FOCUSED` for a focused node that is invisible to the user. Whether VoiceView speaks on that event — a second voice on top of Baton's own narration — can only be answered on the Fire TV Stick with VoiceView on.
- The live AWS pipeline, visual question answering, the question ring, spoken subtitles from a live track, and the accessible UI surfaces beyond the transport bar are all specified and unbuilt. See the scope section above.

---

## Documents

- **[`FRICTION_LOG.md`](FRICTION_LOG.md)** — eighteen entries on where Fire OS, the SDKs, the documentation and the Windows toolchain got in the way. Kept from the first commit rather than reconstructed at the end. Entries 10, 11 and 12 are worked out from AOSP and AndroidX source, with entry 12's mix-period numbers measured on the device.
- **[`PRD.md`](PRD.md)** — the full product definition: fifty user stories, the priority model, the pipeline design, and an explicit out-of-scope list.

---

## Attribution and licence

Baton's own code is MIT-licensed — see [`LICENSE`](LICENSE). Third-party assets below keep their own licences.

The bundled demo clip is an 88.6-second excerpt of **Sintel**, from 01:41.208 to 03:09.792, re-encoded to 854x356 and dubbed into German. Its audio is the Blender Foundation's Sintel music-and-effects stem with a German dub voiced by Amazon Polly (synthetic voices) mixed over it. The German dialogue text comes from the Blender Foundation's `sintel_de.srt`, adapted: lines 3, 4 and 11 are shortened, and "Torwaechter" is spelled "Torwächter". The English subtitles are the text of the Blender Foundation's `sintel_en.srt`, retimed to the German dialogue.

> Sintel — © copyright Blender Foundation | durian.blender.org — Creative Commons Attribution 3.0

Used here under [CC-BY 3.0](https://creativecommons.org/licenses/by/3.0/), which permits excerpting and derivative works with attribution; source links and excerpt details are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

The description cues in `app/src/main/assets/sample_clip.track.json` are original to this project. They are machine-spoken, and the track marks itself `"synthetic": true` — the field is part of the track format, set by the pipeline and read back by the loader, because the American Council of the Blind's guidance on synthetic description is that the audience should be told. The director acts on that field: a synthetic track owes one spoken disclosure per playback session, rendered and measured on the device's engine like every cue, and admitted into the first silence long enough to hold it (see `AudioDirector`). The sentence itself is this project's wording; the guidance is cited for the requirement, not for the words.

The `:pipeline` fixtures under `pipeline/src/main/resources/fixtures/` are invented dialogue and invented descriptions, written to exercise the builder. They contain no third-party content.
