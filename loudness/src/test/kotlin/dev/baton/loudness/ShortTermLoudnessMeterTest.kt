package dev.baton.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The meter is the same measurement as [Bs1770.integratedLufs] over a sliding
 * window, so the tests it has to pass are the same conformance numbers -- fed a
 * sample at a time, from a thread that cannot afford to allocate.
 */
class ShortTermLoudnessMeterTest : FunSpec({

    val rate = 48_000

    test("a full window of a -23 LUFS stereo sine reads -23.0") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(sine(rate, 1_000.0, seconds = 4.0, dbfs = -23.0, channels = 2), channels = 2)

        meter.lufs()!! shouldBe (-23.0 plusOrMinus 0.1)
    }

    test("nothing is reported until a full three seconds has arrived") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(sine(rate, 1_000.0, seconds = 2.5, dbfs = -23.0, channels = 2), channels = 2)
        meter.lufs().shouldBeNull()

        meter.feed(sine(rate, 1_000.0, seconds = 1.0, dbfs = -23.0, channels = 2), channels = 2)
        meter.lufs()!! shouldBe (-23.0 plusOrMinus 0.1)
    }

    test("the window slides, so an old loud passage stops counting") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(sine(rate, 1_000.0, seconds = 3.0, dbfs = -14.0, channels = 2), channels = 2)
        meter.lufs()!! shouldBe (-14.0 plusOrMinus 0.1)

        // Four seconds of quiet is more than the window holds, so the loud
        // passage must be entirely gone -- not averaged in at a lower weight.
        meter.feed(sine(rate, 1_000.0, seconds = 4.0, dbfs = -38.0, channels = 2), channels = 2)
        meter.lufs()!! shouldBe (-38.0 plusOrMinus 0.1)
    }

    test("a quiet passage is reported as quiet rather than gated away") {
        // The distinction that decides whether a near-silent scene gets ducked as
        // though it were an explosion. The integrated measurement discards
        // anything below -70 LUFS; the short-term window must not.
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(sine(rate, 1_000.0, seconds = 4.0, dbfs = -75.0, channels = 2), channels = 2)

        meter.lufs()!! shouldBe (-75.0 plusOrMinus 0.2)
    }

    test("a mono stream is measured as mono") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 1)

        meter.feed(sine(rate, 1_000.0, seconds = 4.0, dbfs = -23.0, channels = 1), channels = 1)

        meter.lufs()!! shouldBe (-26.01 plusOrMinus 0.1)
    }

    test("a reset forgets the window, because the frames across a seek are not adjacent") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(sine(rate, 1_000.0, seconds = 4.0, dbfs = -23.0, channels = 2), channels = 2)
        meter.lufs()!! shouldBe (-23.0 plusOrMinus 0.1)

        meter.reset()
        meter.lufs().shouldBeNull()

        meter.feed(sine(rate, 1_000.0, seconds = 1.0, dbfs = -23.0, channels = 2), channels = 2)
        meter.lufs().shouldBeNull()
    }

    test("digital silence has no loudness rather than negative infinity") {
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)

        meter.feed(DoubleArray(rate * 2 * 4), channels = 2)

        meter.lufs().shouldBeNull()
    }

    test("the meter agrees with the offline measurement on the same audio") {
        // One algorithm, two entry points. They are allowed to differ only by the
        // gating, which is why the signal here is uniform enough for the gates to
        // have nothing to do.
        val signal = sine(rate, 1_000.0, seconds = 3.0, dbfs = -28.0, channels = 2)
        val meter = ShortTermLoudnessMeter(rate, channelCount = 2)
        meter.feed(signal, channels = 2)

        meter.lufs()!! shouldBe (Bs1770.integratedLufs(signal, 2, rate)!! plusOrMinus 0.1)
    }
})

private fun ShortTermLoudnessMeter.feed(interleaved: DoubleArray, channels: Int) {
    var i = 0
    while (i < interleaved.size) {
        for (c in 0 until channels) push(c, interleaved[i + c])
        i += channels
    }
}
