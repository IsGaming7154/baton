package dev.baton.director

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SubtitleFileTest : FunSpec({

    test("a CRLF SubRip file parses into lines in file order, markup stripped and multi-line text joined") {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,500
            <i>Opening titles</i>

            2
            00:00:03,000 --> 00:00:05,000
            The first line
            continues here

            3
            00:00:06,000 --> 00:00:07,000
            Last line
        """.trimIndent().replace("\n", "\r\n")

        parseSubtitles(srt) shouldBe listOf(
            SubtitleLine(1_000, 2_500, "Opening titles"),
            SubtitleLine(3_000, 5_000, "The first line continues here"),
            SubtitleLine(6_000, 7_000, "Last line"),
        )
    }

    test("a WebVTT file with a BOM loses its header, note block, cue identifiers, settings and voice tags") {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE
            A comment that must not become a line.

            intro-cue
            00:01.000 --> 00:00:02.000 line:0 position:50%
            <v Speaker>Hello there</v>

            00:00:03.500 --> 00:00:04.000
            Second cue
        """.trimIndent()

        parseSubtitles("\uFEFF$vtt") shouldBe listOf(
            SubtitleLine(1_000, 2_000, "Hello there"),
            SubtitleLine(3_500, 4_000, "Second cue"),
        )
    }

    test("HTML entities are decoded to the characters they stand for") {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Fish &amp; chips &lt;em&gt;&nbsp;today
        """.trimIndent()

        parseSubtitles(srt).single().text shouldBe "Fish & chips <em> today"
    }

    test("a cue that is nothing but markup is skipped rather than spoken") {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>{\an8}</i>

            2
            00:00:03,000 --> 00:00:04,000
            Real line
        """.trimIndent()

        parseSubtitles(srt) shouldBe listOf(SubtitleLine(3_000, 4_000, "Real line"))
    }

    test("a malformed timing line throws, naming where the cue sits in the file") {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Fine

            2
            00:00:0 --> 00:00:05,000
            Broken
        """.trimIndent()

        val thrown = shouldThrow<IllegalArgumentException> { parseSubtitles(srt) }
        thrown.message shouldContain "line 6"
    }

    test("a cue that ends before it starts throws, naming where the cue sits in the file") {
        val srt = """
            1
            00:00:10,000 --> 00:00:09,000
            Runs backwards
        """.trimIndent()

        val thrown = shouldThrow<IllegalArgumentException> { parseSubtitles(srt) }
        thrown.message shouldContain "line 2"
    }
})
