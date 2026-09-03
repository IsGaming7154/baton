package dev.baton.director

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The track model. Its job is to make the pipeline's promises structural, so that
 * a bad track fails where it is built rather than where it is played.
 */
class DescriptionTrackTest : FunSpec({

    test("a duration is obtained by counting rendered audio frames") {
        MeasuredDurationMs.ofRenderedAudio(frameCount = 72_000, sampleRateHz = 48_000).millis shouldBe 1_500
        MeasuredDurationMs.ofRenderedAudio(frameCount = 11_025, sampleRateHz = 22_050).millis shouldBe 500
    }

    test("a measurement survives being written to a track and read back") {
        val measured = MeasuredDurationMs.ofRenderedAudio(frameCount = 96_000, sampleRateHz = 48_000)
        MeasuredDurationMs.restoredFromTrack(measured.millis) shouldBe measured
    }

    test("nonsense measurements are rejected at the door") {
        shouldThrow<IllegalArgumentException> { MeasuredDurationMs.ofRenderedAudio(-1, 48_000) }
        shouldThrow<IllegalArgumentException> { MeasuredDurationMs.ofRenderedAudio(48_000, 0) }
        shouldThrow<IllegalArgumentException> { MeasuredDurationMs.restoredFromTrack(-1) }
    }

    test("a variant with no audible length is not a variant") {
        shouldThrow<IllegalArgumentException> {
            Variant("Silence.", MeasuredDurationMs.ofRenderedAudio(0, 48_000))
        }
        shouldThrow<IllegalArgumentException> { variant("   ", 500) }
    }

    test("variants must be ordered longest first, because the director trusts that order") {
        shouldThrow<IllegalArgumentException> {
            cue("c1", 0, variants = listOf(variant("short", 300), variant("long", 3_000)))
        }
    }

    test("cue ids are unique within a track") {
        shouldThrow<IllegalArgumentException> {
            trackOf(
                cues = listOf(
                    cue("same", 0, variants = listOf(variant("a", 100))),
                    cue("same", 500, variants = listOf(variant("b", 100))),
                ),
            )
        }
    }

    test("a track declares itself synthetic by default, since the app has to say so out loud") {
        trackOf().synthetic shouldBe true
        DescriptionTrack.empty("sintel", "en-US").synthetic shouldBe true
    }

    test("the gap map is normalized: sorted, merged, and free of empty windows") {
        val silence = SilenceWindows.of(
            listOf(
                Gap(5_000, 6_000),
                Gap(1_000, 2_000),
                Gap(1_500, 2_500),
                Gap(2_500, 3_000),
                Gap(4_000, 4_000),
            ),
        )

        silence.windows shouldBe listOf(Gap(1_000, 3_000), Gap(5_000, 6_000))
    }

    test("a silence window is half-open, so a dialogue onset is dialogue") {
        val silence = SilenceWindows.of(listOf(Gap(1_000, 3_000)))

        silence.windowEndAt(999).shouldBeNull()
        silence.windowEndAt(1_000) shouldBe 3_000
        silence.windowEndAt(2_999) shouldBe 3_000
        silence.windowEndAt(3_000).shouldBeNull()
    }

    test("content with no gap map is dialogue everywhere") {
        val silence = SilenceWindows.of(emptyList())
        silence.isSilentAt(0) shouldBe false
        silence.isSilentAt(10_000) shouldBe false
    }
})
