package dev.baton.director

import io.kotest.assertions.withClue
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll

/**
 * The product's central claim, tested rather than asserted.
 *
 * "Exactly one voice at a time" has two halves. The easy half -- never two of
 * *our* voices -- is settled by the type: a [Directive] has room for one
 * utterance and no representation of two. The half that has to be proven is that
 * a scheduled voice never lands on top of the voice already in the film. Every
 * scenario is driven end to end and checked at every tick.
 *
 * The timelines come from [scenarios] and are deliberately unpleasant. What makes
 * this suite worth trusting is that it also asserts they *reach* the hard states,
 * so it cannot pass by never testing anything.
 */
class AudioDirectorInvariantTest : FunSpec({

    test("no two voices are ever active at once, across randomized timelines") {
        checkAll(1_000, scenarios) { scenario ->
            drive(scenario)
        }
    }

    test("the same holds when the guard, the duck and the backstop are configured differently") {
        checkAll(300, scenarios) { scenario ->
            drive(scenario, IMPATIENT)
        }
    }

    /**
     * A property test that never reaches the interesting states proves nothing.
     * This asserts the generators keep producing them, so the invariant suite
     * cannot quietly go vacuous when the generators are edited later.
     *
     * Its draws are pinned, and the two tests above are not. This one asks a
     * question about the generators rather than the director, and a fresh draw adds
     * nothing to the answer but noise: the rarest cases here are reached ten or
     * fifteen times in eight hundred scenarios, so any floor worth having sits
     * inside the range an unlucky draw can fall to. Pinned, it fails when a change
     * to the generators or the director moves what is reached, and only then.
     */
    test("the generated timelines actually exercise the cases they claim to") {
        val seen = Coverage()
        checkAll(500, seeded(1), scenarios) { scenario -> drive(scenario, coverage = seen) }
        checkAll(300, seeded(2), scenarios) { scenario -> drive(scenario, IMPATIENT, coverage = seen) }

        // Thresholds sit at about a third of the median over 360 unpinned draws,
        // and below all but the lowest one percent of them, so an edit that
        // re-rolls the draws does not trip one by luck and an edit that stops
        // reaching a case still does.
        withClue(seen.toString()) {
            (seen.ticks > 12_000) shouldBe true
            (seen.dialogueTicks > 7_200) shouldBe true
            (seen.scheduledSpeaking > 720) shouldBe true
            (seen.reactiveSpeaking > 3_700) shouldBe true
            (seen.dropsNoVariantFits > 230) shouldBe true
            (seen.dropsNoSilenceAtCueStart > 110) shouldBe true
            (seen.dropsNoVariants > 130) shouldBe true
            (seen.reactivePreemptedScheduled > 68) shouldBe true
            (seen.seekDuringSpeech > 310) shouldBe true
            (seen.pauseDuringSpeech > 350) shouldBe true
            (seen.speakerEndedSpeech > 520) shouldBe true
            (seen.cueReplayedAfterSeek > 22) shouldBe true
            (seen.overranMeasuredLength > 50) shouldBe true
            // All three withdrawal reasons, and at least some of them while the
            // content is stalled -- the case the two clocks were separated for.
            (seen.withdrawnOutOfSilence > 5) shouldBe true
            (seen.withdrawnSpeakerOverdue > 13) shouldBe true
            (seen.withdrawnNeverMeasured > 7) shouldBe true
            (seen.withdrawnDuringStall > 4) shouldBe true
            (seen.stallDuringSpeech > 290) shouldBe true
            (seen.stallAnomalies > 160) shouldBe true
            // The disclosure: spoken, interrupted and spoken again, withdrawn and
            // therefore discharged, and -- the case the "goes first" check reads
            // -- cues starting while it was still owed but did not fit.
            (seen.disclosureStarted > 95) shouldBe true
            (seen.disclosureRestarted > 57) shouldBe true
            (seen.disclosureWithdrawn > 3) shouldBe true
            (seen.cueStartedWhileDisclosureOwed > 48) shouldBe true
            // Subtitle bookings contending with descriptions (issue #5): a description
            // that gave way to a booking, a subtitle whose room a description written
            // inside it did not shorten, and a disclosure that fitted the silence but
            // yielded to a booking. Same derivation, over the director that has them.
            (seen.descriptionsDroppedForBooking > 42) shouldBe true
            (seen.subtitleKeptRoomPastDescription > 22) shouldBe true
            (seen.disclosureYieldedToBooking > 23) shouldBe true
            // Another app's voice (FRICTION_LOG.md entry 19): ours cut under it, cues
            // coming due inside it, and presses refused there. About a third of the
            // pinned draw, which is all these have so far.
            (seen.cutForExternalVoice > 85) shouldBe true
            (seen.dropsYieldedToExternalVoice > 75) shouldBe true
            (seen.reactiveRefused > 150) shouldBe true
        }
    }
})

