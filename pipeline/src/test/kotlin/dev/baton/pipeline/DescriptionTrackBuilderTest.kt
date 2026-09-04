package dev.baton.pipeline

import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.Gap
import dev.baton.director.MeasuredDurationMs
import dev.baton.director.UtteranceKind
import dev.baton.pipeline.mock.SimulatedSynthesis
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * The builder, driven end to end over the recorded clip.
 *
 * Nothing here reaches inside the fit loop. The assertions are all statements
 * about the track that came out and the report that came with it, which is the
 * only thing anything downstream can see.
 */
class DescriptionTrackBuilderTest : FunSpec({

    val result = Fixtures.build()
    val track = result.track

    test("the track carries the derived gap map, and declares itself synthetic") {
        track.sourceId shouldBe "sintel-clip"
        track.locale shouldBe "en-US"
        track.synthetic shouldBe true
        track.gapMap shouldContainExactly listOf(
            Gap(0, 6_000),
            Gap(13_000, 16_000),
            Gap(21_000, 23_000),
            Gap(27_000, 31_500),
            Gap(34_000, 40_000),
        )
    }

    test("a cue is authored for every window that could hold one") {
        track.cues.map { it.id } shouldContainExactly listOf(
            CueId("ad-0"),
            CueId("ad-13000"),
            CueId("ad-27000"),
            CueId("ad-34000"),
        )
        track.cues.all { it.kind == UtteranceKind.Scheduled.AUDIO_DESCRIPTION } shouldBe true
    }

    test("a cue starts a lead-in after its window opens, so the duck ramp lands first") {
        track.cues.map { it.startMs } shouldContainExactly listOf(150L, 13_150L, 27_150L, 34_150L)
    }

    test("the fit loop converges: an overrunning draft is shortened and re-measured until it fits") {
        val fit = result.fits.single { it.cueId == CueId("ad-27000") }
        fit.roomMs shouldBe 4_100
        fit.draftsOverrun shouldBe 1
        fit.draftsRequested shouldBe 4
        fit.variantsAuthored shouldBe 3
        fit.converged shouldBe true

        // The first draft measured 5067 ms against 4100 ms of room. What was kept
        // is the shorter re-draft, and it is inside the room.
        val longest = track.cues.single { it.id == CueId("ad-27000") }.variants.first()
        longest.measuredDuration.millis shouldBe 3_976
    }

    test("a window whose first draft already fits costs one draft, not a wasted round trip") {
        val fit = result.fits.single { it.cueId == CueId("ad-0") }
        fit.draftsOverrun shouldBe 0
        fit.draftsRequested shouldBe 3
        fit.variantsAuthored shouldBe 3
    }

    test("a cue with no fitting variant is a reported drop, not a silent omission") {
        val drop = result.drops.single()
        drop.intendedCueId shouldBe CueId("ad-21000")
        drop.gap shouldBe Gap(21_000, 23_000)

        val reason = drop.reason.shouldBeInstanceOf<AuthoringDropReason.NoVariantFits>()
        reason.roomMs shouldBe 1_600
        reason.shortestMeasuredMs shouldBe 2_885
        reason.attempts shouldBe 3
    }

    test("the window that could hold nothing is still shipped, because the silence is real") {
        track.gapMap.contains(Gap(21_000, 23_000)) shouldBe true
        track.cues.none { it.id == CueId("ad-21000") } shouldBe true
    }

    test("every gap the builder looked at is accounted for, as a cue or as a drop") {
        (track.cues.size + result.drops.size) shouldBe track.gapMap.size
    }

    test("every emitted variant carries a duration measured off rendered audio") {
        val oracle = SimulatedSynthesis()
        for (cue in track.cues) {
            for (variant in cue.variants) {
                val rendered = oracle.synthesize(variant.text, PipelineConfig.DEFAULT_VOICE)
                variant.measuredDuration shouldBe rendered.duration
                variant.measuredDuration shouldBe
                    MeasuredDurationMs.ofRenderedAudio(rendered.frameCount, rendered.sampleRateHz)
            }
        }
    }

    test("variants run longest first and every one of them fits its window") {
        val guardMs = PipelineConfig().guardMs
        for (cue in track.cues) {
            val windowEndMs = track.silence.windowEndAt(cue.startMs)!!
            cue.variants.map { it.measuredDuration.millis } shouldBe
                cue.variants.map { it.measuredDuration.millis }.sortedDescending()
            for (variant in cue.variants) {
                (cue.startMs + variant.measuredDuration.millis + guardMs <= windowEndMs) shouldBe true
            }
        }
    }

    test("three length options per cue, which is what gives the runtime something to choose between") {
        track.cues.eachHaveVariants(3)
    }

    test("the runtime consumes the track unmodified: every authored cue speaks, and none is refused") {
        val playthrough = track.playedThrough(Fixtures.MEDIA_DURATION_MS)
        playthrough.spoken shouldContainExactly track.cues.map { it.id }
        playthrough.drops.shouldBeEmpty()
    }

    test("a variant points at the audio that was rendered to measure it, when the synthesizer kept it") {
        val stored = Fixtures
            .builder(synthesis = SimulatedSynthesis(audioRefFor = { "s3://baton/${it.hashCode()}.pcm" }))
            .build(Fixtures.request)
        stored.track.cues.flatMap { it.variants }.all { it.audioRef != null } shouldBe true
    }

    test("the drop report is legible without reading the object graph") {
        result.summary() shouldContain "sintel-clip: 4 cues, 1 drops, 18 drafts requested"
        result.summary() shouldContain "DROP ad-21000 gap 21000-23000"
    }

    test("the track survives the wire format the runtime will read it through") {
        DescriptionTrackJson.decode(DescriptionTrackJson.encode(track)) shouldBe track
    }

    test("two builds over the same fixture produce the same track") {
        Fixtures.build().track shouldBe track
    }

    test("golden file") {
        val golden = File("src/test/resources/golden/sintel-clip.track.json")
        val encoded = DescriptionTrackJson.encode(track).replace("\r\n", "\n")
        // Regenerate with -Dbaton.updateGolden=true after a deliberate change to
        // the format or the rate model, and read the diff before committing it.
        if (System.getProperty("baton.updateGolden") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(encoded + "\n")
        }
        golden.readText().replace("\r\n", "\n").trimEnd() shouldBe encoded.trimEnd()
    }

    context("the loop is bounded, and the bounds are numbers") {

        test("a drafter that never shortens is cut off at maxDraftAttempts") {
            val stubborn = RecordingDrafter(
                BrokenRecord("A very long sentence that will never fit inside two seconds of silence."),
            )
            val stubbornResult = Fixtures
                .builder(PipelineConfig(maxDraftAttempts = 4), drafting = stubborn)
                .build(Fixtures.request)

            stubborn.asks.count { it.scene.gap == Gap(21_000, 23_000) } shouldBe 4
            stubbornResult.drops
                .single { it.intendedCueId == CueId("ad-21000") }
                .reason.shouldBeInstanceOf<AuthoringDropReason.NoVariantFits>()
                .attempts shouldBe 4
        }

        test("a drafter with nothing to say drops every window, and says why") {
            val silent = Fixtures.builder(drafting = SilentDrafter).build(Fixtures.request)
            silent.track.cues.shouldBeEmpty()
            silent.drops shouldHaveSize 5
            silent.drops.all { it.reason == AuthoringDropReason.NoDraftOffered } shouldBe true
        }

        test("asking for fewer variants asks the drafter fewer times") {
            val single = Fixtures.build(PipelineConfig(variantsPerCue = 1))
            single.track.cues.eachHaveVariants(1)
            single.draftsRequested shouldBe 10
            result.draftsRequested shouldBe 18
        }
    }

    context("the conversation the fit loop actually has") {

        val recorder = RecordingDrafter(Fixtures.drafting())
        Fixtures.builder(drafting = recorder).build(Fixtures.request)
        val asks = recorder.asks.filter { it.scene.gap == Gap(13_000, 16_000) }

        test("the first ask is for the whole room") {
            asks.first().attempt shouldBe 0
            asks.first().targetDurationMs shouldBe 2_600
            asks.first().previousText shouldBe null
        }

        test("after an overrun the next ask backs off below the room") {
            asks[1].targetDurationMs shouldBe 2_210
        }

        test("after a fit the next ask is for something shorter than what just fitted") {
            asks[2].targetDurationMs shouldBe 1_402
        }

        test("each ask carries the draft it is trying to beat, so the drafter has the thread") {
            asks[1].previousText shouldBe "He turns the blade over, watching the firelight."
            asks[2].previousText shouldBe "He turns the blade over."
        }

        test("the drafter is shown the dialogue either side of the window it is writing for") {
            asks.first().scene.precedingDialogue shouldEndWith "Where did you find it"
            asks.first().scene.followingDialogue shouldStartWith "The shaman said the dragon"
            asks.first().scene.media shouldBe Fixtures.media
        }
    }

    context("configurations that disagree with themselves") {

        test("a lead-in and guard that swallow the window leave nothing to author in") {
            val overheavy = Fixtures.build(PipelineConfig(cueLeadInMs = 1_000, guardMs = 2_000))
            overheavy.drops.filter { it.reason is AuthoringDropReason.NoRoomAfterGuard }
                .map { it.intendedCueId } shouldContainExactly listOf(CueId("ad-13000"), CueId("ad-21000"))
            overheavy.track.cues.map { it.id } shouldContainExactly listOf(CueId("ad-0"), CueId("ad-34000"))
        }

        test("a variant count above the attempt ceiling is a contradiction") {
            shouldThrow<IllegalArgumentException> { PipelineConfig(variantsPerCue = 5, maxDraftAttempts = 2) }
        }
    }
})

private fun List<Cue>.eachHaveVariants(expected: Int) {
    for (cue in this) cue.variants shouldHaveSize expected
}
