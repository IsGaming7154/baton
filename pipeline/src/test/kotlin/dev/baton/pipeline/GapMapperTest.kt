package dev.baton.pipeline

import dev.baton.director.Gap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The gap map is the pipeline's one authoritative statement about where the film
 * is quiet. Everything downstream -- what gets written, how long it may be,
 * whether the runtime will admit it -- is a consequence of these windows, so the
 * derivation is worth testing on its own rather than only through the builder.
 */
class GapMapperTest : FunSpec({

    val transcript = Fixtures.transcription().transcribe(Fixtures.media)

    test("silence is the complement of the dialogue, closed by the length of the media") {
        GapMapper.derive(transcript, Fixtures.MEDIA_DURATION_MS) shouldBe listOf(
            Gap(0, 6_000),
            Gap(13_000, 16_000),
            Gap(21_000, 23_000),
            Gap(27_000, 31_500),
            Gap(34_000, 40_000),
        )
    }

    test("a pause inside a line is bridged rather than reported as a window") {
        // The third line of the fixture has a 250 ms beat in the middle of it.
        // Transcribe times every word separately, so without bridging that beat
        // would look exactly like a place to put a description.
        GapMapper.dialogueSpans(transcript, Fixtures.MEDIA_DURATION_MS) shouldContain Gap(16_000, 21_000)
    }

    test("a window nothing could be said in is not shipped") {
        val tooShort = Gap(9_500, 10_200)
        tooShort.durationMs shouldBe 700
        GapMapper.derive(transcript, Fixtures.MEDIA_DURATION_MS) shouldNotContain tooShort
    }

    test("the minimum useful gap is a number, not a rule baked into the loop") {
        val permissive = GapMapConfig(minimumUsefulGapMs = 500)
        GapMapper.derive(transcript, Fixtures.MEDIA_DURATION_MS, permissive) shouldContain Gap(9_500, 10_200)
    }

    test("the bridge is a number too, and widening it swallows a real window") {
        val greedy = GapMapConfig(dialogueBridgeMs = 1_000)
        GapMapper.derive(transcript, Fixtures.MEDIA_DURATION_MS, greedy) shouldNotContain Gap(9_500, 10_200)
    }

    test("content with no dialogue at all is one window from end to end") {
        val silentFilm = Transcript("en-US", emptyList())
        GapMapper.derive(silentFilm, 12_000) shouldBe listOf(Gap(0, 12_000))
    }

    test("two speakers talking over each other are one span of dialogue, not two") {
        val overlapping = Transcript(
            "en-US",
            listOf(
                Word("no", 1_000, 3_000),
                Word("wait", 2_000, 4_000),
            ),
        )
        GapMapper.dialogueSpans(overlapping, 10_000) shouldBe listOf(Gap(1_000, 4_000))
    }

    test("a word running past the end of the media is clamped rather than trusted") {
        val overrunning = Transcript("en-US", listOf(Word("fade", 8_000, 99_000)))
        GapMapper.derive(overrunning, 10_000) shouldBe listOf(Gap(0, 8_000))
    }

    test("dialogue that reaches the final frame leaves no trailing window") {
        val toTheEnd = Transcript("en-US", listOf(Word("end", 2_000, 10_000)))
        GapMapper.derive(toTheEnd, 10_000) shouldBe listOf(Gap(0, 2_000))
    }
})
