package dev.baton.director

/**
 * Arbitrates every voice the viewer can hear, so that exactly one speaks at a time.
 *
 * ## The guarantee
 *
 * A [Directive] can name at most one utterance, so "two of ours at once" is
 * impossible by construction. The part that needs enforcing is the harder half:
 * the content's own dialogue is a voice too, and a scheduled utterance must never
 * land on top of it. That is what the eligibility rule below is for.
 *
 * ## Two clocks, on purpose
 *
 * `advance` takes a monotonic wall clock (`nowMs`) and is told the content
 * position ([DirectorInputs.positionMs]) separately. Scheduled speech is timed
 * against the content, because its justification is a fact about the content:
 * it fits between here and the next line of dialogue. Reactive speech is timed
 * against the wall clock, because the viewer is waiting in real time and the
 * content may be paused underneath them. Collapsing the two into one number
 * would make one of those cases wrong.
 *
 * ## Policy
 *
 * 1. Reactive preempts scheduled, always.
 * 2. Among reactive, the most recent user intent wins.
 * 3. Among scheduled, `SPOKEN_SUBTITLE` outranks `AUDIO_DESCRIPTION`.
 * 4. Content dialogue is never spoken over by a scheduled utterance.
 *
 * A cue may start only when
 * `now >= cue.startMs && now + variant.measuredDurationMs + guardMs <= nextDialogueOnsetMs`,
 * where `nextDialogueOnsetMs` is the end of the silence window the cue was
 * written for. The longest variant satisfying that wins. If none does, the cue is
 * dropped and the drop is reported with a reason.
 *
 * ## Deliberate readings of the policy, where it was silent
 *
 * - **Scheduled does not preempt scheduled.** Rule 3 ranks candidates when the
 *   floor is free; it does not cut a description in half to start a subtitle. A
 *   truncated sentence is a worse artefact than a reported drop, and the PRD uses
 *   the word "preempts" only of the reactive class.
 * - **A cue is bound to the silence window it was written for.** Once playback
 *   has passed the end of that window, the cue is dropped rather than carried
 *   into some later gap where it would describe the wrong moment.
 * - **Reactive speech still works while paused.** Pause stops speech that was in
 *   flight, and no cue starts while the content is not moving; but a button press
 *   is still answered, because a viewer who cannot see the screen has no other way
 *   to operate a paused player.
 * - **The tick that carries a seek is silent.** A seek is a discontinuity;
 *   scheduling resumes on the following advance, and only cues at or after the
 *   new position remain in play.
 *
 * Not thread-safe, and not meant to be: one player, one director, one caller.
 */
