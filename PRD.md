# Baton — PRD

**One voice at a time.**

Build, Ship, Shape: Amazon Developer Hackathon · Fire TV track
Target device: Fire TV Stick HD (Fire OS 7 / Android 9 / API 28, 1 GB RAM)
Stack: Kotlin · Jetpack Compose for TV · Media3/ExoPlayer · AWS (Transcribe, Bedrock Nova, Polly, S3)

---

## Problem Statement

A blind or low-vision person watching television is served by three separate accessibility services that do not know about each other.

Turn on audio description, turn on spoken subtitles, and leave the VoiceView screen reader running — the configuration a blind user of a foreign-language film actually needs — and Fire TV plays all three at once, on top of the film's own dialogue. The user hears the actors speaking a language they can't understand, a synthetic voice reading the translated subtitle, and VoiceView reading the same subtitle a second time, simultaneously. The result is not degraded; it is unusable.

Fire OS has no hierarchy of auditory focus. Nothing in the platform decides who speaks. Amazon's own documented workaround is to switch VoiceView off — which means the user surrenders the ability to operate the television in order to be able to hear it. A companion defect compounds this: audio description, once enabled, becomes stuck on across all content, and the support-recommended remedy is a factory reset.

Underneath the collision sits a supply problem. Roughly 7% of streaming content carries audio description at all. Netflix, under a 2016 legal settlement, reaches about 40%; YouTube is effectively zero. Human description costs $15–30 per finished minute and takes two to four weeks per hour of video, because scripting alone runs six to twelve times the runtime. Ninety-three percent of blind and low-vision viewers call audio description essential; forty-five percent report they cannot find described programming.

So the user faces two problems at once. Most of what they want to watch has no description. And on the minority that does, the accessibility features fight each other.

## Solution

Baton is a Fire TV video player that owns every voice in the room.

A single component — the audio director — arbitrates every spoken stream the user can hear: the film's own dialogue, AI-generated audio description, spoken subtitles, in-app menu narration, and answers to questions the user asks about what is on screen. It guarantees, as an invariant rather than as a best effort, that exactly one voice speaks at a time.

The guarantee rests on a two-class priority model. **Reactive voices** — menu narration and question answers — are things the user just asked for by pressing a button. They duck the content immediately and never wait. **Scheduled voices** — audio description and spoken subtitles — are things the app decided to say. They may only occupy silence in the content, they are measured before they are allowed to start, and they are dropped rather than permitted to run long into the next line of dialogue. Reactive always preempts scheduled. Within reactive, the most recent user intent wins. Within scheduled, a spoken subtitle outranks a description, because a subtitle is tied to dialogue the user would otherwise lose entirely.

Baton also generates the description it plays. An offline pipeline transcribes the audio, sends the whole video to Amazon Nova for understanding, drafts descriptions bounded by professional style rules, synthesizes them with Amazon Polly, measures the resulting audio, and shortens and re-synthesizes anything that will not fit the gap it was written for. The output is a timed description track that the director can schedule with confidence, because every cue arrives with a measured duration rather than an estimate.

Because DRM blanks captured frames from other applications, Baton must be the player rather than an overlay. This is a constraint that turns out to be an advantage: owning the decode path gives us the frames, the audio, and sample-accurate timing for free.

The pitch is not "an AI audio description app." It is the audio director Fire TV is missing — which also writes the description.

---

## User Stories

### Hearing one voice at a time

1. As a blind viewer, I want only one voice speaking at any moment, so that I can actually understand what I am hearing instead of parsing three overlapping streams.
2. As a blind viewer watching a foreign-language film, I want the spoken subtitle to be read to me in the pause after the actor finishes the line, so that I get the meaning without it colliding with the performance.
3. As a blind viewer, I want audio description to yield to a spoken subtitle when both want the same gap, so that I never lose dialogue in favour of scenery.
4. As a blind viewer, I want a description that will not fit before the next line of dialogue to be dropped or shortened rather than played over the dialogue, so that the film is never damaged by the accessibility feature.
5. As a blind viewer, I want the film's audio to duck rather than mute when another voice speaks, so that I keep the music and atmosphere and stay oriented in the scene.
6. As a blind viewer, I want ducking to fade in and out rather than step, so that the transitions do not sound like a fault.
7. As a blind viewer, I want a consistent overall loudness across dialogue, description, and menu speech, so that I am not repeatedly reaching for the volume control.
8. As a blind viewer, I want the app to tell me when it dropped a description because there was no room, so that I know silence was a decision and not a bug.

### Operating the television without seeing it