// ---------------------------------------------------------------------------
// Driving
// ---------------------------------------------------------------------------

/**
 * Every number moved off its default, and the three thresholds moved a long way:
 * a generated timeline is a few seconds of content assembled from steps of about
 * a tenth of a second, so a two-second slack would put the withdrawal path almost
 * out of reach, a fifteen-second ceiling would put it entirely out of reach, and a
 * one-second stall threshold would put the stall report there too. The coverage
 * guard would then be certifying cases the suite hardly reaches.
 */
private val IMPATIENT = DirectorConfig(
    guardMs = 0,
    interUtteranceGapMs = 120,
    duckDb = -6.0,
    speechTimeoutSlackMs = 300,
    unmeasuredSpeechCeilingMs = 900,
    stallAnomalyMs = 50,
)

/** Opted in here, once: kotest marks the [PropTestConfig] constructor experimental. */
@OptIn(ExperimentalKotest::class)
private fun seeded(seed: Long) = PropTestConfig(seed = seed)

private fun drive(
    scenario: Scenario,
    config: DirectorConfig = DirectorConfig(),
    coverage: Coverage = Coverage(),
) {
    val director = AudioDirector(scenario.track, config, scenario.disclosure)
    val invariants = Invariants(scenario.track, config, scenario.disclosure)
    val speaker = FakeSpeaker()

    var nowMs = 0L
    var positionMs = 0L
    var playback = Playback.PLAYING
    var previous: Speaking = Speaking.Silent
    val starts = mutableMapOf<UtteranceId, Long>()

    scenario.steps.forEachIndexed { index, step ->
        nowMs += step.dtMs
        // The content clock moves only while frames do, which is what makes a
        // stall and a pause indistinguishable to anything downstream of it.
        if (playback == Playback.PLAYING) positionMs += step.dtMs

        val requests = step.reactives.map { InputEvent.ReactiveRequested(it) }
        val events = mutableListOf<InputEvent>()
        if (step.reactiveBeforeSeek) events += requests
        step.seek?.let { seek ->
            positionMs = when (seek) {
                is Seek.To -> seek.positionMs
                is Seek.Back -> (positionMs - seek.byMs).coerceAtLeast(0L)
            }
            events += InputEvent.Seeked
        }
        if (!step.reactiveBeforeSeek) events += requests
        speaker.report(previous as? Speaking.Utterance, step, nowMs, positionMs)?.let { events += it }
        if (step.reportPhantomEnd) events += InputEvent.SpeechEnded(UtteranceId("phantom"))

        val previousPlayback = playback
        playback = step.playback

        val directive = director.advance(
            nowMs,
            DirectorInputs(positionMs, playback, events, externalVoice = step.externalVoice),
        )
        invariants.check(index, nowMs, positionMs, playback, events, directive, step.externalVoice)
        coverage.record(
            scenario.track, previous, directive, positionMs, events, previousPlayback, playback, starts,
            externalVoice = step.externalVoice,
            disclosureOwed = invariants.disclosureOwed,
            disclosure = scenario.disclosure,
            guardMs = config.guardMs,
        )
        previous = directive.speaking
    }
}

/**
 * A speaker with three behaviours: it usually reports the moment the audio it was
 * handed runs out, it sometimes reports early, and it sometimes takes an utterance
 * and is never heard from again.
 *
 * The last one is the reason the director waits to be told rather than working the
 * ending out for itself, so the generated runs have to contain it.
 */
private class FakeSpeaker {
    private val seen = mutableSetOf<Pair<String, Long>>()
    private val stalled = mutableSetOf<Pair<String, Long>>()

    fun report(holder: Speaking.Utterance?, step: Step, nowMs: Long, positionMs: Long): InputEvent.SpeechEnded? {
        if (holder == null) return null
        val instance = holder.id.value to holder.startedAtMs
        if (seen.add(instance) && step.speakerStalls) stalled += instance
        if (instance in stalled) return null
        if (step.reportSpeechEnded) return InputEvent.SpeechEnded(holder.id)

        val runOut = when (holder) {
            is Speaking.ScheduledUtterance -> positionMs >= holder.endsAtPositionMs
            is Speaking.ReactiveUtterance -> holder.endsAtMs?.let { nowMs >= it } ?: false
        }
        return if (runOut) InputEvent.SpeechEnded(holder.id) else null
    }
}

