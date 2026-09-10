# Friction Log — Baton

A running record of where the platform, the SDKs, the documentation, or the tooling got in the way while building Baton for Fire TV.

Kept from the first commit rather than reconstructed at the end. Each entry records what was attempted, what happened, and what the workaround was.

Environment: Windows 11, Fire TV Stick HD (Fire OS 7 / Android 9 / API 28), targeting the Amazon Developer Hackathon Fire TV track.

---

## Building, installing and running

Recorded here because half of it is the workaround for entry 7 and the other half is the workaround for entry 9.

**Prerequisites.** A JDK 17 or newer, and the Android SDK platform-tools for `adb`. On this machine `java` on `PATH` is Java 8, so every Gradle invocation names the JDK explicitly:

```sh
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
```

**On the Stick, once.** Settings → My Fire TV → Developer Options → ADB Debugging on. Settings → My Fire TV → About → Network for its IP address. The first `adb connect` puts an authorisation prompt on the television that has to be accepted with the remote.

**Build, install, run, and watch what it decides:**

```sh
adb connect 192.168.1.17:5555
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.baton.tv/.MainActivity
adb logcat -s Baton:V
```

The app also appears on the Fire TV home row with its banner, because it declares `android.software.leanback` and a `LEANBACK_LAUNCHER` category.

`Baton` is the one tag the whole runtime logs under, and the run reads end to end in it: which text-to-speech engines are installed, which one was selected and why, the frame count and measured duration of every rendered variant, each cue starting, the duck target, speech starting and ending, and which cause released the floor. A floor released by timeout rather than by the speaker is logged as `ANOMALY`.

**The remote.** Play/pause, rewind and fast-forward come from the Media3 transport bar, which takes D-pad left/right and the dedicated media keys. `adb shell input keyevent 85` (play/pause), `89` (rewind) and `90` (fast-forward) drive the same path over the wire.

**Removing it:** `adb uninstall dev.baton.tv`.

**Two things that will waste an afternoon if you skip them.** `minSdk` is what governs whether the Stick will install the APK; `compileSdk` is 35 for the reason in entry 8 and is unrelated. And engine selection depends on `<queries>` for `android.intent.action.TTS_SERVICE` in the manifest — without it a build against a newer platform sees an empty device and speaks nothing, with no error.

---

## 1. Fire OS has no hierarchy of auditory focus

**Severity:** high — this is the project's thesis
**Area:** Fire OS accessibility platform

**What was attempted.** Establish which component decides who speaks when audio description, spoken subtitles, and the VoiceView screen reader are all active over content that has its own dialogue.

**What happened.** Nothing decides. There is no arbitration layer and no API through which one service can learn that another is speaking. All of them speak simultaneously. For a blind viewer watching foreign-language content — the case where all three services are genuinely needed at once — the result is three overlapping voices plus the original dialogue.

**Workaround.** Amazon's own documented advice is to disable VoiceView. That restores intelligibility at the cost of the user's ability to operate the television. There is no workaround that preserves both.

**Feature request.** Fire OS should expose an **acoustic priority API**: a system-level service through which any component that intends to speak declares an utterance with a priority class, and receives back either a grant or a deferral. Minimum viable surface:

- Register an utterance with a class (`user_initiated`, `time_critical`, `ambient`) and an expected duration
- Receive a callback when the floor is granted, and when it is revoked by a higher-priority claim
- Observe whether the foreground media session's content is currently in dialogue
- A system-owned ducking policy so every participant attenuates content identically

Without this, every application that wants to be accessible must reimplement arbitration privately, and cannot arbitrate against VoiceView at all — because VoiceView is outside the app's process and exposes no such signal.

Baton implements this arbitration inside a single application as a reference for what the platform API should look like.

---

## 2. Third-party applications cannot access the microphone

**Severity:** high
**Area:** Fire TV input APIs