9. As a blind viewer, I want every function of the app reachable by remote without looking at the screen, so that I can use the television independently.
10. As a blind viewer, I want a screen curtain mode that renders the display black while audio continues, so that I am not broadcasting my viewing to a room I cannot see.
11. As a blind viewer, I want menu narration to duck the film immediately when I press a button, so that my input is acknowledged without delay.
12. As a blind viewer, I want a new button press to interrupt whatever the app was saying, so that navigating quickly does not queue up a backlog of stale speech.
13. As a blind viewer, I want the accessibility controls to sit at the same level as play and pause rather than buried in a settings tree, so that I can reach them the way EN 301 549 clause 7.3 requires.
14. As a blind viewer, I want the app to avoid the remote gestures VoiceView already claims, so that using Baton never accidentally toggles Review Mode, the text banner, or the magnifier.
15. As a blind viewer, I want the app to announce its current state when I return to it, so that I am never guessing whether something is playing.
16. As a low-vision viewer, I want high-contrast large-type surfaces, so that I can use the app with residual vision rather than audio alone.
17. As a blind viewer, I want focus to be announced with both the item and its role, so that I know whether I am on a button, a row, or a toggle.

### Asking about what is on screen

18. As a blind viewer, I want to press a button and be told what is on screen right now, so that I can resolve confusion the moment it happens instead of waiting for the next scheduled description.
19. As a blind viewer, I want that answer to be spoken immediately with the film ducked, so that the answer is useful while the moment is still on screen.
20. As a blind viewer, I want a short menu of question types — who is here, where are we, what changed, read the text on screen — so that I can ask something specific without typing.
21. As a blind viewer, I want to ask a follow-up question about the same moment, so that I can resolve a misunderstanding rather than accept the first answer.
22. As a blind viewer, I want asking a question to pause the film if I choose that setting, so that I do not miss what happens while I am listening to the answer.
23. As a blind viewer, I want the answer to be interruptible, so that I can cut it off once I have what I need.
24. As a blind viewer, I want to be told when the app is not confident about what it sees, so that I can decide whether to trust it.

### Tuning the description to the viewer

25. As a blind viewer, I want to choose how often descriptions occur — sparse, moderate, or frequent — so that the density matches the genre and my mood.
26. As a blind viewer, I want to choose how long each description is, so that a documentary and an action film do not get the same treatment.
27. As a blind viewer, I want to choose what descriptions emphasise — characters, environment, or a balance — so that I get the information I actually care about.
28. As a blind viewer, I want to change these settings mid-playback and hear the effect at the next cue, so that I can tune by ear rather than by guessing in a settings screen.
29. As a blind viewer, I want my settings remembered per title and globally, so that I do not reconfigure every time.
30. As a blind viewer, I want to adjust the description voice's speaking rate independently of VoiceView's rate, so that one service changing does not break the other.
31. As a blind viewer, I want to mute description for a single title without turning the feature off everywhere, so that I never fall into the stuck-on trap that requires a factory reset.

### Watching with other people

32. As a blind viewer watching with sighted family, I want to suppress description with one button, so that I am not imposing the accessibility track on the room.
33. As a blind viewer, I want to restore description just as quickly, so that suppressing it is a moment-to-moment choice rather than a commitment.
34. As a sighted co-viewer, I want the description voice to be clearly distinct from the film's voices, so that I am not confused about what is dialogue.

### Trusting what the app tells me

35. As a blind viewer, I want the app to state plainly that the descriptions are synthetic, so that I know what I am listening to, as the ACB guidelines require.
36. As a blind viewer, I want descriptions written in the present tense, third person, without editorial judgement, so that I am told what happens and left to draw my own conclusions.
37. As a blind viewer, I want characters not to be named before the film names them, so that description does not spoil the story.
38. As a blind viewer, I want descriptions not to restate what a sound already told me, so that my time is not wasted on redundancy.
39. As a blind viewer, I want all characters described consistently regardless of their appearance, so that description does not carry an unmarked default.
40. As a blind viewer, I want the app never to leak its own machinery into speech, so that I never hear a reference to a description number or a prompt instruction.

### Producing a described title

41. As a content operator, I want to point the pipeline at a video file and get back a timed description track, so that producing description is a single operation rather than a project.
42. As a content operator, I want the pipeline to derive an authoritative map of dialogue and silence from the transcript, so that scheduling is exact rather than inferred at runtime.
43. As a content operator, I want each cue emitted in several length variants, so that the runtime can pick one that fits the gap it actually has.
44. As a content operator, I want the pipeline to measure real synthesized audio rather than estimate from character count, so that the durations the director trusts are true.
45. As a content operator, I want cues that cannot be made to fit reported rather than silently discarded, so that I can review the gaps.
46. As a content operator, I want generated descriptions scored on clarity, accuracy, objectivity, and descriptiveness, so that I have evidence of quality rather than an assertion.
47. As a content operator, I want the whole pipeline runnable against recorded fixtures with no cloud calls, so that I can iterate without spending money.

