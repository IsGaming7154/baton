package dev.baton.speech

import dev.baton.director.DirectorConfig
import dev.baton.loudness.Bs1770
import dev.baton.loudness.TruePeak
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * The chain runs between synthesis and measurement, so the properties that
 * matter are the ones the rest of the system assumes: the frame count survives,
 * nothing clips, and the bands that carry intelligibility actually move in the
 * direction claimed.
 *
 * Each EQ stage is checked with a steady tone at its own centre frequency, which
 * is the only way to attribute a level change to the stage that caused it: a
 * measurement on speech would show every stage at once.
 */
class SpeechClarityTest : FunSpec({

    val rate = 24_000

    test("frame count, rate and channel count are unchanged") {
        val file = tone(rate, hz = 300.0, frames = 12_000)
        val before = WavAudio.read(file)

        SpeechClarity.apply(file) shouldBe true

        val after = WavAudio.read(file)
        after.frameCount shouldBe before.frameCount
        after.sampleRateHz shouldBe before.sampleRateHz
        after.channelCount shouldBe before.channelCount
        after.bitsPerSample shouldBe before.bitsPerSample
    }

    // The chain normalises, so an absolute level says nothing about any one
    // stage. Two tones in the same file share whatever overall gain the
    // normaliser and compressor apply, so the balance between them isolates the
    // filter -- which is the thing under test.

    test("rumble below the voice is removed relative to the voice") {
        val file = twoTone(rate, 40.0, 1_000.0, frames = 24_000)
        val before = balanceDb(file, 40.0, 1_000.0)

        SpeechClarity.apply(file)

        // 40 Hz is 1.46 octaves below a 12 dB/oct corner at 110 Hz, so about
        // 17.5 dB. Asserted at 15 so the test fails on the filter going wrong
        // rather than on the last half decibel of arithmetic.
        balanceDb(file, 40.0, 1_000.0) shouldBeLessThan before - 15.0
    }

    test("the boxy band is cut and the band that carries intelligibility is lifted") {
        // 1600 Hz is where ANSI S3.5 puts the peak of the band importance
        // function, which is why the lift is centred there rather than up in
        // the region that merely sounds bright.
        val file = twoTone(rate, 420.0, 1_600.0, frames = 24_000)
        val before = balanceDb(file, 1_600.0, 420.0)

        SpeechClarity.apply(file)

        // -4 dB at 420 Hz against +6.5 dB at 1.6 kHz is a 10.5 dB swing
        // nominally; the bells are wide and their skirts overlap each other's
        // centres, so assert well inside that.
        balanceDb(file, 1_600.0, 420.0) shouldBeGreaterThan before + 6.0
    }

    test("the exciter puts energy above the engine's ceiling") {
        // energyAbove is a fraction of the total, so it is immune to whatever
        // overall gain the normaliser settles on and the two runs are comparable
        // without pinning the level.
        val quiet = SpeechClarity.Settings(exciterMix = 0.0)
        val excited = SpeechClarity.Settings()

        val without = tone(rate, hz = 3_000.0, frames = 24_000, amplitude = 0.5)
        val with = tone(rate, hz = 3_000.0, frames = 24_000, amplitude = 0.5)
        SpeechClarity.apply(without, quiet)
        SpeechClarity.apply(with, excited)

        // A 3 kHz tone has nothing above 7.5 kHz until the exciter makes some.
        energyAbove(with, 7_500.0) shouldBeGreaterThan energyAbove(without, 7_500.0) * 4.0
    }

    test("output is delivered at the broadcast loudness target, not at a peak") {
        // Amazon's quality control rejects description narration outside
        // -27 LKFS +-2 LU automatically, so the assertion is the window itself.
        // A tone is not speech, but the normaliser does not care what it is
        // handed -- it measures whatever arrives, and the property under test is
        // that the number it lands on is a loudness and not a peak.
        val file = tone(rate, hz = 800.0, frames = 72_000, amplitude = 0.95)
        SpeechClarity.apply(file)

        val samples = read(file)
        Bs1770.integratedLufs(samples, channelCount = 1, sampleRateHz = rate)!! shouldBe
            (-27.0 plusOrMinus 0.2)
        TruePeak.dbtp(samples, channelCount = 1, sampleRateHz = rate) shouldBeLessThan -2.0
        samples.count { abs(it) >= 0.9999 } shouldBe 0
    }

    test("two utterances at very different input levels come out at the same loudness") {
        // The whole reason for the change. Peak normalisation would have put both
        // of these at -3 dBFS and left them audibly different; what the listener
        // needs is for every description to arrive at the same level.
        val loud = tone(rate, hz = 800.0, frames = 72_000, amplitude = 0.9)
        val quiet = tone(rate, hz = 800.0, frames = 72_000, amplitude = 0.05)
        SpeechClarity.apply(loud)
        SpeechClarity.apply(quiet)

        val loudLkfs = Bs1770.integratedLufs(read(loud), 1, rate)!!
        val quietLkfs = Bs1770.integratedLufs(read(quiet), 1, rate)!!

        (loudLkfs - quietLkfs) shouldBe (0.0 plusOrMinus 0.3)
    }

    test("the true-peak ceiling wins when the loudness target cannot be reached under it") {
        // A target that cannot be met without going through the ceiling. The
        // ceiling holds and the loudness target is missed, which is the
        // documented trade: fall short of the number rather than introduce a
        // limiter the chain was never scored with.
        val file = tone(rate, hz = 800.0, frames = 72_000, amplitude = 0.5)
        SpeechClarity.apply(file, SpeechClarity.Settings(targetLkfs = 0.0))

        val samples = read(file)
        TruePeak.dbtp(samples, 1, rate) shouldBe (-2.0 plusOrMinus 0.15)
        Bs1770.integratedLufs(samples, 1, rate)!! shouldBeLessThan -2.0
    }

    test("audio too short to have a loudness falls back to the peak target") {
        // 200 ms, against BS.1770's 400 ms gating block. There is no partial
        // block rule, so there is no loudness -- and an utterance that short
        // still has to come out audible.
        val file = tone(rate, hz = 800.0, frames = 4_800, amplitude = 0.2)
        SpeechClarity.apply(file) shouldBe true

        20.0 * log10(read(file).maxOf { abs(it) }) shouldBe (-3.0 plusOrMinus 0.3)
    }

    test("a rate with no room above the exciter's corner leaves the stage out") {
        // Pico renders at 16 kHz, where Nyquist is 8 kHz and a 7 kHz corner
        // leaves a single kilohertz that would be mostly folded harmonics. The
        // stage must be skipped, not run into a ceiling it cannot clear.
        val file = tone(16_000, hz = 3_000.0, frames = 16_000, amplitude = 0.5)
        SpeechClarity.apply(file) shouldBe true

        energyAbove(file, 6_500.0) shouldBeLessThan 0.02
    }

    test("the level rendered to is the level the director computes ducks against") {
        // Two modules, one number, and no compiler anywhere that would notice
        // them drifting apart. The director derives every duck depth from what it
        // believes the narration was normalised to; if that belief and this target
        // stop agreeing, every duck in the product is wrong by the difference and
        // nothing else fails. So it is pinned here.
        SpeechClarity.Settings().targetLkfs shouldBe DirectorConfig.DEFAULT_NARRATION_LKFS
    }

    test("audio the chain is not defined over is left alone") {
        val file = eightBit()
        val before = file.readBytes()

        SpeechClarity.apply(file) shouldBe false
        file.readBytes() shouldBe before
    }
})