**What was attempted.** Add a spoken "ask about what is on screen" interaction, so a blind user could ask a question without navigating a menu.

**What happened.** Three separate dead ends, none of them documented together in one place:

- The microphone button on the Alexa Voice Remote routes directly to Alexa's cloud service. It is not delivered to the foreground application as an input event.
- Fire OS ships no Google Play Services, so the platform `SpeechRecognizer` API is unavailable. Code written against the standard Android speech APIs compiles and then fails at runtime.
- `AudioRecord` with `RECORD_AUDIO` granted appears to succeed and returns silent buffers, which is worse than failing, because it looks like a bug in your own code.

**Workaround.** Abandon voice input entirely and bind the interaction to a remote button. This turned out better for the use case — no wake word, no recognition latency, and it works with the television screen off — but arriving there took three failed approaches.

**Documentation gap.** The voice-enablement documentation describes what an app *can* do with Alexa integration. It does not state plainly that direct microphone capture is unavailable to third-party apps. A single sentence saying so, on the audio input page, would have saved the entire investigation.

---

## 3. Audio description becomes stuck on across all content

**Severity:** medium
**Area:** Fire OS accessibility settings

**What was observed.** A recurring user report: once audio description is enabled, it stays enabled for all content indefinitely, including content where the user never requested it. The support-recommended remedy is a factory reset.

**Why it matters.** The cost of enabling an accessibility feature should never be a factory reset. This defect teaches users not to try accessibility features at all, which is the opposite of the intent.

**How Baton avoids it.** Description enablement is stored per title, with the global default off. Enabling description on one title does not enable it anywhere else, and a title's description can be muted without disabling the feature globally.

---

## 4. Amazon's sample repositories are not under the organisation the docs imply

**Severity:** low, but costs time
**Area:** documentation and discovery

**What was attempted.** Fetch `react-native-multi-tv-app-sample`, referenced from the hackathon resources page.

**What happened.** `amzn/react-native-multi-tv-app-sample` returns "repository not found." The samples live under `AmazonAppDev`. Amazon publishes under several GitHub organisations — `amzn`, `AmazonAppDev`, `amazon-archives` — and the resources page links to repositories without making the owning organisation obvious at a glance.

**Workaround.** Search for the repository name rather than trusting a constructed path.

---

## 5. The Agent Skills ecosystem for these devices is nearly empty

**Severity:** low — this is an opportunity, not a defect
**Area:** developer tooling

**What was observed.** `AmazonAppDev/devices-agent-skills` currently contains two skills: React Native TV UI best practices, and Vega multi-TV migration. Nothing covers Fire TV accessibility, Fire OS platform constraints, or building for older API levels on memory-constrained devices.

For a hackathon that explicitly rewards open-source contribution, this is the most obviously useful place to contribute, and the contributing guide is already open.

---

## 6. Devpost blocks non-browser user agents

**Severity:** low
**Area:** hackathon tooling

**What was attempted.** Read the hackathon rules and resources pages programmatically.

**What happened.** Every path returned `HTTP 403 Forbidden` to a plain HTTP client, regardless of path.

**Workaround.** Drive a real browser and read the rendered text out of the DOM.

---

## 7. Windows toolchain: nothing needed for Fire TV development is present by default

**Severity:** medium
**Area:** getting started

**What was attempted.** Build an Android application for Fire TV on a clean Windows 11 machine.

**What happened.** A sequence of small obstacles, each individually trivial and collectively an afternoon:

- A JDK 21 installation was present and `JAVA_HOME` pointed at it, but `java` on `PATH` resolved to **Java 8**. The Android Gradle Plugin requires 17 or newer, and the resulting failure message does not point at the `PATH`/`JAVA_HOME` mismatch.
- No Android SDK, no `adb`, no `gradle`. `adb` is what you need to put anything on a Fire TV Stick at all, and it arrives only as part of the SDK's platform-tools.
- The command-line-tools download URL embeds a build number that changes with every release. Every build number found in documentation, blog posts, and older tutorials still returns HTTP 200 and installs an outdated toolchain, so there is no error to tell you that you have the wrong one. The only reliable source is scraping the current filename off `developer.android.com/studio`.

