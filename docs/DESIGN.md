# What is novel here

## The arbitration state machine, and its invariant

"Exactly one voice at a time" has two halves. The easy half — never two of *our* voices — is settled by the type: a `Directive` has room for one utterance and no representation of two. The half that has to be proven is that a scheduled voice never lands on top of the voice already in the film.

A cue may start only when

```
now >= cue.startMs  &&  now + variant.measuredDurationMs + guardMs <= nextBlockingEventMs

nextBlockingEventMs = min(endOfItsSilenceWindow, nextBookedSubtitleStartMs)
```

Spoken subtitles are known in advance, so they are booked against the timeline before descriptions are placed: a description, or the synthetic-speech disclosure, that would still be speaking when a subtitle is due is not started, even where the film is silent, and a description dropped for that is reported as `RunsIntoBookedSubtitle`, naming the subtitle.

The longest variant that satisfies it wins; if none does, the cue is **dropped and the drop is reported with a reason** rather than played late or played over. `guardMs` is 250.

No scheduled voice starts until `interUtteranceGapMs` (300 ms, chosen rather than measured) of content has passed since the floor was last released, so two of our voices never run end to start. A cue held back by it is judged by the rule above at the moment it actually starts. Button presses don't wait for it, and a seek clears it.

`AudioDirectorInvariantTest` drives 1,000 randomised timelines through this — randomised cue placement, dialogue onsets, durations, button presses, seeks, pauses and stalls — asserting at every tick that no two voices are ever active, then repeats it 300 times under a deliberately hostile configuration. It also asserts that the generators keep *reaching* the hard states, so the suite cannot quietly go vacuous.

Three readings of the policy are worth pulling out because they were decisions, not omissions:

- **The floor is released by observation, not by prediction.** The measured duration says whether a cue may *start*. It does not decide when it has finished — the speaker reporting does. Answering the second question with arithmetic is how two voices end up in the room on a memory-constrained device, and it only ever reproduces on real hardware. The duration is demoted to a backstop, and a backstop that fires is reported as an anomaly.
- **A stall is not a pause.** Both stop the picture. A pause is a decision and is obeyed; a stall is an interruption and is waited out, with the floor and the duck held. Collapsing them cost a cut-in-half sentence every time a stream hiccupped — and reported nothing, because from the director's seat somebody had simply paused.
- **Scheduled does not preempt scheduled.** A truncated sentence is a worse artefact than a reported drop.

## An adaptive duck derived from measured loudness

The standard answer is −12 dB. The research is blunt about what that number is: a starting position a professional then rides the fader away from, cue by cue. Left static it is wrong in both directions at once — under an explosion 12 dB does not uncover the narrator, and under quiet room tone it tears an audible hole in the soundscape.

What has to be held constant is not the dip but the **loudness difference** between narration and bed. Since `SpeechClarity` renders every utterance to a known −27 LKFS, the dip is arithmetic:

```
dip = narrationLkfs − targetLoudnessDifferenceLu − programmeLufs
```

clamped so it never boosts and never digs past −19 dB, and held through a 1 dB deadband so a wobbling meter does not restart the fade thirteen times a second.

This is only possible because the app measures the programme itself. On the bundled demo clip, `ffmpeg`'s `ebur128` reports −30.3 LUFS integrated with a loudness range of 20.0 LU — LRA low −47.9, high −27.9. Put those two ends through the rule and it asks for no dip at all at the quiet end — −47.9 LUFS is far below the −36.49 LUFS where the rule has already reached zero — and about −8.6 dB at the loud end. That is arithmetic from a measured programme loudness, not a hardware observation; what it establishes is that on this material the duck has something to adapt to.

The same body of research publishes a three-band table — −19 / −9 / −3 dB, and no ducking below −48 LUFS. At the default 12.5 LU the rule lands on those three values at about −17.49, −27.49 and −33.49 LUFS, and reaches zero dip on its own at about −36.49 LUFS without needing the near-silent threshold as a separate constant. The source comment is careful to call that a **range check and not a reproduction**, because the study does not publish where its band boundaries fall.

## An in-house BS.1770 meter, because there is no other way to get the number

Documented as friction-log entry 10. Every platform route to programme loudness on API 28 fails for a different reason, and on Fire TV two of them are structurally blind: under Widevine L1 the decrypted PCM lives in a TEE the application cannot address, and under HDMI passthrough what travels the pipeline is an IEC61937 payload of encoded packets rather than decoded samples.

So `:loudness` measures the samples the application can still see, in its own render chain. Its tests pin the EBU Tech 3341 compliance signals to their published values — a 1 kHz stereo sine at −23 dBFS reads −23.0 LUFS and at −33 dBFS reads −33.0 — which only comes out right if the K-weighting shelf, the RLB high-pass, the channel summation and the calibration offset are *all* correct at once. Friction-log entry 10 additionally records a cross-check against `ffmpeg`'s `ebur128` on decoded content: −21.751 LUFS against ffmpeg's −21.8, and −11.631 dBTP against −11.6.