private fun tone(rate: Int, hz: Double, frames: Int, amplitude: Double = 0.5): File {
    val samples = ShortArray(frames) {
        (amplitude * sin(2.0 * PI * hz * it / rate) * 32767.0).toInt().toShort()
    }
    return write(rate, channels = 1, samples = samples)
}

private fun twoTone(rate: Int, a: Double, b: Double, frames: Int, amplitude: Double = 0.4): File {
    val samples = ShortArray(frames) {
        val v = amplitude * (sin(2.0 * PI * a * it / rate) + sin(2.0 * PI * b * it / rate)) / 2.0
        (v * 32767.0).toInt().toShort()
    }
    return write(rate, channels = 1, samples = samples)
}

/** Level at [hz], by a single-frequency Goertzel-style correlation. */
private fun levelAt(file: File, hz: Double): Double {
    val rate = WavAudio.read(file).sampleRateHz.toDouble()
    val x = read(file)
    var re = 0.0
    var im = 0.0
    for (i in x.indices) {
        val w = 2.0 * PI * hz * i / rate
        re += x[i] * kotlin.math.cos(w)
        im += x[i] * sin(w)
    }
    return re * re + im * im
}

/** How much louder [hz] is than [against], in dB. Immune to any overall gain. */
private fun balanceDb(file: File, hz: Double, against: Double): Double =
    10.0 * log10(levelAt(file, hz) / (levelAt(file, against) + 1e-18))

private fun read(file: File): DoubleArray {
    val wav = WavAudio.read(file)
    val shorts = ByteBuffer.wrap(wav.readPcm(file)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return DoubleArray(shorts.limit()) { shorts.get(it) / 32768.0 }
}

/** Fraction of total energy above [hz], by a direct Goertzel-style sweep. */
private fun energyAbove(file: File, hz: Double): Double {
    val wav = WavAudio.read(file)
    val x = read(file)
    val rate = wav.sampleRateHz.toDouble()
    var above = 0.0
    var total = 0.0
    var f = 100.0
    while (f < rate / 2.0 - 100.0) {
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val w = 2.0 * PI * f * i / rate
            re += x[i] * kotlin.math.cos(w)
            im += x[i] * sin(w)
        }
        val power = re * re + im * im
        total += power
        if (f >= hz) above += power
        f += 100.0
    }
    return if (total == 0.0) 0.0 else above / total
}

private fun eightBit(): File {
    val file = File.createTempFile("clarity8", ".wav").also { it.deleteOnExit() }
    val frames = 100
    val buffer = ByteBuffer.allocate(44 + frames).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + frames).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16)
    buffer.putShort(1).putShort(1).putInt(8_000).putInt(8_000).putShort(1).putShort(8)
    buffer.put("data".toByteArray()).putInt(frames)
    repeat(frames) { buffer.put(0x40) }
    file.writeBytes(buffer.array())
    return file
}

private fun write(rate: Int, channels: Int, samples: ShortArray): File {
    val file = File.createTempFile("clarity", ".wav").also { it.deleteOnExit() }
    val bytes = samples.size * 2
    val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16)
    buffer.putShort(1).putShort(channels.toShort()).putInt(rate)
    buffer.putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
    buffer.put("data".toByteArray()).putInt(bytes)
    samples.forEach { buffer.putShort(it) }
    file.writeBytes(buffer.array())
    return file
}