**Workaround.** Install command-line tools only rather than the full Android Studio — roughly 150 MB against roughly 1 GB, no GUI, and scriptable. Scrape the current build number rather than hardcoding one.

**Documentation gap.** Amazon's Fire TV getting-started material assumes Android Studio is already installed and configured. For a developer arriving from outside the Android ecosystem — which a hackathon will attract — there is no lean, scriptable path documented.

---

---

## 8. `androidx.tv:tv-foundation:1.0.0` forces a compileSdk bump the project's target device has nothing to do with

**Severity:** medium
**Area:** Android Gradle Plugin / dependency resolution

**What was attempted.** Add `androidx.tv:tv-foundation:1.0.0` and `androidx.tv:tv-material:1.0.0` to a project pinned at `compileSdk = 34` (matching the Fire TV Stick HD's real ceiling of interest, API 28) — the dependency pair that Compose-for-TV guides and starter templates hand you.

**What happened.** The build failed immediately with roughly 30 separate `Dependency ... requires libraries and applications that depend on it to compile against ... API 35` / `... AGP 8.6.0 or higher` errors — one per transitive artifact pulled in by tv-foundation, not one summary error. The actual constraint is a single fact (tv-foundation 1.0.0's AAR metadata hard-requires `minCompileSdk=35` and AGP ≥ 8.6.0) baked into the artifact itself; it has nothing to do with anything the developer controls, but the failure presents as a wall of near-identical messages that reads like a cascading dependency conflict rather than one clear statement.

Separately, once compileSdk/AGP were raised to satisfy that gate, resolving the tv artifacts' transitive graph without pinning pulled in `kotlin-stdlib:2.1.20` (a hard dependency constraint in tv-foundation's own POM) and `compose-ui-tooling:1.10.x`, both incompatible with this project's Kotlin 1.9.24 compiler — `compileDebugKotlin` failed with "Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.1.0, expected version is 1.9.0." The tv-foundation-1.0.0 AAR was itself built with a Kotlin 2.1 compiler, and Kotlin's metadata format is not forward-readable by an older compiler.

**The workaround we reached for first, and why it was wrong.** compileSdk and minSdk are independent, and the error text does say so — raising `compileSdk` to 35 costs nothing at runtime and does not raise the device floor, so `minSdk` stayed 28. For the transitive graph we then excluded `androidx.compose.*`, `androidx.activity`, `androidx.lifecycle`, `androidx.savedstate`, `androidx.core` and `org.jetbrains.kotlin` from both `androidx.tv` dependencies, re-declared them against compose-bom 2024.06.00, and added `-Xskip-metadata-version-check` to `freeCompilerArgs`. That built. It was also the wrong answer: `-Xskip-metadata-version-check` disables a real correctness gate for *every* Kotlin compilation in the module, and this project is about to grow several more.

**The actual fix.** `tv-foundation` was never imported. The single `androidx.tv` symbol anywhere in the codebase was `androidx.tv.material3.Surface`, which comes from `tv-material`. Deleting the `tv-foundation` dependency removed the AAR-metadata gate, the Kotlin metadata conflict, the `org.jetbrains.kotlin` exclusion and the compiler flag in one line. A clean build then succeeded with no override of any correctness check.

This turned out to be the documented path rather than a lucky escape. `tv-foundation` 1.0.0 (January 2025) **removed** `TvLazyRow`, `TvLazyColumn`, `TvLazyHorizontalGrid` and `TvLazyVerticalGrid` outright — that functionality was absorbed into `compose-foundation` 1.7.0, which gained the focus-positioning behaviour TV apps needed. Google's own migration guidance is, verbatim, to remove the dependency and swap `TvLazy*` for `Lazy*`. So the artifact that forced compileSdk 35, AGP 8.6.0+, a nine-group exclusion block and a project-wide correctness override is a near-empty shell nobody is supposed to depend on any more.

**Documentation gap.** Two distinct ones.

First, nothing in the AAR-metadata error text or the `androidx.tv` release notes mentions the Kotlin compiler version constraint. It only surfaces after compileSdk and AGP are already fixed, so you see two unrelated-looking failures in sequence for one root cause.

Second, and more costly: `tv-foundation` is still published, still resolves, and is still paired with `tv-material` in most Compose-for-TV tutorials and starter templates, with nothing at the point of use to say it is now empty. Its deprecation is recorded in release notes a developer only reads *after* something breaks. An emptied artifact that still imposes hard toolchain requirements on everyone who adds it is worse than a removed one — the cost lands entirely on people who copied a dependency pair from a guide.

**Lesson recorded for later slices.** When a single dependency demands a toolchain escalation, check whether anything actually imports it before working around it. We escalated three times — compileSdk, then exclusions, then a compiler flag — for a library contributing zero symbols.

---

## 9. The good text-to-speech voice is gated behind enabling the screen reader, and fails silently until you do

**Severity:** high
**Area:** Fire OS text-to-speech / device provisioning

**What was attempted.** Speak a line of text from a third-party application on a Fire TV Stick (AFTSSS, Fire OS 7.7.1.5, Android 9 / API 28, 900 MB RAM), using the standard Android `TextToSpeech` API.

**What happened.** Two engines are installed and enabled, both declaring `android.intent.action.TTS_SERVICE`. IVONA is the platform default, and on a device where VoiceView has never been switched on it is a shell with no voice data:

| state | engine | `onInit` | voices | `synthesizeToFile` | audio produced |
|---|---|---|---|---|---|
| VoiceView never enabled | `com.ivona.tts.oem` *(default)* | `SUCCESS` | 0 | returns `SUCCESS` | **none** |
| VoiceView never enabled | `com.svox.pico` | `SUCCESS` | 11 | returns `SUCCESS` | 4648 ms, 16 kHz |
| VoiceView enabled | `com.ivona.tts.oem` | `SUCCESS` | 1 (`kajal`, en-IN) | returns `SUCCESS` | 3730 ms, 24 kHz |
| VoiceView enabled, then **disabled** | `com.ivona.tts.oem` | `SUCCESS` | 1 (`kajal`, en-IN) | returns `SUCCESS` | 3730 ms, 24 kHz |

Three distinct problems are visible in that table.

**First, the default engine fails silently.** IVONA is what `getDefaultEngine()` returns, so the ordinary two-argument constructor — `TextToSpeech(context, listener)`, the form in every tutorial — resolves to it. Every synchronous signal reports a healthy engine. `onInit` returns `SUCCESS`. `synthesizeToFile` returns `SUCCESS`. The failure arrives later, asynchronously, through the **deprecated** `onError(utteranceId)` callback, carrying no error code. An application that checks return values and does not implement the obsolete overload of an optional listener observes nothing wrong and produces silence. `IvonaTTS.apk` contains no voice data; voices are fetched on demand by `com.ivona.orchestrator` (`INSTALL_VOICE_EXTENSION`, `GetBestVoiceForDownloadJobService`). By contrast SVOX Pico ships 6.1 MB of voice data in `/system/tts/lang_pico` and works from first boot.

**Second, the provisioning trigger is enabling the screen reader.** Nothing else we could find causes a voice to be fetched. Enabling VoiceView provisions one — here `kajal`, matching the device's en-IN locale — and the voice then **persists after VoiceView is switched off again**. So it is a one-time unlock, not a runtime dependency. Which means the quality of speech a third-party application can produce depends on whether the user has, at some point in the life of the device, turned on a service they may have no reason to touch. Two identical Fire TV Sticks running identical software will give the same application different voices, and neither the user nor the developer is told why.

**Third — and this is the one that costs correctness, not just quality — the two engines disagree about duration by 25%.** The same sentence renders as 3730 ms on IVONA and 4648 ms on Pico: a 918 ms divergence. Any application that measures an utterance with one engine and plays it with another is wrong by nearly a second. For Baton, which admits speech into a gap in the dialogue only when it has measured that the speech fits, 918 ms is roughly four times the guard interval. It is the difference between speaking in silence and speaking over an actor.

**Workaround.** Never take the default engine. Enumerate `TextToSpeech.getEngines()`, name a candidate explicitly in the three-argument constructor, and treat an engine reporting zero voices as unavailable regardless of what its init status claims. Implement both `onError` overloads, including the deprecated one, because on this path only the deprecated one fires. Never reuse a duration measured on one engine when synthesising on another.

**Why this matters beyond one bug.** The engine that works out of the box is a 2010-era formant synthesiser at 16 kHz. The engine that sounds good is IVONA — the same lineage as Amazon Polly, and the voice VoiceView itself speaks with. It is present on every device, it is the documented default, and it is inert until the screen reader has been enabled once.

There is a further turn, and it lands on this project's thesis. The path that provisions the good voice is enabling VoiceView. But Amazon's own published remedy for simultaneous audio description and screen-reader speech — the defect described in entry 1 of this log — is to *disable* VoiceView. A user who follows Amazon's accessibility guidance to the letter on a fresh device therefore never triggers provisioning, and every application on that device, including the accessibility applications that need it most, is left with the worse voice and no indication that a better one exists.

**Feature request.** Three, in order of value:

1. **Provision a default IVONA voice at first boot**, independent of whether the screen reader is ever enabled. The voice data is small, the download already exists, and the current trigger couples an unrelated user choice to the speech quality of every application on the device.
2. **Fail loudly.** An engine with zero installed voices should return an error from `onInit` rather than `SUCCESS`, and `synthesizeToFile` should not return `SUCCESS` for an utterance it cannot render. Reporting success and then emitting silence through a deprecated callback is the most expensive possible way to communicate a missing asset.
3. **Expose the provisioning state.** If the good voice is going to remain gated, give applications a supported way to ask whether it is available and a supported way to request it, so an application can explain the situation to the user instead of silently sounding worse.

**How Baton responds.** Scheduled speech — audio description and spoken subtitles, both authored ahead of time — ships as pre-rendered audio referenced by `Variant.audioRef` and never touches the platform engine, which also removes the cross-engine duration divergence from the correctness path entirely. Reactive speech, which cannot be known before the button is pressed, selects an engine at runtime: IVONA when it reports a usable voice, Pico otherwise. Because the failure is silent, engine selection validates by voice count rather than by init status.

---

## 10. An application cannot find out how loud the content it is playing is

**Severity:** high — it forces every well-behaved application to reimplement a broadcast standard
**Area:** Fire OS media framework (API 28)

**What was attempted.** Duck the content by a defensible amount. The dip that makes description intelligible is not a fixed number of decibels; it is whatever holds the narration a constant loudness difference above the bed, so it depends on how loud the bed currently is. That requires measuring the programme's loudness — ITU-R BS.1770 — while it plays.

**What happened.** There is no route to that measurement on API 28. Every candidate fails for a different reason:

- **`LoudnessEnhancer`** (API 19) is an effect, not a meter. Its entire public surface is `setTargetGain()` / `getTargetGain()`. It changes audio; it never reports it.
- **`MediaFormat.KEY_AAC_DRC_OUTPUT_LOUDNESS`** is the readback key, and it **arrives at API 30**. Confirmed by diffing the field list across tagged AOSP releases of `frameworks/base/media/java/android/media/MediaFormat.java`: absent in `android-9.0.0_r36` (API 28) and `android-10.0.0_r41` (API 29), present in `android-11.0.0_r1` (API 30). At API 28 only the *input* DRC keys exist — `KEY_AAC_DRC_TARGET_REFERENCE_LEVEL`, `_BOOST_FACTOR`, `_ATTENUATION_FACTOR`, `_HEAVY_COMPRESSION`. You may tell the decoder how to apply DRC. You may not read back what it measured. Even at API 30 it is AAC-only, quantised to 0.25 LKFS steps, and returns `-1` when the bitstream carries no MPEG-D DRC metadata — the common case for a film master.
- **`LoudnessCodecController`** is **API 35** and `@FlaggedApi` besides — seven major versions above this device — and is a CTA-2075 normalisation applier rather than a meter even where it exists.
- **`Visualizer`** (API 9) is the only real candidate, and it fails three times over. It requires `android.permission.RECORD_AUDIO` merely to observe audio the application is itself playing. It returns 8-bit unsigned mono samples and an 8-bit magnitude FFT — roughly 48 dB of usable range against BS.1770's −70 LKFS absolute gate, so quantisation noise sits exactly where the gate operates. Android's own class documentation says it plainly: *"It is not an audio recording interface and only returns partial and low quality audio content."*

  On Fire TV it is worse than inadequate — it is structurally blind on the two paths that matter most. Under Widevine L1, decrypted PCM is routed through the SoC's Trusted Execution Environment into secure ION heaps that user space cannot address at all. Under HDMI passthrough, what travels the pipeline is an IEC61937 payload of encoded packets, not decoded samples. In both cases a visualizer attached to the session returns zeroes.

**Workaround.** Implement ITU-R BS.1770-4 and run it over the decoded PCM inside the render chain, where the application can still see its own samples. Baton's `:loudness` module is that: K-weighting, gated integrated LUFS, a sliding short-term meter, and a 4× polyphase true-peak meter. It is validated against the EBU Tech 3341 compliance signals to their published values (−23.0 and −33.0 LUFS, ±0.1 LU) and cross-checked against `ffmpeg`'s `ebur128` on real decoded content (integrated −21.8 ffmpeg / −21.751 ours; true peak −11.6 dBFS / −11.631 dBTP).

Two costs that are worth naming, because they are the reason this should not be application work. The standard's coefficient table is specified at 48 kHz and nothing on this device runs at 48 kHz — IVONA renders at 24 kHz, Pico at 16 kHz — so the filters must be re-derived from the analogue prototype with a `tan()` prewarp per sample rate. And a mono file measured as stereo reads 3 dB hot, which would have put every rendered utterance at the wrong end of Amazon's own ±2 LU window while every stereo test still passed.

**Feature request.** A **programme loudness meter** on the media session: BS.1770 short-term and integrated loudness for the content the application is currently playing, readable without `RECORD_AUDIO`, and available on the DRM and passthrough paths where the platform is doing the decoding anyway. The platform already has the decoded samples in a place the application cannot reach; it is uniquely positioned to measure them. Note that this gap is **not** closed by Amazon's next-generation audio library — see entry 12 — which fixes the control side of this problem and leaves the measurement side untouched.

---

## 11. The loudness metadata that ships inside the stream is parsed, then thrown away

**Severity:** medium — the number already exists, computed by the people best placed to compute it
**Area:** AndroidX Media3 / ExoPlayer extractor

**What was attempted.** Before writing a loudness meter from scratch (entry 10), establish whether the content already carries its own loudness. AC-3 and E-AC-3 both do: `dialnorm` is a mandatory five-bit field in every syncframe header, set by the mastering engineer to describe the programme's dialogue level.

**What happened.** The value is in the bitstream, the parser reads right past it, and nothing downstream ever sees it. In `androidx/media`, `libraries/extractor/src/main/java/androidx/media3/extractor/Ac3Util.java`, inside `parseAc3SyncframeInfo` (line 280):

```java
data.skipBits(5 + 5);   // bsid, dialnorm      (line 331)
if (data.readBit()) {   // compre
  data.skipBits(8);     // compr               (line 333)
}
if (acmod == 0) {
  data.skipBits(5);     // dialnorm2           (line 336)
```

Three loudness-related fields — `dialnorm`, the compression gain word `compr`, and `dialnorm2` for dual-mono — are stepped over by advancing the bit cursor. None is read into a variable, so there is nothing for the returned `SyncFrameInfo` to expose even in principle: it carries `mimeType`, `streamType`, `sampleRate`, `channelCount`, `frameSize`, `sampleCount` and `bitrate`, and no loudness of any kind. The source comments name the fields being discarded, so this is deliberate rather than an oversight.

Nor is there anywhere else to get it. There is no `KEY_AC3_*` or `KEY_EAC3_*` in `MediaFormat` at any API level, and the Fire TV vendor Dolby decoder exposes no private vendor key for it either. Media3 hand-parses this header precisely *because* the platform surfaces nothing — and then discards the one field that would have answered the question.

**Workaround.** None. Measure it yourself, as in entry 10. This is the more frustrating of the two gaps: entry 10 is "the platform will not measure this for you", whereas this is "a mastering engineer already measured it to a published standard and encoded it into the file, and the library reads past it".

**Feature request.** Surface `dialnorm` on `SyncFrameInfo`, and the equivalent loudness metadata for other codecs on `Format`. It costs a field and a few bits of parsing that already happen. Two open issues on `androidx/media` — [#1806](https://github.com/androidx/media/issues/1806) and [#2497](https://github.com/androidx/media/issues/2497) — ask for adjacent loudness support and remain unresolved, so this is not a niche request.

*Verified against the `androidx/media` release branch. Baton pins Media3 1.4.1; the skip is longstanding ExoPlayer code and is not believed to differ, but the exact 1.4.1 tag was not diffed.*

---

## 12. An application cannot duck faster than one mixer buffer — and Amazon's own next-generation audio library agrees it should be able to

**Severity:** high — it puts a broadcast mandate out of reach of any Fire TV application
**Area:** AudioFlinger / AudioMixer (API 28)

**What was attempted.** Meet Netflix's Audio Description Style Guide, which mandates a ducking attack of **no less than 2 ms and no more than 15 ms**. Longer than 15 ms and the bed is still on its way down when the narrator's first consonant arrives, masking it — the exact failure this product exists to prevent.

**What happened.** The platform cannot deliver it, and the reason is structural rather than a matter of tuning.

`AudioTrack.setVolume` does not reach the mixer when it is called. It packs the gain as a minifloat into the track's shared control block and returns; the only IPC is a `signal()`, and only for offloaded tracks (`frameworks/av`, `media/libaudioclient/AudioTrack.cpp:867-885`). AudioFlinger's mixer thread reads that block **once per mix buffer** in `prepareTracks_l` (`services/audioflinger/Threads.cpp:4414`, `:4561`) and hands the value to `AudioMixer` as `RAMP_VOLUME` (`Threads.cpp:4543`), which interpolates linearly from the previous gain to the new one across the whole of that buffer.

Two consequences follow. Every value written between two of those reads is overwritten before it is ever seen, so ticking the volume faster than the mix period changes nothing audible. And the ramp length is the mixer buffer: `new AudioMixer(mNormalFrameCount, mSampleRate)` (`Threads.cpp:3801`), where `mNormalFrameCount` is clamped between `kMinNormalSinkBufferSizeMs = 20` and `kMaxNormalSinkBufferSizeMs = 24` (`Threads.cpp:135`, `:137`, `:2546-2549`) whenever the fast mixer is compiled in — and it is, `kUseFastMixer = FastMixer_Static` (`Threads.cpp:171`). The clamp only ever scales the buffer *up*, so where the HAL period already exceeds 20 ms it stays there. The application's own track cannot take the fast path either, because `DefaultAudioSink` requests at least 250 ms of PCM.

Measured on the device rather than inferred. `dumpsys media.audio_flinger` on the Fire TV Stick (AFTSSS, Fire OS 7.7.1.5) reports four output threads, all running at 48 kHz:

| Thread | HAL frames | Normal frames | Mix period | FastMixer |
|---|---|---|---|---|
| `AudioOut_D` | 768 | 768 | **16 ms** | No |
| `AudioOut_15` | 480 | **960** | **20 ms** | Yes |
| `AudioOut_4D` | 1536 | 1536 | **32 ms** | No |
| `AudioOut_55` (duplicating) | 1536 | 1536 | 32 ms | No |

`AudioOut_15` shows the AOSP clamp working exactly as the source describes: the HAL offers 480 frames (10 ms) and AudioFlinger rounds it up to 960 (20 ms), which is `kMinNormalSinkBufferSizeMs` to the frame. The other threads keep the HAL's own period.

**The shortest mix period available on this device is therefore 16 ms, against a mandated ceiling of 15 ms, and no amount of application-side work closes the gap.** The margin is small, but it is the wrong side of the specification and it is not a number an application can influence.

One honest caveat: `AudioOut_D` at 768 frames is *not* what the AOSP source above would produce — the multiplier branch (`Threads.cpp:2557-2567`) should scale 768 up to 1152 frames (24 ms), since `2 × 768` exceeds `maxNormalFrameCount`. It reports 768. Either that thread is not taking the clamp path, or Fire OS differs from stock AOSP here. This does not change the conclusion — every observed period exceeds 15 ms — but the discrepancy is recorded rather than smoothed over, and which of the four threads a media track actually lands on has not yet been pinned down.

**Workaround.** Command a 10 ms attack and accept that the platform's own ramp — 16 ms at best on this hardware — is what is actually heard. Baton splits attack from release for this — `attackMs = 10`, `releaseMs = 150` — chosen by direction of travel, so a duck that deepens mid-utterance attacks while one returning to unity releases. The release is bounded by the director's own 250 ms guard interval rather than by any platform number. There is one compensation worth recording: because `RAMP_VOLUME` smooths every write across a buffer, no single volume step can click, however large. The platform takes the fine control away and hands back a guarantee of smoothness in its place.

**Feature request.** A ducking API with an explicit, application-supplied ramp duration.

This one requires no speculation about what the API should look like, because **Amazon has already built it.** In `@amazon-devices/keplerscript-audio-lib@2.0.18` — the audio library for Vega OS, Amazon's next-generation device operating system — `dist/AudioPlaybackStream.d.ts:712`:

```typescript
duckVolumeAsync(mode: Int32, value: Int32, rampDurationMs: Int32): Promise<Int32>;
```

with `DuckingMode.DB (0)` or `PERCENTAGE (1)`, a `value` documented as `0-144` dB of reduction where `0` unducks, and `rampDurationMs` documented as *"Time in milliseconds to go from current volume to target volume"*. Alongside it, `StreamDuckingPolicy` distinguishes `SYSTEM (0)`, where the platform ducks automatically, from `EXPLICIT (1)` — *"Application must handle ducking"* — set through `builder.setDuckingPolicy(StreamDuckingPolicy.EXPLICIT)`.

That is precisely the surface Baton needs and precisely the surface Fire OS 7 withholds: an application-owned ducking policy, a depth in decibels, and a ramp duration in milliseconds. Baton arrived at the same three primitives independently and had to build them by hand on a platform that offers none of them.

The request for Fire OS is simply that this reach API 28's installed base, which will outlive the migration by years. It is worth noting what Vega does *not* add: there is no loudness, LUFS, or `dialnorm` surface anywhere in that library, so entries 10 and 11 remain open even on the new stack. The control problem is solved there. The measurement problem is not solved anywhere.

---

*Entries are appended as they are encountered. This log is a deliverable, not a postscript.*
