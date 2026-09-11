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
 *
 * There are three states and not two, for the same reason [Playback] has three.
 * "Nobody should be speaking" and "nobody should be speaking, and *that* one has
 * to be cut off to make it true" are different instructions, and a caller that
 * had to infer the second from the first would be inferring it from the absence
 * of a name -- which is also what a clean ending looks like. See [Withdrawn].
 */
sealed interface Speaking {

    /** Nothing but the content itself. */
    data object Silent : Speaking

    /**
     * Nobody should be speaking, and one utterance has to be actively silenced
     * for that to become true.
     *
     * The floor was taken back from a speaker that had not finished with it, so
     * unlike [Silent] this is not a description of a room that has already gone
     * quiet -- it is the instruction that makes it quiet. The caller stops the
     * named utterance.
     *
     * **This case is why the director can say "stop".** Withdrawal used to be
     * inexpressible: un-naming an utterance is how a clean ending is reported,
     * so an overrunning speaker had to go on being named or the next voice would
     * start on top of it rather than in place of it. Because the withdrawal
     * occupies the same single slot an utterance would, "cut that off *and*
     * start this" cannot be written down. The tick that withdraws the floor
     * starts nothing, exactly as the tick that carries a seek does, and the type
     * is what enforces it rather than a rule somebody has to remember.
     */
    data class Withdrawn(val withdrawal: Withdrawal) : Speaking

    sealed interface Utterance : Speaking {
        val id: UtteranceId
        val kind: UtteranceKind
        val text: String

        /** Pre-rendered audio to play, when there is any; otherwise synthesize [text]. */
        val audioRef: String?

        /** The wall clock reading passed to `advance` when this utterance began. */
        val startedAtMs: Long

