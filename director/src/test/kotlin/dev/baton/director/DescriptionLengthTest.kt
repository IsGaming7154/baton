package dev.baton.director

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Description length (issue #13): Short takes a description's shortest variant, Long
 * is what the director always did, and a change is heard from the next cue on.
 *
 * Asserted on [Directive] values like the rest of the policy suite. The setting is
 * level state the app holds ([DirectorInputs.descriptionLength]).
 */
class DescriptionLengthTest : FunSpec({

    /** Two descriptions and a subtitle, each with room for its longest variant. */
    fun roomy() = trackOf(
        gaps = listOf(Gap(1_000, 8_000), Gap(10_000, 17_000), Gap(20_000, 27_000)),
        cues = listOf(
            cue(
                "first",
                startMs = 1_000,
                variants = listOf(variant("She crosses the moonlit courtyard.", 3_000), variant("She crosses.", 1_000)),
            ),
            cue(
                "second",
                startMs = 10_000,
                variants = listOf(variant("The iron gate swings shut behind her.", 3_000), variant("A gate shuts.", 1_000)),
            ),
            cue(
                "sub",
                startMs = 20_000,
                kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                variants = listOf(variant("Where have you been all night?", 3_000), variant("Where were you?", 1_000)),
            ),
        ),
        synthetic = false,
    )

    fun Session.variantsSpoken(): Map<CueId, Int> =
        history.scheduledStarts().associate { it.cueId!! to it.variantIndex }

    test("Long takes the longest variant that fits, as before") {
        val session = Session(roomy())

        session.playTo(24_000)

        session.variantsSpoken() shouldBe mapOf(CueId("first") to 0, CueId("second") to 0, CueId("sub") to 0)
    }

    test("Short takes a description's shortest variant, and leaves spoken subtitles alone") {
        val session = Session(roomy())
        session.descriptionLength = DescriptionLength.SHORT

        session.playTo(24_000)

        session.variantsSpoken() shouldBe mapOf(CueId("first") to 1, CueId("second") to 1, CueId("sub") to 0)
        session.history.allDrops().shouldBeEmpty()
    }

    test("a change applies at the next cue, never to the description already speaking") {
        val session = Session(roomy())

        session.playTo(1_000).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .variantIndex shouldBe 0

        // Switched mid-sentence: the long variant keeps the floor to its end, withdrawn by nothing.
        session.descriptionLength = DescriptionLength.SHORT
        val stillSpeaking = session.tick(100)
        stillSpeaking.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().variantIndex shouldBe 0

        session.playTo(12_000)

        session.variantsSpoken() shouldBe mapOf(CueId("first") to 0, CueId("second") to 1)
        session.history.withdrawals().shouldBeEmpty()
    }
})
