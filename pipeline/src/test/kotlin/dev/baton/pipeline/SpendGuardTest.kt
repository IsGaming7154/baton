package dev.baton.pipeline

import dev.baton.director.DescriptionTrackJson
import dev.baton.director.Gap
import dev.baton.pipeline.mock.SimulatedSynthesis
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The spend guard, driven with billed stand-ins that count the calls that got through.
 * Nothing here reaches AWS, and every ledger lives in a fresh temporary directory, never
 * the real one under the home directory.
 */
class SpendGuardTest : FunSpec({

    val voice = PipelineConfig.DEFAULT_VOICE
    // 1,000 characters at Polly's neural $16 per million is $0.016.
    val thousandCharacters = "a".repeat(1_000)

    test("the call that would cross the per-run cap is refused before it is made") {
        val polly = CountingSynthesis()
        val guarded = SpendGuard(SpendConfig(perRunCapUsd = 0.02, ledgerPath = freshLedger()), ONE_SECOND).guarded(polly)

        guarded.synthesize(thousandCharacters, voice)
        shouldThrow<SpendRefused> { guarded.synthesize(thousandCharacters, voice) }.message shouldContain "per-run cap"
        polly.calls shouldBe 1
    }

    test("a ledger already near the cumulative cap refuses the next call, and is left as it was") {
        val ledger = freshLedger().apply { writeText("""{"spentUsd":9.99}""") }
        val polly = CountingSynthesis()
        val guarded = SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND).guarded(polly)

        shouldThrow<SpendRefused> { guarded.synthesize(thousandCharacters, voice) }.message shouldContain "cumulative cap"
        polly.calls shouldBe 0
        ledger.readText() shouldBe """{"spentUsd":9.99}"""
    }

    test("the characters Polly reports billing replace the estimate, in the run and in the ledger") {
        val ledger = freshLedger()
        val guard = SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND)

        guard.guarded(CountingSynthesis(billedCharacters = 900)).synthesize(thousandCharacters, voice)

        guard.runUsd shouldBe (900 * 16.0 / 1e6 plusOrMinus 1e-12)
        SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND).cumulativeUsd shouldBe (900 * 16.0 / 1e6 plusOrMinus 1e-12)
        val polly = guard.report(ledger.resolveSibling("t.json"), PipelineConfig()).polly
        polly.estimatedUsd shouldBe (0.016 plusOrMinus 1e-12)
        polly.recordedUsd shouldBe (900 * 16.0 / 1e6 plusOrMinus 1e-12)
    }

    test("billing that comes back over the estimate is recorded past the cap, and the next call is refused") {
        val ledger = freshLedger()
        val polly = CountingSynthesis(billedCharacters = 1_500)
        val guard = SpendGuard(SpendConfig(perRunCapUsd = 0.02, ledgerPath = ledger), ONE_SECOND)
        val guarded = guard.guarded(polly)

        // $0.016 estimated, under the $0.02 cap; $0.024 billed, over it.
        guarded.synthesize(thousandCharacters, voice)
        guard.runUsd shouldBe (0.024 plusOrMinus 1e-12)
        SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND).cumulativeUsd shouldBe (0.024 plusOrMinus 1e-12)

        shouldThrow<SpendRefused> { guarded.synthesize("a", voice) }.message shouldContain "per-run cap"
        polly.calls shouldBe 1
    }

    test("an unbilled port's reported characters still record nothing") {
        val ledger = freshLedger()
        val guard = SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND)
        val free = object : CountingSynthesis(billedCharacters = 1_000), Unbilled {}

        guard.guarded(free).synthesize(thousandCharacters, voice)

        guard.runUsd shouldBe 0.0
        Files.exists(ledger) shouldBe false
    }

    test("the 151st Nova call of a run is refused") {
        val nova = CountingDrafter()
        val guarded = SpendGuard(SpendConfig(ledgerPath = freshLedger()), ONE_SECOND).guarded(nova)
        val ask = DraftRequest(SceneContext(MediaRef("s3://baton/clip.mp4"), Gap(0, 3_000), "", ""), 2_000, 0)

        repeat(150) { guarded.draft(ask) }
        shouldThrow<SpendRefused> { guarded.draft(ask) }.message shouldContain "150 calls"
        nova.calls shouldBe 150
    }

    test("the ledger persists across runs, while each run's own cap starts again from zero") {
        val config = SpendConfig(perRunCapUsd = 0.02, ledgerPath = freshLedger())

        SpendGuard(config, ONE_SECOND).guarded(CountingSynthesis()).synthesize(thousandCharacters, voice)
        val second = SpendGuard(config, ONE_SECOND)
        second.cumulativeUsd shouldBe (0.016 plusOrMinus 1e-9)
        // $0.032 in all, which this run's $0.02 cap would refuse if the first run counted against it.
        second.guarded(CountingSynthesis()).synthesize(thousandCharacters, voice)

        SpendGuard(config, ONE_SECOND).cumulativeUsd shouldBe (0.032 plusOrMinus 1e-9)
    }

    test("a missing ledger is nothing spent, and a corrupt one refuses to run rather than start again from zero") {
        SpendGuard(SpendConfig(ledgerPath = freshLedger()), ONE_SECOND).cumulativeUsd shouldBe 0.0
        for (corrupt in listOf("", "not json", """{"spentUsd":-1.0}""", """{"spent":3.0}""")) {
            val ledger = freshLedger().apply { writeText(corrupt) }
            withClue("ledger content '$corrupt'") {
                shouldThrow<SpendRefused> { SpendGuard(SpendConfig(ledgerPath = ledger), ONE_SECOND) }
            }
        }
    }

    test("a recorded-fixture run writes a report beside its track: every call counted and priced, nothing spent") {
        // What the same build asks of Nova and Polly, counted outside any guard.
        val recorder = RecordingDrafter(Fixtures.drafting())
        val spoken = mutableListOf<String>()
        val listening = object : Synthesis {
            override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio =
                SimulatedSynthesis().synthesize(text, voice).also { spoken += text }
        }
        Fixtures.builder(drafting = recorder, synthesis = listening).build(Fixtures.request)

        val dir = Files.createTempDirectory("baton-run")
        val trackFile = dir.resolve("sintel-clip.track.json")
        val ledger = dir.resolve("aws-spend.json")
        buildGuarded(
            Fixtures.request,
            Fixtures.transcription(),
            Fixtures.drafting(),
            SimulatedSynthesis(),
            trackFile,
            spend = SpendConfig(ledgerPath = ledger),
        )

        DescriptionTrackJson.decode(trackFile.readText()) shouldBe Fixtures.build().track
        val report = Json.decodeFromString(RunReport.serializer(), dir.resolve("sintel-clip.track.report.json").readText())

        // The worst cases a live run would have been checked against, at HANDOFF.md §6's
        // prices, for the 40 s fixture clip.
        val novaCalls = recorder.asks.size
        report.transcribe.calls shouldBe 1
        report.transcribe.estimatedUsd shouldBe (40 * 0.0001 plusOrMinus 1e-12)
        report.nova.calls shouldBe novaCalls
        report.nova.estimatedUsd shouldBe (novaCalls * ((288 * 40 + 2_000) * 0.33 + 512 * 2.75) / 1e6 plusOrMinus 1e-12)
        report.polly.calls shouldBe spoken.size
        report.polly.estimatedUsd shouldBe (spoken.sumOf { it.length } * 16.0 / 1e6 plusOrMinus 1e-12)
        listOf(report.transcribe, report.nova, report.polly).map { it.recordedUsd } shouldBe listOf(0.0, 0.0, 0.0)

        report.caps shouldBe CapState(
            perRunCapUsd = 2.0,
            runUsd = 0.0,
            runRemainingUsd = 2.0,
            cumulativeCapUsd = 10.0,
            cumulativeUsd = 0.0,
            cumulativeRemainingUsd = 10.0,
            novaCallsPerRunCap = 150,
            novaCalls = novaCalls,
        )
        report.trackPath shouldBe trackFile.toAbsolutePath().toString()
        report.novaModelId shouldBe null
        report.novaPrice shouldBe AwsPrices.NOVA_2_LITE
        report.voice shouldBe PipelineConfig.DEFAULT_VOICE
        report.descriptionLocale shouldBe "en-US"
        report.transcriptLocale shouldBe "en-US"
        // A run that spent nothing never writes the ledger.
        Files.exists(ledger) shouldBe false
    }
})

private const val ONE_SECOND: Long = 1_000L

private fun freshLedger(): Path = Files.createTempDirectory("baton-spend").resolve("aws-spend.json")

/**
 * A billed stand-in for Polly: priced as Polly, counting the calls that reached it, and
 * reporting [billedCharacters] as Polly's `x-amzn-RequestCharacters` would.
 */
private open class CountingSynthesis(private val billedCharacters: Int? = null) : Synthesis {
    var calls = 0

    override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio {
        calls++
        return RenderedAudio.ofPcm16Mono(ByteArray(3_200), voice.sampleRateHz, requestCharacters = billedCharacters)
    }
}

/** A billed stand-in for Nova, counting the calls that reached it. */
private class CountingDrafter : Drafting {
    var calls = 0

    override fun draft(request: DraftRequest): Draft {
        calls++
        return Draft("A woman kneels beside a fire.")
    }
}
