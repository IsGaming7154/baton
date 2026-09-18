package dev.baton.director

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Yielding to another app's voice (FRICTION_LOG.md entry 19): while VoiceView has
 * the floor, Baton cuts what it is saying, starts nothing, drops what comes due
 * with a reason, refuses button presses, and picks up from the current position
 * when the floor comes back -- replaying nothing.
 *
 * [DirectorInputs.externalVoice] is level state the app holds from audio focus;
 * here it is [Session.externalVoice], set and ticked like the access-services
 * switches.
 */
class ExternalVoiceYieldTest : FunSpec({

    /** One long silence, three descriptions with a room each. */
    fun threeCues() = trackOf(
        gaps = listOf(Gap(1_000, 8_000)),
        cues = listOf(
            cue("a", startMs = 1_000, variants = listOf(variant("She crosses the courtyard.", 1_500))),
            cue("b", startMs = 3_000, variants = listOf(variant("The gate swings shut.", 1_500))),
            cue("c", startMs = 5_500, variants = listOf(variant("Rain on the stones.", 1_000))),
        ),
        synthetic = false,
    )

    test("the utterance speaking when another voice takes the floor is cut on that tick, and the duck lets go") {
        val session = Session(threeCues())
        session.playTo(1_500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        session.externalVoice = true
        val cut = session.tick()

        cut.speaking shouldBe Speaking.Silent
        cut.contentDuck shouldBe ContentDuck.Unity
        // A decision, not an anomaly: nothing overran.
        session.history.withdrawals().shouldBeEmpty()
    }

    test("nothing scheduled starts while yielded, and a cue whose moment comes is dropped with the yield as its reason") {
        val session = Session(threeCues())
        session.playTo(2_900)

        session.externalVoice = true
        session.playTo(3_500)

        session.history.spokenCueIds() shouldBe listOf(CueId("a"))
        val drop = session.history.allDrops().single()
        drop.cueId shouldBe CueId("b")
        drop.reason shouldBe DropReason.YieldedToExternalVoice
    }

    test("a button press while yielded is refused and reported, and is not answered when the floor comes back") {
        val session = Session(threeCues())
        session.playTo(500)
        session.externalVoice = true

        val request = uiNarration("ui-1", millis = 600)
        val pressed = session.press(request)

        pressed.speaking shouldBe Speaking.Silent
        pressed.refused shouldBe listOf(request)

        session.externalVoice = false
        session.tick().speaking shouldBe Speaking.Silent
        session.history.refusals() shouldBe listOf(request)
    }

    test("when the floor comes back the next due cue starts normally, and nothing cut or dropped is replayed") {
        val session = Session(threeCues())
        session.playTo(1_500)
        session.externalVoice = true
        session.playTo(3_500)
        session.externalVoice = false
        session.playTo(7_000)

        // "a" was cut part-way, "b" came due during the yield: neither returns.
        session.history.spokenCueIds() shouldBe listOf(CueId("a"), CueId("c"))
        session.history.allDrops().map { it.cueId to it.reason } shouldBe
            listOf(CueId("b") to DropReason.YieldedToExternalVoice)
    }

    test("a kind the viewer switched off stays silent under a yield rather than being reported as refused") {
        val session = Session(threeCues())
        session.silencedKinds = setOf(UtteranceKind.Scheduled.AUDIO_DESCRIPTION)
        session.externalVoice = true

        session.playTo(7_000)

        session.history.allDrops().shouldBeEmpty()
    }

    test("pause and seek under a yield: nothing starts until the floor is back and the content is moving") {
        val session = Session(threeCues())
        session.playTo(1_500)
        session.externalVoice = true
        session.pause()
        session.seekTo(900)
        session.resume()
        session.playTo(1_200)

        // Sought back over "a", which came due again while yielded: dropped, not queued.
        session.history.allDrops().single().reason shouldBe DropReason.YieldedToExternalVoice

        session.pause()
        session.externalVoice = false
        session.tick(500).speaking shouldBe Speaking.Silent

        session.resume()
        session.playTo(3_100)
        (session.history.last().speaking as Speaking.ScheduledUtterance).cueId shouldBe CueId("b")
    }

    test("a disclosure the yield cut off is still owed, and is spoken from the top once the floor is back") {
        val track = trackOf(gaps = listOf(Gap(0, 8_000)), synthetic = true)
        val session = Session(track, disclosure = disclosure(2_000))
        session.playTo(500).speaking.shouldBeInstanceOf<Speaking.ScheduledUtterance>()

        session.externalVoice = true
        session.playTo(1_000).speaking shouldBe Speaking.Silent

        session.externalVoice = false
        session.playTo(1_500)

        val starts = session.history.scheduledStarts()
        starts.size shouldBe 2
        starts.all { it.id == AudioDirector.DISCLOSURE_ID } shouldBe true
    }
})
