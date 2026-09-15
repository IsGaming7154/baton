package dev.baton.pipeline

import dev.baton.director.Gap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
})

private const val ONE_SECOND: Long = 1_000L

private fun freshLedger(): Path = Files.createTempDirectory("baton-spend").resolve("aws-spend.json")

/** A billed stand-in for Polly: priced as Polly, and counting the calls that reached it. */
private class CountingSynthesis : Synthesis {
    var calls = 0

    override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio {
        calls++
        return RenderedAudio.ofPcm16Mono(ByteArray(3_200), voice.sampleRateHz)
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
