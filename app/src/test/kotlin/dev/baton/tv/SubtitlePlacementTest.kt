package dev.baton.tv

import dev.baton.director.Gap
import dev.baton.director.SilenceWindows
import dev.baton.director.SubtitleLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Where a subtitle line's booking starts, checked here rather than on the device.
 *
 * The rule is placement, not arbitration: the four cases below cover every branch
 * of [SubtitlePlacement.placedStartMs], and the branch that keeps `line.endMs`
 * over dialogue is the load-bearing one -- it hands the director a cue it will
 * drop and report as `NoSilenceAtCueStart`, which is the honest outcome, rather
 * than a cue that was quietly never booked.
 */
class SubtitlePlacementTest : FunSpec({

    test("a line that ends inside a silence window is spoken the moment it ends") {
        val silence = SilenceWindows.of(listOf(Gap(9_000, 12_000)))
        val line = SubtitleLine(startMs = 6_042, endMs = 9_292, text = "This blade has a dark past.")

        SubtitlePlacement.placedStartMs(line, next = null, silence) shouldBe 9_292
    }

    test("a line that ends in dialogue is booked into the window that opens before the next line") {
        val silence = SilenceWindows.of(listOf(Gap(12_000, 14_000)))
        val line = SubtitleLine(startMs = 8_000, endMs = 10_500, text = "It has shed much innocent blood.")
        val next = SubtitleLine(startMs = 14_500, endMs = 16_000, text = "You are a fool.")

        SubtitlePlacement.placedStartMs(line, next, silence) shouldBe 12_000
    }

    test("a line whose only window opens after the next line keeps its own end and is left for the director to drop") {
        val silence = SilenceWindows.of(listOf(Gap(15_000, 17_000)))
        val line = SubtitleLine(startMs = 8_000, endMs = 10_500, text = "It has shed much innocent blood.")
        val next = SubtitleLine(startMs = 12_000, endMs = 14_000, text = "You are a fool.")

        SubtitlePlacement.placedStartMs(line, next, silence) shouldBe 10_500
    }

    test("the last line may take any later window") {
        val silence = SilenceWindows.of(listOf(Gap(15_000, 17_000)))
        val line = SubtitleLine(startMs = 8_000, endMs = 10_500, text = "It has shed much innocent blood.")

        SubtitlePlacement.placedStartMs(line, next = null, silence) shouldBe 15_000
    }

    test("startsFor walks the file, so each line sees its own follower") {
        val silence = SilenceWindows.of(listOf(Gap(12_000, 14_000), Gap(21_000, 24_000)))
        val lines = listOf(
            // Ends inside the first window.
            SubtitleLine(startMs = 6_042, endMs = 12_500, text = "one"),
            // Ends in dialogue, on the first window that opens before line three.
            SubtitleLine(startMs = 14_500, endMs = 16_000, text = "two"),
            // The last line, ending where no window follows: its own end stands.
            SubtitleLine(startMs = 22_000, endMs = 25_000, text = "three"),
        )

        SubtitlePlacement.startsFor(lines, silence) shouldBe listOf(12_500, 21_000, 25_000)
    }
})