/** Tallies which shapes the generated timelines actually reached. */
private class Coverage {
    var ticks = 0
    var dialogueTicks = 0
    var scheduledSpeaking = 0
    var reactiveSpeaking = 0
    var dropsNoVariantFits = 0
    var dropsNoSilenceAtCueStart = 0
    var dropsNoVariants = 0
    var reactivePreemptedScheduled = 0
    var seekDuringSpeech = 0
    var pauseDuringSpeech = 0
    var speakerEndedSpeech = 0
    var cueReplayedAfterSeek = 0
    var overranMeasuredLength = 0
    var withdrawnOutOfSilence = 0
    var withdrawnSpeakerOverdue = 0
    var withdrawnNeverMeasured = 0
    var withdrawnDuringStall = 0
    var stallDuringSpeech = 0
    var stallAnomalies = 0
    var disclosureStarted = 0
    var disclosureRestarted = 0
    var disclosureWithdrawn = 0
    var cueStartedWhileDisclosureOwed = 0
    var descriptionsDroppedForBooking = 0
    var subtitleKeptRoomPastDescription = 0
    var disclosureYieldedToBooking = 0
    var cutForExternalVoice = 0
    var dropsYieldedToExternalVoice = 0
    var reactiveRefused = 0

    fun record(
        track: DescriptionTrack,
        previous: Speaking,
        directive: Directive,
        positionMs: Long,
        events: List<InputEvent>,
        previousPlayback: Playback,
        playback: Playback,
        starts: MutableMap<UtteranceId, Long>,
        externalVoice: Boolean,
        disclosureOwed: Boolean,
        disclosure: Variant?,
        guardMs: Long,
    ) {
        ticks++
        if (!track.silence.isSilentAt(positionMs)) dialogueTicks++

        when (val speaking = directive.speaking) {
            is Speaking.Silent, is Speaking.Withdrawn -> Unit
            is Speaking.ReactiveUtterance -> {
                reactiveSpeaking++
                if (previous is Speaking.ScheduledUtterance) reactivePreemptedScheduled++
            }

            is Speaking.ScheduledUtterance -> {
                scheduledSpeaking++
                if (positionMs >= speaking.endsAtPositionMs) overranMeasuredLength++
                val disclosing = speaking.cueId == null
                val firstStart = starts[speaking.id]
                val started = firstStart != speaking.startedAtMs
                if (firstStart == null) {
                    if (disclosing) disclosureStarted++
                } else if (started) {
                    if (disclosing) disclosureRestarted++ else cueReplayedAfterSeek++
                }
                if (started && !disclosing && disclosureOwed) {
                    cueStartedWhileDisclosureOwed++
                    // The disclosure fitted the silence, so only a booking held it back.
                    val windowEndMs = track.silence.windowEndAt(positionMs)
                    if (disclosure != null && windowEndMs != null &&
                        disclosure.measuredDuration.millis + guardMs <= windowEndMs - positionMs
                    ) {
                        disclosureYieldedToBooking++
                    }
                }
                // A subtitle granted room past a description written after its start:
                // the description did not shorten the booking.
                if (started && speaking.kind == UtteranceKind.Scheduled.SPOKEN_SUBTITLE) {
                    val ownStartMs = track.cues.single { it.id == speaking.cueId }.startMs
                    val overDescription = track.cues.any {
                        it.kind == UtteranceKind.Scheduled.AUDIO_DESCRIPTION &&
                            it.startMs > ownStartMs && it.startMs < speaking.roomEndMs
                    }
                    if (overDescription) subtitleKeptRoomPastDescription++
                }
                starts[speaking.id] = speaking.startedAtMs
            }
        }

        directive.drops.forEach {
            when (it.reason) {
                is DropReason.NoVariantFits -> dropsNoVariantFits++
                is DropReason.RunsIntoBookedSubtitle ->
                    if (it.kind == UtteranceKind.Scheduled.AUDIO_DESCRIPTION) descriptionsDroppedForBooking++
                DropReason.NoSilenceAtCueStart -> dropsNoSilenceAtCueStart++
                DropReason.NoVariants -> dropsNoVariants++
                DropReason.YieldedToExternalVoice -> dropsYieldedToExternalVoice++
            }
        }

        directive.withdrawal?.let {
            when (it.reason) {
                is WithdrawalReason.OutOfSilence -> withdrawnOutOfSilence++
                is WithdrawalReason.SpeakerOverdue -> withdrawnSpeakerOverdue++
                is WithdrawalReason.NeverMeasured -> withdrawnNeverMeasured++
            }
            if (playback == Playback.STALLED) withdrawnDuringStall++
            if (it.kind == UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE) disclosureWithdrawn++
        }
        if (directive.stall != null) stallAnomalies++
        reactiveRefused += directive.refused.size

        if (previous is Speaking.Utterance) {
            if (events.any { it is InputEvent.Seeked }) seekDuringSpeech++
            if (previousPlayback != Playback.PAUSED && playback == Playback.PAUSED) pauseDuringSpeech++
            if (previousPlayback == Playback.PLAYING && playback == Playback.STALLED) stallDuringSpeech++
            if (events.any { it is InputEvent.SpeechEnded && it.id == previous.id }) speakerEndedSpeech++
            if (externalVoice) cutForExternalVoice++
        }
    }

