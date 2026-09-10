package dev.baton.director

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Adversarial timelines for AudioDirectorInvariantTest.
// ---------------------------------------------------------------------------

/**
 * Short on purpose. A generated run walks a few seconds of content, so a long
 * timeline would put most of the gap map out of reach and quietly starve the
 * scheduling paths of coverage.
 */
internal const val TIMELINE_END_MS = 8_000L

internal sealed interface Seek {
    class To(val positionMs: Long) : Seek
    class Back(val byMs: Long) : Seek
}

internal class Step(
    val dtMs: Long,
    /**
     * Deliberately three-valued. A stall is not a pause, and a generator that only
     * produced two of the three would leave the invariants holding over a state
     * the product now spends real time in every time a stream hiccups.
     */
    val playback: Playback,
    val seek: Seek?,
    val reactives: List<ReactiveRequest>,
    val reactiveBeforeSeek: Boolean,
    /** The speaker reports done before the audio it was handed has run out. */
    val reportSpeechEnded: Boolean,
    /** The speaker takes this utterance and never reports on it again. */
    val speakerStalls: Boolean,
    val reportPhantomEnd: Boolean,
)

internal class Scenario(val track: DescriptionTrack, val steps: List<Step>)

/**
 * Most scenarios are shaped like a film: dialogue and silence alternating, cues
 * written into the silence, variant lengths sized against the gap they were
 * written for. Those are the timelines that actually reach the scheduling code,
 * and without them the invariants would hold by never being tested -- which is
 * why the suite carries a coverage guard as well.
 *
 * The rest are deliberate garbage: a gap map with no relationship to the cue
 * list, zero-length windows, overlapping windows out of order, cues written over
 * dialogue, cues at t=0, cues sharing a start time, cues whose every variant is
 * longer than any gap in the film, cues with no variants at all, and empty
 * tracks. The same guarantees have to survive all of it.
 */
internal val scenarios: Arb<Scenario> = arbitrary { rs: RandomSource ->
    val random = rs.random
    val filmLike = random.nextInt(10) < 7
    val gaps = if (filmLike) filmLikeGaps(random) else chaoticGaps(random)
    Scenario(
        track = DescriptionTrack(
            formatVersion = DescriptionTrack.CURRENT_FORMAT_VERSION,
            sourceId = "generated",
            locale = "en-US",
            gapMap = gaps,
            cues = if (filmLike) filmLikeCues(random, gaps) else chaoticCues(random, gaps),
        ),
        steps = randomSteps(random),
    )
}

private fun Random.between(from: Long, until: Long): Long = if (until <= from) from else nextLong(from, until)

/** Dialogue and silence alternating from the top of the film. */
private fun filmLikeGaps(random: Random): List<Gap> {
    val gaps = mutableListOf<Gap>()
    var cursor = random.between(0, 400)
    while (cursor < TIMELINE_END_MS) {
        val endMs = minOf(cursor + random.between(400, 2_500), TIMELINE_END_MS)
        if (endMs > cursor) gaps += Gap(cursor, endMs)
        cursor = endMs + random.between(150, 1_500)
    }
    return gaps
}

/** Zero-length windows, overlapping windows, windows out of order. */
private fun chaoticGaps(random: Random): List<Gap> = List(random.nextInt(0, 7)) {
    val startMs = random.between(0, TIMELINE_END_MS)
    val lengthMs = when (random.nextInt(6)) {
        0 -> 0L
        1 -> random.between(1, 100)
        2 -> random.between(100, 1_200)
        3 -> random.between(1_200, 5_000)
        4 -> random.between(1, 20)
        else -> random.between(0, 3_000)
    }
    Gap(startMs, startMs + lengthMs)
}

/** Cues in the silence, sized against it: comfortable, marginal, and hopeless. */
private fun filmLikeCues(random: Random, gaps: List<Gap>): List<Cue> {
    val alreadyUsed = mutableListOf<Long>()
    return List(random.nextInt(0, 12)) { index ->
        val gap = gaps.randomOrNull(random)
        val startMs = when {
            gap == null -> random.between(0, TIMELINE_END_MS)
            random.nextInt(9) == 0 -> gap.endMs
            random.nextInt(7) == 0 -> alreadyUsed.randomOrNull(random) ?: gap.startMs
            else -> gap.startMs + random.between(0, maxOf(1, gap.durationMs / 3))
        }
        alreadyUsed += startMs
        val roomMs = gap?.durationMs ?: 1_000L
        cueOf(
            index = index,
            startMs = startMs,
            random = random,
            durations = List(random.nextInt(0, 5)) { fittedDurationMs(random, roomMs) }.sortedDescending(),
        )
    }
}

