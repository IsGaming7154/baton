package dev.baton.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The point of a true-peak meter is the peaks it finds that a sample-peak meter
 * cannot, so that is what is asserted: a case constructed so the answer is known
 * exactly in advance, rather than whatever this implementation happens to say.
 */
class TruePeakTest : FunSpec({

    val rate = 48_000

    test("a quarter-rate sine sampled off its crest reads 3.01 dB above its sample peak") {
        // At exactly fs/4 with a 45 degree phase offset, every sample lands on
        // +-A/sqrt(2) and not one of them touches the crest. Sample peak is
        // therefore 3.01 dB below the real peak of the waveform, exactly, and any
        // meter that reports the sample peak is wrong by that much -- which is
        // the whole argument for oversampling before measuring against a ceiling.
        val amplitude = 0.5
        val frames = 4_800
        val samples = DoubleArray(frames) {
            amplitude * sin(2.0 * PI * (rate / 4.0) * it / rate + PI / 4.0)
        }

        val samplePeakDb = 20.0 * log10(samples.maxOf { abs(it) })
        val truePeakDb = TruePeak.dbtp(samples, channelCount = 1, sampleRateHz = rate)

        samplePeakDb shouldBe (20.0 * log10(amplitude) - 3.01 plusOrMinus 0.01)
        (truePeakDb - samplePeakDb) shouldBeGreaterThan 2.5
        (truePeakDb - samplePeakDb) shouldBeLessThan 3.2
    }

    test("a sine sampled on its crest reads its sample peak and no more") {
        // fs/4 again, this time with the samples landing on the crest. There is
        // nothing between them to find, so an interpolator that invented
        // headroom here would be adding ripple rather than measuring.
        val amplitude = 0.5
        val samples = DoubleArray(4_800) { amplitude * sin(2.0 * PI * (rate / 4.0) * it / rate) }

        val truePeakDb = TruePeak.dbtp(samples, channelCount = 1, sampleRateHz = rate)

        truePeakDb shouldBe (20.0 * log10(amplitude) plusOrMinus 0.3)
    }

    test("a slow sine is already well sampled and gains nothing from oversampling") {
        val samples = DoubleArray(48_000) { 0.5 * sin(2.0 * PI * 100.0 * it / rate) }

        TruePeak.dbtp(samples, channelCount = 1, sampleRateHz = rate) shouldBe
            (20.0 * log10(0.5) plusOrMinus 0.05)
    }

    test("the loudest channel wins, because the ceiling is per channel") {
        val frames = 4_800
        val interleaved = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            interleaved[i * 2] = 0.1 * sin(2.0 * PI * 1_000.0 * i / rate)
            interleaved[i * 2 + 1] = 0.5 * sin(2.0 * PI * 1_000.0 * i / rate)
        }

        TruePeak.dbtp(interleaved, channelCount = 2, sampleRateHz = rate) shouldBe
            (20.0 * log10(0.5) plusOrMinus 0.1)
    }

    test("digital silence is reported as a floor rather than negative infinity") {
        val silence = DoubleArray(4_800)

        TruePeak.dbtp(silence, channelCount = 1, sampleRateHz = rate).isFinite() shouldBe true
    }

    test("the gain needed to reach a ceiling is the ceiling minus the measurement") {
        // The one arithmetic the caller does with this number, checked here so
        // that the direction of the subtraction is pinned by a test rather than
        // by whoever last read the call site.
        val samples = DoubleArray(4_800) { 0.5 * sin(2.0 * PI * 997.0 * it / rate) }

        val measured = TruePeak.dbtp(samples, channelCount = 1, sampleRateHz = rate)
        val gain = 10.0.pow((-2.0 - measured) / 20.0)
        val scaled = DoubleArray(samples.size) { samples[it] * gain }

        TruePeak.dbtp(scaled, channelCount = 1, sampleRateHz = rate) shouldBe (-2.0 plusOrMinus 0.05)
    }
})
