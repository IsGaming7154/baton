package dev.baton.director

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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
        // left of the same gap once the subtitle has finished and the
        // inter-utterance gap has run.
        session.playTo(2_300).speaking
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
        session.history.withdrawals().shouldBeEmpty()

        // And it is the speaker, not the clock, that ends it.
        val released = session.speakerReports(UtteranceId("cue:desc"))
        released.speaking shouldBe Speaking.Silent
        released.contentDuck shouldBe ContentDuck.Unity
        released.withdrawal shouldBe null
    }

    test("a speaker that never reports has the floor taken back, and the withdrawal is recorded") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        session.playTo(3_900).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // Measured length ran out at 2000; the slack runs out at 4000, and there is
        // eighteen seconds of silence left, so it is our patience that expires here
        // and not the gap. Nothing was cut: the audio was over, the speaker was
        // simply never heard from about it.
        val withdrawn = session.playTo(4_000)
        withdrawn.speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("cue:desc"),
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                reason = WithdrawalReason.SpeakerOverdue(DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS),
                measuredDurationMs = 1_000,
                heldForMs = 3_000,
            ),
        )
        withdrawn.contentDuck shouldBe ContentDuck.Unity

        // Reported once, and the cue does not come back for a second try.
        session.playTo(8_000)
        session.history.withdrawals() shouldHaveSize 1
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    // -----------------------------------------------------------------------
    // Withdrawal: the director can say stop.
    //
    // The decision these tests encode is that a clipped description is the
    // cheaper artefact. A cut costs the tail of one sentence and arrives with a
    // number saying how much; an overrun costs the line of dialogue underneath
    // it and arrives looking like success. See the AudioDirector class doc.
    // -----------------------------------------------------------------------

    test("a description that overruns its gap is stopped at the dialogue, not merely reported") {
        val session = Session(
            trackOf(
                // A two-second gap, and a cue that wants three quarters of it.
                gaps = listOf(Gap(1_000, 3_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A dragon lands.", 1_500)))),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")

        // The old flat slack put the deadline at 4500 -- a second and a half past
        // the dialogue onset at 3000, every millisecond of it audible over an
        // actor, and then a clean-looking release. The window is the deadline now.
        session.playTo(2_950).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        val withdrawn = session.playTo(3_000)
        withdrawn.speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("cue:desc"),
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                reason = WithdrawalReason.OutOfSilence(windowEndMs = 3_000),
                measuredDurationMs = 1_500,
                // Held 2000 for a 1500 ms utterance: it was cut 500 ms past its own
                // length, which is the only part of it a cut can ever reach.
                heldForMs = 2_000,
            ),
        )
        withdrawn.contentDuck shouldBe ContentDuck.Unity
    }

    test("a withdrawal never cuts into the length the cue was admitted on, only into its overrun") {
        // The guarantee behind choosing truncation. A cue is admitted only if its
        // measured length plus the guard fits the window, so the window deadline
        // cannot arrive before the measured end -- whatever a cut takes, the
        // utterance had already promised to be finished with it.
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 3_000), Gap(4_000, 7_000), Gap(8_000, 30_000)),
                cues = listOf(
                    cue("a", startMs = 500, variants = listOf(variant("One.", 2_200))),
                    cue("b", startMs = 4_100, variants = listOf(variant("Two.", 2_600))),
                    cue("c", startMs = 8_100, variants = listOf(variant("Three.", 1_000))),
                ),
            ),
            speakerReportsCompletion = false,
        )
        session.playTo(20_000, stepMs = 50)

        session.history.withdrawals().forEach { withdrawal ->
            withdrawal.measuredDurationMs.shouldNotBeNull()
            (withdrawal.heldForMs >= withdrawal.measuredDurationMs!!) shouldBe true
        }
        session.history.withdrawals() shouldHaveSize 3
    }

    test("the deadline comes from the granted window, so the same cue in a longer gap is not cut") {
        // Same cue, same speaker, same failure to report. The only difference is
        // how much silence the film offered -- which is the whole claim: the
        // deadline is a fact about the gap and not a constant.
        fun sessionWithGapEnding(windowEndMs: Long) = Session(
            trackOf(
                gaps = listOf(Gap(1_000, windowEndMs)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A dragon lands.", 1_500)))),
            ),
            speakerReportsCompletion = false,
        )

        val tight = sessionWithGapEnding(3_000)
        tight.playTo(3_000)
        tight.history.withdrawals().single().reason shouldBe WithdrawalReason.OutOfSilence(windowEndMs = 3_000)

        val roomy = sessionWithGapEnding(20_000)
        roomy.playTo(3_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        roomy.history.withdrawals().shouldBeEmpty()

        // It is still bounded, just by the weaker of the two deadlines: measured
        // length at 2500, plus the slack.
        roomy.playTo(4_500).speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("cue:desc"),
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                reason = WithdrawalReason.SpeakerOverdue(DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS),
                measuredDurationMs = 1_500,
                heldForMs = 3_500,
            ),
        )
    }

    test("the tick that withdraws the floor starts nothing, so no voice begins on top of one being cut") {
        val session = Session(
            trackOf(
                // Two windows with a sliver of dialogue between them, so that the
                // description's window ends while the next one's is already open.
                gaps = listOf(Gap(1_000, 3_000), Gap(3_050, 9_000)),
                cues = listOf(
                    cue("desc", startMs = 1_000, variants = listOf(variant("A dragon lands.", 1_500))),
                    cue("next", startMs = 3_050, variants = listOf(variant("It breathes fire.", 1_000))),
                ),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")

        // At 3100 the description is past the end of its window and "next" is
        // comfortably inside its own, so it would take the floor the instant it is
        // free. It does not get it on this tick: a directive that carries a
        // withdrawal has nowhere left to put an utterance, and the speaker has
        // this moment been told to stop and has not done so yet.
        val withdrawn = session.tick(2_100)
        withdrawn.speaking.shouldBeInstanceOf<Speaking.Withdrawn>()
            .withdrawal.reason shouldBe WithdrawalReason.OutOfSilence(windowEndMs = 3_000)
        withdrawn.drops.shouldBeEmpty()

        // Once the floor is genuinely empty and the inter-utterance gap has run, it starts.
        session.tick(300).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("next")
    }

    test("a scheduled utterance that cannot be constructed inside its window is not a runtime problem") {
        // The withdrawal path never has to cope with an utterance that was doomed
        // on admission, because there is no way to make one.
        shouldThrow<IllegalArgumentException> {
            Speaking.ScheduledUtterance(
                id = UtteranceId("cue:impossible"),
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                text = "Too long for the room it was given.",
                audioRef = null,
                startedAtMs = 0,
                cueId = CueId("impossible"),
                variantIndex = 0,
                duration = measuredMs(3_000),
                startedAtPositionMs = 1_000,
                windowEndMs = 2_000,
            )
        }
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
        session.history.withdrawals().shouldBeEmpty()
    }

    test("a speaker that finishes early releases the floor early") {
        // The two cues share a start time, which is the only way one can be
        // waiting on the floor while the other is still speaking. Two cues at
        // *different* points in one window reserve room from each other, so the
        // second is not due until the first has finished and there would be no
        // handover to watch. Same beat, two voices, one of them ranked higher --
        // see CueRooms on why that case is left to the arbitration.
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue(
                        "desc",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("A long description.", 4_000)),
                    ),
                    cue("next", startMs = 1_000, variants = listOf(variant("Another one.", 1_000))),
                ),
            ),
            speakerReportsCompletion = false,
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")

        session.playTo(1_500)
        session.speakerReports(UtteranceId("cue:desc")).speaking shouldBe Speaking.Silent
        session.playTo(1_800).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("next")

        session.history.withdrawals().shouldBeEmpty()
    }

    test("a speaker that never reports on unmeasured speech cannot wedge the floor") {
        // The FRICTION_LOG entry 9 failure, stated as a policy: an engine that
        // returns success and emits nothing. There is no measured length to be
        // late against, so before the ceiling existed this held the floor -- and
        // the duck under it -- for the rest of the film.
        val session = Session(trackOf(), speakerReportsCompletion = false)
        val ceilingMs = session.config.unmeasuredSpeechCeilingMs

        session.press(vqaAnswer("q1", "A dragon fills the doorway.")).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>()

        // Held right up to the ceiling, because a long answer is a legitimate
        // answer and the viewer asked for it.
        session.tick(ceilingMs - 1).speaking
            .shouldBeInstanceOf<Speaking.ReactiveUtterance>().id shouldBe UtteranceId("q1")

        val withdrawn = session.tick(1)
        withdrawn.speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("q1"),
                kind = UtteranceKind.Reactive.VQA_ANSWER,
                reason = WithdrawalReason.NeverMeasured(ceilingMs = ceilingMs),
                measuredDurationMs = null,
                heldForMs = ceilingMs,
            ),
        )

        // And the film comes back, which is the point: the failure being recovered
        // from is a permanently ducked bed under a voice that never arrived.
        withdrawn.contentDuck shouldBe ContentDuck.Unity
        session.tick(60_000).speaking shouldBe Speaking.Silent
    }

    test("a measured reactive answer is withdrawn on the wall clock, not the content timeline") {
        val session = Session(trackOf(), speakerReportsCompletion = false)
        session.pause()

        session.press(vqaAnswer("q1", "She is holding a knife.", millis = 800))
        session.tick(2_700).speaking.shouldBeInstanceOf<Speaking.ReactiveUtterance>()

        val withdrawn = session.tick(100)
        withdrawn.speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("q1"),
                kind = UtteranceKind.Reactive.VQA_ANSWER,
                reason = WithdrawalReason.SpeakerOverdue(DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS),
                measuredDurationMs = 800,
                heldForMs = 2_800,
            ),
        )
    }

    test("the timeout slack must sit outside the guard, or it fires on ordinary jitter") {
        shouldThrow<IllegalArgumentException> {
            DirectorConfig(guardMs = 250, speechTimeoutSlackMs = 250)
        }
    }

    test("the unmeasured ceiling must sit outside the slack, or unmeasured speech is judged harder") {
        shouldThrow<IllegalArgumentException> {
            DirectorConfig(speechTimeoutSlackMs = 2_000, unmeasuredSpeechCeilingMs = 2_000)
        }
    }

    // -----------------------------------------------------------------------
    // A stall is not a pause.
    //
    // The first two tests are the same track, the same cue and the same moment,
    // and differ in exactly one input. That is the point of them: the difference
    // between a description delivered and a description cut in half is which of
    // the two the director was told, so the pair has to be read together.
    // -----------------------------------------------------------------------

    test("a stall mid-description holds the floor, and the cue is still speaking when frames return") {
        val session = Session(stallScenario())

        val started = session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        started.cueId shouldBe CueId("desc")

        // The stream runs dry. Nobody decided anything, so nothing is given up:
        // the same utterance instance is still named, which is what stops the
        // player from calling stop() on a sentence that is halfway out.
        val stalled = session.stall()
        val holding = stalled.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        holding.id shouldBe started.id
        holding.startedAtMs shouldBe started.startedAtMs

        // And the duck holds with it, so the film does not come back up under a
        // narrator who never stopped talking.
        stalled.contentDuck shouldBe ContentDuck.Ducked(DirectorConfig.DEFAULT_DUCK_DB)

        // Time passes on the wall clock while the content stands still.
        session.tick(400)
        session.positionMs shouldBe 1_500

        val resumed = session.resume().speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        resumed.id shouldBe started.id
        resumed.startedAtMs shouldBe started.startedAtMs

        // Nothing is owed to a cue that was held rather than stopped: it plays
        // out the once and is not started again on the way out of the stall.
        session.playTo(4_500)
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("a user pause mid-description clears the floor, and the cue does not re-speak on resume") {
        val session = Session(stallScenario())

        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")

        val paused = session.pause()
        paused.speaking shouldBe Speaking.Silent
        paused.contentDuck shouldBe ContentDuck.Unity

        session.tick(400)
        session.resume().speaking shouldBe Speaking.Silent

        // It had its gap. The viewer stopped watching the scene it describes, and
        // hearing it again from the top would describe a moment already gone.
        session.playTo(4_500).speaking shouldBe Speaking.Silent
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
        session.history.stalls().shouldBeEmpty()
    }

    test("a pause during a stall still stops the speech: a decision outranks an interruption") {
        val session = Session(stallScenario())
        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        session.stall().speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // The viewer reaches for the button while the picture is frozen. They have
        // still decided to stop, and the stall they decided it during does not
        // make the decision any less theirs.
        val paused = session.pause()
        paused.speaking shouldBe Speaking.Silent
        paused.contentDuck shouldBe ContentDuck.Unity
    }

    test("the schedule does not advance while the content is stalled, and resumes when frames do") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("b", startMs = 1_500, variants = listOf(variant("Snow covers the road.", 1_000)))),
            ),
        )
        session.playTo(1_400).speaking shouldBe Speaking.Silent

        // Ten seconds of wall clock and not one millisecond of film. A cue is
        // eligible on the content timeline, so a frozen timeline schedules
        // nothing -- admitting one here would be admitting it against a stopped
        // clock.
        session.stall()
        session.tick(10_000).speaking shouldBe Speaking.Silent
        session.positionMs shouldBe 1_400

        session.resume()
        session.playTo(1_500).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("b")
    }

    test("a stall shorter than the threshold passes unreported; one longer than it is an anomaly") {
        val session = Session(trackOf(gaps = listOf(Gap(0, 20_000))))
        val thresholdMs = session.config.stallAnomalyMs
        session.playTo(1_000)

        session.stall()
        session.tick(thresholdMs - 100).stall shouldBe null
        session.resume().stall shouldBe null

        // The same hiccup, held a little longer.
        session.stall()
        session.tick(thresholdMs - 100).stall shouldBe null
        val reported = session.tick(200).stall.shouldNotBeNull()
        reported.atPositionMs shouldBe 1_000
        reported.stalledForMs shouldBe thresholdMs + 100
        reported.thresholdMs shouldBe thresholdMs
        reported.floorHeldBy shouldBe null

        // Once, not once per tick: the second tick of the same stall is not news.
        session.tick(30_000).stall shouldBe null
        session.history.stalls() shouldHaveSize 1
    }

    test("a stall that crosses the threshold mid-description names the utterance it ran under") {
        val session = Session(stallScenario())
        val started = session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        session.stall()
        val directive = session.tick(session.config.stallAnomalyMs + 500)

        val reported = directive.stall.shouldNotBeNull()
        reported.floorHeldBy shouldBe started.id
        reported.atPositionMs shouldBe 1_500

        // Reporting it is not the same as giving up on it. The director says what
        // it saw and goes on holding the floor.
        directive.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().id shouldBe started.id
        directive.contentDuck shouldBe ContentDuck.Ducked(DirectorConfig.DEFAULT_DUCK_DB)
    }

    test("buffering before anything has played is not a stall and is not reported") {
        val session = Session(trackOf(gaps = listOf(Gap(0, 20_000))))

        // The player exists but is not moving, and then starts filling its buffer:
        // the shape of a cold start from a network source. Nothing has been
        // interrupted, so there is nothing to report -- and an anomaly that fired
        // on every launch would stop being read.
        session.pause()
        session.stall()
        session.tick(30_000).stall shouldBe null
        session.history.stalls().shouldBeEmpty()
    }

    test("a stall freezes the window deadline with the dialogue it protects against") {
        // A tight window: the description is granted 900 ms and uses 600 of it, so
        // its content deadline sits 900 ms after it starts.
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 1_900)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate.", 600)))),
            ),
        )
        val started = session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // The stream runs dry for 1200 ms -- a third longer than the deadline is
        // from the start of the utterance. A deadline that ran on the wall clock
        // would cut the sentence here, to protect it from a line of dialogue that
        // cannot arrive, because the picture that would carry it is frozen. That
        // is the artefact the stall work removed, and it stays removed.
        session.stall()
        val held = session.tick(1_200)
        held.speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().startedAtMs shouldBe started.startedAtMs
        session.positionMs shouldBe 1_000

        // Frames return, the sentence finishes of its own accord, nothing was cut.
        session.resume()
        session.playTo(1_700).speaking shouldBe Speaking.Silent
        session.history.withdrawals().shouldBeEmpty()
        session.history.spokenCueIds() shouldBe listOf(CueId("desc"))
    }

    test("a stall cannot wedge the floor: the wall-clock ceiling runs through it") {
        // The other half of the same decision. The window deadline is frozen with
        // the content and can never be reached here, because the position never
        // moves again -- so if the ceiling froze too, this speaker would hold the
        // floor and the duck for as long as the network stayed away.
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 60_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
            speakerReportsCompletion = false,
        )
        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        session.stall()
        session.tick(2_900).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        // A correct speaker would have reported long ago: this utterance's audio
        // plays from a local file that did not stall when the video did, so it ran
        // out on the wall clock at 2000 whatever the buffer was doing.
        val withdrawn = session.tick(100)
        withdrawn.speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = UtteranceId("cue:desc"),
                kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                reason = WithdrawalReason.SpeakerOverdue(DirectorConfig.DEFAULT_SPEECH_TIMEOUT_SLACK_MS),
                measuredDurationMs = 1_000,
                heldForMs = 3_000,
            ),
        )
        withdrawn.contentDuck shouldBe ContentDuck.Unity
        session.positionMs shouldBe 1_000
    }

    test("a stall threshold inside the guard is rejected: the guard already absorbs that much") {
        shouldThrow<IllegalArgumentException> {
            DirectorConfig(guardMs = 250, stallAnomalyMs = 250)
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

    // -----------------------------------------------------------------------
    // The synthetic-speech disclosure.
    //
    // One sentence, owed once per session by a synthetic track, admitted by the
    // same rule as every other scheduled voice. See the AudioDirector class doc
    // for why it waits for silence, why it outranks the cues, and what ends the
    // debt.
    // -----------------------------------------------------------------------

    test("a synthetic track tells the viewer so at the first silence that holds it, ahead of the description due there") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 8_000)),
                cues = listOf(cue("desc", startMs = 1_500, variants = listOf(variant("A gate swings shut.", 1_000)))),
            ),
            disclosure = disclosure(2_000),
        )

        // Dialogue until 1000: nothing, the disclosure included.
        session.playTo(900).speaking shouldBe Speaking.Silent

        val told = session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        told.id shouldBe AudioDirector.DISCLOSURE_ID
        told.kind shouldBe UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE
        told.cueId shouldBe null
        told.text shouldBe "Descriptions in this title use a synthetic voice."
        told.windowEndMs shouldBe 8_000
        session.history.last().contentDuck shouldBe ContentDuck.Ducked(DirectorConfig.DEFAULT_DUCK_DB)

        // The description came due at 1500 and waits for the floor, as it would
        // behind any voice; it is not cut in, and it is not dropped.
        session.playTo(2_900).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().id shouldBe told.id
        session.playTo(3_300).speaking
            .shouldBeInstanceOf<Speaking.ScheduledUtterance>()
            .cueId shouldBe CueId("desc")

        session.playTo(20_000)
        session.history.scheduledStarts().map { it.id } shouldBe listOf(AudioDirector.DISCLOSURE_ID, UtteranceId("cue:desc"))
        session.history.allDrops().shouldBeEmpty()
    }

    test("the disclosure is never spoken over dialogue: a silence too short for it is passed over") {
        val session = Session(
            trackOf(
                // 2000 ms of opening silence cannot hold 2000 ms of disclosure plus
                // the guard; the second window can.
                gaps = listOf(Gap(0, 2_000), Gap(4_000, 9_000)),
                cues = listOf(cue("early", startMs = 500, variants = listOf(variant("Rain.", 1_000)))),
            ),
            disclosure = disclosure(2_000),
        )

        // The price of refusing to wait for a long enough silence before describing
        // anything, paid in the open: the description that fits the short window
        // is spoken there, before the viewer has been told.
        session.playTo(3_900)
        session.history.scheduledStarts().map { it.id } shouldBe listOf(UtteranceId("cue:early"))

        val told = session.playTo(4_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        told.id shouldBe AudioDirector.DISCLOSURE_ID
        told.startedAtPositionMs shouldBe 4_000
        told.windowEndMs shouldBe 9_000
    }

    test("a disclosure heard in full is not repeated after a seek") {
        val session = Session(trackOf(gaps = listOf(Gap(0, 20_000))), disclosure = disclosure(1_000))

        session.playTo(3_000)
        session.history.scheduledStarts() shouldHaveSize 1

        // Back to the top of the film. The cues ahead are released; the viewer has
        // already been told, and a jump inside the same film does not un-tell them.
        session.seekTo(0).speaking shouldBe Speaking.Silent
        session.playTo(10_000)
        session.history.scheduledStarts() shouldHaveSize 1
    }

    test("a seek that cuts the disclosure off leaves it owed, and it is spoken again from the top") {
        val session = Session(trackOf(gaps = listOf(Gap(0, 20_000))), disclosure = disclosure(2_000))

        val first = session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        first.id shouldBe AudioDirector.DISCLOSURE_ID

        // Half a sentence is not a disclosure. The seek tick is silent, as every
        // seek tick is; the next one starts it again, from the top.
        session.seekTo(0).speaking shouldBe Speaking.Silent
        val again = session.tick().speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        again.id shouldBe AudioDirector.DISCLOSURE_ID
        again.startedAtPositionMs shouldBe 100
        (again.startedAtMs != first.startedAtMs) shouldBe true

        // This time it is heard to the end, and that discharges it for good.
        session.playTo(3_000)
        session.seekTo(0)
        session.playTo(5_000)
        session.history.scheduledStarts() shouldHaveSize 2
    }

    test("a track that is not synthetic owes no disclosure, even when one is handed over") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A gate swings shut.", 1_000)))),
                synthetic = false,
            ),
            disclosure = disclosure(1_000),
        )

        session.playTo(10_000)
        session.history.scheduledStarts().map { it.id } shouldBe listOf(UtteranceId("cue:desc"))
    }

    test("a disclosure withdrawn at the end of its silence is not retried") {
        val session = Session(
            trackOf(gaps = listOf(Gap(1_000, 3_500), Gap(5_000, 20_000))),
            speakerReportsCompletion = false,
            disclosure = disclosure(2_000),
        )

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().id shouldBe
            AudioDirector.DISCLOSURE_ID

        // Held to the window like any scheduled voice: stopped at the dialogue.
        session.playTo(3_500).speaking shouldBe Speaking.Withdrawn(
            Withdrawal(
                id = AudioDirector.DISCLOSURE_ID,
                kind = UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE,
                reason = WithdrawalReason.OutOfSilence(windowEndMs = 3_500),
                measuredDurationMs = 2_000,
                heldForMs = 2_500,
            ),
        )

        // Its whole measured length had elapsed before the cut, so the sentence had
        // been given all of its time; a speaker that did not deliver it in that time
        // would not deliver it on a second try either, and would take a silence
        // from the descriptions every time it failed.
        session.playTo(15_000)
        session.history.scheduledStarts() shouldHaveSize 1
        session.history.withdrawals() shouldHaveSize 1
    }

    test("a description squeezed out while the disclosure speaks is dropped with the disclosure named as the holder") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 4_500)),
                cues = listOf(
                    // Its room runs out at 2000, where "later" starts.
                    cue("desc", startMs = 1_200, variants = listOf(variant("A gate swings shut.", 1_000))),
                    cue("later", startMs = 2_000, variants = listOf(variant("Rain.", 500))),
                ),
            ),
            disclosure = disclosure(2_000),
        )

        session.playTo(4_000)

        session.history.scheduledStarts().map { it.id } shouldBe
            listOf(AudioDirector.DISCLOSURE_ID, UtteranceId("cue:later"))
        session.history.allDrops().single().reason shouldBe DropReason.NoVariantFits(
            shortestVariantMs = 1_000,
            availableMs = 800,
            guardMs = DirectorConfig.DEFAULT_GUARD_MS,
            floorHeldBy = UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE,
            nextCueStartMs = 2_000,
        )
    }

    // -----------------------------------------------------------------------
    // Subtitles are bookings, not competitors (issue #5).
    //
    // Subtitle lines are known in advance, so they are booked against the
    // timeline before descriptions are placed. See CueRooms for exactly what a
    // booking reserves, and the AudioDirector class doc for the disclosure.
    // -----------------------------------------------------------------------

    test("a description that would run into a booked subtitle is not started, even where dialogue is silent") {
        val session = Session(
            trackOf(
                // Nineteen seconds of silence and no dialogue anywhere near: the only
                // thing in the description's way is the subtitle booked at 3000.
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue("desc", startMs = 1_000, variants = listOf(variant("Rain sheets off the station roof.", 2_000))),
                    cue(
                        "sub",
                        startMs = 3_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Where have you been?", 1_000)),
                    ),
                ),
            ),
        )

        val directive = session.playTo(1_000)
        directive.speaking shouldBe Speaking.Silent
        directive.drops.single().reason shouldBe DropReason.RunsIntoBookedSubtitle(
            subtitleCueId = CueId("sub"),
            subtitleStartMs = 3_000,
            shortestVariantMs = 2_000,
            availableMs = 2_000,
            guardMs = DirectorConfig.DEFAULT_GUARD_MS,
            floorHeldBy = null,
        )

        session.playTo(3_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("sub")
        session.playTo(10_000)
        session.history.spokenCueIds() shouldBe listOf(CueId("sub"))
    }

    test("a description written after a subtitle's start does not shorten the booking; it queues behind it") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue(
                        "sub",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("I told you not to follow me.", 2_000)),
                    ),
                    cue("desc", startMs = 1_500, variants = listOf(variant("She turns away.", 1_000))),
                ),
            ),
        )

        // Rooms used to be drawn without regard to kind, so the description at 1500
        // cut this subtitle's room to 500 ms and it was dropped for scenery.
        val subtitle = session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        subtitle.cueId shouldBe CueId("sub")
        subtitle.roomEndMs shouldBe 20_000

        session.playTo(2_900).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("sub")
        session.playTo(3_300).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("desc")
        session.history.allDrops().shouldBeEmpty()
    }

    test("a subtitle's room still ends at the next booked subtitle, and the drop names it") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue(
                        "first",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Nobody leaves this valley.", 2_000)),
                    ),
                    cue(
                        "second",
                        startMs = 2_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Then I stay.", 500)),
                    ),
                ),
            ),
        )

        session.playTo(3_000)
        session.history.spokenCueIds() shouldBe listOf(CueId("second"))
        session.history.allDrops().single().reason shouldBe DropReason.RunsIntoBookedSubtitle(
            subtitleCueId = CueId("second"),
            subtitleStartMs = 2_000,
            shortestVariantMs = 2_000,
            availableMs = 1_000,
            guardMs = DirectorConfig.DEFAULT_GUARD_MS,
            floorHeldBy = null,
        )
    }

    test("the disclosure does not start if it would run into a booked subtitle") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(0, 20_000)),
                cues = listOf(
                    cue(
                        "sub",
                        startMs = 1_500,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Where have you been?", 1_000)),
                    ),
                ),
            ),
            disclosure = disclosure(2_000),
        )

        // The window would hold it from the first tick; the booking at 1500 does not.
        session.playTo(1_400).speaking shouldBe Speaking.Silent

        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("sub")
        val told = session.playTo(2_800).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        told.id shouldBe AudioDirector.DISCLOSURE_ID
        told.startedAtPositionMs shouldBe 2_800
        told.roomEndMs shouldBe 20_000

        session.playTo(10_000)
        session.history.scheduledStarts().map { it.id } shouldBe listOf(UtteranceId("cue:sub"), AudioDirector.DISCLOSURE_ID)
        session.history.allDrops().shouldBeEmpty()
    }

    test("a subtitle that is due takes the floor ahead of the disclosure, whose ranking stops at a booking") {
        val session = Session(
            trackOf(
                gaps = listOf(Gap(1_000, 20_000)),
                cues = listOf(
                    cue(
                        "sub",
                        startMs = 1_000,
                        kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                        variants = listOf(variant("Where have you been?", 1_000)),
                    ),
                ),
            ),
            disclosure = disclosure(2_000),
        )

        // Both fit the silence that opens at 1000. The subtitle's booking begins there.
        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("sub")
        session.playTo(2_300).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().id shouldBe
            AudioDirector.DISCLOSURE_ID

        session.playTo(10_000)
        session.history.scheduledStarts().map { it.id } shouldBe listOf(UtteranceId("cue:sub"), AudioDirector.DISCLOSURE_ID)
    }

    // -----------------------------------------------------------------------
    // The inter-utterance gap.
    //
    // On the device a spoken subtitle ended and a description started 47 ms
    // later, in the same synthetic voice, and it was heard as one voice running
    // over another. Scheduled starts now wait for the gap after any release.
    // -----------------------------------------------------------------------

    test("a cue due 47 ms after the previous voice ends waits for the gap, then starts") {
        val session = Session(handoffTrack(windowEndMs = 20_000, descriptionMs = 1_000))

        session.playTo(1_000).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("s10")
        // The speaker reports at 2000; the description is due at 2047.
        session.playTo(2_047).speaking shouldBe Speaking.Silent
        session.playTo(2_299, stepMs = 1).speaking shouldBe Speaking.Silent

        val started = session.tick(1).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        started.cueId shouldBe CueId("d2")
        started.startedAtPositionMs shouldBe 2_000 + DirectorConfig.DEFAULT_INTER_UTTERANCE_GAP_MS
        session.history.allDrops().shouldBeEmpty()
    }

    test("a cue the gap costs its room is dropped with the ordinary reason, and with no gap it would have spoken") {
        // 300 ms of description plus the guard fits the 553 ms left at 2047, where the
        // gap holds it back, and not the 453 ms left at the next tick, 2147, still inside it.
        val track = handoffTrack(windowEndMs = 2_600, descriptionMs = 300)

        val session = Session(track)
        session.playTo(2_047)
        session.playTo(2_600)
        session.history.spokenCueIds() shouldBe listOf(CueId("s10"))
        session.history.allDrops().single().reason shouldBe DropReason.NoVariantFits(
            shortestVariantMs = 300,
            availableMs = 453,
            guardMs = DirectorConfig.DEFAULT_GUARD_MS,
            floorHeldBy = null,
        )

        val ungapped = Session(track, DirectorConfig(interUtteranceGapMs = 0))
        ungapped.playTo(2_047)
        ungapped.playTo(2_600)
        ungapped.history.spokenCueIds() shouldBe listOf(CueId("s10"), CueId("d2"))
        ungapped.history.scheduledStarts().last().startedAtPositionMs shouldBe 2_047
    }

    test("a button press straight after a release is answered at once; only scheduled speech waits") {
        val session = Session(handoffTrack(windowEndMs = 20_000, descriptionMs = 1_000))

        session.playTo(2_000).speaking shouldBe Speaking.Silent
        val answer = session.press(uiNarration("ui", "Pause", millis = 500)).speaking
        answer.shouldBeInstanceOf<Speaking.ReactiveUtterance>().id shouldBe UtteranceId("ui")

        // The answer's own release at 2500 is a release like any other.
        session.playTo(2_700).speaking shouldBe Speaking.Silent
        session.playTo(2_800).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>().cueId shouldBe CueId("d2")
    }

    test("a seek clears the gap, so the first cue at the new position is not held back by it") {
        val session = Session(handoffTrack(windowEndMs = 20_000, descriptionMs = 1_000))

        session.playTo(2_000)
        // Back over the subtitle, inside what would still be the gap from 2000.
        session.seekTo(1_000).speaking shouldBe Speaking.Silent
        val replayed = session.tick().speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()
        replayed.cueId shouldBe CueId("s10")
        replayed.startedAtPositionMs shouldBe 1_100
    }
})

/**
 * The handoff from the device log: subtitle s10 speaks from 1000 to 2000, and
 * description d2 is due 47 ms after it ends, in a silence ending at [windowEndMs].
 */
private fun handoffTrack(windowEndMs: Long, descriptionMs: Long): DescriptionTrack = trackOf(
    gaps = listOf(Gap(0, windowEndMs)),
    cues = listOf(
        cue(
            "s10",
            startMs = 1_000,
            kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
            variants = listOf(variant("Where have you been?", 1_000)),
        ),
        cue("d2", startMs = 2_047, variants = listOf(variant("She turns away.", descriptionMs))),
    ),
)

/**
 * One description, comfortably inside a long silence, three seconds long and due
 * at a second in. Shared by the stall tests so that the only thing separating
 * them is the input under examination.
 */
private fun stallScenario(): DescriptionTrack = trackOf(
    gaps = listOf(Gap(0, 20_000)),
    cues = listOf(cue("desc", startMs = 1_000, variants = listOf(variant("A dragon lands.", 3_000)))),
)