        /**
         * How long the audio was measured to be, or null when nothing measured it.
         *
         * Null is a real answer and not a missing one: live text-to-speech has no
         * length until the engine says it is done. The distinction decides which
         * ceiling bounds the utterance -- see [AudioDirector].
         */
        val measuredDurationMs: Long?
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
        /**
         * The cue being spoken, or null for the synthetic-speech disclosure, which
         * belongs to the playback session rather than to any moment in the track.
         *
         * Null and not a reserved id, because any id reserved here is one a track
         * is free to have authored for a cue of its own. The kind says the same
         * thing, and the two are made to agree below rather than trusted to.
         */
        val cueId: CueId?,
        /** Which of the cue's variants was chosen. Always 0 for the disclosure, which has one. */
        val variantIndex: Int,
        val duration: MeasuredDurationMs,
        /** Content position at which this utterance began. */
        val startedAtPositionMs: Long,
        /**
         * End of the silence window this utterance was admitted into -- the
         * content position at which the next line of dialogue begins.
         *
         * Carried rather than looked up again later, because it is the room the
         * utterance was *granted*, and the deadline that stops it is derived from
         * that grant. Re-deriving it from the track each tick would make the
         * deadline depend on a lookup that could, in principle, answer differently
         * than it did at admission.
         */
        val windowEndMs: Long,
        /**
         * End of the room this utterance was actually granted: the start of the
         * next cue written into the same silence window, or [windowEndMs] when it
         * is the last one there.
         *
         * A second boundary and not a replacement for the first, because the two
         * are owed to different parties and only one of them is a deadline. The
         * window end is the film's: past it an utterance is audible over an
         * actor's line, and that is worth cutting a sentence in half to prevent.
         * The room end is the *next description's*, and running past it costs a
         * description that has not started yet -- which by the policy above is a
         * reported drop rather than a truncation, since scheduled speech does not
         * preempt scheduled speech. So this bounds admission and nothing else:
         * nothing is ever withdrawn for reaching it. See [AudioDirector].
         *
         * Defaults to [windowEndMs], which is the honest reading of a cue with
         * nothing behind it in its window rather than a convenience.
         */
        val roomEndMs: Long = windowEndMs,
    ) : Utterance {
        init {
            // Not merely checked: this is the shape of the promise. An utterance
            // whose own arithmetic already runs past the dialogue it was placed in
            // front of, or past the description queued behind it, cannot be
            // brought into existence -- so no deadline downstream has to cope with
            // one, and no cue can be starved by a neighbour that took the whole
            // window because nobody was counting.
            require((cueId == null) == (kind == UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE)) {
                "utterance $id: only the disclosure speaks without a cue, and the disclosure " +
                    "never speaks one (kind $kind, cue $cueId)"
            }
            require(roomEndMs <= windowEndMs) {
                "utterance $id was granted room to $roomEndMs, past the silence " +
                    "window it sits in (ends $windowEndMs)"
            }
            require(startedAtPositionMs + duration.millis <= roomEndMs) {
                "utterance $id would end at ${startedAtPositionMs + duration.millis}, " +
                    "past the room it was granted (ends $roomEndMs)"
            }
        }

        override val measuredDurationMs: Long get() = duration.millis

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
        override val measuredDurationMs: Long?
            get() = when (val l = length) {
                is SpeechLength.Measured -> l.duration.millis
                SpeechLength.UntilSpeakerReports -> null
            }

        /** Wall clock reading at which it ends by itself, or null if only the speaker knows. */
        val endsAtMs: Long? get() = measuredDurationMs?.let { startedAtMs + it }
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
     * No length variant could finish, plus the guard interval, before the room ran
     * out. [floorHeldBy] records whether another voice was using the gap at the
     * moment we gave up -- the difference between "the gap was too short" and "the
     * gap was taken".
     *
     * [nextCueStartMs] draws the same distinction for the other way a gap gets
     * taken. Null means the room ran all the way to the dialogue that closes the
     * window and there simply was not enough silence. Set means the window had
     * more silence in it, and it is spoken for: the next description in the same
     * window starts there. Those read identically in [availableMs] alone, and they
     * call for opposite fixes -- a longer window in the first case, a differently
     * packed one in the second.
     */
    data class NoVariantFits(
        val shortestVariantMs: Long,
        val availableMs: Long,
        val guardMs: Long,
        val floorHeldBy: UtteranceKind?,
        val nextCueStartMs: Long? = null,
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

/** Which deadline an utterance was past when the floor was taken back from it. */
sealed interface WithdrawalReason {

    /**
     * The utterance was still holding the floor at the end of the silence window
     * it was admitted into. One more millisecond of it would have been heard on
     * top of a line of dialogue.
     *
     * This is the reason the withdrawal mechanism exists, and the only one of the
     * three that is a boundary in the content rather than a limit on our patience.
     */
    data class OutOfSilence(val windowEndMs: Long) : WithdrawalReason

    /**
     * The audio was measured, that length has been and gone, and the speaker has
     * still not reported -- by [slackMs] and with silence to spare.
     *
     * An accusation against the speaker rather than a shortage of room.
     */
    data class SpeakerOverdue(val slackMs: Long) : WithdrawalReason

    /**
     * Nothing ever measured this utterance, so there was no length to be late
     * against; it hit the flat wall-clock ceiling instead. See
     * [DirectorConfig.unmeasuredSpeechCeilingMs].
     */
    data class NeverMeasured(val ceilingMs: Long) : WithdrawalReason
}

/**
 * The floor was taken back from an utterance in progress, and the speaker told to
 * stop.
 *
 * This is an anomaly, not a normal ending. The floor is released by the speaker
 * saying it has stopped; a deadline is only a backstop against a speaker that
 * goes quiet -- an audio stall under memory pressure on a 1 GB device, an engine
 * that drops an utterance, a device that suspends the render thread. Every one of
 * those is worth seeing, so a withdrawal is reported rather than folded into the
 * ordinary path.
 *
 * A withdrawal is a decision with a cost, so it reports the cost:
 * [heldForMs] against [measuredDurationMs] is how far past its own length the
 * utterance had run when it was cut. That difference is what makes the
 * truncate-over-overrun policy auditable after the fact rather than merely
 * asserted -- and it is never negative, which is the strongest thing this type
 * has to say. A cue is admitted only if its measured length plus the guard fits
 * inside its window, so the window deadline cannot fall before the measured end:
 * **a withdrawal never cuts into audio the director admitted, only into the part
 * that had already broken its promise.** The one exception is
 * [WithdrawalReason.NeverMeasured], where by definition there was no promise --
 * which is why that ceiling is set generously rather than tightly.
 */
data class Withdrawal(
    val id: UtteranceId,
    val kind: UtteranceKind,
    val reason: WithdrawalReason,
    /** The length the audio was measured to be, or null when nothing measured it. */
    val measuredDurationMs: Long?,
    /**
     * How long the floor was held in the end, on the clock the deadline was drawn
     * on: the content timeline for a scheduled utterance stopped by its window,
     * the wall clock for anything stopped by its ceiling.
     */
    val heldForMs: Long,
)

/**
 * The content stopped without anybody stopping it, and stayed stopped long enough
 * to be worth seeing.
 *
 * An anomaly on the same terms as a [Withdrawal], and reported for the same
 * reason: the director observes and says so rather than absorbing. A stall is not
 * a fault of ours -- it is the network, or the disk -- but it is the difference
 * between a description that landed and one that talked over a frozen frame, and
 * a log that cannot tell those apart cannot explain either.
 *
 * Nothing about the arbitration changes when this is emitted. The floor is held
 * through a stall of any length and the duck with it; this is the report, not the
 * response.
 */
data class PlaybackStall(
    /** Content position the picture froze at. It does not move while the stall lasts. */
    val atPositionMs: Long,
    /**
     * How long the stall had run when it crossed [thresholdMs], on the wall clock.
     *
     * The length at the crossing and not the total, which is not known until the
     * stall ends. Reporting at the crossing rather than in retrospect is
     * deliberate: a stall that never ends is the worst one there is, and a
     * post-mortem report is exactly the report that one would never produce.
     */
    val stalledForMs: Long,
    /** The threshold actually applied, so a report can be read without the config. */
    val thresholdMs: Long,
    /**
     * The utterance still speaking through the stall, or null if the gap was
     * empty. The difference between a hiccup nobody heard and one that ran
     * underneath a description of a frame that is no longer on screen.
     */
    val floorHeldBy: UtteranceId?,
)

/**
 * The director's entire output for one tick.
 *
 * [drops] are the cues abandoned *on this tick* -- an edge, not a running total.
 * The caller accumulates them; a later slice surfaces them to the viewer, so that
 * a missing description reads as a decision rather than a fault.
 *
 * [withdrawal] and [stall] are likewise edges. At most one withdrawal, because
 * only one utterance can hold the floor to begin with; at most one stall report
 * per stall, because the second tick of the same stall is not news.
 */
data class Directive(
    val speaking: Speaking,
    val contentDuck: ContentDuck,
    val drops: List<DroppedCue> = emptyList(),
    val stall: PlaybackStall? = null,
) {
    /**
     * The floor taken back from an utterance on this tick, if it was.
     *
     * Read off [speaking] rather than stored alongside it, so there is exactly one
     * place the fact lives. A directive that reported a withdrawal *and* named a
     * new utterance would be a directive that started a voice on top of one that
     * has not been stopped yet; keeping the two in one slot is what makes that
     * combination unwritable rather than merely disallowed.
     */
    val withdrawal: Withdrawal? get() = (speaking as? Speaking.Withdrawn)?.withdrawal
}
