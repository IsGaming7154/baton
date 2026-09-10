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
 * `now >= cue.startMs && now + variant.measuredDurationMs + guardMs <= roomEndMs`,
 * where `roomEndMs` is the end of the silence window the cue was written for, or
 * the start of the next cue written into that same window, whichever comes first.
 * The longest variant satisfying that wins. If none does, the cue is dropped and
 * the drop is reported with a reason.
 *
 * The second half of that boundary is what lets one silence hold several
 * sentences. Judged against the window alone, the first cue in a long gap takes
 * the longest variant that fits the whole of it and the rest are dropped for want
 * of room the winner did not need -- a thirty-second action sequence described in
 * one line, with the drops reading as though the film had never offered the
 * silence. See [CueRooms] for why the reservation is a property of the track
 * rather than a scan performed per tick.
 *
 * How far the content is ducked while that happens is not a constant. It is
 * computed from how loud the content actually is at the cue, so that the
 * narration ends up a fixed loudness difference above the bed rather than a fixed
 * number of decibels above wherever the bed happened to be. See [duckFor].
 *
 * ## The floor is released by observation, not by prediction
 *
 * An utterance keeps the floor until the speaker reports it has stopped. The
 * measured duration says whether a cue may *start*; it does not decide when it
 * has finished. Those are different questions, and answering the second one with
 * arithmetic is how two voices end up in the room: on a 1 GB device, audio that
 * stalls under memory pressure is still audible long after the clock says it
 * ended, and a director that believed the clock would start the next voice on top
 * of it. That failure only ever reproduces on real hardware.
 *
 * So `SpeechEnded` is authoritative and the duration is demoted to a deadline: an
 * utterance past its deadline has the floor taken back and is silenced, and the
 * act is reported as a [Withdrawal]. The report is half the point -- a withdrawal
 * means the speaker misbehaved, and that is worth seeing rather than absorbing.
 *
 * ## Truncate, rather than run over
 *
 * The choice is between two artefacts, and it is not the same choice the gap
 * contest below makes. There, two cues want one free window and the answer is
 * that a truncated sentence is worse than a reported drop. Here the loser is not
 * a description, it is the film: a description still speaking at the end of its
 * window is a voice on top of an actor's line, which is the single failure this
 * whole product exists to prevent. **So the answer flips.** A clipped
 * description costs the viewer the tail of one sentence, and it is *legible* --
 * it arrives as a [Withdrawal] carrying how long the floor was held against how
 * long the audio was measured to be. An overrun costs them the line of dialogue
 * underneath it, and everything around it, and it arrives looking exactly like
 * success.
 *
 * The asymmetry is larger than it first looks, because of what a cut can actually
 * reach. A cue is admitted only if its measured length plus the guard fits inside
 * its window, so the window deadline can never fall before the measured end:
 * **a withdrawal only ever truncates the part of an utterance that had already
 * run past its own length.** The description that gets clipped is one that had
 * already stopped keeping its side of the bargain. (The exception is speech
 * nothing measured, which had no length to exceed -- hence a generous ceiling
 * there rather than a tight one.)
 *
 * That decides it: **truncate.** Withdrawal is not a last resort that ought to be
 * avoided, it is the correct response, and the arithmetic is arranged so that the
 * alternative is not available. An utterance cannot be constructed that already
 * promises to run past its window ([Speaking.ScheduledUtterance]); a withdrawal
 * occupies the same slot an utterance would, so "cut that off and start this in
 * the same breath" cannot be expressed ([Speaking.Withdrawn]); and the caller
 * cannot start the next voice on a tick that withdraws, because there is no next
 * voice named on it.
 *
 * What is *not* claimed: that nothing is ever heard over dialogue. The director
 * only decides on the ticks it is given, so a withdrawal lands within one caller
 * tick of the boundary rather than on it, and the speaker takes its own moment to
 * go quiet. The claim is that an overrun is bounded by the tick rate instead of by
 * a two-second slack, and that it always leaves a report behind.
 *
 * ## Two deadlines, on two clocks, for two different failures
 *
 * Every utterance is held to a **wall-clock ceiling**, and a scheduled utterance
 * is additionally held to a **content-clock deadline**. It is stopped at
 * whichever it reaches first. They are not redundant; they guard different things
 * and are therefore drawn on different clocks.
 *
 * The content deadline is `min(endsAtPositionMs + speechTimeoutSlackMs,
 * windowEndMs)`. The second term is the one that matters, and it is a boundary
 * rather than a tolerance: past the end of the granted silence window, an
 * utterance that is still audible is audible over dialogue. Note what the first
 * term alone would allow. A speaker that stalls at the start of a 1.5 s cue in a
 * 2 s gap holds the floor for 3.5 s on a flat two-second slack -- about 1.5 s of
 * it over dialogue -- and then reports normally, so the log records a clean
 * release and nothing distinguishes it from a description that landed perfectly.
 * An overrun that can be seen is a bug with a number attached; an overrun that
 * reports success is this product's central failure wearing the costume of the
 * thing working. A deadline derived from the granted window has no slack for it
 * to hide in.
 *
 * The window end and not the window end minus [DirectorConfig.guardMs], because
 * the guard is exactly the displacement budget the cue was admitted on: it was
 * let in only because its measured length plus the guard fitted, so spending the
 * guard on being slightly late is spending it on what it was reserved for.
 * Cutting at the guard instead would truncate sentences that were about to land
 * cleanly.
 *
 * The wall-clock ceiling is `measuredDurationMs + speechTimeoutSlackMs`, or, for
 * an utterance nothing ever measured, the flat
 * [DirectorConfig.unmeasuredSpeechCeilingMs]. Its job is not dialogue but the
 * wedge: a speaker that goes silent and never reports holds the floor, holds the
 * duck, and stops every cue behind it. Reactive speech used to have no backstop
 * at all -- an engine that returns success and emits nothing (FRICTION_LOG.md
 * entry 9) would keep the floor for the rest of the film.
 *
 * **What each does during a stall follows from what it guards.** The content
 * deadline freezes with the content, because dialogue cannot arrive while the
 * picture is frozen: running it through a stall would cut a description short to
 * protect it from a line that is not coming, which is the artefact issue 20 was
 * filed to remove. The wall-clock ceiling keeps running, because a speaker that
 * has gone quiet does not resume when the network does, and because a correct
 * speaker is never near it: an utterance's audio plays from a local file that did
 * not stall when the video did, so it finishes on the wall clock on schedule
 * whatever the buffer is doing. A ceiling that paused with the picture could be
 * held open forever by one stall.
 *
 * ## A stall is not a pause
 *
 * Both stop the picture, and for a while that was all the director was told. The
 * player reported `isPlaying`, which is false while buffering, so one tick of a
 * network hiccup in the middle of a description read as a pause, cleared the
 * floor, and cut the sentence in half -- and reported nothing, because from here
 * nothing had gone wrong: somebody paused. The two are now separate inputs. See
 * [Playback].
 *
 * A pause is a decision and is obeyed: speech stops, and the cue does not come
 * back, because the viewer has stopped watching the scene it describes. A stall
 * is an interruption and is waited out: the floor is held, the duck is held, and
 * nothing new is scheduled until frames return -- the content clock has stopped,
 * and a cue admitted against a stopped clock is a cue admitted against no
 * evidence at all.
 *
 * Holding is not a compromise. The utterance's audio is playing from a local file
 * through its own `AudioTrack`; it did not stall when the video did, so it keeps
 * talking and finishes by itself -- and cutting it off would be precisely the
 * artefact the pause path was producing by accident.
 *
 * **A long stall is still not a pause.** The obvious worry is narration droning
 * on over a frozen frame, but that is bounded by the utterance and not by the
 * stall: a scheduled variant is only ever as long as the silence window it was
 * admitted into, so it ends of its own accord within a couple of seconds whatever
 * the network is doing. Converting a long stall into a pause could therefore only
 * ever truncate a sentence that was about to finish anyway, and would put the
 * original bug back at a longer horizon. What a long stall gets instead is a
 * report: past [DirectorConfig.stallAnomalyMs] it is surfaced as a
 * [PlaybackStall], on the same terms as a [Withdrawal].
 *
 * Nor can a stall push an utterance into dialogue. Speech advances on the wall
 * clock while the content position does not, so an utterance that is spoken
 * through a stall arrives at the far end of it *further* through itself than the
 * content thinks -- it finishes earlier against the film, never later, and the
 * gap it was granted is still there in front of it.
 *
 * One consequence is worth naming rather than discovering. A scheduled
 * utterance's content deadline is measured on the content timeline, which is
 * frozen for exactly as long as the stall is, so the *window* half of its
 * protection cannot fire until frames return -- and should not, because the
 * dialogue it protects against is frozen too. What does not freeze is the
 * wall-clock ceiling, which is the half aimed at a speaker that has gone silent,
 * and a stall does nothing to make such a speaker any less silent. So a broken
 * speaker is still bounded during a stall of any length, and a working one is
 * still not touched by either.
 *
 * Nothing is owed on the way out. The content position did not move while the
 * picture was frozen, so no cue was passed over and there is nothing to
 * rebaseline; the utterance that was speaking never stopped, so it does not
 * restart. Scheduling resumes exactly where it left off.
 *
 * ## Deliberate readings of the policy, where it was silent
 *
 * - **Scheduled does not preempt scheduled.** Rule 3 ranks candidates when the
 *   floor is free; it does not cut a description in half to start a subtitle. A
 *   truncated sentence is a worse artefact than a reported drop, and the PRD uses
 *   the word "preempts" only of the reactive class. Note the scope of that
 *   reasoning: it settles which of two of *our* voices gets a free window, where
 *   the cost of truncating is paid for nothing. It says nothing about running
 *   over dialogue, where the cost of *not* truncating is paid by the film -- and
 *   there the answer is the other way round. See "Truncate, rather than run
 *   over" above.
 * - **A cue is bound to the silence window it was written for.** Once playback
 *   has passed the end of that window, the cue is dropped rather than carried
 *   into some later gap where it would describe the wrong moment.
 * - **And to its room inside that window.** Playback past the start of the next
 *   cue in the same window drops this one too, rather than letting it spill into
 *   its neighbour's slot if the neighbour happens not to want it. The neighbour
 *   might yet speak -- the floor could free up a tick later -- and a cue that had
 *   already been let into that room would be describing a moment the film has
 *   moved on from, on top of the description of the moment it is actually in.
 *   Dropping is reported; spilling would not be.
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
     * Wall clock reading at which the interruption in progress began, or null when
     * the content is not stalled -- or is stalled without having interrupted
     * anything. See [noteStall].
     */
    private var stalledSinceMs: Long? = null

    /** Whether the stall in progress has already been reported. One report per stall. */
    private var stallReported = false

    /**
     * The dip currently in force, which [duckFor] needs in order to decide whether
     * a newly computed one has moved far enough to be worth applying. It lives
     * here rather than inside that function so the rule itself stays pure.
     */
    private var appliedDuck: ContentDuck = ContentDuck.Unity

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

        // Entering pause stops whatever was in flight. Staying paused does not,
        // so an answer the viewer asked for while paused is allowed to finish.
        //
        // Entering pause *from a stall* counts: the viewer who reaches for the
        // button while the picture is frozen has still decided to stop, and their
        // decision outranks the interruption they made it during. What does not
        // count is entering the stall itself -- nobody decided that, so nothing is
        // given up for it. See the class doc.
        if (inputs.playback == Playback.PAUSED && lastPlayback != Playback.PAUSED) {
            active = null
        }
        noteStall(nowMs, inputs.playback)
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

        // Only after the speaker has had its say on this tick, so that an
        // utterance that reported on the same tick it came due is an ordinary
        // ending rather than an anomaly.
        val withdrawal = withdrawOverdue(nowMs, inputs.positionMs)

        // Scheduling needs a moving content clock, and a stall is exactly the
        // state in which there is not one -- so PLAYING, and not merely "not
        // paused", is the condition. A stall holds what is already speaking; it
        // does not start anything.
        //
        // Nor does the tick that withdraws the floor, for the reason a seek does
        // not: the speaker has this instant been told to stop and has not done so
        // yet, and starting the next voice into that half-second would be adding
        // one rather than replacing one. The type says so too -- a directive
        // carrying a withdrawal has no room left to name an utterance -- so this
        // is where the two agree rather than a second rule.
        val drops = if (inputs.playback == Playback.PLAYING && !timelineJumped && withdrawal == null) {
            arbitrateScheduled(nowMs, inputs.positionMs)
        } else {
            emptyList()
        }

        // After the events and the deadlines, so the report names whoever is
        // actually holding the floor as the stall runs under them.
        val stall = reportStall(nowMs, inputs.positionMs)

        val holder = active
        // Nothing speaking means the bed comes back, exactly and without passing
        // through the deadband -- see duckFor. Otherwise the dip is computed
        // against the one already applied, so that a meter wobbling in its low
        // digits does not restart the fade on every tick.
        //
        // A stall changes nothing here, and that is a decision rather than an
        // omission. The floor is still held, so the duck is still applied -- and
        // it has to be, because the content is about to come back: release it and
        // the first frames after the stall arrive at full level underneath a
        // narrator who never stopped talking, followed by a second fade to put
        // them back down. Two audible moves for an event the viewer should not
        // have been able to hear at all.
        appliedDuck = if (holder == null) {
            ContentDuck.Unity
        } else {
            duckFor(inputs.programmeLufs, config, appliedDuck)
        }
        return Directive(
            speaking = holder ?: withdrawal?.let { Speaking.Withdrawn(it) } ?: Speaking.Silent,
            contentDuck = appliedDuck,
            drops = drops,
            stall = stall,
        )
    }

    /**
     * Run the clock that decides when a stall has gone on long enough to report.
     *
     * It starts only on the edge out of [Playback.PLAYING]. A stall entered from a
     * pause, or from a standing start before anything has played, has interrupted
     * nothing: no cue is in flight, and the content was not moving to begin with.
     * Filling the buffer at the top of a stream is that case, and timing it would
     * put an anomaly in the log on every cold start from a network source -- an
     * anomaly that fires on every launch is one that stops being read.
     */
    private fun noteStall(nowMs: Long, playback: Playback) {
        when {
            playback != Playback.STALLED -> {
                stalledSinceMs = null
                stallReported = false
            }

            lastPlayback == Playback.PLAYING -> {
                stalledSinceMs = nowMs
                stallReported = false
            }
        }
    }

    /**
     * The stall in progress, if it has just become long enough to be worth seeing.
     *
     * Timed on the wall clock, because the content clock is the thing that has
     * stopped -- that is what a stall *is*, and asking a frozen clock how long it
     * has been frozen only ever answers zero.
     *
     * Emitted once per stall, on the tick it crosses. Repeating it every tick
     * would turn one event into a column of identical lines, and the interesting
     * fact -- that this happened, here, under this utterance -- is already in the
     * first one.
     */
    private fun reportStall(nowMs: Long, positionMs: Long): PlaybackStall? {
        val since = stalledSinceMs ?: return null
        if (stallReported) return null
        val stalledForMs = nowMs - since
        if (stalledForMs < config.stallAnomalyMs) return null

        stallReported = true
        return PlaybackStall(
            atPositionMs = positionMs,
            stalledForMs = stalledForMs,
            thresholdMs = config.stallAnomalyMs,
            floorHeldBy = active?.id,
        )
    }

    /**
     * Take the floor back from an utterance that is past a deadline, so the caller
     * can silence it.
     *
     * The content deadline is asked first, because while frames are moving it is
     * the tighter of the two and it carries the more specific accusation: an
     * utterance stopped at the end of its window ran out of *silence*, which is a
     * fact about the film, whereas one stopped by its ceiling merely ran out of
     * our patience. Both stop it; only the reasons differ, and the log is the
     * place that difference has to survive to.
     */
    private fun withdrawOverdue(nowMs: Long, positionMs: Long): Withdrawal? {
        val holder = active ?: return null
        val withdrawal = holder.pastContentDeadline(positionMs)
            ?: holder.pastWallCeiling(nowMs)
            ?: return null
        active = null
        return withdrawal
    }

    /**
     * The deadline drawn on the content timeline, which only scheduled speech has:
     * `min(endsAtPositionMs + slack, windowEndMs)`.
     *
     * The window term is the one this exists for. A flat slack is a statement
     * about how long we are willing to wait, and it bears no relation to how much
     * room the utterance was actually given -- so on a tight gap it expires long
     * after the dialogue has started, which is to say never, in the only sense
     * that matters. The window end is where the next line begins, and an utterance
     * still audible there is the failure. Frozen while the content is, because so
     * is the dialogue it is protecting: see the class doc.
     */
    private fun Speaking.Utterance.pastContentDeadline(positionMs: Long): Withdrawal? {
        if (this !is Speaking.ScheduledUtterance) return null
        val overdueAtMs = endsAtPositionMs + config.speechTimeoutSlackMs
        val outOfSilence = windowEndMs <= overdueAtMs
        if (positionMs < minOf(overdueAtMs, windowEndMs)) return null
        return Withdrawal(
            id = id,
            kind = kind,
            reason = if (outOfSilence) {
                WithdrawalReason.OutOfSilence(windowEndMs = windowEndMs)
            } else {
                WithdrawalReason.SpeakerOverdue(slackMs = config.speechTimeoutSlackMs)
            },
            measuredDurationMs = duration.millis,
            heldForMs = positionMs - startedAtPositionMs,
        )
    }

    /**
     * The ceiling drawn on the wall clock, which every utterance has: its measured
     * length plus the slack, or [DirectorConfig.unmeasuredSpeechCeilingMs] when
     * nothing measured it.
     *
     * The unmeasured case is the one with no previous answer at all. Speech that
     * runs [SpeechLength.UntilSpeakerReports] was bounded by nothing, so a speaker
     * that went quiet held the floor -- and the duck under it -- until the viewer
     * pressed something else. A ceiling is a worse bound than a measurement and it
     * is a great deal better than none.
     *
     * Never frozen, whatever the playback state. Audio plays from a local file
     * that did not stall when the video did, so a speaker that is behaving reports
     * on this clock on schedule through a stall of any length, and one that is not
     * behaving is not made any more likely to report by the network coming back.
     */
    private fun Speaking.Utterance.pastWallCeiling(nowMs: Long): Withdrawal? {
        val measuredMs = measuredDurationMs
        val ceilingAtMs = startedAtMs +
            (measuredMs?.plus(config.speechTimeoutSlackMs) ?: config.unmeasuredSpeechCeilingMs)
        if (nowMs < ceilingAtMs) return null
        return Withdrawal(
            id = id,
            kind = kind,
            reason = if (measuredMs == null) {
                WithdrawalReason.NeverMeasured(ceilingMs = config.unmeasuredSpeechCeilingMs)
            } else {
                WithdrawalReason.SpeakerOverdue(slackMs = config.speechTimeoutSlackMs)
            },
            measuredDurationMs = measuredMs,
            heldForMs = nowMs - startedAtMs,
        )
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
                        best = Candidate(
                            cue = cue,
                            variantIndex = assessment.variantIndex,
                            windowEndMs = assessment.windowEndMs,
                            roomEndMs = assessment.roomEndMs,
                        )
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
                windowEndMs = winner.windowEndMs,
                roomEndMs = winner.roomEndMs,
            )
        }

        return drops ?: emptyList()
    }

    /**
     * The eligibility rule. A cue is bound to the silence window containing its
     * own start time, and to the part of that window that has not been promised to
     * a description behind it; what is left of *that* at [positionMs] is all the
     * room it will ever have, and the room only shrinks.
     *
     * The second clause is the whole of it, and it used to be missing. Reading the
     * room off the window end alone is right exactly while a window holds one cue,
     * and silently wrong the moment it holds two: the first cue takes the longest
     * variant that fits the remaining silence, which on a thirty-second gap is all
     * of it, and the cues behind it are dropped for want of room the first one did
     * not need. Nothing observable distinguishes that from working -- the utterance
     * ended inside the silence, and the drops read as a gap that was too short. See
     * [CueRooms].
     *
     * The guard is charged against the room and not only against the window, so it
     * sits between two adjacent descriptions exactly as it sits between a
     * description and a line of dialogue. It is not there for dialogue in
     * particular; it is there because the duck ramps over 150 ms and clocks are
     * not exact, and two sentences run together with no air between them are one
     * long sentence delivered by a machine.
     */
    private fun assess(cue: Cue, positionMs: Long): Assessment {
        if (cue.variants.isEmpty()) return Assessment.Drop(DropReason.NoVariants)

        val windowEndMs = track.silence.windowEndAt(cue.startMs)
            ?: return Assessment.Drop(DropReason.NoSilenceAtCueStart)
        val roomEndMs = track.rooms.roomEndFor(cue.id) ?: windowEndMs

        val availableMs = (roomEndMs - positionMs).coerceAtLeast(0L)
        val variantIndex = if (positionMs >= roomEndMs) {
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
                    nextCueStartMs = roomEndMs.takeIf { it < windowEndMs },
                ),
            )
        } else {
            Assessment.Fits(variantIndex, windowEndMs, roomEndMs)
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

    private class Candidate(
        val cue: Cue,
        val variantIndex: Int,
        val windowEndMs: Long,
        val roomEndMs: Long,
    )

    private sealed interface Assessment {
        /**
         * [windowEndMs] and [roomEndMs] travel with the verdict rather than being
         * looked up again at admission: the room the cue was judged against and
         * the room it is granted have to be the same numbers, or the deadline that
         * holds it to that grant is holding it to something else.
         */
        class Fits(val variantIndex: Int, val windowEndMs: Long, val roomEndMs: Long) : Assessment
        class Drop(val reason: DropReason) : Assessment
    }
}
