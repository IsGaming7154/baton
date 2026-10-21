# Build and run

## Prerequisites

- **JDK 17 or newer.** Verified here on Adoptium JDK 21.0.11. If `java -version` on your `PATH` reports anything older, set `JAVA_HOME` explicitly — the Android Gradle Plugin's failure message does not point at a `PATH`/`JAVA_HOME` mismatch.
- **The Android SDK.** Command-line tools are enough; Android Studio is not required. Either set `ANDROID_HOME` to the SDK root, or create a `local.properties` at the repository root containing `sdk.dir=/path/to/Android/Sdk`. (`local.properties` is git-ignored, so a fresh clone will not have one.)
- **`adb`**, from the SDK's `platform-tools`, if you want to put it on a device. It is not on `PATH` by default on any platform.

Gradle 8.7 comes from the wrapper. AGP 8.6.1, Kotlin 1.9.24 and every dependency are pinned in the build files.

## Verify it

```sh
./gradlew test :speech:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest assembleDebug
```

This is the command that is known to work. Run here with `--rerun-tasks`, so nothing was taken from the cache, it ends in `BUILD SUCCESSFUL`. Counting the JUnit XML it leaves behind under each module's `build/test-results`:

**459 test executions, 0 failures, 0 errors, 0 skipped — and 0 Kotlin compiler warnings.**

That is 372 distinct tests, by classname and name; the Android modules run their suites for both the debug and release variants (`ShellNarrationTest`, `SyntheticVoiceNoticeTest` and `AccessServicesRowTest` run on debug only), which accounts for 86 of the other 87 executions — the last is `DescriptionTrackBuilderTest`'s two independent tests that happen to share the display name "golden file". The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, 32,194,832 bytes after `./gradlew clean` — the last few bytes move between builds because the zip carries its own timestamps, and an incremental build lands around 400 KB larger.

Nothing here needs a device, a network, an emulator, or an AWS account. `:director`, `:loudness` and `:pipeline` are pure-JVM modules and their tests are plain JUnit.

`./gradlew build` — the whole thing, lint included — also passes: 0 lint errors, 16 lint warnings, none of them new. This was verified the way a reviewer meets it, by cloning this repository into an empty directory and running both commands with nothing set but `ANDROID_HOME` and `JAVA_HOME`: 3 minutes 4 seconds to `BUILD SUCCESSFUL` on a machine whose Gradle cache was already warm, 405 executions, and a debug APK the same size as the one above. Two lint false positives are suppressed at the narrowest scope and each carries a comment saying why: `RestrictedApi` on `MainActivity.dispatchKeyEvent`, because `androidx.core.app.ComponentActivity` carries a class-level `@RestrictTo(LIBRARY_GROUP_PREFIX)` and so every override of a framework method declared there is flagged, and `RememberReturnType` in `AccessServicesRowTest`, which lint reads as returning `Unit` when it returns a `MutableState`.

## `:player:lintDebug` and Media3's `@UnstableApi` surface

Much of what this project needs from Media3 — `DefaultAudioSink.Builder`, `AudioProcessor`/`BaseAudioProcessor`, `DefaultRenderersFactory.buildAudioSink`, and `PlayerView`'s control-visibility setters — is annotated `@UnstableApi`, and Android lint treats an unannotated use of it as an error rather than a warning: unannotated, `:player:lintDebug` reports 37 `UnsafeOptInUsageError` errors, every one of them the same rule. The three declarations that actually reach those APIs — the class `BatonPlayer`, the class `DuckingAudioProcessor`, and the function `BatonPlayerSurface` — now each carry `@OptIn(UnstableApi::class)` at that scope, which is the acknowledgment Media3 asks for rather than a suppression of the rule. `:player:lintDebug` passes clean.

## Install it on a Fire TV

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

## One thing that will make it silent

Baton needs a text-to-speech engine that actually has a voice. On a Fire TV Stick where VoiceView has never been enabled, the platform default engine reports `SUCCESS` from both `onInit` and `synthesizeToFile` and then produces no audio at all, failing asynchronously through a deprecated callback with no error code. Baton detects this — it selects an engine by voice count rather than by init status, and falls back to SVOX Pico — but if no engine on the device reports a usable voice, it withholds the cues rather than schedule audio that does not exist, and says so in the log:

```
ERROR no text-to-speech engine reported a usable voice; withholding 6 cues
rather than scheduling audio that does not exist
```

Enabling VoiceView once, anywhere, permanently provisions the better voice. The whole investigation is friction-log entry 9.

## Reading a run

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
