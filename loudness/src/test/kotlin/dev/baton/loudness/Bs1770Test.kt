package dev.baton.loudness

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Conformance, not regression.
 *
 * The first two cases are EBU Tech 3341's published compliance signals with
 * their published expected results, which is the difference between a test that
 * proves the implementation is right and one that proves it has not changed. A
 * 1 kHz sine at -23.0 dBFS in both channels must read -23.0 LUFS, to within the
 * +-0.1 LU tolerance the document allows a compliant meter. Getting that number
 * requires the K-weighting shelf, the RLB high-pass, the channel summation and
 * the -0.691 dB calibration offset all to be simultaneously correct: it is a
 * single number that only comes out right for one reason.
 *
 * The gating cases are constructed rather than taken from the EBU set, because
 * the published test files are not redistributable and the durations are not
 * something to reproduce from memory. Each one instead states the arithmetic the
 * standard prescribes and asserts the result of it, so the expectation is
 * derived from the specification and not from a previous run of this code.
 */
class Bs1770Test : FunSpec({

    val rate = 48_000

    // EBU Tech 3341 compliance cases 1 and 2.

    test("a 1 kHz stereo sine at -23 dBFS reads -23.0 LUFS") {
        val signal = sine(rate, hz = 1_000.0, seconds = 20.0, dbfs = -23.0, channels = 2)

        Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate)!! shouldBe (-23.0 plusOrMinus 0.1)
    }

    test("a 1 kHz stereo sine at -33 dBFS reads -33.0 LUFS") {
        val signal = sine(rate, hz = 1_000.0, seconds = 20.0, dbfs = -33.0, channels = 2)

        Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate)!! shouldBe (-33.0 plusOrMinus 0.1)
    }

    // The case this codebase actually runs. Every rendered utterance is mono, and
    // a mono file measured as though it were stereo reads 3 dB hot -- which would
    // put every utterance we ship at the wrong end of Amazon's +-2 LU window
    // while every stereo test above still passed.

    test("the same sine in one channel reads 3.01 LU quieter, because loudness sums over channels") {
        val signal = sine(rate, hz = 1_000.0, seconds = 20.0, dbfs = -23.0, channels = 1)

        Bs1770.integratedLufs(signal, channelCount = 1, sampleRateHz = rate)!! shouldBe (-26.01 plusOrMinus 0.1)
    }

    // The two gates, each shown to do its own job.

    test("the relative gate discards background 17 LU under the foreground") {
        // 20 s at -40 LUFS, 20 s at -23 LUFS, 20 s at -40 LUFS.
        //
        // Ungated, the whole thing measures -27.6 LUFS: z = (40*10^-3.9309 +
        // 20*10^-2.2309)/60 = 2.036e-3, and -0.691 + 10*log10(2.036e-3) = -27.60.
        // The relative gate therefore lands at -37.60 LUFS, the -40 LUFS blocks
        // fall under it, and what is left is the -23 LUFS passage alone.
        val signal = sine(rate, 1_000.0, seconds = 20.0, dbfs = -40.0, channels = 2) +
            sine(rate, 1_000.0, seconds = 20.0, dbfs = -23.0, channels = 2) +
            sine(rate, 1_000.0, seconds = 20.0, dbfs = -40.0, channels = 2)

        // Slightly wider than the +-0.1 LU meter tolerance: the 400 ms blocks
        // that straddle the two boundaries are half-loud and survive the gate,
        // which pulls the answer down by a few hundredths.
        Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate)!! shouldBe (-23.0 plusOrMinus 0.2)
    }

    test("the relative gate keeps background only 8 LU under the foreground") {
        // The same shape at -31 LUFS instead of -40. Ungated it measures -26.58,
        // so the gate sits at -36.58 and the quiet passage stays in. A gate that
        // discarded it would be measuring the loudest passage rather than the
        // programme, which is the failure mode the relative gate exists to have
        // bounded at exactly 10 LU.
        val signal = sine(rate, 1_000.0, seconds = 20.0, dbfs = -31.0, channels = 2) +
            sine(rate, 1_000.0, seconds = 20.0, dbfs = -23.0, channels = 2) +
            sine(rate, 1_000.0, seconds = 20.0, dbfs = -31.0, channels = 2)

        val measured = Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate)!!

        // z = (40*10^-3.0309 + 20*10^-2.2309)/60 = 2.5789e-3, and
        // -0.691 + 10*log10(2.5789e-3) = -26.58 LUFS. Nothing is discarded, so
        // the gated answer is the ungated one.
        measured shouldBe (-26.58 plusOrMinus 0.3)
    }

    test("a signal entirely below the absolute gate has no measurable loudness") {
        val signal = sine(rate, 1_000.0, seconds = 5.0, dbfs = -80.0, channels = 2)

        Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate).shouldBeNull()
    }

    test("digital silence has no measurable loudness rather than negative infinity") {
        val signal = DoubleArray(rate * 2 * 5)

        Bs1770.integratedLufs(signal, channelCount = 2, sampleRateHz = rate).shouldBeNull()
    }

    test("audio shorter than one gating block cannot be measured") {
        // 300 ms, against a 400 ms block. There is no partial-block rule in the
        // standard and inventing one would produce a number that no other meter
        // would reproduce.
        val signal = sine(rate, 1_000.0, seconds = 0.3, dbfs = -23.0, channels = 1)

        Bs1770.integratedLufs(signal, channelCount = 1, sampleRateHz = rate).shouldBeNull()
    }

    // The rates this actually runs at. The K-weighting coefficients in the
    // standard are printed for 48 kHz; used verbatim at 24 kHz they put the
    // shelf an octave low and every reading is wrong. They are derived here from
    // the analogue prototype with a tan() prewarp instead, so the same signal
    // has to measure the same at every rate the engines produce.

    test("the same signal measures the same at 16, 24 and 48 kHz") {
        val at48 = Bs1770.integratedLufs(sine(48_000, 1_000.0, 5.0, -23.0, 1), 1, 48_000)!!
        val at24 = Bs1770.integratedLufs(sine(24_000, 1_000.0, 5.0, -23.0, 1), 1, 24_000)!!
        val at16 = Bs1770.integratedLufs(sine(16_000, 1_000.0, 5.0, -23.0, 1), 1, 16_000)!!

        at24 shouldBe (at48 plusOrMinus 0.1)
        at16 shouldBe (at48 plusOrMinus 0.1)
    }

    test("K-weighting boosts the presence region and rejects rumble") {
        // The shape of the weighting, checked at two frequencies where it has an
        // opinion: a +4 dB shelf above ~2 kHz, and the RLB high-pass whose corner
        // sits at 38.1 Hz with a Q of 0.5, so 40 Hz lands about 5.6 dB down.
        val presence = Bs1770.integratedLufs(sine(rate, 4_000.0, 5.0, -23.0, 1), 1, rate)!!
        val reference = Bs1770.integratedLufs(sine(rate, 1_000.0, 5.0, -23.0, 1), 1, rate)!!
        val rumble = Bs1770.integratedLufs(sine(rate, 40.0, 5.0, -23.0, 1), 1, rate)!!

        (presence - reference) shouldBe (3.3 plusOrMinus 0.5)
        (rumble - reference) shouldBe (-6.3 plusOrMinus 1.0)
    }

    test("an unsupported channel count is rejected rather than silently mismeasured") {
        val thrown = runCatching {
            Bs1770.integratedLufs(DoubleArray(48_000 * 3), channelCount = 0, sampleRateHz = rate)
        }.exceptionOrNull()

        (thrown is IllegalArgumentException) shouldBe true
    }
})

/**
 * A sine of [seconds] at [dbfs], the same in every channel.
 *
 * Interleaved, which is the layout everything downstream of a decoder uses, so
 * that the tests exercise the same de-interleaving the production path does.
 */
internal fun sine(rate: Int, hz: Double, seconds: Double, dbfs: Double, channels: Int): DoubleArray {
    val frames = (rate * seconds).toInt()
    val amplitude = 10.0.pow(dbfs / 20.0)
    val out = DoubleArray(frames * channels)
    for (i in 0 until frames) {
        val v = amplitude * sin(2.0 * PI * hz * i / rate)
        for (c in 0 until channels) out[i * channels + c] = v
    }
    return out
}