class AudioDirector(
    private val track: DescriptionTrack,
    private val config: DirectorConfig = DirectorConfig(),
) {

    private var lastNowMs: Long? = null
    private var lastPlayback: Playback = Playback.PLAYING
    private var active: Speaking.Utterance? = null

    /**
     * Cues that have had their moment: started, dropped, or silently passed over
     * by a rebaseline. Cleared and recomputed whenever the timeline jumps.
     */
    private val resolved: MutableSet<CueId> = mutableSetOf()

    /**
     * Advance the machine to [nowMs] and report who should be speaking.
     *
     * [nowMs] must not run backwards. Call this on every playback tick; the
     * director only makes decisions when it is asked.
     */
    fun advance(nowMs: Long, inputs: DirectorInputs): Directive {
        val previousNowMs = lastNowMs
        require(previousNowMs == null || nowMs >= previousNowMs) {
            "advance() clock must not run backwards: $nowMs after $previousNowMs"
        }
        if (previousNowMs == null) {
            // First look at this timeline. Playback may be resuming from a saved
            // position, so everything already behind us is passed over silently
            // rather than reported as a wall of drops.
            rebaseline(inputs.positionMs)
        }
        lastNowMs = nowMs

        retireFinished(nowMs, inputs.positionMs)

        // Entering pause stops whatever was in flight. Staying paused does not,
        // so an answer the viewer asked for while paused is allowed to finish.
        if (inputs.playback == Playback.PAUSED && lastPlayback == Playback.PLAYING) {
            active = null
        }
        lastPlayback = inputs.playback

        var timelineJumped = false
        for (event in inputs.events) {
            when (event) {
                is InputEvent.Seeked -> {
                    active = null
                    rebaseline(inputs.positionMs)
                    timelineJumped = true
                }

                is InputEvent.SpeechEnded -> {
                    if (active?.id == event.id) active = null
                }

                is InputEvent.ReactiveRequested -> {
                    // Rules 1 and 2 in one line: the newest user intent takes the
                    // floor from whatever held it.
                    active = event.request.takeFloor(nowMs)
                }
            }
        }

        val drops = if (inputs.playback == Playback.PLAYING && !timelineJumped) {
            arbitrateScheduled(nowMs, inputs.positionMs)
        } else {
            emptyList()
        }

        val holder = active
        return Directive(
            speaking = holder ?: Speaking.Silent,
            contentDuck = if (holder == null) ContentDuck.Unity else ContentDuck.Ducked(config.duckDb),
            drops = drops,
        )
    }

    /** Let go of the floor when the utterance holding it has run its length. */
    private fun retireFinished(nowMs: Long, positionMs: Long) {
        when (val holder = active) {
            null -> Unit
            is Speaking.ScheduledUtterance ->
                if (positionMs >= holder.endsAtPositionMs) active = null

            is Speaking.ReactiveUtterance -> {
                val endsAtMs = holder.endsAtMs
                if (endsAtMs != null && nowMs >= endsAtMs) active = null
            }
        }
    }

    /**
     * Re-derive which cues are still ahead of us after a discontinuity: after a
     * jump the viewer gets what is in front of them and nothing else.
     *
     * Cues left behind are marked resolved *without a report*. They were not
     * dropped for want of room -- they were jumped over -- and reporting a
     * fast-forward as several hundred failed descriptions would bury the drops
     * that actually mean something. Cues ahead of the new position are released,
     * so seeking backwards genuinely replays a scene rather than skipping it.
     */
    private fun rebaseline(positionMs: Long) {
        resolved.clear()
        for (cue in track.cues) {
            if (cue.startMs < positionMs) resolved += cue.id
        }
    }

    /**
     * Assess every unresolved cue whose start time has passed: drop the ones that
     * can no longer fit, and, if the floor is free, start the best of the rest.
     *
     * Every candidate is assessed on every tick, not just the one that gets to
     * speak, so that a cue squeezed out by a longer neighbour is still reported at
     * the moment it becomes impossible rather than silently forgotten.
     *
     * This is a linear scan of the cue list per tick. At a feature film's cue
     * count and a playback tick rate that is tens of hertz, that is a rounding
     * error even on the 1 GB target device, and it is worth paying to keep the
     * arbitration readable -- there is no cursor to get out of step with a seek.
     */
    private fun arbitrateScheduled(nowMs: Long, positionMs: Long): List<DroppedCue> {
        var drops: MutableList<DroppedCue>? = null
        var best: Candidate? = null

        for (cue in track.cues) {
            if (cue.id in resolved || positionMs < cue.startMs) continue
            when (val assessment = assess(cue, positionMs)) {
                is Assessment.Drop -> {
                    resolved += cue.id
                    val list = drops ?: mutableListOf<DroppedCue>().also { drops = it }
                    list += DroppedCue(
                        cueId = cue.id,
                        kind = cue.kind,
                        cueStartMs = cue.startMs,
                        atPositionMs = positionMs,
                        reason = assessment.reason,
                    )
                }

                is Assessment.Fits -> {
                    val incumbent = best
                    if (incumbent == null || outranks(cue, incumbent.cue)) {
                        best = Candidate(cue, assessment.variantIndex)
                    }
                }
            }
        }

        val winner = best
        if (active == null && winner != null) {
            resolved += winner.cue.id
            val variant = winner.cue.variants[winner.variantIndex]
            active = Speaking.ScheduledUtterance(
                id = UtteranceId("cue:${winner.cue.id.value}"),
                kind = winner.cue.kind,
                text = variant.text,
                audioRef = variant.audioRef,
                startedAtMs = nowMs,
                cueId = winner.cue.id,
                variantIndex = winner.variantIndex,
                duration = variant.measuredDuration,
                startedAtPositionMs = positionMs,
            )
        }

        return drops ?: emptyList()
    }

    /**
     * The eligibility rule. A cue is bound to the silence window containing its
     * own start time; what is left of that window at [positionMs] is all the room
     * it will ever have, and the room only shrinks.
     */
    private fun assess(cue: Cue, positionMs: Long): Assessment {
        if (cue.variants.isEmpty()) return Assessment.Drop(DropReason.NoVariants)

        val windowEndMs = track.silence.windowEndAt(cue.startMs)
            ?: return Assessment.Drop(DropReason.NoSilenceAtCueStart)

        val availableMs = (windowEndMs - positionMs).coerceAtLeast(0L)
        val variantIndex = if (positionMs >= windowEndMs) {
            -1
        } else {
            // Variants run longest first, so the first that fits is the longest that fits.
            cue.variants.indexOfFirst { it.measuredDuration.millis + config.guardMs <= availableMs }
        }

        return if (variantIndex < 0) {
            Assessment.Drop(
                DropReason.NoVariantFits(
                    shortestVariantMs = cue.variants.last().measuredDuration.millis,
                    availableMs = availableMs,
                    guardMs = config.guardMs,
                    floorHeldBy = active?.kind,
                ),
            )
        } else {
            Assessment.Fits(variantIndex)
        }
    }

    /** Rule 3, then earliest, then id -- so the choice never depends on iteration luck. */
    private fun outranks(challenger: Cue, incumbent: Cue): Boolean {
        if (challenger.kind.priority != incumbent.kind.priority) {
            return challenger.kind.priority < incumbent.kind.priority
        }
        if (challenger.startMs != incumbent.startMs) return challenger.startMs < incumbent.startMs
        return challenger.id.value < incumbent.id.value
    }

    private fun ReactiveRequest.takeFloor(nowMs: Long): Speaking.ReactiveUtterance =
        Speaking.ReactiveUtterance(
            id = id,
            kind = kind,
            text = text,
            audioRef = audioRef,
            startedAtMs = nowMs,
            length = length,
        )

    private class Candidate(val cue: Cue, val variantIndex: Int)

    private sealed interface Assessment {
        class Fits(val variantIndex: Int) : Assessment
        class Drop(val reason: DropReason) : Assessment
    }
}