### Demonstrating and judging

48. As a hackathon judge, I want to hear the same scene before and after, so that I can perceive the problem rather than take it on faith.
49. As a hackathon judge, I want the demo to work with no network, so that a connectivity failure does not obscure the work.
50. As a developer, I want the Fire TV accessibility patterns published as an Agent Skill, so that the next person building for this platform inherits what we learned.

---

## Implementation Decisions

### Module structure

**`director`** — The audio director. A pure Kotlin state machine with no Android dependencies. Exposes `advance(nowMs, inputs) → Directive` and owns the entire arbitration policy. This module is the product's correctness surface.

**`ports`** — Interface definitions only. `PlaybackClock`, `ContentGain`, `Speaker`, `ContentAudioProbe`, `VideoUnderstanding`, `SpeechSynth`, `Transcription`. Each has a real implementation and a fake.

**`track`** — The `DescriptionTrack` data model, its serialization, and its loader. Shared between the runtime and the pipeline; it is the contract between the two halves of the system.

**`pipeline`** — The offline track builder. Runs on the JVM, not the device. Pure Kotlin over the cloud ports.

**`player`** — Media3/ExoPlayer host. Implements `PlaybackClock`, `ContentGain` (via an `AudioProcessor` in the render chain), and frame extraction for visual question answering. Deliberately thin: it adapts ExoPlayer to the ports and contains no policy.

**`speech`** — `Speaker` implementations. Live: pre-rendered Polly audio played through `AudioTrack`. Mock: Android's built-in `TextToSpeech`. Both report completion and measured duration through the same interface.

**`vqa`** — On-demand visual question answering. Captures the current decoded frame, pairs it with a question, and returns an answer as a reactive utterance.

**`ui`** — Compose for TV surfaces. Eyes-free by construction: every surface exposes its own narration text, and no surface conveys information visually that it does not also convey in speech.

**`skill`** — The Agent Skill contributed upstream to `AmazonAppDev/devices-agent-skills`. Not shipped in the app.

### The priority model

Utterances fall into two classes, and the class determines behaviour toward the content audio.

**Reactive** — `UI_NARRATION`, `VQA_ANSWER`. Originated by a button press within the last interaction. Ducks the content immediately regardless of whether dialogue is playing. Preempts any scheduled utterance in progress.

**Scheduled** — `SPOKEN_SUBTITLE`, `AUDIO_DESCRIPTION`. Originated by the app. May only start inside a gap, must be measured to fit before starting, and is dropped if it cannot.

Resolution order:

1. Reactive preempts scheduled, always.
2. Among reactive, the most recent user intent wins — a new button press interrupts the previous answer.
3. Among scheduled, `SPOKEN_SUBTITLE` outranks `AUDIO_DESCRIPTION`.
4. Content dialogue is never spoken over by a scheduled utterance, under any setting.

A scheduled utterance is eligible only when `now >= cue.startMs` and `now + variant.measuredDurationMs + GUARD_MS <= nextDialogueOnsetMs`. `GUARD_MS` is 250. If no length variant satisfies the inequality, the cue is dropped and recorded as a drop with its reason.

### The gap map

Dialogue boundaries come from two sources, in priority order.

The **baked gap map** is authoritative. It is derived at pipeline time from Amazon Transcribe's word-level timings and shipped inside the `DescriptionTrack`. This costs nothing at runtime, which matters on a 1 GB device.

**Runtime voice activity detection** is the fallback, used for content with no track and as drift correction. It is energy-based — windowed RMS with hysteresis and a minimum-silence threshold — not a model. An ML VAD is not affordable on API 28 with this memory budget and is not needed when the transcript is available.

### Ducking

Content is attenuated by −12 dB rather than muted, with a 150 ms raised-cosine fade in each direction. Mixing targets follow EBU R128: −23 LUFS integrated, −1 dBTP maximum true peak. Attenuation is applied through a Media3 `AudioProcessor` in the render chain so that it is sample-accurate and survives seeks.

### The `DescriptionTrack` contract

A track carries a format version, a source identifier, a locale, an explicit `synthetic: true` marker, the baked gap map, and an ordered list of cues. Each cue carries a stable id, a start time, the kind of utterance it represents, and two or three length variants. Each variant carries its text, a reference to pre-rendered audio, and a measured duration in milliseconds — measured, never estimated.