    override fun toString(): String = "Coverage(ticks=$ticks dialogueTicks=$dialogueTicks " +
        "scheduled=$scheduledSpeaking reactive=$reactiveSpeaking " +
        "noFit=$dropsNoVariantFits noSilence=$dropsNoSilenceAtCueStart noVariants=$dropsNoVariants " +
        "preempted=$reactivePreemptedScheduled seekDuringSpeech=$seekDuringSpeech " +
        "pauseDuringSpeech=$pauseDuringSpeech stallDuringSpeech=$stallDuringSpeech " +
        "speakerEnded=$speakerEndedSpeech " +
        "replayed=$cueReplayedAfterSeek overran=$overranMeasuredLength " +
        "outOfSilence=$withdrawnOutOfSilence overdue=$withdrawnSpeakerOverdue " +
        "neverMeasured=$withdrawnNeverMeasured withdrawnDuringStall=$withdrawnDuringStall " +
        "stallAnomalies=$stallAnomalies disclosureStarted=$disclosureStarted " +
        "disclosureRestarted=$disclosureRestarted disclosureWithdrawn=$disclosureWithdrawn " +
        "cueStartedWhileDisclosureOwed=$cueStartedWhileDisclosureOwed " +
        "descriptionsDroppedForBooking=$descriptionsDroppedForBooking " +
        "subtitleKeptRoomPastDescription=$subtitleKeptRoomPastDescription " +
        "disclosureYieldedToBooking=$disclosureYieldedToBooking " +
        "cutForExternalVoice=$cutForExternalVoice dropsYieldedToExternalVoice=$dropsYieldedToExternalVoice " +
        "reactiveRefused=$reactiveRefused)"
}

/**
 * Everything that must be true of the directive stream. Reads only the
 * [Directive]s and the inputs that produced them; knows nothing about how the
 * director is built.
 */
