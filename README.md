[![Baton: one voice at a time](docs/images/banner.png)](#baton-one-voice-at-a-time)

[![Demo video](https://img.shields.io/badge/demo-watch%20on%20Vimeo-1ab7ea.svg)](https://vimeo.com/1238851719)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform: Fire TV](https://img.shields.io/badge/platform-Fire%20TV-orange.svg)](docs/BUILD.md)
[![Android API 28](https://img.shields.io/badge/Android-API%2028-blue.svg)](docs/BUILD.md)
[![Kotlin 1.9.24](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg)](docs/ARCHITECTURE.md)
[![Tests: 459 passing](https://img.shields.io/badge/tests-459%20passing-brightgreen.svg)](docs/NUMBERS.md)

## Baton: one voice at a time

> #### [▶ Watch the demo (2:57), filmed on a real Fire TV Stick](https://vimeo.com/1238851719)

A blind viewer puts on a foreign-language film. They need three voices at once: **audio description** to know what is on screen, **spoken subtitles** because they cannot read the translation, and the **VoiceView** screen reader to operate the TV at all. On Fire OS none of them knows the others exist, so all three talk at once, on top of the actors. Blind users report switching VoiceView off to escape it, trading the ability to operate the TV for the ability to hear it.

**Baton is the missing layer.** It is a Fire TV video player that owns every voice the viewer hears, speaks audio description into the gaps in the dialogue, dips the film only as much as the scene needs, and never lets two voices talk at once. That last part is an invariant with a property test behind it, not a best effort. It is built inside one application as a reference for what a Fire OS platform API should look like; the case for that API is [friction-log entry 1](FRICTION_LOG.md).

Built for the Build, Ship, Shape: Amazon Developer Hackathon, Fire TV track. Runs on a Fire TV Stick HD (Fire OS 7, Android 9, API 28).

## Table of Contents

- [What Baton Does](#what-baton-does)
- [It Writes Its Own Description](#it-writes-its-own-description)
- [Proven, Not Claimed](#proven-not-claimed)
- [Quick Start](#quick-start)
- [Documentation](#documentation)
- [Reporting Bugs and Issues](#reporting-bugs-and-issues)
- [License and Attribution](#license-and-attribution)

### What Baton Does

- **One director decides who speaks.** The film's dialogue, spoken subtitles, audio description and Baton's own spoken menus all go through one arbitration state machine, `:director`.
- **Fit or drop.** A description starts only if its measured length fits the silence it was scheduled for. If it doesn't fit, it is dropped and logged, never played late or over dialogue.
- **An adaptive duck.** While a voice speaks, the film is lowered by an amount computed from how loud the film is at that moment, measured live with an ITU-R BS.1770-4 meter written from scratch.
- **Eyes-free from the first press.** Every button speaks its name when focused. Description is off by default and remembered per title. A screen curtain blacks out the picture and leaves the sound alone.
- **It gets out of VoiceView's way.** When VoiceView asks to speak, Baton stops talking; on the Stick its voice was released 24 ms after VoiceView took focus.
- **Honest about itself.** Every voice is synthetic, and Baton says so, out loud and on screen.

### It Writes Its Own Description

An offline pipeline turns a film into a description track:

```
Amazon Transcribe ─► map of speech and silence ─► video model drafts a description per silence
                 ─► Amazon Polly speaks it ─► measured ─► shortened until it fits
```

Every cue reaches the runtime with a duration measured off real audio, not estimated from a character count.

Drafting was built for Amazon Bedrock (Nova). AWS declined Bedrock access for this account ([friction-log entry 21](FRICTION_LOG.md)), so it runs on Google's Gemini API, free tier, behind the same interface. Each draft is shown only the seconds it will be spoken over. On the demo clip it wrote 12 descriptions ([`gemini_drafted_track.json`](submission/gemini_drafted_track.json)); checked against frames from each one's moment, none describes something that does not happen, and the one slip is calling the girl's red hair pink. That run used speech lengths simulated rather than rendered by Polly, to keep it free; the bundled demo track is still the hand-reviewed one.

> #### [Read the full scope: what is built, and what is not](docs/SCOPE.md)

### Proven, Not Claimed

| | |
|---|---|
| **459** test executions, 0 failures | including property tests over **1,300** randomised timelines |
| **0.997 dB** of dip for every dB asked for | measured at a Fire TV Stick's own output |
| **12.1 LU** voice above the film | where the rule asks for 12.5 |
| **24 ms** to yield to VoiceView | measured on the Stick |
| **2.96 s** warm start to the film | median of five launches |

> #### [Every number, and where it comes from](docs/NUMBERS.md)

### Quick Start

**Just want to try it?** [Download the APK](https://github.com/IsGaming7154/baton/releases/latest) and follow the install steps on the release page: one `adb install` onto a Fire TV.

To build it yourself, you need JDK 17+, the Android SDK (`ANDROID_HOME` or `local.properties`), and `adb` to install on a device. No device, network or AWS account is needed to run the tests.

```sh
./gradlew test :speech:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest assembleDebug
```

> #### [Full build, install and remote-control guide](docs/BUILD.md)

### Documentation

| Document | What's in it |
|---|---|
| [`docs/SCOPE.md`](docs/SCOPE.md) | What is built, what is designed but not built, what the platform makes impossible, and known gaps |
| [`docs/BUILD.md`](docs/BUILD.md) | Prerequisites, verifying the build, installing on a Fire TV, reading a run's log, remote bindings |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The seven modules, why the arrows point the way they do, and every API used |
| [`docs/DESIGN.md`](docs/DESIGN.md) | What is novel: the arbitration invariant, the adaptive duck, the in-house loudness meter, the speech chain |
| [`docs/NUMBERS.md`](docs/NUMBERS.md) | Every measurement, with its source |
| [`FRICTION_LOG.md`](FRICTION_LOG.md) | Twenty-one entries on where Fire OS, the SDKs and the docs got in the way, and the case for a platform API |
| [`PRD.md`](PRD.md) | The full product definition: fifty user stories, the priority model, the pipeline design |

### Reporting Bugs and Issues

Found a bug? [Open an issue](https://github.com/IsGaming7154/baton/issues) with your device model, Fire OS version, and the output of `adb logcat -s Baton:I` from the run. Every decision the director makes is logged under that one tag, so the log usually shows exactly what happened.

### License and Attribution

Baton's own code is licensed under the [MIT License](LICENSE). Third-party assets keep their own licences.

The bundled demo clip is an 88.6-second excerpt of **Sintel**, from 01:41.208 to 03:09.792, re-encoded to 854x356 and dubbed into German. Its audio is the Blender Foundation's Sintel music-and-effects stem with a German dub voiced by Amazon Polly (synthetic voices) mixed over it. The German dialogue text comes from the Blender Foundation's `sintel_de.srt`, adapted: lines 3, 4 and 11 are shortened, and "Torwaechter" is spelled "Torwächter". The English subtitles are the text of the Blender Foundation's `sintel_en.srt`, retimed to the German dialogue.

> Sintel — © copyright Blender Foundation | durian.blender.org — Creative Commons Attribution 3.0

Used here under [CC-BY 3.0](https://creativecommons.org/licenses/by/3.0/), which permits excerpting and derivative works with attribution; source links and excerpt details are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

The description cues in `app/src/main/assets/sample_clip.track.json` are original to this project. The `:pipeline` fixtures under `pipeline/src/main/resources/fixtures/` are invented dialogue and invented descriptions, written to exercise the builder. They contain no third-party content.
