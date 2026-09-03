package dev.baton.director

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

    test("the same holds when the guard and duck are configured differently") {
        val config = DirectorConfig(guardMs = 0, duckDb = -6.0)
        checkAll(300, scenarios) { scenario ->
            drive(scenario, config)
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
        }
    }
})

// ---------------------------------------------------------------------------
// Driving
// ---------------------------------------------------------------------------

private fun drive(
    scenario: Scenario,
    config: DirectorConfig = DirectorConfig(),
    coverage: Coverage = Coverage(),
) {
    val director = AudioDirector(scenario.track, config)
    val invariants = Invariants(scenario.track, config)

    var nowMs = 0L
    var positionMs = 0L
    var playback = Playback.PLAYING
    var previous: Speaking = Speaking.Silent
    val cueStarts = mutableMapOf<CueId, Long>()

    scenario.steps.forEachIndexed { index, step ->
        nowMs += step.dtMs
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
        (previous as? Speaking.Utterance)?.let { if (step.reportSpeechEnded) events += InputEvent.SpeechEnded(it.id) }
        if (step.reportPhantomEnd) events += InputEvent.SpeechEnded(UtteranceId("phantom"))

        val wasPlaying = playback == Playback.PLAYING
        playback = if (step.playing) Playback.PLAYING else Playback.PAUSED

        val directive = director.advance(nowMs, DirectorInputs(positionMs, playback, events))
        invariants.check(index, nowMs, positionMs, playback, events, directive)
        coverage.record(scenario.track, previous, directive, positionMs, events, wasPlaying, playback, cueStarts)
        previous = directive.speaking
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

    fun record(
        track: DescriptionTrack,
        previous: Speaking,
        directive: Directive,
        positionMs: Long,
        events: List<InputEvent>,
        wasPlaying: Boolean,
        playback: Playback,
        cueStarts: MutableMap<CueId, Long>,
    ) {
        ticks++
        if (!track.silence.isSilentAt(positionMs)) dialogueTicks++

        when (val speaking = directive.speaking) {
            is Speaking.Silent -> Unit
            is Speaking.ReactiveUtterance -> {
                reactiveSpeaking++
                if (previous is Speaking.ScheduledUtterance) reactivePreemptedScheduled++
            }

            is Speaking.ScheduledUtterance -> {
                scheduledSpeaking++
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

        if (previous is Speaking.Utterance) {
            if (events.any { it is InputEvent.Seeked }) seekDuringSpeech++
            if (wasPlaying && playback == Playback.PAUSED) pauseDuringSpeech++
            if (events.any { it is InputEvent.SpeechEnded && it.id == previous.id }) speakerEndedSpeech++
        }
    }

    override fun toString(): String = "Coverage(ticks=$ticks dialogueTicks=$dialogueTicks " +
        "scheduled=$scheduledSpeaking reactive=$reactiveSpeaking " +
        "noFit=$dropsNoVariantFits noSilence=$dropsNoSilenceAtCueStart noVariants=$dropsNoVariants " +
        "preempted=$reactivePreemptedScheduled seekDuringSpeech=$seekDuringSpeech " +
        "pauseDuringSpeech=$pauseDuringSpeech speakerEnded=$speakerEndedSpeech " +
        "replayed=$cueReplayedAfterSeek)"
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
                is Speaking.Silent -> Unit
                is Speaking.ScheduledUtterance -> checkScheduled(speaking, positionMs, playback)
                is Speaking.ReactiveUtterance -> checkReactive(speaking, nowMs)
            }
            checkNobodyWasCutOff(directive, nowMs, positionMs, playback, events)
            checkSeekLeavesSilence(directive, events)
        }

        previous = directive
        previousPlayback = playback
    }

    /** The content is attenuated exactly when a non-content voice holds the floor. */
    private fun checkDuck(directive: Directive) {
        val expected = when (directive.speaking) {
            is Speaking.Silent -> ContentDuck.Unity
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
        withClue("a scheduled utterance may not run while the content is paused") {
            playback shouldBe Playback.PLAYING
        }

        val cue = track.cues.single { it.id == speaking.cueId }
        withClue("a scheduled utterance must quote its cue faithfully") {
            speaking.kind shouldBe cue.kind
            (speaking.variantIndex in cue.variants.indices) shouldBe true
            speaking.text shouldBe cue.variants[speaking.variantIndex].text
            speaking.duration shouldBe cue.variants[speaking.variantIndex].measuredDuration
        }

        // THE invariant: the film's own dialogue is a voice, and nothing of ours
        // is ever heard on top of it.
        withClue("a scheduled utterance is speaking over content dialogue") {
            track.silence.isSilentAt(positionMs) shouldBe true
        }

        val grantedWindowEnd = track.silence.windowEndAt(speaking.startedAtPositionMs)
        withClue("a scheduled utterance started outside any silence window") {
            (grantedWindowEnd != null) shouldBe true
        }
        withClue("a scheduled utterance was granted room it does not have") {
            (speaking.endsAtPositionMs + config.guardMs <= grantedWindowEnd!!) shouldBe true
        }
        withClue("a scheduled utterance drifted out of the window it was granted") {
            track.silence.windowEndAt(positionMs) shouldBe grantedWindowEnd
        }
        withClue("a scheduled utterance is still named after it should have finished") {
            (positionMs >= speaking.startedAtPositionMs && positionMs < speaking.endsAtPositionMs) shouldBe true
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

    private fun checkReactive(speaking: Speaking.ReactiveUtterance, nowMs: Long) {
        val request = issued[speaking.id]
        withClue("a reactive utterance nobody asked for") { (request != null) shouldBe true }
        requireNotNull(request)
        withClue("a reactive utterance must quote the request faithfully") {
            speaking.kind shouldBe request.kind
            speaking.text shouldBe request.text
            speaking.length shouldBe request.length
        }
        speaking.endsAtMs?.let { endsAtMs ->
            withClue("a reactive utterance is still named after it should have finished") {
                (nowMs < endsAtMs) shouldBe true
            }
        }
    }

    /**
     * Whoever held the floor last tick either still holds it, finished it, or was
     * displaced for a reason the policy allows. Nothing is ever cut off silently:
     * that is the failure that would put two voices in the room during the fade.
     */
    private fun checkNobodyWasCutOff(
        directive: Directive,
        nowMs: Long,
        positionMs: Long,
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
        val pausedEdge = previousPlayback == Playback.PLAYING && playback == Playback.PAUSED
        val ranItsLength = when (before) {
            is Speaking.ScheduledUtterance -> positionMs >= before.endsAtPositionMs
            is Speaking.ReactiveUtterance -> before.endsAtMs?.let { nowMs >= it } ?: false
        }

        withClue("utterance ${before.id} stopped speaking with no cause the policy allows") {
            (preemptedByUser || timelineJumped || speakerReported || pausedEdge || ranItsLength) shouldBe true
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