private class Invariants(
    private val track: DescriptionTrack,
    private val config: DirectorConfig,
    private val disclosure: Variant?,
) {
    /**
     * The session's debt, reconstructed from the outside: owed by a synthetic track
     * that was handed a disclosure, and cleared by a speaker report naming it or by
     * a withdrawal of it -- both of which are visible in the stream. See
     * [checkDisclosure].
     */
    var disclosureOwed = track.synthetic && disclosure != null
        private set

    private var previous: Directive? = null
    private var previousPlayback = Playback.PLAYING
    private val issued = mutableMapOf<UtteranceId, ReactiveRequest>()
    private val refused = mutableSetOf<UtteranceId>()

    /** Cue -> the wall clock reading at which it began, since the last seek. */
    private val spokenSinceSeek = mutableMapOf<CueId, Long>()
    private val droppedSinceSeek = mutableSetOf<CueId>()

    /**
     * Where the director last rebaselined: the first tick, and every seek. Cues that
     * start before it were passed over without a report, so they are not still due.
     */
    private var baselinePositionMs = 0L
    private var ticked = false

    /** Content position of the last floor release seen in the stream since the last seek. */
    private var releasedAtPositionMs: Long? = null

    fun check(
        step: Int,
        nowMs: Long,
        positionMs: Long,
        playback: Playback,
        events: List<InputEvent>,
        directive: Directive,
        externalVoice: Boolean,
    ) {
        events.filterIsInstance<InputEvent.ReactiveRequested>().forEach { issued[it.request.id] = it.request }
        val seeked = events.any { it is InputEvent.Seeked }
        if (seeked) {
            spokenSinceSeek.clear()
            droppedSinceSeek.clear()
        }
        if (seeked || !ticked) baselinePositionMs = positionMs
        ticked = true

        val clue = "step $step: now=$nowMs position=$positionMs playback=$playback " +
            "events=$events directive=$directive track=$track"

        withClue(clue) {
            checkDuck(directive)
            checkDrops(directive, externalVoice)
            checkExternalVoice(directive, events, externalVoice)
            when (val speaking = directive.speaking) {
                is Speaking.Silent, is Speaking.Withdrawn -> Unit
                is Speaking.ScheduledUtterance -> checkScheduled(speaking, positionMs, playback)
                is Speaking.ReactiveUtterance -> checkReactive(speaking)
            }
            checkWallClockCeiling(directive, nowMs)
            checkWithdrawal(directive)
            checkStall(directive, playback)
            checkNobodyWasCutOff(directive, playback, events, externalVoice)
            checkSeekLeavesSilence(directive, events)
            checkDisclosure(directive, positionMs, events)
            checkBookings(directive)
            checkInterUtteranceGap(directive, positionMs, seeked)
        }
        // After the checks: the tick that withdraws the disclosure names nothing, so
        // it is the following ticks that must not name it again.
        if (directive.withdrawal?.id == AudioDirector.DISCLOSURE_ID) disclosureOwed = false

        previous = directive
        previousPlayback = playback
    }

    /**
     * The content is attenuated exactly when a non-content voice holds the floor.
     *
     * A withdrawal counts as nobody holding it. The utterance being cut is on its
     * way out, so the bed comes back on the same tick the speaker is told to stop
     * -- exactly as it does when the viewer pauses.
     */
    private fun checkDuck(directive: Directive) {
        val expected = when (directive.speaking) {
            is Speaking.Silent, is Speaking.Withdrawn -> ContentDuck.Unity
            is Speaking.Utterance -> ContentDuck.Ducked(config.duckDb)
        }
        withClue("duck gain must be non-zero only while a voice is active") {
            directive.contentDuck shouldBe expected
        }
    }

    private fun checkDrops(directive: Directive, externalVoice: Boolean) {
        directive.drops.forEach { drop ->
            withClue("a cue was dropped for another app's voice while none had the floor") {
                (drop.reason != DropReason.YieldedToExternalVoice || externalVoice) shouldBe true
            }
            val cue = track.cues.singleOrNull { it.id == drop.cueId }
            withClue("a drop names a cue that is not in the track") { (cue != null) shouldBe true }
            withClue("a cue that already spoke was later reported as dropped") {
                spokenSinceSeek.containsKey(drop.cueId) shouldBe false
            }
            withClue("a cue was reported as dropped more than once") {
                droppedSinceSeek.add(drop.cueId) shouldBe true
            }
            val reason = drop.reason
            if (reason is DropReason.NoVariantFits) {
                withClue("a drop reason must report the guard actually applied") {
                    reason.guardMs shouldBe config.guardMs
                }
            }
            if (reason is DropReason.RunsIntoBookedSubtitle) {
                withClue("a drop reason must report the guard actually applied") {
                    reason.guardMs shouldBe config.guardMs
                }
                val booking = track.cues.singleOrNull { it.id == reason.subtitleCueId }
                withClue("a booking drop must name a subtitle booked later in the same silence") {
                    booking?.kind shouldBe UtteranceKind.Scheduled.SPOKEN_SUBTITLE
                    booking?.startMs shouldBe reason.subtitleStartMs
                    (reason.subtitleStartMs > drop.cueStartMs) shouldBe true
                    track.silence.windowEndAt(reason.subtitleStartMs) shouldBe track.silence.windowEndAt(drop.cueStartMs)
                }
            }
        }
    }

    private fun checkScheduled(speaking: Speaking.ScheduledUtterance, positionMs: Long, playback: Playback) {
        // Paused, and not merely "not playing". A description speaks straight
        // through a stall on purpose: its audio comes from a local file that did
        // not stall with the video, and stopping it would cut the sentence in
        // half for an interruption nobody chose.
        withClue("a scheduled utterance may not run while the content is paused") {
            playback shouldNotBe Playback.PAUSED
        }

        val cueId = speaking.cueId
        if (cueId == null) {
            withClue("the disclosure must quote the sentence it was handed") {
                speaking.kind shouldBe UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE
                speaking.text shouldBe disclosure?.text
                speaking.duration shouldBe disclosure?.measuredDuration
            }
        } else {
            val cue = track.cues.single { it.id == cueId }
            withClue("a scheduled utterance must quote its cue faithfully") {
                speaking.kind shouldBe cue.kind
                (speaking.variantIndex in cue.variants.indices) shouldBe true
                speaking.text shouldBe cue.variants[speaking.variantIndex].text
                speaking.duration shouldBe cue.variants[speaking.variantIndex].measuredDuration
            }
        }

        val grantedWindowEnd = track.silence.windowEndAt(speaking.startedAtPositionMs)
        withClue("a scheduled utterance started outside any silence window") {
            (grantedWindowEnd != null) shouldBe true
        }
        withClue("a scheduled utterance was granted room it does not have") {
            (speaking.endsAtPositionMs + config.guardMs <= grantedWindowEnd!!) shouldBe true
        }
        withClue("a scheduled utterance disagrees with the track about the room it was granted") {
            speaking.windowEndMs shouldBe grantedWindowEnd
        }

        // THE invariant: the film's own dialogue is a voice, and nothing of ours
        // is ever heard on top of it.
        //
        // Unconditional, and it did not used to be. It was once scoped to the
        // measured length, because past that the director could observe an overrun
        // and not act on one: un-naming the utterance released the floor rather
        // than silencing the speaker, so it would have added a second voice to the
        // overrun instead of ending it. The floor can be withdrawn now, so there
        // is no longer any interval in which a scheduled voice is named over
        // dialogue and that is somehow acceptable.
        withClue("a scheduled utterance is speaking over content dialogue") {
            track.silence.isSilentAt(positionMs) shouldBe true
        }
        withClue("a scheduled utterance drifted out of the window it was granted") {
            track.silence.windowEndAt(positionMs) shouldBe grantedWindowEnd
        }

        withClue("a scheduled utterance is still named past the deadline drawn on the content clock") {
            (positionMs >= speaking.startedAtPositionMs) shouldBe true
            val deadlineMs = minOf(
                speaking.endsAtPositionMs + config.speechTimeoutSlackMs,
                speaking.windowEndMs,
            )
            (positionMs < deadlineMs) shouldBe true
        }
        // The rest is about cues. The disclosure's own once-only rule is not keyed to
        // seeks at all, and lives in checkDisclosure.
        if (cueId == null) return
        withClue("a cue that was reported as dropped is speaking anyway") {
            droppedSinceSeek.contains(cueId) shouldBe false
        }

        val firstStart = spokenSinceSeek[cueId]
        if (firstStart == null) {
            spokenSinceSeek[cueId] = speaking.startedAtMs
        } else {
            withClue("a cue started a second time without an intervening seek") {
                speaking.startedAtMs shouldBe firstStart
            }
        }
    }

    /**
     * The disclosure is spoken only while it is owed, and while it is owed it goes
     * first.
     *
     * "Only while owed" is the once-per-session rule stated so that interruptions
     * are allowed for: a disclosure cut off by a seek, a pause or a button press may
     * start again, but one the speaker reported finishing, or one withdrawn at a
     * deadline, may never be named again -- and one a track that is not synthetic
     * never owed may never be named at all. The rest of what a scheduled voice owes
     * the dialogue, it owes through [checkScheduled] like any cue.
     *
     * "Goes first" is checked where it can be broken: on the tick a cue starts. If
     * the disclosure was still owed then, it must not have fitted the silence at
     * that position up to the next booked subtitle, or the cue took a floor the
     * policy gave to the disclosure. The one cue it yields a fitting floor to is a
     * subtitle that is due, whose booking has already begun (issue #5).
     */
    private fun checkDisclosure(directive: Directive, positionMs: Long, events: List<InputEvent>) {
        if (events.any { it is InputEvent.SpeechEnded && it.id == AudioDirector.DISCLOSURE_ID }) {
            disclosureOwed = false
        }
        val speaking = directive.speaking as? Speaking.ScheduledUtterance ?: return
        if (speaking.cueId == null) {
            withClue("the disclosure was named when nothing was owed: already heard, or never synthetic") {
                disclosureOwed shouldBe true
            }
            return
        }

        val before = previous?.speaking as? Speaking.Utterance
        val cueStarted = before == null || before.id != speaking.id || before.startedAtMs != speaking.startedAtMs
        val owed = disclosure?.takeIf { disclosureOwed && cueStarted } ?: return
        if (speaking.kind == UtteranceKind.Scheduled.SPOKEN_SUBTITLE) return
        val windowEndMs = track.silence.windowEndAt(positionMs) ?: return
        val roomEndMs = minOf(windowEndMs, nextBookingAfter(positionMs))
        withClue("a cue took a silence that the disclosure was owed and fitted") {
            (owed.measuredDuration.millis + config.guardMs > roomEndMs - positionMs) shouldBe true
        }
    }

    /**
     * No scheduled utterance ever overlaps a booked subtitle: issue #5's property.
     *
     * Checked on the tick an utterance starts, because admission is where the
     * promise is made, and in two halves. It must finish, plus the guard, before
     * every subtitle booked ahead of where it starts. And it must not take the floor
     * while a subtitle it did not start with is already due and has neither spoken
     * nor been dropped -- that booking has begun, and a voice starting inside it is
     * the collision issue #5 was opened for, arriving late rather than early.
     *
     * Two subtitles booked at the same instant are exempt from the second half.
     * Both cannot have that moment; the arbitration's tie-break gives it to one, and
     * the other takes what is left, exactly as a description sharing a beat does.
     *
     * What is deliberately not claimed: that an utterance which then overruns its
     * measured length -- a speaker that stalls -- is cut at the booking. A booking
     * binds admission, like every room; see the AudioDirector class doc.
     */
    private fun checkBookings(directive: Directive) {
        val speaking = directive.speaking as? Speaking.ScheduledUtterance ?: return
        val before = previous?.speaking as? Speaking.Utterance
        if (before != null && before.id == speaking.id && before.startedAtMs == speaking.startedAtMs) return

        val ownStartMs = speaking.cueId?.let { id -> track.cues.single { it.id == id }.startMs }
        for (booking in track.cues) {
            if (booking.kind != UtteranceKind.Scheduled.SPOKEN_SUBTITLE || booking.id == speaking.cueId) continue
            if (booking.startMs > speaking.startedAtPositionMs) {
                withClue("${speaking.id} was admitted to run into subtitle ${booking.id}, booked at ${booking.startMs}") {
                    (speaking.endsAtPositionMs + config.guardMs <= booking.startMs) shouldBe true
                }
                continue
            }
            val stillDue = booking.startMs >= baselinePositionMs &&
                booking.id !in spokenSinceSeek &&
                booking.id !in droppedSinceSeek
            val doubleBooked = speaking.kind == UtteranceKind.Scheduled.SPOKEN_SUBTITLE && booking.startMs == ownStartMs
            if (doubleBooked) continue
            withClue("${speaking.id} took the floor from subtitle ${booking.id}, due at ${booking.startMs} and still unspoken") {
                stillDue shouldBe false
            }
        }
    }

    /**
     * Every scheduled start is at least [DirectorConfig.interUtteranceGapMs] of
     * content after the previous floor release, unless a seek came in between.
     *
     * Read off the stream: the floor is released on any tick where the utterance
     * named last tick is no longer the one named, a preemption included. Counting a
     * preemption is stricter than the director needs -- a reactive voice that takes
     * the floor holds it until its own release, which then replaces this one -- and
     * it means a scheduled voice handed straight to another is caught too.
     */
    private fun checkInterUtteranceGap(directive: Directive, positionMs: Long, seeked: Boolean) {
        val before = previous?.speaking as? Speaking.Utterance
        val after = directive.speaking as? Speaking.Utterance
        val sameInstance = before != null && after != null &&
            before.id == after.id && before.startedAtMs == after.startedAtMs
        if (seeked) {
            releasedAtPositionMs = null
        } else if (before != null && !sameInstance) {
            releasedAtPositionMs = positionMs
        }

        if (after !is Speaking.ScheduledUtterance || sameInstance) return
        val released = releasedAtPositionMs ?: return
        withClue("${after.id} started ${positionMs - released} ms after the floor was released at $released") {
            (positionMs >= released + config.interUtteranceGapMs) shouldBe true
        }
    }

    private fun nextBookingAfter(positionMs: Long): Long = track.cues
        .filter { it.kind == UtteranceKind.Scheduled.SPOKEN_SUBTITLE && it.startMs > positionMs }
        .minOfOrNull { it.startMs } ?: Long.MAX_VALUE

    /**
     * While another app's voice has the floor, none of ours does: nothing is named,
     * every press on this tick is refused and reported, and none is refused
     * otherwise. A refused press is never answered later -- [checkReactive] holds
     * that half, since the refusal is recorded here first.
     */
    private fun checkExternalVoice(directive: Directive, events: List<InputEvent>, externalVoice: Boolean) {
        val requested = events.filterIsInstance<InputEvent.ReactiveRequested>().map { it.request }
        if (externalVoice) {
            withClue("one of our voices was named while another app's voice had the floor") {
                (directive.speaking as? Speaking.Utterance) shouldBe null
            }
            withClue("a press made while yielded was not refused") { directive.refused shouldBe requested }
        } else {
            withClue("a press was refused while no other voice had the floor") { directive.refused shouldBe emptyList() }
        }
        refused += directive.refused.map { it.id }
    }

    private fun checkReactive(speaking: Speaking.ReactiveUtterance) {
        withClue("a refused press was answered after all") { (speaking.id in refused) shouldBe false }
        val request = issued[speaking.id]
        withClue("a reactive utterance nobody asked for") { (request != null) shouldBe true }
        requireNotNull(request)
        withClue("a reactive utterance must quote the request faithfully") {
            speaking.kind shouldBe request.kind
            speaking.text shouldBe request.text
            speaking.length shouldBe request.length
        }
    }

    /**
     * Every utterance has a ceiling on the wall clock, measured or not, and that
     * clock is never frozen -- so nothing can hold the floor indefinitely,
     * whatever the content is doing underneath it. Before this existed, speech
     * with no measured length had no bound at all.
     */
    private fun checkWallClockCeiling(directive: Directive, nowMs: Long) {
        val speaking = directive.speaking as? Speaking.Utterance ?: return
        val ceilingMs = speaking.measuredDurationMs
            ?.plus(config.speechTimeoutSlackMs)
            ?: config.unmeasuredSpeechCeilingMs
        withClue("an utterance is still named past its wall-clock ceiling") {
            (nowMs < speaking.startedAtMs + ceilingMs) shouldBe true
        }
    }

    /**
     * A withdrawal is an accusation against the speaker *and* an instruction to
     * cut it off, so both halves have to be earned. It may only name the utterance
     * that actually held the floor; the deadline it claims expired must genuinely
     * have expired; and -- the half that is new -- the directive carrying it may
     * not name any utterance at all, so the caller cannot be asked to start one
     * voice while it is still stopping another.
     */
    private fun checkWithdrawal(directive: Directive) {
        val withdrawal = directive.withdrawal ?: return
        val holder = previous?.speaking as? Speaking.Utterance
        withClue("a withdrawal names an utterance that was not holding the floor") {
            (holder != null && holder.id == withdrawal.id) shouldBe true
        }
        withClue("a withdrawal must report the numbers actually applied") {
            when (val reason = withdrawal.reason) {
                is WithdrawalReason.OutOfSilence ->
                    reason.windowEndMs shouldBe (holder as Speaking.ScheduledUtterance).windowEndMs

                is WithdrawalReason.SpeakerOverdue -> reason.slackMs shouldBe config.speechTimeoutSlackMs
                is WithdrawalReason.NeverMeasured -> reason.ceilingMs shouldBe config.unmeasuredSpeechCeilingMs
            }
        }
        withdrawal.measuredDurationMs?.let { measuredMs ->
            // The property that makes truncation the cheap option: a cut can only
            // ever reach the part of an utterance that was already overdue.
            withClue("a withdrawal cut into the length the utterance was admitted on") {
                (withdrawal.heldForMs >= measuredMs) shouldBe true
            }
        }
        // The half that stops the caller being asked to do two things at once. It
        // holds today because a withdrawal and an utterance occupy the same slot,
        // and the assertion is here so that it goes on holding if the report is
        // ever moved out into a field of its own.
        withClue("a withdrawal tick named an utterance, so a voice would start over one being cut off") {
            (directive.speaking as? Speaking.Utterance) shouldBe null
        }
        withClue("a withdrawal misquotes the measurement of the utterance it cut off") {
            withdrawal.measuredDurationMs shouldBe holder?.measuredDurationMs
        }
    }

    /**
     * A stall report is an observation about the content and has to be earned the
     * way a [Withdrawal] does: only while the content is actually stalled, only
     * once it has lasted the configured threshold, and naming whoever the stall
     * is running underneath rather than whoever happens to be handy.
     */
    private fun checkStall(directive: Directive, playback: Playback) {
        val stall = directive.stall ?: return
        withClue("a stall was reported while the content was not stalled") {
            playback shouldBe Playback.STALLED
        }
        withClue("a stall report must carry the threshold actually applied") {
            stall.thresholdMs shouldBe config.stallAnomalyMs
        }
        withClue("a stall was reported before it was long enough to matter") {
            (stall.stalledForMs >= config.stallAnomalyMs) shouldBe true
        }
        withClue("a stall report names an utterance that is not the one holding the floor") {
            stall.floorHeldBy shouldBe (directive.speaking as? Speaking.Utterance)?.id
        }
    }

    /**
     * Whoever held the floor last tick either still holds it or was released for a
     * reason the policy allows. Nothing is ever cut off silently: that is the
     * failure that would put two voices in the room during the fade.
     *
     * Note what is *not* on the list of allowed causes: the utterance having run
     * the length the pipeline measured. That used to end it, and this is the test
     * that says it no longer may. Either the speaker reports, or the viewer
     * intervenes, or a deadline expires and the floor is withdrawn and said so.
     *
     * A stall is not on the list either, and that is the whole of issue 20 stated
     * as a property: if buffering ever released the floor again, every generated
     * timeline that stalls mid-utterance would fail here.
     */
    private fun checkNobodyWasCutOff(
        directive: Directive,
        playback: Playback,
        events: List<InputEvent>,
        externalVoice: Boolean,
    ) {
        val before = previous?.speaking as? Speaking.Utterance ?: return
        val after = directive.speaking
        val stillTheSame = after is Speaking.Utterance &&
            after.id == before.id &&
            after.startedAtMs == before.startedAtMs
        if (stillTheSame) return

        val preemptedByUser = events.any { it is InputEvent.ReactiveRequested }
        val timelineJumped = events.any { it is InputEvent.Seeked }
        val speakerReported = events.any { it is InputEvent.SpeechEnded && it.id == before.id }
        val pausedEdge = previousPlayback != Playback.PAUSED && playback == Playback.PAUSED
        val floorWithdrawn = directive.withdrawal?.id == before.id

        withClue("utterance ${before.id} stopped speaking with no cause the policy allows") {
            (preemptedByUser || timelineJumped || speakerReported || pausedEdge || floorWithdrawn || externalVoice) shouldBe true
        }
    }

    /** A seek clears the queue: the tick that carries one speaks nothing new. */
    private fun checkSeekLeavesSilence(directive: Directive, events: List<InputEvent>) {
        val lastSeek = events.indexOfLast { it is InputEvent.Seeked }
        if (lastSeek < 0) return
        val lastRequest = events.indexOfLast { it is InputEvent.ReactiveRequested }
        if (lastRequest > lastSeek) return
        withClue("a seek left something speaking") { directive.speaking shouldBe Speaking.Silent }
    }
}

