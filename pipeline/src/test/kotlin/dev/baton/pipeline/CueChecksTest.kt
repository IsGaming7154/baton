package dev.baton.pipeline

import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.MeasuredDurationMs
import dev.baton.director.UtteranceKind
import dev.baton.director.Variant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * The style rules a machine can check. Violating cues are built by hand, because
 * the recorded fixtures are the passing case and the builder's placement never
 * produces an overlapping one.
 */
class CueChecksTest : FunSpec({

    fun cue(startMs: Long, lengthMs: Long, text: String) = Cue(
        id = CueId("ad-$startMs"),
        startMs = startMs,
        kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
        variants = listOf(Variant(text, MeasuredDurationMs.ofRenderedAudio(lengthMs * 16, 16_000))),
    )

    val transcript = Fixtures.transcription().transcribe(Fixtures.media)
    val dialogue = GapMapper.dialogueSpans(transcript, Fixtures.MEDIA_DURATION_MS)

    context("never over dialogue") {

        test("a cue that runs into dialogue fails, naming the cue and both spans") {
            val error = shouldThrow<IllegalStateException> {
                CueChecks.checkNoDialogueOverlap(listOf(cue(5_000, 2_000, "She stands.")), dialogue)
            }
            error.message shouldContain "ad-5000"
            error.message shouldContain "never-over-dialogue"
            error.message shouldContain "5000-7000 ms over dialogue at 6000-"
        }

        test("a cue that ends as dialogue starts passes") {
            CueChecks.checkNoDialogueOverlap(listOf(cue(4_000, 2_000, "She stands.")), dialogue)
        }
    }

    context("no reference to other cues or to the generation process") {

        test("a cue pointing at another description fails, quoting the phrase") {
            val error = shouldThrow<IllegalStateException> {
                CueChecks.checkNoSelfReference(listOf(cue(0, 1_000, "As described above, she runs.")))
            }
            error.message shouldContain "ad-0"
            error.message shouldContain "no-self-reference"
            error.message shouldContain "\"described above\""
        }

        test("each documented pattern catches its example") {
            val examples = listOf(
                "Cue 2 again.", "See description #3.", "Like #4.", "As mentioned earlier.",
                "The previous description.", "As an AI, I see a fire.", "Per the prompt.",
                "Generated text.", "The model sees a fire.", "I cannot see it.",
            )
            for (text in examples) {
                shouldThrow<IllegalStateException> { CueChecks.checkNoSelfReference(listOf(cue(0, 1_000, text))) }
            }
        }

        test("ordinary description that happens to say 'above' passes") {
            CueChecks.checkNoSelfReference(listOf(cue(0, 1_000, "The dragon turns above the valley.")))
        }

        test("the builder fails rather than ship a self-reference") {
            val error = shouldThrow<IllegalStateException> {
                Fixtures.builder(drafting = ScriptedDrafter("Description 2 continues.")).build(Fixtures.request)
            }
            error.message shouldContain "\"Description 2\""
        }
    }

    context("characters unnamed until the film names them") {

        val named = Transcript("en-US", listOf(Word("Sintel", 20_000, 20_500)))

        test("a name the transcript never says fails") {
            val error = shouldThrow<IllegalStateException> {
                CueChecks.checkNamesAfterTranscript(listOf(cue(150, 1_000, "The old man hands Sintel a blade.")), transcript)
            }
            error.message shouldContain "ad-150"
            error.message shouldContain "unnamed-until-named"
            error.message shouldContain "\"Sintel\" at 150 ms, which the transcript says never"
        }

        test("a name used before the film says it fails") {
            val error = shouldThrow<IllegalStateException> {
                CueChecks.checkNamesAfterTranscript(listOf(cue(10_000, 1_000, "He turns to Sintel's hood.")), named)
            }
            error.message shouldContain "says not until 20500 ms"
        }

        test("a name after the film says it passes, and sentence openers and 'I' are not names") {
            CueChecks.checkNamesAfterTranscript(
                listOf(cue(21_000, 1_000, "He hands Sintel's blade back. She reads \"I remain\" on it.")),
                named,
            )
        }

        test("the builder fails rather than ship a name too early") {
            shouldThrow<IllegalStateException> {
                Fixtures.builder(drafting = ScriptedDrafter("A woman, Sintel, kneels.")).build(Fixtures.request)
            }.message shouldContain "\"Sintel\""
        }
    }

    test("the drafting prompt carries every style rule of issue #12") {
        val rules = listOf(
            "active voice in the present tense",
            "third person, omniscient",
            "precise verb over a verb with a qualifier",
            "Do not editorialise",
            "unnamed until the film names them",
            "all characters with equal specificity",
            "person-first language for disability",
            "Never speak over dialogue",
            "song lyrics as dialogue",
            "what the viewer can already hear",
            "this prompt, the generation process, other descriptions",
        )
        for (rule in rules) DraftingPrompt.STYLE_RULES shouldContain rule
    }
})