/** Cues that ignore the gap map entirely. */
private fun chaoticCues(random: Random, gaps: List<Gap>): List<Cue> {
    val alreadyUsed = mutableListOf<Long>()
    return List(random.nextInt(0, 9)) { index ->
        val startMs = when (random.nextInt(9)) {
            0 -> 0L
            1 -> gaps.randomOrNull(random)?.startMs ?: 0L
            2 -> gaps.randomOrNull(random)?.endMs ?: 0L
            3 -> alreadyUsed.randomOrNull(random) ?: random.between(0, TIMELINE_END_MS)
            4 -> gaps.randomOrNull(random)?.let { it.startMs + 1 } ?: 0L
            5 -> gaps.randomOrNull(random)?.let { (it.endMs - 1).coerceAtLeast(0) } ?: 0L
            else -> random.between(0, TIMELINE_END_MS)
        }
        alreadyUsed += startMs
        cueOf(
            index = index,
            startMs = startMs,
            random = random,
            durations = List(random.nextInt(0, 4)) { chaoticDurationMs(random) }.sortedDescending(),
        )
    }
}

private fun cueOf(index: Int, startMs: Long, random: Random, durations: List<Long>): Cue = Cue(
    id = CueId("c$index"),
    startMs = startMs,
    kind = if (random.nextBoolean()) {
        UtteranceKind.Scheduled.SPOKEN_SUBTITLE
    } else {
        UtteranceKind.Scheduled.AUDIO_DESCRIPTION
    },
    variants = durations.mapIndexed { v, millis -> Variant("cue $index, variant $v", measuredMs(millis)) },
)

/** Sized against the gap this cue was written for: fits easily, fits barely, fits nowhere. */
private fun fittedDurationMs(random: Random, roomMs: Long): Long = when (random.nextInt(8)) {
    0 -> random.between(1, maxOf(2, roomMs / 4))
    1 -> random.between(1, maxOf(2, roomMs / 3))
    2 -> random.between(1, maxOf(2, roomMs / 2))
    3 -> random.between(1, maxOf(2, roomMs * 3 / 4))
    4 -> random.between(roomMs, roomMs * 2 + 2)
    5 -> 60_000L
    6 -> 1L
    else -> random.between(1, maxOf(2, roomMs / 2))
}.coerceAtLeast(1L)

private fun chaoticDurationMs(random: Random): Long = when (random.nextInt(8)) {
    0 -> 1L
    1 -> random.between(20, 200)
    2 -> random.between(200, 800)
    3 -> random.between(800, 2_500)
    4 -> random.between(2_500, 6_000)
    5 -> 60_000L
    6 -> random.between(1, 60)
    else -> random.between(100, 4_000)
}.coerceAtLeast(1L)

private fun randomRequest(random: Random, step: Int, ordinal: Int): ReactiveRequest = ReactiveRequest(
    id = UtteranceId("r$step-$ordinal"),
    kind = if (random.nextBoolean()) {
        UtteranceKind.Reactive.UI_NARRATION
    } else {
        UtteranceKind.Reactive.VQA_ANSWER
    },
    text = "reactive $step-$ordinal",
    length = if (random.nextInt(5) == 0) {
        SpeechLength.UntilSpeakerReports
    } else {
        SpeechLength.Measured(measuredMs(random.between(1, 2_500)))
    },
)

private fun randomSteps(random: Random): List<Step> = List(random.nextInt(8, 90)) { index ->
    Step(
        dtMs = when (random.nextInt(10)) {
            0 -> 0L
            1 -> 1L
            2 -> 17L
            3 -> 33L
            4 -> 250L
            5 -> 600L
            6 -> random.between(1, 40)
            else -> 100L
        },
        playback = when (random.nextInt(12)) {
            0 -> Playback.PAUSED
            1 -> Playback.STALLED
            else -> Playback.PLAYING
        },
        seek = when {
            random.nextInt(14) != 0 -> null
            random.nextBoolean() -> Seek.Back(random.between(500, 5_000))
            else -> Seek.To(random.between(0, TIMELINE_END_MS + 2_000))
        },
        reactives = when (random.nextInt(20)) {
            0 -> listOf(randomRequest(random, index, 0))
            1 -> listOf(randomRequest(random, index, 0), randomRequest(random, index, 1))
            else -> emptyList()
        },
        reactiveBeforeSeek = random.nextBoolean(),
        reportSpeechEnded = random.nextInt(8) == 0,
        speakerStalls = random.nextInt(4) == 0,
        reportPhantomEnd = random.nextInt(20) == 0,
    )
}