The `synthetic` marker is load-bearing rather than decorative: the app reads it to decide whether to make the ACB transparency announcement.

### The pipeline

Transcribe produces word-level timings, which yield the gap map. Amazon Nova receives the whole video by S3 URI, with the transcript passed separately as text since the video channel does not carry audio. Nova drafts descriptions under a prompt bounded by professional style rules, targeted at the gaps the gap map identified. Polly synthesizes each draft with speech marks enabled, giving word and sentence timings alongside the audio.

The fit loop then runs: measure the synthesized audio, compare against the gap it was written for, and if it overruns, request a shorter variant and re-synthesize. This loop exists because `amazon:max-duration` works only on Standard voices and not on Neural or Long-Form, so duration cannot be constrained at synthesis time and must be converged on.

### Style rules encoded in the prompt

Active present tense. Third person omniscient. High-density verbs over verb-plus-qualifier. No editorializing — describe the behaviour, not the inferred emotion. Characters unnamed until the film names them, then introduced by insertion. All characters described with equal specificity. Person-first language for disability. Never over dialogue; song lyrics count as dialogue. No acoustic redundancy. No reference to the generation process, other cues, or prior descriptions.

### Remote control mapping

Available to third-party apps: D-pad, Select, Back, Menu, Play/Pause, Rewind, Fast-Forward. Not available: volume, power, and the app-shortcut buttons.

- **Select, long press** — ask about the current frame with the default question.
- **Menu, short press** — open the question-type ring.
- **Down from the transport bar** — the access services row, at play/pause prominence per clause 7.3.

Reserved and must not be bound, because VoiceView owns them: Menu long-press (Review Mode), Back+Menu (VoiceView toggle), Menu+Select (Exploration Mode), Rewind+Fast-Forward (Text Banner), Back+Fast-Forward (Screen Magnifier).

### No voice input

Fire TV does not expose the remote microphone to third-party applications. The microphone button routes directly to Alexa's cloud service; there is no Google Play Services on Fire OS so `SpeechRecognizer` is unavailable; and `AudioRecord` returns silent buffers. Every input is therefore a remote button. This is faster than voice for our purposes — no wake word, no recognition latency — and it works with the screen off.

### Cloud mode

A single `CloudMode` flag with values `MOCK` and `LIVE`, resolved at startup.

In `MOCK`, the description track is loaded from a bundled fixture, speech comes from Android's `TextToSpeech`, and visual question answering returns canned answers keyed by timestamp bucket. No AWS call is made and nothing bills.

`MOCK` is not only a cost control. It is the demo's safety net: the full experience runs with no network at all.

`LIVE` is not enabled until AWS promotional credits are in the account and explicitly authorised. When it is enabled, a CloudWatch billing alarm fires at $25 and the pipeline hard-stops at $50.

### Per-title state

Description enablement is stored per title, not globally, and the global default is off. This directly addresses the stuck-on defect for which Amazon's support guidance is a factory reset.

### Demo content

Sintel (Blender Foundation, CC-BY 3.0), which ships with subtitle tracks in forty languages. A non-English dub is generated with Polly — derivative works are permitted under the licence with attribution — so that the film's dialogue is unintelligible to an English-speaking judge while the subtitle track is authentic. This reproduces the three-voice collision exactly, with no licensing exposure and no network dependency.

---

## Testing Decisions

### What makes a good test here

A good test drives a seam and asserts observable behaviour. For the director that means: construct a timeline, advance the clock, and assert the sequence of directives that comes out. It does not mean reaching into the state machine to check which internal state it occupies. If a test would still pass after the arbitration policy was rewritten but its behaviour preserved, it is testing the right thing.

There is no prior art — this is greenfield — so these conventions are the prior art for everything that follows.

### Seam 1 — `AudioDirector.advance()`

Plain JVM unit tests. No Robolectric, no instrumentation, no device, no audio hardware. Inputs are constructed by hand or generated; the clock is injected.

The single highest-value test in the project is a property test over randomized timelines asserting the core invariant: **no two utterances are ever simultaneously active.** Randomize cue placement, dialogue onsets, durations, button presses, seeks, and pauses; assert the invariant holds across every generated schedule. If this property holds, the product's central claim is proven.

Example-based tests cover the policy edges:

- A reactive utterance preempts a scheduled one mid-speech.
- A second button press preempts the first answer.
- A spoken subtitle wins a gap contested with a description.
- A description that does not fit is dropped, and the drop is reported with a reason.
- A description whose long variant does not fit but whose short variant does starts with the short variant.
- Duck gain is non-zero only while a non-content voice is active, and returns to unity afterward.
- A seek clears the queue and leaves no utterance speaking.
- A pause stops all speech; a resume does not replay cues whose window has passed.
- Changing the frequency setting mid-playback takes effect at the next cue and not retroactively.

