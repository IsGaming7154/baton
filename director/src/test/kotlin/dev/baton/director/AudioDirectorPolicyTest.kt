package dev.baton.director

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The arbitration policy, one edge at a time.
 *
 * Every assertion here is on a [Directive] -- the seam the player consumes.
 * Nothing inspects the director's state. Rewrite the policy internals however you
 * like; if the behaviour is preserved, these still pass.
 */
class AudioDirectorPolicyTest : FunSpec({

    test("a reactive utterance preempts a scheduled one mid-speech") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 10_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A dragon lands.", 3_000)))),
            ),
        )

        session.playTo(1_500).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("desc")

        val answer = session.press(vqaAnswer("q1", "She is holding a knife.", millis = 800))
        val speaking = answer.speaking.shouldBeInstanceOf<Speaking.ReactiveUtterance>()
        speaking.kind shouldBe UtteranceKind.Reactive.VQA_ANSWER
        speaking.text shouldBe "She is holding a knife."
        answer.contentDuck shouldBe ContentDuck.Ducked(DirectorConfig.DEFAULT_DUCK_DB)

        // The description does not resume once the answer is done. It had its gap.
        session.tick(800).speaking shouldBe Speaking.Silent
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("a second button press preempts the first answer") {
        val session = Session(trackOf())

        session.press(vqaAnswer("q1", "The first answer.")).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()
            .id shouldBe UtteranceId("q1")

        session.tick(200)
        session.press(vqaAnswer("q2", "The second answer.")).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()
            .id shouldBe UtteranceId("q2")
    }

    test("two button presses in the same millisecond leave only the last one speaking") {
        val session = Session(trackOf())

        val directive = session.tick(
            stepMs = 0,
            events = listOf(
                InputEvent.ReactiveRequested(uiNarration("a", "Settings")),
                InputEvent.ReactiveRequested(uiNarration("b", "Audio description")),
                InputEvent.ReactiveRequested(uiNarration("c", "Spoken subtitles")),
            ),
        )

        directive.speaking.shouldBeInstanceOf<Speaking.ReactiveUtterance>().id shouldBe UtteranceId("c")
    }

    test("a spoken subtitle wins a gap contested with a description") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 5_000)),
                cues = listOf(
                    cue(
                        "desc",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                        variants = listOf(variant("Rain sheets off the roof.", 1_000)),
                    ),
                    cue(
                        "sub",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Where have you been?", 1_000)),
                    ),
                ),
            ),
        )

        session.playTo(1_000).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .kind shouldBe UtteranceKind.Scheduled.SPOKEN_SUBTITLE

        // The description is not dropped -- it simply waits, and takes what is
        // left of the same gap once the subtitle has finished.
        session.playTo(2_000).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .kind shouldBe UtteranceKind.Scheduled.AUDIO_DESCRIPTION

        session.history.spokenCueIds() shouldBe listOf(CueId("sub"), CueId("desc"))
        session.history.allDrops().shouldBeEmpty()
    }

    test("a cue whose long variant overruns starts with the longest variant that fits") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 3_000)),
                cues = listOf(
                    cue(
                        "desc",
                        startMs = 1_000,
                        variants = listOf(
                            variant("She crosses the flooded courtyard and pushes the gate open.", 3_000),
                            variant("She crosses the courtyard and opens the gate.", 1_500),
                            variant("She opens the gate.", 600),
                        ),
                    ),
                ),
            ),
        )

        // 2000 ms of gap, 250 ms of guard: anything up to 1750 ms fits.
        val speaking = session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        speaking.variantIndex shouldBe 1
        speaking.text shouldBe "She crosses the courtyard and opens the gate."
        speaking.duration shouldBe measuredMs(1_500)
        session.history.allDrops().shouldBeEmpty()
    }

    test("the guard interval is enforced to the millisecond") {
        fun fits(durationMs: Long): Boolean {
            val session = Session(
                trackOf(
                    gaps = listOf(Gap(1_000, 3_000)),
                    cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("x", durationMs)))),
                ),
            )
            return session.playTo(1_000).speaking is Speaking.ScheduledUtterance
        }

        // 2000 ms of silence, GUARD_MS of 250.
        DirectorConfig.DEFAULT_GUARD_MS shouldBe 250L
        fits(1_750) shouldBe true
        fits(1_751) shouldBe false
    }

    test("a cue with no fitting variant is dropped and reported with a reason") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 1_500)),
                cues = listOf(
                    cue(
                        "desc",
                        startMs = 1_000,
                        variants = listOf(variant("A long description.", 3_000), variant("Short.", 2_000)),
                    ),
                ),
            ),
        )

        val directive = session.playTo(1_000)
        directive.speaking shouldBe Speaking.Silent
        directive.contentDuck shouldBe ContentDuck.Unity
        directive.drops shouldHaveSize 1

        val drop = directive.drops.single()
        drop.cueId shouldBe CueId("desc")
        drop.kind shouldBe UtteranceKind.Scheduled.AUDIO_DESCRIPTION
        drop.cueStartMs shouldBe 1_000
        drop.atPositionMs shouldBe 1_000
        drop.reason shouldBe DropReason.NoVariantFits(
            shortestVariantMs = 2_000,
            availableMs = 500,
            guardMs = 250,
            floorHeldBy = null,
        )

        // Reported once, not once per tick for the rest of the film.
        session.playTo(4_000)
        session.history.allDrops() shouldHaveSize 1
    }

    test("a cue the pipeline wrote over dialogue is dropped, not spoken") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 1_000), Gap(4_000, 9_000)),
                cues = listOf(cue("desc", startMs = 2_000, variants = listOf(variant("Over the line.", 500)))),
            ),
        )

        session.playTo(5_000)
        session.history.spokenCueIds().shouldBeEmpty()
        session.history.allDrops().single().reason shouldBe DropReason.NoSilenceAtCueStart
    }

    test("a cue that arrived with no variants is dropped, not silently forgotten") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 9_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = emptyList())),
            ),
        )

        session.playTo(2_000)
        session.history.allDrops().single().reason shouldBe DropReason.NoVariants
    }

    test("a drop that loses the gap to another voice records who was holding it") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 3_000)),
                cues = listOf(
                    cue(
                        "sub",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Get down!", 1_400)),
                    ),
                    cue(
                        "desc",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                        variants = listOf(variant("Dust settles over the wreck.", 1_400)),
                    ),
                ),
            ),
        )

        session.playTo(3_000)
        session.history.spokenCueIds() shouldBe listOf(CueId("sub"))

        val reason = session.history.allDrops().single().reason.shouldBeInstanceOf<DropReason.NoVariantFits>()
        reason.floorHeldBy shouldBe UtteranceKind.Scheduled.SPOKEN_SUBTITLE
    }

    test("duck gain is non-zero only while a non-content voice is active, and returns to unity") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 10_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
        )

        session.playTo(900).contentDuck shouldBe ContentDuck.Unity
        session.playTo(1_000).contentDuck shouldBe ContentDuck.Ducked(-12.0)
        session.playTo(1_900).contentDuck shouldBe ContentDuck.Ducked(-12.0)
        session.playTo(2_000).contentDuck shouldBe ContentDuck.Unity

        session.press(uiNarration("nav", "Audio description, on")).contentDuck shouldBe ContentDuck.Ducked(-12.0)
        session.speakerReports(UtteranceId("nav")).contentDuck shouldBe ContentDuck.Unity

        // Every directive in the run agrees: ducked exactly when someone is speaking.
        session.history.forEach { directive ->
            val expected = if (directive.speaking is Speaking.Silent) {
                ContentDuck.Unity
            } else {
                ContentDuck.Ducked(-12.0)
            }
            directive.contentDuck shouldBe expected
        }
    }

    test("the duck depth comes from configuration, not from a literal buried in the policy") {
        val session = Session(trackOf(), DirectorConfig(duckDb = -9.0))
        session.press(uiNarration("nav")).contentDuck shouldBe ContentDuck.Ducked(-9.0)
    }

    test("a seek clears the queue and leaves no utterance speaking") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A long description.", 4_000)))),
            ),
        )

        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        val afterSeek = session.seekTo(9_000)
        afterSeek.speaking shouldBe Speaking.Silent
        afterSeek.contentDuck shouldBe ContentDuck.Unity

        // And it stays cleared: the cue we jumped over does not resurface.
        session.playTo(12_000).speaking shouldBe Speaking.Silent
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("seeking backwards over a cue puts it back in play") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
        )

        session.playTo(2_000)
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))

        session.seekTo(500).speaking shouldBe Speaking.Silent
        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"), CueId("desc"))
    }

    test("a pause stops all speech; a resume does not replay cues whose window has passed") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue("first", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 3_000))),
                    cue("second", startMs = 8_000, variants = listOf(variant("Snow covers the road.", 1_000))),
                ),
            ),
        )

        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        val paused = session.pause()
        paused.speaking shouldBe Speaking.Silent
        paused.contentDuck shouldBe ContentDuck.Unity

        // Time passes with the content standing still; nothing starts.
        session.tick(5_000).speaking shouldBe Speaking.Silent
        session.positionMs shouldBe 1_500

        session.resume().speaking shouldBe Speaking.Silent
        session.playTo(7_000).speaking shouldBe Speaking.Silent

        // Playback is not wedged: the next cue still gets its gap.
        session.playTo(8_000).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("second")

        session.history.spokenCueIds() shouldBe listOf(CueId("first"), CueId("second"))
    }

    test("a button press is still answered while the content is paused") {
        val session = Session(trackOf())
        session.pause()

        session.press(uiNarration("nav", "Resume")).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()
            .id shouldBe UtteranceId("nav")
    }

    test("content with no description track never produces a voice") {
        val session = Session(trackOf(gaps = listOf(Gap(0, 60_000))))
        session.playTo(30_000, stepMs = 500)

        session.history.forEach {
            it.speaking shouldBe Speaking.Silent
            it.contentDuck shouldBe ContentDuck.Unity
            it.drops.shouldBeEmpty()
        }
    }

    test("a scheduled utterance always finishes inside the silence it was granted") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 2_500), Gap(6_000, 9_000)),
                cues = listOf(
                    cue("a", startMs = 500, variants = listOf(variant("One.", 1_600))),
                    cue("b", startMs = 6_100, variants = listOf(variant("Two.", 2_000))),
                ),
            ),
        )
        session.playTo(12_000, stepMs = 50)

        session.history.mapNotNull { it.speaking as? Speaking.ScheduledUtterance }.forEach { utterance ->
            val windowEnd = session.track.silence.windowEndAt(utterance.startedAtPositionMs)
            windowEnd shouldBe session.track.silence.windowEndAt(utterance.endsAtPositionMs)
            (utterance.endsAtPositionMs + DirectorConfig.DEFAULT_GUARD_MS <= windowEnd!!) shouldBe true
        }
        session.history.spokenCueIds() shouldBe listOf(CueId("a"), CueId("b"))
    }

    test("the floor is held past the measured duration until the speaker reports") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // Measured length is up at position 2000. The speaker has not reported, so
        // the utterance still holds the floor and the content stays ducked.
        val overrunning = session.playTo(2_500)
        overrunning.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")
        overrunning.contentDuck shouldBe ContentDuck.Ducked(-12.0)
        session.history.floorTimeouts().shouldBeEmpty()

        // And it is the speaker, not the clock, that ends it.
        val released = session.speakerReports(UtteranceId("cue:desc"))
        released.speaking shouldBe Speaking.Silent
        released.contentDuck shouldBe ContentDuck.Unity
        released.floorTimeout shouldBe null
    }

    test("a speaker that never reports has the floor taken back, and the timeout is recorded") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        session.playTo(3_900).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // Measured length ran out at 2000; the slack runs out at 4000.
        val timedOut = session.playTo(4_000)
        timedOut.speaking shouldBe Speaking.Silent
        timedOut.contentDuck shouldBe ContentDuck.Unity
        timedOut.floorTimeout shouldBe FloorTimeout(
            id = UtteranceId("cue:desc"),
            kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
            predictedDurationMs = 1_000,
            slackMs = DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS,
            heldForMs = 3_000,
        )

        // Reported once, and the cue does not come back for a second try.
        session.playTo(8_000)
        session.history.floorTimeouts() shouldHaveSize 1
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("a speaker reporting on time is not an anomaly") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue("a", startMs = 1_000, variants = listOf(variant("One.", 1_000))),
                    cue("b", startMs = 5_000, variants = listOf(variant("Two.", 1_000))),
                ),
            ),
        )

        session.playTo(9_000)
        session.history.spokenCueIds() shouldBe listOf(CueId("a"), CueId("b"))
        session.history.floorTimeouts().shouldBeEmpty()
    }

    test("a speaker that finishes early releases the floor early") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue("desc", startMs = 1_000, variants = listOf(variant("A long description.", 4_000))),
                    cue("next", startMs = 1_500, variants = listOf(variant("Another one.", 1_000))),
                ),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")

        session.playTo(1_500)
        session.speakerReports(UtteranceId("cue:desc")).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("next")

        session.history.floorTimeouts().shouldBeEmpty()
    }

    test("a reactive utterance with no measured length is never timed out") {
        val session = Session(trackOf(), speakerReportsCompletion = false)

        session.press(vqaAnswer("q1", "A dragon fills the doorway.")).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()

        session.tick(60_000).speaking.shouldBeInstanceOf<Speaking.ReactiveUtterance>().id shouldBe UtteranceId("q1")
        session.history.floorTimeouts().shouldBeEmpty()
    }

    test("a measured reactive answer is timed out on the wall clock, not the content timeline") {
        val session = Session(trackOf(), speakerReportsCompletion = false)
        session.pause()

        session.press(vqaAnswer("q1", "She is holding a knife.", millis = 800))
        session.tick(2_700).speaking.shouldBeInstanceOf<Speaking.ReactiveUtterance>()

        val timedOut = session.tick(100)
        timedOut.speaking shouldBe Speaking.Silent
        timedOut.floorTimeout shouldBe FloorTimeout(
            id = UtteranceId("q1"),
            kind = UtteranceKind.Reactive.VQA_ANSWER,
            predictedDurationMs = 800,
            slackMs = DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS,
            heldForMs = 2_800,
        )
    }

    test("the timeout slack must sit outside the guard, or it fires on ordinary jitter") {
        shouldThrow<IllegalArgumentException> {
            DirectorConfig(guardMs = 250, speechTimeoutSlackMs = 250)
        }
    }

    test("playback that resumes from a saved position does not report everything behind it as dropped") {
        val cues = (0 until 40).map { index ->
            cue("c$index", startMs = 1_000L + index * 1_000L, variants = listOf(variant("Line $index.", 20_000)))
        }
        val session = Session(trackOf(gaps = listOf(Gap(0, 60_000)), cues = cues))

        // First advance lands deep into the film, the way "continue watching" does.
        session.seekTo(50_000)
        session.playTo(51_000)

        session.history.allDrops().shouldBeEmpty()
        session.history.spokenCueIds().shouldBeEmpty()
    }
})
