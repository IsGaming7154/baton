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
 * 3. Among scheduled, the synthetic-speech disclosure outranks `SPOKEN_SUBTITLE`,
 *    which outranks `AUDIO_DESCRIPTION`.
 * 4. Content dialogue is never spoken over by a scheduled utterance.
 *
 * A cue may start only when
 * `now >= cue.startMs && now + variant.measuredDurationMs + guardMs <= roomEndMs`,
 * where `roomEndMs` is the end of the silence window the cue was written for, or
 * the start of the next cue written into that same window, whichever comes first
 * -- and for a spoken subtitle, the next spoken subtitle; see "Subtitles are
 * bookings, not competitors" below.
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
 * And no scheduled utterance -- cue or disclosure -- starts until the content has
 * moved [DirectorConfig.interUtteranceGapMs] past the position the floor was last
 * released at, by a speaker's report, a pause or a withdrawal. Back to back, a
 * subtitle and the description behind it in the same synthetic voice are heard as
 * one voice running over another. A cue the gap holds back is judged by the rule
 * above at the position it actually starts at, and dropped on the ordinary reasons
 * if the gap has cost it its room. Reactive speech does not wait for the gap, and a
 * seek clears it.
 *
 * How far the content is ducked while that happens is not a constant. It is
 * computed from how loud the content actually is at the cue, so that the
 * narration ends up a fixed loudness difference above the bed rather than a fixed
 * number of decibels above wherever the bed happened to be. See [duckFor].
 *
 * ## Subtitles are bookings, not competitors
 *
 * Rule 3 ranks a subtitle above a description, but a ranking only settles a tie for
 * a free floor. Scheduled speech does not preempt scheduled speech, so a
 * description that started in a silence and was still speaking when a subtitle
 * came due would cost the subtitle its turn: dialogue meaning traded for scenery,
 * the trade rule 3 exists to refuse. Subtitle lines are known in advance -- they
 * come from the subtitle file, not from the player -- so rather than compete for
 * the floor they are booked against the timeline before descriptions are placed,
 * and the eligibility boundary widens from "the next line of dialogue" to
 * `nextBlockingEventMs = min(nextDialogueOnsetMs, nextBookedSubtitleStartMs)`.
 *
 * A booking is a `SPOKEN_SUBTITLE` cue. It reserves its start against anything that
 * would still be speaking when it arrives, and its room against anything written
 * inside it, for as long as the subtitle is still due; [CueRooms] says exactly what
 * that is and why. A cue that cannot finish before a booking is dropped with
 * [DropReason.RunsIntoBookedSubtitle], naming the subtitle, so that the drop reads
 * as the trade it is. On dense foreign-language content descriptions will be
 * dropped more often, and that is the correct trade: a viewer who misses a line of
 * dialogue has lost the plot, and one who misses a description of the room has not.
 *
 * The booking binds admission, as every room does. A voice admitted to finish in
 * time that then overruns -- a speaker that stalls -- is not withdrawn at the
 * booking, because the booking belongs to another of our voices and not to the
 * film: the subtitle waits for the floor, and is reported if it is squeezed out.
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
 * ## The synthetic-speech disclosure
 *
 * A track marked [DescriptionTrack.synthetic] is spoken by a machine, and the
 * guidance this project cites (the American Council of the Blind's, per PRD.md) is
 * that the audience must be told so. Telling them is a voice, so it is arbitrated
 * here like every other voice. An announcement the app played before starting the
 * film would be the one utterance in the product that nothing had checked against
 * the dialogue.
 *
 * **It is scheduled speech, and it waits for silence.** "Before the film starts"
 * is the obvious slot, and it is not one this machine has. The director is told
 * where the content is and whether it is moving; it has no way to hold the content
 * back while it talks, and until the player is told to play it reports
 * [Playback.PAUSED], in which nothing is scheduled at all. Once the film is moving,
 * the gap map is the only evidence of silence there is. So the disclosure is held
 * to the rule the cues are held to -- `now + measuredDurationMs + guardMs <=
 * windowEndMs`, against whatever silence window the playback position is in -- and,
 * once speaking, to the same window deadline. A film that opens on silence gets it
 * at the top with no special case; one that opens on dialogue gets it at the first
 * pause long enough to hold it.
 *
 * **It outranks every description, and ignores the room descriptions reserve.**
 * The point of it is that the viewer is told early, so it takes the first window
 * that holds it, ahead of any description contesting the same free floor. Honouring
 * the rooms descriptions reserve would defeat that. A window with a description in
 * it is reserved from that description's start onwards, so what is left for
 * anything else is the sliver before it: on the bundled clip the longest
 * unreserved span anywhere is 2.25 s, short of the sentence plus the guard at even
 * the authored speaking rate. The cost of not honouring it is bounded, and
 * reported. A cue that comes due while the disclosure speaks waits for the floor as
 * it would behind any voice -- this does not preempt scheduled speech, and
 * scheduled speech does not preempt it -- and one squeezed out entirely is a drop
 * naming the disclosure as the holder.
 *
 * **It honours a subtitle booking, though.** Being told the voice is synthetic a
 * sentence later costs the viewer nothing; losing the line a subtitle translates
 * costs them the scene, which is the loss bookings exist to prevent, and a
 * disclosure that ignored them would be the one voice left that could still
 * inflict it. So its boundary is the same `nextBlockingEventMs` a cue's is: it must
 * finish, plus the guard, before the next booked subtitle as well as before the
 * dialogue. And it does not take the floor while a subtitle is due and fits, since
 * that subtitle's booking has already begun -- rule 3's ranking of the disclosure
 * over a subtitle survives for the free floor ahead of a booking, and not inside
 * one. This does not cost it the sliver argument above. A description reserves its
 * room statically, from its start, whether or not it ever speaks; a booking is
 * released the moment its subtitle has spoken or been dropped, so what is left for
 * the disclosure after a subtitle is the rest of that silence.
 *
 * What that does not buy is a guarantee that nothing synthetic is heard first. A
 * description in a window too short for the disclosure still speaks there.
 * Holding every description back until a long enough silence came along would
 * trade one sentence of disclosure for however many descriptions a
 * dialogue-heavy opening holds -- and on a film with no such silence, for all of
 * them.
 *
 * **Owed once per session, and discharged by observation.** The debt is cleared by
 * one of two things, and nothing else. Either the speaker reports the end of it --
 * a speaker reports only audio it played to the last frame, and never an utterance
 * it was told to stop -- or the floor is withdrawn from it. A withdrawal counts
 * because of what a withdrawal can reach: an utterance admitted on its measured
 * length can only be cut *past* that length, so the sentence had been given all of
 * its time, and a speaker that failed to deliver it in that time would fail again
 * and take a silence from the descriptions on every attempt. Anything else that
 * ends it early leaves it owed, and it starts again from the top at the next
 * silence that holds it: a pause, a button press, or a seek, each of which cuts
 * into the part of the sentence the viewer had not heard yet.
 *
 * A seek after it has been heard does not bring it back. A seek releases the cues
 * ahead of the new position because they describe scenes the viewer is about to
 * watch again; the disclosure describes the voice, and jumping around inside the
 * same film does not make the viewer any less told.
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
 *   cue in the same window (for a subtitle, the next subtitle) drops this one too,
 *   rather than letting it spill into
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
 * - **A silenced kind is absent, not muted.** [DirectorInputs.silencedKinds] is
 *   consulted in one place, before a cue is assessed, and a cue of a silenced kind
 *   is resolved without a report. Nothing starts, so nothing is stopped and the
 *   floor is never taken -- which is why switching a kind off cannot cut a
 *   sentence in half, and cannot produce two voices. What is already speaking when
 *   the switch is thrown finishes, unless the button press that threw it is
 *   answered by a reactive announcement, which preempts it under rule 1.
 *
 * ## Another app's voice outranks all of ours
 *
 * "Exactly one voice at a time" counts voices the director does not own. On Fire
 * TV the screen reader, VoiceView, speaks on its own schedule -- FRICTION_LOG.md
 * entry 19 -- and the caller can see it coming only as a loss of audio focus. While
 * [DirectorInputs.externalVoice] holds, Baton yields completely: what it is saying
 * is cut on the first such tick, nothing scheduled or reactive starts, a cue whose
 * moment comes is dropped with [DropReason.YieldedToExternalVoice], and a button
 * press is refused into [Directive.refused]. When it clears, scheduling resumes
 * from the current position against the ordinary rules, gap included; nothing that
 * was cut or dropped comes back.
 *
 * Cut rather than held, because the other voice is not one this machine can time:
 * it did not come from the track, so there is no room it was written into and no
 * deadline it will keep. A cut description is the pause path's artefact and is
 * reported the same way; the disclosure, cut by it, stays owed exactly as a pause
 * leaves it, since the viewer was not told. The duck follows the floor as it always
 * does, so the film comes back up as Baton goes quiet -- the other voice brings its
 * own ducking with it or does not, and that is the platform's business.
 *
 * Not thread-safe, and not meant to be: one player, one director, one caller.
 */
class AudioDirector(
    private val track: DescriptionTrack,
    private val config: DirectorConfig = DirectorConfig(),
    /**
     * The disclosure sentence, rendered and measured by the caller on the engine
     * that will speak it, or null when the caller has nothing rendered. Whether it
     * is owed is decided here from [DescriptionTrack.synthetic], never by the
     * caller: a disclosure handed over with a track that is not synthetic is not
     * spoken. See "The synthetic-speech disclosure" above.
     */
    private val disclosure: Variant? = null,
) {

    /**
     * Whether this session still owes the viewer the disclosure. Set once, at
     * construction, and cleared once -- by the speaker reporting the end of it, or
     * by the floor being withdrawn from it. Nothing sets it again: not a seek, and
     * not the end of the film.
     */
    private var disclosureOwed: Boolean = track.synthetic && disclosure != null

    private var lastNowMs: Long? = null
    private var lastPlayback: Playback = Playback.PLAYING
    private var active: Speaking.Utterance? = null

    /**
     * Content position at which the floor was last released, or null when nothing
     * has released it since the last rebaseline. No scheduled utterance starts until
     * the content has moved [DirectorConfig.interUtteranceGapMs] past it.
     *
     * The content clock and not the wall clock, because the gap is paid for out of
     * the room a cue is admitted against, and that room is measured on the content
     * timeline: a cue held back by the gap is re-assessed at the position it would
     * actually start at, and one the gap has squeezed out is dropped on the numbers
     * it was judged on. The two clocks only part company while the picture is
     * stopped, and there the content clock errs towards air. A release during a
     * pause or a stall is still owed its gap once frames return, because resuming is
     * itself something the viewer hears, and a voice on that instant is butted
     * against it. Cleared by a seek, which is a discontinuity: the position a
     * release was recorded at says nothing about the new one.
     */
    private var releasedAtPositionMs: Long? = null

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
        if (inputs.playback == Playback.PAUSED && lastPlayback != Playback.PAUSED && active != null) {
            active = null
            releasedAtPositionMs = inputs.positionMs
        }
        noteStall(nowMs, inputs.playback)
        lastPlayback = inputs.playback

        // Level, not an edge: nothing can take the floor while another voice holds
        // it, so in practice this fires on the first tick of the yield and never
        // again -- but stated as a level it cannot be missed by a caller that
        // delivered the edge on a tick the director was not asked about.
        if (inputs.externalVoice && active != null) {
            active = null
            releasedAtPositionMs = inputs.positionMs
        }

        var refused: MutableList<ReactiveRequest>? = null
        var timelineJumped = false
        for (event in inputs.events) {
            when (event) {
                is InputEvent.Seeked -> {
                    active = null
                    rebaseline(inputs.positionMs)
                    timelineJumped = true
                }

                is InputEvent.SpeechEnded -> {
                    if (active?.id == event.id) {
                        active = null
                        releasedAtPositionMs = inputs.positionMs
                    }
                    // Whether or not it still holds the floor by this point in the
                    // tick. A speaker reports only audio it played to the last frame,
                    // so this is evidence the viewer heard the sentence even when a
                    // seek or a button press queued ahead of the report has already
                    // taken the floor away.
                    if (event.id == DISCLOSURE_ID) disclosureOwed = false
                }

                is InputEvent.ReactiveRequested -> {
                    // Rules 1 and 2 in one line: the newest user intent takes the
                    // floor from whatever held it -- unless the floor is not ours.
                    if (inputs.externalVoice) {
                        (refused ?: mutableListOf<ReactiveRequest>().also { refused = it }) += event.request
                    } else {
                        active = event.request.takeFloor(nowMs)
                    }
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
            arbitrateScheduled(nowMs, inputs.positionMs, inputs.silencedKinds, inputs.descriptionLength, inputs.externalVoice)
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
            refused = refused ?: emptyList(),
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
        releasedAtPositionMs = positionMs
        // Discharged, not re-owed: a withdrawal only reaches past the measured
        // length, so the sentence had all of its time. See the class doc.
        if (withdrawal.id == DISCLOSURE_ID) disclosureOwed = false
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
        releasedAtPositionMs = null
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
     *
     * A cue of a kind in [silenced] is passed over here and nowhere else. That is
     * the whole of the switch, and deliberately so: it can only ever shrink the
     * candidate set, so it cannot put a second voice in the room, and with nothing
     * silenced -- which is every existing test, including the randomised timelines
     * -- this loop is the loop it always was. A silenced kind still keeps its
     * booking, because the rooms were derived from the track once
     * ([DescriptionTrack.rooms]) and re-deriving them per tick to reclaim the space
     * would be a different and much larger change; the launch-time toggle in
     * `TrackPreparation` is the one that reclaims it, by removing the cues before
     * the track is built.
     */
    private fun arbitrateScheduled(
        nowMs: Long,
        positionMs: Long,
        silenced: Set<UtteranceKind.Scheduled>,
        length: DescriptionLength,
        yielded: Boolean,
    ): List<DroppedCue> {
        var drops: MutableList<DroppedCue>? = null
        var best: Candidate? = null
        // The next booked subtitle ahead of the position, which bounds the disclosure
        // as it bounds a cue. Found in the walk already being made, not a second one.
        var nextBookingMs = Long.MAX_VALUE

        for (cue in track.cues) {
            if (cue.kind == UtteranceKind.Scheduled.SPOKEN_SUBTITLE && cue.startMs > positionMs) {
                nextBookingMs = minOf(nextBookingMs, cue.startMs)
            }
            if (cue.id in resolved || positionMs < cue.startMs) continue
            if (cue.kind in silenced) {
                // Its moment passed with the switch off. Resolved without a report,
                // exactly as a rebaseline passes over what a seek jumped: nothing
                // was refused it. Turning the switch back on does not bring it
                // back, because the scene it describes has gone.
                resolved += cue.id
                continue
            }
            // After the switch, so a kind the viewer turned off stays silent rather
            // than being reported as refused. Before assessment, so a cue that would
            // have fitted is dropped for the reason it actually lost its moment.
            val assessment = if (yielded) Assessment.Drop(DropReason.YieldedToExternalVoice) else assess(cue, positionMs, length)
            when (assessment) {
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

        // Every due cue was dropped above, so only the disclosure is left to hold back.
        if (yielded) return drops ?: emptyList()

        // The floor is free for a scheduled start only once the gap since it was last
        // released has run. Admission alone waits: every cue above was assessed at
        // this position regardless, so one the gap squeezes out is dropped on the
        // ordinary reasons, and one it merely delays is judged again, afresh, at the
        // position it actually starts at.
        val released = releasedAtPositionMs
        if (released != null && positionMs < released + config.interUtteranceGapMs) {
            return drops ?: emptyList()
        }

        // Rule 3's first clause. Taken before the cue contest's winner, and outside
        // it: the cues above were all assessed regardless, so one that loses the
        // floor to the disclosure waits exactly as it would behind any voice. Except
        // to a subtitle that is due and fits, whose booking has already begun.
        if (active == null && best?.cue?.kind != UtteranceKind.Scheduled.SPOKEN_SUBTITLE) {
            active = disclosureThatFits(nowMs, positionMs, nextBookingMs)
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
     * a cue behind it -- a booked subtitle, or for a description any cue; what is
     * left of *that* at [positionMs] is all the room it will ever have, and the
     * room only shrinks. A room that ends at a booking is reported as that when the
     * cue does not fit it.
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
    private fun assess(cue: Cue, positionMs: Long, length: DescriptionLength): Assessment {
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

        if (variantIndex >= 0) {
            // Short asks for the last variant. If any variant fits, the last one does,
            // since it is the shortest -- so the fit is settled above and only the choice
            // changes here, and a cue is dropped under Short exactly when it is under Long.
            val short = length == DescriptionLength.SHORT && cue.kind == UtteranceKind.Scheduled.AUDIO_DESCRIPTION
            return Assessment.Fits(if (short) cue.variants.lastIndex else variantIndex, windowEndMs, roomEndMs)
        }

        val booking = track.rooms.bookingThatEndsRoomOf(cue.id)
        return Assessment.Drop(
            if (booking != null) {
                DropReason.RunsIntoBookedSubtitle(
                    subtitleCueId = booking,
                    subtitleStartMs = roomEndMs,
                    shortestVariantMs = cue.variants.last().measuredDuration.millis,
                    availableMs = availableMs,
                    guardMs = config.guardMs,
                    floorHeldBy = active?.kind,
                )
            } else {
                DropReason.NoVariantFits(
                    shortestVariantMs = cue.variants.last().measuredDuration.millis,
                    availableMs = availableMs,
                    guardMs = config.guardMs,
                    floorHeldBy = active?.kind,
                    nextCueStartMs = roomEndMs.takeIf { it < windowEndMs },
                )
            },
        )
    }

    /** Rule 3, then earliest, then id -- so the choice never depends on iteration luck. */
    private fun outranks(challenger: Cue, incumbent: Cue): Boolean {
        if (challenger.kind.priority != incumbent.kind.priority) {
            return challenger.kind.priority < incumbent.kind.priority
        }
        if (challenger.startMs != incumbent.startMs) return challenger.startMs < incumbent.startMs
        return challenger.id.value < incumbent.id.value
    }

    /**
     * The disclosure, if the session still owes it and the silence at [positionMs]
     * can hold it.
     *
     * The eligibility rule with its cue-specific halves taken out. Bound to the
     * window the playback position is in, not to one it was written for, because it
     * was not written for a moment. Bound to that window's end or [nextBookingMs],
     * whichever is sooner, and not to a description's room, because it neither
     * reserves room nor honours the room descriptions reserve, but does honour a
     * subtitle booking: see the class doc. And not a drop when it does not fit --
     * the debt belongs to the session, so it waits for a silence that does.
     */
    private fun disclosureThatFits(nowMs: Long, positionMs: Long, nextBookingMs: Long): Speaking.ScheduledUtterance? {
        val variant = disclosure?.takeIf { disclosureOwed } ?: return null
        val windowEndMs = track.silence.windowEndAt(positionMs) ?: return null
        val roomEndMs = minOf(windowEndMs, nextBookingMs)
        if (variant.measuredDuration.millis + config.guardMs > roomEndMs - positionMs) return null
        return Speaking.ScheduledUtterance(
            id = DISCLOSURE_ID,
            kind = UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE,
            text = variant.text,
            audioRef = variant.audioRef,
            startedAtMs = nowMs,
            cueId = null,
            variantIndex = 0,
            duration = variant.measuredDuration,
            startedAtPositionMs = positionMs,
            windowEndMs = windowEndMs,
            roomEndMs = roomEndMs,
        )
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

    companion object {
        /**
         * The disclosure's utterance id. Cue utterances are all `cue:`-prefixed, so it
         * cannot be mistaken for one; a restart after an interruption is a new
         * instance by [Speaking.Utterance.startedAtMs], exactly as a replayed cue is.
         */
        val DISCLOSURE_ID: UtteranceId = UtteranceId("disclosure")
    }
}
