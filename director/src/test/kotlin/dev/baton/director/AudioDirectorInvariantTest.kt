package dev.baton.director

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
     */
    test("the generated timelines actually exercise the cases they claim to") {
        val seen = Coverage()
        checkAll(500, scenarios) { scenario -> drive(scenario, coverage = seen) }
        checkAll(300, scenarios) { scenario -> drive(scenario, IMPATIENT, coverage = seen) }

        // Thresholds sit at roughly a third of what the generators actually
        // produce, so they catch a generator that stops reaching a case without
        // flaking on seed-to-seed variance.
        withClue(seen.toString()) {
            (seen.ticks > 15_000) shouldBe true
            (seen.dialogueTicks > 5_000) shouldBe true
            (seen.scheduledSpeaking > 300) shouldBe true
            (seen.reactiveSpeaking > 1_000) shouldBe true
            (seen.dropsNoVariantFits > 80) shouldBe true
            (seen.dropsNoSilenceAtCueStart > 80) shouldBe true
            (seen.dropsNoVariants > 80) shouldBe true
            (seen.reactivePreemptedScheduled > 15) shouldBe true
            (seen.seekDuringSpeech > 100) shouldBe true
            (seen.pauseDuringSpeech > 100) shouldBe true
            (seen.speakerEndedSpeech > 300) shouldBe true
            (seen.cueReplayedAfterSeek > 20) shouldBe true
            (seen.overranMeasuredLength > 90) shouldBe true
            // All three withdrawal reasons, and at least some of them while the
            // content is stalled -- the case the two clocks were separated for.
            (seen.withdrawnOutOfSilence > 5) shouldBe true
            (seen.withdrawnSpeakerOverdue > 12) shouldBe true
            (seen.withdrawnNeverMeasured > 6) shouldBe true
            (seen.withdrawnDuringStall > 3) shouldBe true
            (seen.stallDuringSpeech > 300) shouldBe true
            (seen.stallAnomalies > 15) shouldBe true
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
    duckDb = -6.0,
    speechTimeoutSlackMs = 300,
    unmeasuredSpeechCeilingMs = 900,
    stallAnomalyMs = 50,
)

private fun drive(
    scenario: Scenario,
    config: DirectorConfig = DirectorConfig(),
    coverage: Coverage = Coverage(),
) {
    val director = AudioDirector(scenario.track, config)
    val invariants = Invariants(scenario.track, config)
    val speaker = FakeSpeaker()

    var nowMs = 0L
    var positionMs = 0L
    var playback = Playback.PLAYING
    var previous: Speaking = Speaking.Silent
    val cueStarts = mutableMapOf<CueId, Long>()

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

        val directive = director.advance(nowMs, DirectorInputs(positionMs, playback, events))
        invariants.check(index, nowMs, positionMs, playback, events, directive)
        coverage.record(scenario.track, previous, directive, positionMs, events, previousPlayback, playback, cueStarts)
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

    fun record(
        track: DescriptionTrack,
        previous: Speaking,
        directive: Directive,
        positionMs: Long,
        events: List<InputEvent>,
        previousPlayback: Playback,
        playback: Playback,
        cueStarts: MutableMap<CueId, Long>,
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
                val firstStart = cueStarts[speaking.cueId]
                if (firstStart == null) {
                    cueStarts[speaking.cueId] = speaking.startedAtMs
                } else if (firstStart != speaking.startedAtMs) {
                    cueReplayedAfterSeek++
                    cueStarts[speaking.cueId] = speaking.startedAtMs
                }
            }
        }

        directive.drops.forEach {
            when (it.reason) {
                is DropReason.NoVariantFits -> dropsNoVariantFits++
                DropReason.NoSilenceAtCueStart -> dropsNoSilenceAtCueStart++
                DropReason.NoVariants -> dropsNoVariants++
            }
        }

        directive.withdrawal?.let {
            when (it.reason) {
                is WithdrawalReason.OutOfSilence -> withdrawnOutOfSilence++
                is WithdrawalReason.SpeakerOverdue -> withdrawnSpeakerOverdue++
                is WithdrawalReason.NeverMeasured -> withdrawnNeverMeasured++
            }
            if (playback == Playback.STALLED) withdrawnDuringStall++
        }
        if (directive.stall != null) stallAnomalies++

        if (previous is Speaking.Utterance) {
            if (events.any { it is InputEvent.Seeked }) seekDuringSpeech++
            if (previousPlayback != Playback.PAUSED && playback == Playback.PAUSED) pauseDuringSpeech++
            if (previousPlayback == Playback.PLAYING && playback == Playback.STALLED) stallDuringSpeech++
            if (events.any { it is InputEvent.SpeechEnded && it.id == previous.id }) speakerEndedSpeech++
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
        "stallAnomalies=$stallAnomalies)"
}

/**
 * Everything that must be true of the directive stream. Reads only the
 * [Directive]s and the inputs that produced them; knows nothing about how the
 * director is built.
 */
private class Invariants(
    private val track: DescriptionTrack,
    private val config: DirectorConfig,
) {
    private var previous: Directive? = null
    private var previousPlayback = Playback.PLAYING
    private val issued = mutableMapOf<UtteranceId, ReactiveRequest>()

    /** Cue -> the wall clock reading at which it began, since the last seek. */
    private val spokenSinceSeek = mutableMapOf<CueId, Long>()
    private val droppedSinceSeek = mutableSetOf<CueId>()

    fun check(
        step: Int,
        nowMs: Long,
        positionMs: Long,
        playback: Playback,
        events: List<InputEvent>,
        directive: Directive,
    ) {
        events.filterIsInstance<InputEvent.ReactiveRequested>().forEach { issued[it.request.id] = it.request }
        val seeked = events.any { it is InputEvent.Seeked }
        if (seeked) {
            spokenSinceSeek.clear()
            droppedSinceSeek.clear()
        }

        val clue = "step $step: now=$nowMs position=$positionMs playback=$playback " +
            "events=$events directive=$directive track=$track"

        withClue(clue) {
            checkDuck(directive)
            checkDrops(directive)
            when (val speaking = directive.speaking) {
                is Speaking.Silent, is Speaking.Withdrawn -> Unit
                is Speaking.ScheduledUtterance -> checkScheduled(speaking, positionMs, playback)
                is Speaking.ReactiveUtterance -> checkReactive(speaking)
            }
            checkWallClockCeiling(directive, nowMs)
            checkWithdrawal(directive)
            checkStall(directive, playback)
            checkNobodyWasCutOff(directive, playback, events)
            checkSeekLeavesSilence(directive, events)
        }

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

    private fun checkDrops(directive: Directive) {
        directive.drops.forEach { drop ->
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

        val cue = track.cues.single { it.id == speaking.cueId }
        withClue("a scheduled utterance must quote its cue faithfully") {
            speaking.kind shouldBe cue.kind
            (speaking.variantIndex in cue.variants.indices) shouldBe true
            speaking.text shouldBe cue.variants[speaking.variantIndex].text
            speaking.duration shouldBe cue.variants[speaking.variantIndex].measuredDuration
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
        withClue("a cue that was reported as dropped is speaking anyway") {
            droppedSinceSeek.contains(speaking.cueId) shouldBe false
        }

        val firstStart = spokenSinceSeek[speaking.cueId]
        if (firstStart == null) {
            spokenSinceSeek[speaking.cueId] = speaking.startedAtMs
        } else {
            withClue("a cue started a second time without an intervening seek") {
                speaking.startedAtMs shouldBe firstStart
            }
        }
    }

    private fun checkReactive(speaking: Speaking.ReactiveUtterance) {
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
            (preemptedByUser || timelineJumped || speakerReported || pausedEdge || floorWithdrawn) shouldBe true
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

