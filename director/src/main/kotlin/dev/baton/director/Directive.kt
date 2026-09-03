package dev.baton.director

/**
 * Who should be speaking right now.
 *
 * This is where "exactly one voice at a time" stops being a policy and becomes a
 * fact about the type: a [Directive] names at most one utterance because
 * [Speaking] has room for at most one. There is no representation of two.
 *
 * The directive is level-triggered, not a command stream: it says what should be
 * true now, and the caller diffs it against what it is currently doing. An
 * utterance instance is identified by [Utterance.id] together with
 * [Utterance.startedAtMs], so a cue that legitimately plays twice -- after the
 * viewer seeks back over it -- is distinguishable from one that never stopped.
 */
sealed interface Speaking {

    /** Nothing but the content itself. */
    data object Silent : Speaking

    sealed interface Utterance : Speaking {
        val id: UtteranceId
        val kind: UtteranceKind
        val text: String

        /** Pre-rendered audio to play, when there is any; otherwise synthesize [text]. */
        val audioRef: String?

        /** The wall clock reading passed to `advance` when this utterance began. */
        val startedAtMs: Long
    }

    /**
     * A cue from the [DescriptionTrack] occupying a gap. Anchored to the content
     * timeline, because its whole justification is a fact about the content: it
     * fits between here and the next line of dialogue.
     */
    data class ScheduledUtterance(
        override val id: UtteranceId,
        override val kind: UtteranceKind.Scheduled,
        override val text: String,
        override val audioRef: String?,
        override val startedAtMs: Long,
        val cueId: CueId,
        val variantIndex: Int,
        val duration: MeasuredDurationMs,
        /** Content position at which this utterance began. */
        val startedAtPositionMs: Long,
    ) : Utterance {
        /** Content position at which it ends. Guaranteed to fall inside the same silence window. */
        val endsAtPositionMs: Long get() = startedAtPositionMs + duration.millis
    }

    /**
     * An answer to a button press. Anchored to the wall clock, because the viewer
     * is waiting in real time and the content may well be paused.
     */
    data class ReactiveUtterance(
        override val id: UtteranceId,
        override val kind: UtteranceKind.Reactive,
        override val text: String,
        override val audioRef: String?,
        override val startedAtMs: Long,
        val length: SpeechLength,
    ) : Utterance {
        /** Wall clock reading at which it ends by itself, or null if only the speaker knows. */
        val endsAtMs: Long?
            get() = when (val l = length) {
                is SpeechLength.Measured -> startedAtMs + l.duration.millis
                SpeechLength.UntilSpeakerReports -> null
            }
    }
}

/**
 * How far the content audio should be attenuated.
 *
 * The director decides the number. It does not implement the fade -- the shape of
 * the 150 ms raised-cosine ramp belongs to the Media3 `AudioProcessor` on the
 * Android side, which is the only place that can apply it sample-accurately.
 */
sealed interface ContentDuck {
    val db: Double

    /** Content at its own level. */
    data object Unity : ContentDuck {
        override val db: Double = 0.0
    }

    /** Content pulled down so a non-content voice can be heard over it. */
    data class Ducked(override val db: Double) : ContentDuck {
        init {
            require(db < 0.0) { "a duck must attenuate; $db dB does not" }
        }
    }
}

/** Why a cue was not spoken. Reported so that silence is legible as a decision. */
sealed interface DropReason {

    /**
     * No length variant could finish, plus the guard interval, before the next
     * line of dialogue. [floorHeldBy] records whether another voice was using the
     * gap at the moment we gave up -- the difference between "the gap was too
     * short" and "the gap was taken".
     */
    data class NoVariantFits(
        val shortestVariantMs: Long,
        val availableMs: Long,
        val guardMs: Long,
        val floorHeldBy: UtteranceKind?,
    ) : DropReason

    /**
     * The cue's start time is not inside any silence window. The pipeline wrote it
     * over dialogue; the runtime will not play it there under any setting.
     */
    data object NoSilenceAtCueStart : DropReason

    /** The cue arrived with no length variants at all, so there was nothing to say. */
    data object NoVariants : DropReason
}

/** A cue the director decided not to speak, and why. */
data class DroppedCue(
    val cueId: CueId,
    val kind: UtteranceKind.Scheduled,
    val cueStartMs: Long,
    /** Content position at which the cue was abandoned. */
    val atPositionMs: Long,
    val reason: DropReason,
)

/**
 * The director's entire output for one tick.
 *
 * [drops] are the cues abandoned *on this tick* -- an edge, not a running total.
 * The caller accumulates them; a later slice surfaces them to the viewer, so that
 * a missing description reads as a decision rather than a fault.
 */
data class Directive(
    val speaking: Speaking,
    val contentDuck: ContentDuck,
    val drops: List<DroppedCue> = emptyList(),
)