### Seam 2 — `DescriptionTrackBuilder`

Plain JVM unit tests over recorded fixtures. Canned Nova responses and canned Transcribe output go in; a deterministic `DescriptionTrack` comes out. `SpeechSynth` is faked by a duration oracle that maps text to a duration by a fixed rule, so the fit loop is exercised without synthesis.

- The gap map derived from word timings matches the expected gaps.
- The fit loop converges: a cue that overruns is shortened and re-measured until it fits or all variants are exhausted.
- A cue with no fitting variant is emitted as a reported drop rather than silently omitted.
- Every emitted variant carries a measured duration; none carries an estimate.
- Style-rule violations that are mechanically detectable — a cue overlapping a dialogue span, a cue referencing another cue by number — fail the build.

Golden-file comparison against a checked-in expected track, for a short fixture clip.

### Everything else

The ExoPlayer adapter gets thin instrumentation tests on device, asserting only that the ports are wired correctly — the clock advances, the gain is applied, a frame can be captured. No policy is tested there, because no policy lives there.

Compose surfaces get a narration-completeness test: every focusable node exposes non-empty narration text and a role. This is a lint-style assertion over the composition tree, not a screenshot test.

### Evaluation, distinct from testing

Description quality is measured, not asserted. Generated descriptions are scored on the four VideoA11y metrics — clarity, accuracy, objectivity, descriptiveness — by a judge model over a fixed sample. This runs manually, produces a number that can move between runs, and never gates the build. It belongs in the submission and the friction log, not in CI.

---

## Out of Scope

- **Extended audio description** (WCAG 1.2.7) — pausing the video to fit a dense description. Real value, but it changes the playback model and the runtime is short.
- **DAPT / TTML2 export.** The internal track format is sufficient; emitting the W3C interchange standard is a follow-on.
- **CTA-708 caption styling depth** — the eight font styles, sixty-four colour combinations, and edge effects. We render captions legibly; we do not implement the full customization matrix.
- **Phone as a private speaker.** Routing description to a companion device so it does not disturb the room. Strongly wanted by users; too much surface area for this timeline. The one-button suppress covers the same need less elegantly.
- **Vega OS.** Simulator-only for this device, and the Windows toolchain is unverified.
- **Describing other applications' video.** DRM and HDCP replace captured frames with black. Architecturally impossible, not merely deferred.
- **Live television and broadcast.**
- **Multiple user profiles.**
- **Localized application UI.** The app describes content in the content's language; the interface itself is English.
- **Accounts, authentication, and any server-side user state.**
- **On-device inference.** 1 GB of RAM on a quad-core 1.7 GHz part; the pre-pass belongs in the cloud.

---

## Further Notes

**Prizes.** The Fire TV track carries $25,000 and $15,000 in AWS credits for first place. One project may additionally win one mini-challenge. Two are in reach and both are already accounted for in the build: the Agent Skill contribution to `AmazonAppDev/devices-agent-skills` targets the Open Source challenge, and the multi-service pipeline — Transcribe, Bedrock, Polly, S3, orchestration — targets the AWS Builder challenge, whose stated bar for "creative" is more than a single Bedrock call.

**Friction log.** Worth up to 10% of the final score, and started from the first commit rather than reconstructed at the end. The headline entry is already known and is the project's own thesis: *Fire OS needs an acoustic priority API.* Baton is the reference implementation of the API that should exist, which makes the feature request evidence-backed rather than speculative.

**Positioning.** The American Council of the Blind's stated position is that human narration is the gold standard, and that defaulting blind audiences to synthetic voices signals they are a compliance obligation rather than an audience. Baton is coverage for the roughly 93% of content that has nothing at all, not a replacement for human describers, and it implements the ACB's TTS guidelines literally — including the transparency requirement that the audience be told the speech is synthetic.

**Regulatory context.** The European Accessibility Act has been enforceable since 28 June 2025 and covers consumer terminal equipment used to access audiovisual media services, which includes set-top boxes and streaming sticks. EN 301 549 clause 7 is the governing media-player specification, and clause 7.3 — access-service controls at the same prominence as primary playback controls — is the one most commonly failed and one Baton satisfies by construction.

**Prerequisites before `LIVE` is enabled.** AWS promotional credits claimed, and ADB debugging enabled on the device with its network address recorded.

**Publication.** No issue tracker is configured for this project and `gh` is not authenticated, so this PRD has not been published. Once a tracker exists it should be filed with the `ready-for-agent` triage label.
