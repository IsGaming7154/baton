package dev.baton.pipeline

import dev.baton.director.Gap
import dev.baton.pipeline.mock.RecordedDrafting
import dev.baton.pipeline.mock.RecordedTranscription
import dev.baton.pipeline.mock.SimulatedSynthesis
import dev.baton.pipeline.mock.SpeakingRate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The three fixture-backed ports. They stand in for Transcribe, Nova and Polly,
 * and the live slice replaces them without touching anything that calls them, so
 * what matters here is that each one behaves the way its real counterpart will.
 */
class RecordedPortsTest : FunSpec({

    context("recorded transcription") {

        test("word timings are read out of Transcribe's own response shape") {
            val transcript = RecordedTranscription.fromResource(Fixtures.TRANSCRIBE_JSON).transcribe(Fixtures.media)
            transcript.words.first() shouldBe Word("This", 6_000, 6_457)
            transcript.words.last().endMs shouldBe 34_000
        }

        test("punctuation occupies no time and is not a word") {
            val transcript = RecordedTranscription.fromResource(Fixtures.TRANSCRIBE_JSON).transcribe(Fixtures.media)
            transcript.words.count() shouldBe 35
            transcript.words.none { it.text in listOf(".", "?", ",") } shouldBe true
        }

        test("the locale comes from the job rather than from a guess") {
            RecordedTranscription.fromResource(Fixtures.TRANSCRIBE_JSON, fallbackLocale = "fr-FR")
                .transcribe(Fixtures.media).locale shouldBe "en-US"
        }

        test("a job that did not identify a language falls back to the one we asked for") {
            val noLanguage = """{"results":{"items":[]}}"""
            RecordedTranscription(noLanguage, fallbackLocale = "nl-NL").transcribe(Fixtures.media).locale shouldBe "nl-NL"
        }
    }

    context("recorded drafting") {

        val drafting = RecordedDrafting.fromResource(Fixtures.DRAFTS_JSON)
        fun ask(gapStartMs: Long, attempt: Int) = drafting.draft(
            DraftRequest(
                scene = SceneContext(Fixtures.media, Gap(gapStartMs, gapStartMs + 3_000), "", ""),
                targetDurationMs = 3_000,
                attempt = attempt,
            ),
        )

        test("successive attempts are successively shorter candidates") {
            val first = ask(0, 0)!!.text
            val second = ask(0, 1)!!.text
            (second.length < first.length) shouldBe true
        }

        test("running out of candidates is how the drafter says it has nothing shorter") {
            ask(0, 3).shouldBeNull()
        }

        test("a gap the fixture never recorded gets no draft at all") {
            ask(99_999, 0).shouldBeNull()
        }

        test("replay is stateless, so two builds over one fixture ask and hear the same things") {
            ask(13_000, 1) shouldBe ask(13_000, 1)
        }
    }

    context("the duration oracle") {

        val voice = PipelineConfig.DEFAULT_VOICE
        val synthesis = SimulatedSynthesis()

        test("a duration is measured off the buffer, not computed from the text") {
            val audio = synthesis.synthesize("A woman kneels beside a fire.", voice)
            audio.bytes!!.size shouldBe (audio.frameCount * RenderedAudio.BYTES_PER_PCM16_MONO_FRAME).toInt()
            audio.duration.millis shouldBe audio.frameCount * 1_000 / voice.sampleRateHz
        }

        test("more words render more frames") {
            val short = synthesis.synthesize("She kneels.", voice)
            val long = synthesis.synthesize("She kneels beside the fire and pulls her hood over her face.", voice)
            long.frameCount shouldBeGreaterThan short.frameCount
        }

        test("the sample rate changes the frame count and leaves the duration alone") {
            val text = "The dragon lifts from the cliff face."
            val at24k = synthesis.synthesize(text, voice)
            val at16k = synthesis.synthesize(text, voice.copy(sampleRateHz = 16_000))
            at16k.frameCount shouldNotBe at24k.frameCount
            at16k.duration shouldBe at24k.duration
        }

        test("the rate model is a parameter, so a slower voice produces longer audio") {
            val slow = SimulatedSynthesis(SpeakingRate(wordsPerMinute = 110))
            val text = "A woman kneels beside a fire."
            slow.synthesize(text, voice).duration.millis shouldBeGreaterThan
                synthesis.synthesize(text, voice).duration.millis
        }

        test("an odd-length buffer is half a frame and is refused") {
            shouldThrow<IllegalArgumentException> { RenderedAudio.ofPcm16Mono(ByteArray(3), 24_000) }
        }
    }
})