Two things the tests pin because they are silent failures otherwise: the same signal must measure the same at 16, 24 and 48 kHz, and a mono stream must be measured as mono. Loudness sums over channels, so **the same sine in one channel reads 3.01 LU below the same sine in two** — measure a mono utterance as stereo and it reads 3 dB hot, which would have put every rendered utterance at the wrong end of the ±2 LU delivery window while every stereo test still passed.

## A speech chain tuned against measured intelligibility, not by ear

The only voice this hardware will provision is IVONA `kajal`, whose usable energy stops around 8 kHz in a 24 kHz container. The chain that fixes it is: high-pass at 110 Hz → −4 dB bell at 420 Hz → 3:1 compression with an 8 ms attack → **+6.5 dB presence at 1600 Hz, Q 0.4** → a small harmonic exciter at 6% mix → normalise to **−27 LKFS with a −2 dBTP ceiling**, which is the delivery specification Amazon Prime Video and Netflix both enforce for description narration.

The presence lift is at 1.6 kHz and the exciter is nearly off, and both look wrong to anyone who has reached for a presence control. They come from ANSI S3.5-1997's Band Importance Functions, which measure the share of intelligibility each band carries: it peaks at **1600 Hz (10.2%)**, is **6.7% at 4 kHz**, and **0.84% at 8 kHz**. The band an engineer reaches for to make a voice sound clear is not the band that carries whether the words are understood.

`SpeechClarity`'s own source comment records the wrong turn that led there: an earlier version put +6 dB at 3.8 kHz with a much larger exciter and won a listening comparison — auditioned dry, in silence, a condition this voice never occurs in. Scored with ESTOI against the earlier Tears of Steel demo bed at the signal-to-noise ratios the product actually sees, that old tuning does beat doing nothing at fixed SNR — +0.054 to +0.067 ESTOI over unprocessed at +6/0/−6 dB — but under the director's own duck, where the bed is already pulled down to protect the voice, its edge collapses to +0.005 and is positive in only 23 of 36 utterance/cue pairs: not a result. The shipped chain holds up better ducked — +0.069 to +0.082 over unprocessed at fixed SNR, +0.015 and 27 of 36 pairs once ducked — and moving the lift alone from 3.8 kHz to 1600 Hz, everything else unchanged, is worth a further +0.006 to +0.008 at fixed SNR and +0.005 under the duck. Speech that will always be heard against something else has to be tuned against something else.

One cross-module hazard worth naming, since nothing else would catch it: `SpeechClarity.Settings.targetLkfs` and `DirectorConfig.DEFAULT_NARRATION_LKFS` are the same number in two modules, and if they drift every duck in the product is wrong by the difference while nothing fails. A test pins them together.

## Netflix's ducking mandate, and the platform's answer to it

Netflix's Audio Description Style Guide mandates a ducking attack of **no less than 2 ms and no more than 15 ms**. Baton asks for 10 ms on the attack and 150 ms on the release, split by direction of travel — a duck that deepens mid-utterance attacks, one that lightens releases — with the release sized to fit inside the director's own 250 ms guard.

The mandate cannot actually be met on this hardware, and friction-log entry 12 works out why from the AOSP source and then measures it: `AudioTrack.setVolume` never reaches the mixer when it is called, AudioFlinger reads the value once per mix buffer and ramps across the whole of it, and `dumpsys media.audio_flinger` on the Fire TV Stick reports mix periods of 16, 20 and 32 ms. The shortest available is 16 ms against a ceiling of 15, and no amount of application-side work closes that gap. `OutputStageContentGain` still commands the 10 ms attack; what actually lands is the mixer's own ramp, 16 ms at best on this device.

That gap between commanded and delivered attack turns out not to cost anything downstream. Measured on the device rather than modelled: the duck is fully applied ~24 ms after the director's decision, and speech starts ~75 ms after that — comfortably ahead of the narrator's first word, with no pre-roll and nothing reserved for it in the admission rule.

The feature request writes itself, because Amazon has already built it. `@amazon-devices/keplerscript-audio-lib`, the audio library for Vega OS, exposes `duckVolumeAsync(mode, value, rampDurationMs)` alongside a `StreamDuckingPolicy` that distinguishes system ducking from `EXPLICIT` application-owned ducking. That is precisely the surface Baton needs and precisely the surface Fire OS 7 withholds — Baton arrived at the same three primitives independently and had to build them by hand. The ask is that they reach API 28's installed base, which will outlive the migration by years.

## Synthetic voices are disclosed

The description cues in `app/src/main/assets/sample_clip.track.json` are original to this project. They are machine-spoken, and the track marks itself `"synthetic": true` — the field is part of the track format, set by the pipeline and read back by the loader, because the American Council of the Blind's guidance on synthetic description is that the audience should be told. The director acts on that field: a synthetic track owes one spoken disclosure per playback session, rendered and measured on the device's engine like every cue, and admitted into the first silence long enough to hold it (see `AudioDirector`). The sentence itself is this project's wording; the guidance is cited for the requirement, not for the words. The same sentence is shown on screen for the first ten seconds of playback, with the track's optional `syntheticDub` line beside it, which for this clip says the German dub's voices are synthetic (Amazon Polly); both lines are hidden from VoiceView, because the director already speaks the disclosure (see `SyntheticVoiceNotice`).
