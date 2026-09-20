package dev.baton.director

import kotlin.math.log10

/**
 * The numbers the arbitration policy is parameterised by.
 *
 * All have defaults that are the product's stated behaviour; they are settable
 * so that tests can show the policy is driven by the numbers rather than by
 * coincidence.
 */
data class DirectorConfig(
    /**
     * Silence that must remain between the end of a scheduled utterance and
     * whatever is entitled to the room after it. Absorbs the ducking fade and
     * clock jitter, and keeps a description from finishing on top of an actor
     * drawing breath.
     *
     * "Whatever is entitled to the room" and not "the next line of dialogue",
     * because a silence window can hold more than one description and the same
     * interval has to sit between two of them. The reason it does is the reason it
     * exists at all: the duck ramps over 150 ms and the clocks are not exact, so
     * the interval is a property of the fade and not of dialogue. Between two
     * sentences it also buys the thing a listener would miss without it -- two
     * descriptions butted together with no air between them stop sounding like two
     * observations and start sounding like one machine reading a list.
     */
    val guardMs: Long = DEFAULT_GUARD_MS,
    /**
     * Silence that must pass, on the content clock, between the floor being released
     * -- a speaker reporting, a pause, a withdrawal -- and the next scheduled
     * utterance starting. Reactive speech does not wait for it; rule 1 is immediate.
     *
     * Not the same interval as [guardMs], which is charged against the room *ahead*
     * of an utterance at admission and so only separates two voices when both were
     * written into one window. Two voices of ours can still meet end to start: a
     * subtitle that ends, and a description booked right behind it, in the same
     * synthetic voice. On the target hardware the log put that handoff at 47 ms,
     * and by ear it passed for one voice running over another. This is the air between
     * them, whatever window each was written for.
     *
     * 300 ms is chosen, not measured -- a calibration knob, to be tuned by ear on
     * the device. Zero reproduces the director as it was before it existed.
     */
    val interUtteranceGapMs: Long = DEFAULT_INTER_UTTERANCE_GAP_MS,
    /**
     * Attenuation applied to the content when there is no measurement to compute
     * one from -- before the loudness meter has filled its first window, or after
     * a seek has emptied it.
     *
     * Not the normal path. When programme loudness is known, [duckFor] derives
     * the dip from it; this is the guess for when it is not, and -12 dB is the
     * professional's default starting position for exactly that situation.
     */
    val duckDb: Double = DEFAULT_DUCK_DB,
    /**
     * The level every rendered utterance is normalised to, which is what makes
     * the dip computable at all: a loudness difference needs both halves, and
     * this is the half we control. Must match the target `SpeechClarity` renders
     * to, or every duck is wrong by the difference.
     */
    val narrationLkfs: Double = DEFAULT_NARRATION_LKFS,
    /**
     * How much louder the narration is as heard than as measured: the offset
     * between its mono measurement and its stereo reproduction.
     *
     * [narrationLkfs] is the level `SpeechClarity` renders and measures the
     * utterance at, as mono. But a mono `AudioTrack` mixed into a stereo output
     * on Android 9 is copied to both channels at full level, with no pan law:
     * `AudioMixer.cpp` lines 252-257 of AOSP `media/libaudioprocessing`
     * (LineageOS `android_frameworks_av`, lineage-16.0) applies unity gain per
     * channel on the mono-to-stereo copy, and `AudioResamplerFirProcess.h`
     * lines 231-242 and `AudioMixerOps.h` lines 294-299 do the same on their
     * paths. A BS.1770 meter sums over channels, so that dual-mono signal
     * measures `10*log10(2)` -- about 3.01 LU -- hotter than the mono
     * measurement. Without this offset every dip is about 3 dB too deep: the
     * voice lands roughly 15.5 LU above the bed instead of the configured
     * 12.5.
     *
     * Confirmed on the Fire TV Stick (AFTSSS, Fire OS 7.7.1.5) on 2026-09-20,
     * by playing the same pink noise twice -- once as a mono file, once already
     * copied into both channels -- and reading the output mix with
     * `OutputMeter`: the two differed by 0.18 dB, so this device duplicates at
     * full level and applies no pan law. Measured against the voice with a
     * stereo bed and this offset at zero, the voice sat 15.1 LU above the
     * ducked bed where 12.5 was asked for.
     *
     * It stays a knob rather than a literal because a device that does apply a
     * pan law would need it set to 0, and the rule in [duckFor] is then
     * untouched.
     */
    val narrationMonoToStereoOffsetDb: Double = DEFAULT_NARRATION_MONO_TO_STEREO_OFFSET_DB,
    /**
     * How far below the narration the content bed should sit while a description
     * is playing. Broadcast research puts the minimum at 10 LU for commentary
     * over music and 15 LU over broadband ambience; the default is the midpoint.
     *
     * Non-expert listeners in the same research preferred the bed about 4 LU
     * further down than professional mixers chose, so if this moves, it moves up.
     */
    val targetLoudnessDifferenceLu: Double = DEFAULT_TARGET_LOUDNESS_DIFFERENCE_LU,
    /** The deepest dip allowed, however loud the content gets. See [duckFor]. */
    val maxDuckDb: Double = DEFAULT_MAX_DUCK_DB,
    /**
     * How far a newly computed dip has to sit from the one already applied before
     * the applied one moves. Inside it the new number is discarded and the duck
     * holds where it is.
     *
     * A deadband and not a smoothing filter, because the input is not noisy in a
     * way an average would help with. [duckFor] is a subtraction, so it reproduces
     * every wobble of the short-term meter it subtracts, down to the eighth decimal
     * place -- and downstream a different number is a different fade. Without this,
     * the ramp restarts around thirteen times a second for the whole of every
     * utterance, and once the programme loudness genuinely moves, the bed audibly
     * chases the meter instead of settling under the narration.
     *
     * One decibel, for three reasons that agree. It is the classic just-noticeable
     * difference for a level change, so the error the deadband holds is never
     * larger than a listener could hear as a mistake. It is a fifth of the width of
     * the 10-15 LU band the intelligibility research calls acceptable, so a dip
     * held up to a decibel from ideal still lands the narration 11.5 to 13.5 LU
     * above the bed, comfortably inside that band either side of the 12.5 default.
     * And it is small against the 19 dB the rule can travel, so a scene change is
     * still followed in a dozen or so steps rather than in one lurch.
     *
     * It also bounds the update *rate* without needing a clock. A BS.1770
     * short-term meter integrates over three seconds, so it cannot slew faster than
     * the size of a level change divided by that window; demanding a whole decibel
     * of movement before acting caps the applied changes at a few per second on the
     * sharpest cut and at none at all on a steady scene. That is why there is no
     * separate rate limit as well: one would delay a genuine change without
     * removing anything this leaves behind.
     *
     * Zero is a legal setting and means "no deadband" -- the raw rule, tick by
     * tick, which is only useful to a test that wants to check the arithmetic
     * underneath.
     */
    val duckDeadbandDb: Double = DEFAULT_DUCK_DEADBAND_DB,
    /**
     * How far past its measured length an utterance may run before the director
     * stops waiting for the speaker, takes the floor back and silences it.
     *
     * This is a backstop, not a schedule. The floor is released by the speaker
     * reporting completion; this number only bounds how long a speaker that
     * never reports can wedge the machine. It must exceed [guardMs], because
     * anything inside the guard is ordinary jitter the guard already absorbs --
     * a timeout has to mean "something went wrong", or it means nothing.
     *
     * It is the *weaker* of the two deadlines a scheduled utterance is held to,
     * and on a tight gap it is not the one that fires: an utterance is also
     * stopped at the end of the silence window it was granted, whichever comes
     * first. A flat slack has no relationship to the room the utterance was
     * admitted into, so on its own it will happily let a description finish a
     * second deep into a line of dialogue and report nothing. See [AudioDirector].
     */
    val speechTimeoutSlackMs: Long = DEFAULT_SPEECH_TIMEOUT_SLACK_MS,
    /**
     * The wall-clock ceiling for speech that nothing ever measured -- live
     * text-to-speech, which has no length until the engine says it is done.
     *
     * Everything else is bounded by its own measured length plus
     * [speechTimeoutSlackMs]. This is the number for the case where there is no
     * length to add the slack to, and without it such an utterance has no
     * backstop at all: a speaker that returns success and emits nothing holds the
     * floor for the rest of the film, with the content sitting a dozen decibels
     * down underneath a voice that never arrives, and no recovery short of
     * another button press or a seek. That engine is not hypothetical -- it is
     * FRICTION_LOG.md entry 9, on the target hardware.
     *
     * Fifteen seconds, from both ends. It has to clear the longest answer this
     * product would ever produce, or the ceiling truncates working speech: at the
     * 165 wpm the synthesizer reads at -- the middle of the range professional
     * describers use, and the same figure the pipeline sizes its gaps against --
     * fifteen seconds is about forty words, which is three or four full sentences
     * of answer to "what is on screen". And it has to be short enough that a
     * wedged floor clears itself while the viewer is still in the same scene,
     * rather than leaving them to work out that the film has gone quiet because
     * something broke.
     *
     * It must exceed [speechTimeoutSlackMs]: unmeasured speech is speech we know
     * *less* about, and cutting it off sooner than we would cut off an utterance
     * we had measured would be treating the absence of a number as evidence
     * against the speaker.
     */
    val unmeasuredSpeechCeilingMs: Long = DEFAULT_UNMEASURED_SPEECH_CEILING_MS,
    /**
     * How long the content may be stalled before the stall is worth reporting.
     *
     * Not a deadline and not a policy switch: crossing it changes nothing the
     * viewer can hear. The director holds the floor and the duck for as long as
     * the stall lasts, however long that is -- see [AudioDirector]. This number
     * only decides when the stall stops being ordinary and starts being evidence.
     *
     * One second, which is four times [guardMs]. The guard is the slop the whole
     * scheduler is built to absorb -- fade time and clock jitter -- so anything
     * inside it is by construction not an anomaly; a stall has to be several times
     * that before it can be distinguished from the timing noise the system already
     * tolerates. Four is the smallest multiple that clears it convincingly, and it
     * lands on a second, which is also the classic boundary at which a delay stops
     * reading as instantaneous and starts reading as an interruption.
     *
     * It sits below [speechTimeoutSlackMs] on purpose. Two seconds is where this
     * system says a *speech* fault has occurred; a stall is an interruption of the
     * picture the whole product exists to describe, and it should become legible in
     * the log sooner than a misbehaving synthesiser does, not later.
     *
     * Unlike the backstop it has no upper constraint against the slack, because
     * nothing goes wrong if it has one: a stall cannot itself cause a withdrawal.
     * The deadline drawn on the content clock freezes for exactly as long as the
     * stall does, and the one drawn on the wall clock bounds audio that plays
     * from a local file which never stalled with the video -- so a speaker that
     * is behaving reaches neither. See [AudioDirector].
     */
    val stallAnomalyMs: Long = DEFAULT_STALL_ANOMALY_MS,
) {
    init {
        require(guardMs >= 0) { "guardMs must be >= 0, was $guardMs" }
        require(interUtteranceGapMs >= 0) { "interUtteranceGapMs must be >= 0, was $interUtteranceGapMs" }
        require(duckDb < 0.0) { "duckDb must attenuate; $duckDb dB does not" }
        require(maxDuckDb < 0.0) { "maxDuckDb must attenuate; $maxDuckDb dB does not" }
        require(duckDb >= maxDuckDb) {
            "duckDb ($duckDb) is deeper than maxDuckDb ($maxDuckDb); the fallback would " +
                "duck harder than the measured path is ever allowed to"
        }
        require(duckDeadbandDb >= 0.0) {
            "duckDeadbandDb is a distance and cannot be negative, was $duckDeadbandDb"
        }
        require(targetLoudnessDifferenceLu > 0.0) {
            "the narration has to end up louder than the bed; " +
                "targetLoudnessDifferenceLu was $targetLoudnessDifferenceLu"
        }
        require(speechTimeoutSlackMs > guardMs) {
            "speechTimeoutSlackMs ($speechTimeoutSlackMs) must exceed guardMs ($guardMs); " +
                "a timeout inside the guard would fire on ordinary jitter"
        }
        require(unmeasuredSpeechCeilingMs > speechTimeoutSlackMs) {
            "unmeasuredSpeechCeilingMs ($unmeasuredSpeechCeilingMs) must exceed " +
                "speechTimeoutSlackMs ($speechTimeoutSlackMs); speech nobody measured would " +
                "otherwise be cut off sooner than speech we did measure"
        }
        require(stallAnomalyMs > guardMs) {
            "stallAnomalyMs ($stallAnomalyMs) must exceed guardMs ($guardMs); " +
                "a stall inside the guard is the jitter the guard already absorbs, not an anomaly"
        }
    }

    companion object {
        const val DEFAULT_GUARD_MS: Long = 250L

        /** Chosen, not measured; a calibration knob. See [interUtteranceGapMs]. */
        const val DEFAULT_INTER_UTTERANCE_GAP_MS: Long = 300L

        const val DEFAULT_DUCK_DB: Double = -12.0

        /** Amazon Prime Video and Netflix both specify -27 LKFS, +-2 LU, for description narration. */
        const val DEFAULT_NARRATION_LKFS: Double = -27.0

        /**
         * The loudness gain of a mono signal duplicated to both channels of a
         * stereo output, as BS.1770 reads it. See [narrationMonoToStereoOffsetDb].
         */
        val DEFAULT_NARRATION_MONO_TO_STEREO_OFFSET_DB: Double = 10.0 * log10(2.0)

        /** Midpoint of the 10-15 LU the intelligibility research recommends. */
        const val DEFAULT_TARGET_LOUDNESS_DIFFERENCE_LU: Double = 12.5

        /**
         * The deepest dip the published listener testing covered. Past it the
         * film is not quiet, it is gone -- and a viewer who cannot see the screen
         * is getting most of the scene from that soundtrack.
         */
        const val DEFAULT_MAX_DUCK_DB: Double = -19.0

        /**
         * The just-noticeable difference for a level change, and a fifth of the
         * band the intelligibility research is happy with. See [duckDeadbandDb].
         */
        const val DEFAULT_DUCK_DEADBAND_DB: Double = 1.0

        /**
         * Eight times the guard. Sized above the largest cross-engine timing
         * divergence measured on the target hardware -- 918 ms between the two
         * installed text-to-speech engines, FRICTION_LOG.md entry 9 -- so that a
         * timeout is evidence of a stall rather than of a slow synthesiser.
         */
        const val DEFAULT_SPEECH_TIMEOUT_SLACK_MS: Long = 2_000L

        /**
         * About forty words at the 165 wpm the synthesizer reads at -- longer
         * than any answer this product produces, short enough that a wedged floor
         * clears inside a scene. See [unmeasuredSpeechCeilingMs].
         */
        const val DEFAULT_UNMEASURED_SPEECH_CEILING_MS: Long = 15_000L

        /**
         * Four times the guard, and half the backstop: past the jitter the
         * scheduler absorbs, and inside the point at which a speech fault would
         * be declared. See [stallAnomalyMs].
         */
        const val DEFAULT_STALL_ANOMALY_MS: Long = 1_000L
    }
}
