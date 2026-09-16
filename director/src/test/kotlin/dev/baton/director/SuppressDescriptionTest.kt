package dev.baton.director

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Suppress (issue #7): one button silences description for the moment, the same
 * button restores it, and dialogue is never traded for it.
 *
 * Asserted on [Directive] values like the rest of the policy suite -- nothing here
 * reaches into the director. The switch itself is level state the app holds
 * ([DirectorInputs.silencedKinds]); whether it came from the suppress button or
 * the description toggle is not a fact the director has, which is the point:
 * suppress cannot write the toggle's state because it does not reach it.
 */
class SuppressDescriptionTest : FunSpec({

    /** A description and a subtitle in silences of their own, far enough apart never to contest one. */
    fun bothKinds() = trackOf(
        gaps = listOf(Gap(1_000, 6_000), Gap(10_000, 16_000)),
        cues = listOf(
            cue(
                "desc",
                startMs = 1_000,
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                variants = listOf(variant("She crosses the courtyard.", 1_000)),
            ),
            cue(
                "sub",
                startMs = 10_000,
                kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                variants = listOf(variant("Where have you been?", 1_000)),
            ),
        ),
        synthetic = false,
    )

    test("suppressed, the description never speaks and the spoken subtitle still does") {
        val session = Session(bothKinds())
        session.silencedKinds = setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION)

        session.playTo(12_000)

        session.history.spokenCueIds() shouldBe listOf(CueId("sub"))
    }

    test("a suppressed cue is passed over silently, not reported as a drop") {
        val session = Session(bothKinds())
        session.silencedKinds = setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION)

        session.playTo(12_000)

        // It was never refused room. Reporting it would put a line in the log for
        // every description in the film for as long as the switch is off.
        session.history.allDrops().shouldBeEmpty()
    }

    test("unsuppressing restores description, and the toggle it did not touch still governs") {
        val track = trackOf(
            gaps = listOf(Gap(1_000, 6_000), Gap(10_000, 16_000)),
            cues = listOf(
                cue("early", startMs = 1_000, variants = listOf(variant("She crosses the courtyard.", 1_000))),
                cue("late", startMs = 10_000, variants = listOf(variant("The gate swings shut.", 1_000))),
            ),
            synthetic = false,
        )
        val session = Session(track)

        session.silencedKinds = setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION)
        session.playTo(6_000)
        session.history.spokenCueIds().shouldBeEmpty()

        // The same button again. Descriptions come back from the next cue on; the
        // one whose moment passed while it was off does not return, because the
        // scene it describes has gone.
        session.silencedKinds = emptySet()
        session.playTo(12_000)

        session.history.spokenCueIds() shouldBe listOf(CueId("late"))
    }

    test("silencing subtitles leaves description alone -- the switches are per kind") {
        val session = Session(bothKinds())
        session.silencedKinds = setOf(UtteranceKind.Scheduled.SPOKEN_SUBTITLE)

        session.playTo(12_000)

        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("suppressing while a description speaks takes no floor, and the announcement that did cuts it off") {
        val session = Session(bothKinds())

        session.playTo(1_000).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("desc")

        // The switch alone changes nothing about what holds the floor: nothing is
        // withdrawn, so no sentence is cut in half by a level change.
        session.silencedKinds = setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION)
        val stillSpeaking = session.tick(100)
        stillSpeaking.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")
        stillSpeaking.withdrawal shouldBe null

        // What silences it now is the button press being answered: a reactive
        // request preempts under rule 1, which is how the app makes suppress
        // immediate without the director learning a second way to stop a voice.
        session.press(uiNarration("ui-1", "Suppress description, button, on", millis = 900)).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()
            .id shouldBe UtteranceId("ui-1")
    }

    test("no tick ever names two voices, whatever the switches do mid-film") {
        val session = Session(bothKinds())

        // Thrown and un-thrown across both kinds while the film runs.
        val flips = listOf(
            setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION),
            emptySet(),
            setOf(UtteranceKind.Scheduled.SPOKEN_SUBTITLE),
            setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION, UtteranceKind.Scheduled.SPOKEN_SUBTITLE),
            emptySet(),
        )
        for ((index, silenced) in flips.withIndex()) {
            session.silencedKinds = silenced
            session.playTo(2_000L * (index + 1) + 1_000L)
        }

        // A Directive can name at most one utterance by construction; what this
        // checks is the half that is not free -- that a switch never withdraws a
        // floor, which is the only way this change could have produced an overlap.
        session.history.withdrawals().shouldBeEmpty()
    }
})
