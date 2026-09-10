package dev.baton.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.baton.director.ContentDuck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The half of "attenuation fades rather than steps" that can be settled without
 * ears: the depth is the depth that was asked for, the ramp is the length it is
 * supposed to be, it is monotonic, and it arrives and departs with no corner in
 * it -- a straight line would fail the last of those, and a straight line is what
 * you get by accident.
 *
 * Whether the result is *audibly* clean is not decidable here and is not claimed.
 *
 * The second group covers the loudness meter this processor carries. The one that
 * matters is the last: the meter must read the content as it arrived, not as this
 * class left it, or the duck ends up responding to its own output.
 */
class DuckingAudioProcessorTest : FunSpec({

    val sampleRateHz = 48_000
    val fadeFrames = 150 * sampleRateHz / 1_000
    val fullScale = 30_000
    val minusTwelveDb = 0.251188643

    test("unity passes the content through untouched") {
        val processor = configured(sampleRateHz, channels = 2)

        val out = processor.push(frames = 256, sample = fullScale, channels = 2)
        out.distinct() shouldBe listOf(fullScale)
        processor.fadesStarted shouldBe 0
        processor.framesProcessed shouldBe 256L
    }

    test("the duck reaches exactly the depth it was asked for, and no further") {
        val processor = configured(sampleRateHz, channels = 1)
        processor.apply(ContentDuck.Ducked(-12.0))

        processor.push(frames = fadeFrames, sample = fullScale, channels = 1)
        val settled = processor.push(frames = 64, sample = fullScale, channels = 1)

        settled.distinct().size shouldBe 1
        abs(settled.first() / fullScale.toDouble() - minusTwelveDb) shouldBeLessThan 0.001
        processor.fadesStarted shouldBe 1
    }

    test("the fade takes 150 ms of frames, not a buffer and not an instant") {
        val processor = configured(sampleRateHz, channels = 1)
        processor.apply(ContentDuck.Ducked(-12.0))

        // Fed one frame at a time, so the ramp cannot be an artefact of buffer size.
        val gains = (0 until fadeFrames + 2).map { processor.push(1, fullScale, 1).single() }
        val settled = gains.last()

        gains.first() shouldBe fullScale
        abs(settled / fullScale.toDouble() - minusTwelveDb) shouldBeLessThan 0.001

        // Halfway through the ramp, halfway down it. That is the defining point
        // of the shape, and the point a per-buffer step would miss entirely.
        abs(gains[fadeFrames / 2] - fullScale * (1 + minusTwelveDb) / 2) shouldBeLessThan 2.0

        val arrivedAt = gains.indexOfFirst { it == settled }
        (arrivedAt > fadeFrames * 95 / 100) shouldBe true
    }

    test("the ramp is monotonic and has no corner at either end") {
        val processor = configured(sampleRateHz, channels = 1)
        processor.apply(ContentDuck.Ducked(-12.0))

        val gains = processor.push(frames = fadeFrames, sample = fullScale, channels = 1)
        val steps = gains.zipWithNext { a, b -> a - b }

        steps.all { it >= 0 } shouldBe true

        // Raised cosine: the slope is zero where the ramp meets each level and
        // steepest in the middle. A linear fade would make these three equal.
        val steepest = steps.max()
        steepest shouldBeGreaterThan 0
        steps.first() shouldBeLessThanOrEqual steepest / 5
        steps.last() shouldBeLessThanOrEqual steepest / 5
    }

    test("returning to unity fades back up rather than snapping") {
        val processor = configured(sampleRateHz, channels = 1)
        processor.apply(ContentDuck.Ducked(-12.0))
        processor.push(frames = fadeFrames + 1, sample = fullScale, channels = 1)

        processor.apply(ContentDuck.Unity)
        val up = processor.push(frames = fadeFrames + 1, sample = fullScale, channels = 1)

        up.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        up.last() shouldBe fullScale
        processor.fadesStarted shouldBe 2
    }

    test("both channels of a frame get the same gain") {
        val processor = configured(sampleRateHz, channels = 2)
        processor.apply(ContentDuck.Ducked(-12.0))

        val out = processor.push(frames = 512, sample = fullScale, channels = 2)
        out.chunked(2).all { (left, right) -> left == right } shouldBe true
    }

    test("a seek lands on the current level instead of fading across the cut") {
        val processor = configured(sampleRateHz, channels = 1)
        processor.apply(ContentDuck.Ducked(-12.0))
        processor.flush()

        val out = processor.push(frames = 32, sample = fullScale, channels = 1)
        out.distinct().size shouldBe 1
        abs(out.first() / fullScale.toDouble() - minusTwelveDb) shouldBeLessThan 0.001
    }

    test("an encoding the processor cannot duck is refused rather than passed through") {
        shouldThrow<AudioProcessor.UnhandledAudioFormatException> {
            DuckingAudioProcessor().configure(
                AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT),
            )
        }
    }

    test("written position tracks frames processed at the configured rate") {
        val processor = configured(sampleRateHz, channels = 1)

        processor.writtenPositionMs() shouldBe 0L

        processor.push(frames = sampleRateHz / 2, sample = fullScale, channels = 1)
        processor.writtenPositionMs() shouldBe 500L
    }

    test("written position is zero before the processor has been configured") {
        DuckingAudioProcessor().writtenPositionMs() shouldBe 0L
    }

    test("no loudness is reported before there is a full window to measure") {
        val processor = configured(sampleRateHz, channels = 2)

        processor.shortTermLufs().shouldBeNull()

        processor.pushTone(seconds = 1.0, amplitude = 0.05, channels = 2, rate = sampleRateHz)
        processor.shortTermLufs().shouldBeNull()
    }

    test("the meter reports the content's own loudness") {
        val processor = configured(sampleRateHz, channels = 2)

        processor.pushTone(seconds = 4.0, amplitude = 0.05, channels = 2, rate = sampleRateHz)

        // A 1 kHz sine at 0.05 of full scale is -26.0 dBFS, and for a stereo sine
        // the BS.1770 reading equals the peak level in dBFS.
        processor.shortTermLufs()!! shouldBe (-26.0 plusOrMinus 0.2)
    }

    test("an unconfigured processor has no loudness rather than a zero") {
        DuckingAudioProcessor().shortTermLufs().shouldBeNull()
    }

    test("a seek clears the window, because the frames across a cut are not adjacent") {
        val processor = configured(sampleRateHz, channels = 2)
        processor.pushTone(seconds = 4.0, amplitude = 0.05, channels = 2, rate = sampleRateHz)
        processor.shortTermLufs()!! shouldBe (-26.0 plusOrMinus 0.2)

        processor.flush()

        processor.shortTermLufs().shouldBeNull()
    }

    test("the duck does not feed back into the measurement") {
        // The loop this prevents: a duck lowers the bed, the meter reads the
        // lowered bed as quieter, a quieter bed asks for a shallower duck, and the
        // level walks to somewhere nobody chose. The meter reads the input, so a
        // processor ducking by 19 dB must measure exactly what an open one does.
        val open = configured(sampleRateHz, channels = 2)
        val ducked = configured(sampleRateHz, channels = 2)
        ducked.apply(ContentDuck.Ducked(-19.0))

        open.pushTone(seconds = 4.0, amplitude = 0.05, channels = 2, rate = sampleRateHz)
        ducked.pushTone(seconds = 4.0, amplitude = 0.05, channels = 2, rate = sampleRateHz)

        ducked.shortTermLufs()!! shouldBe (open.shortTermLufs()!! plusOrMinus 0.01)
    }
})

/**
 * Feed a 1 kHz sine. A constant sample cannot be used here: K-weighting opens
 * with a high-pass at 38 Hz, so direct current measures as silence however large
 * it is.
 */
private fun DuckingAudioProcessor.pushTone(seconds: Double, amplitude: Double, channels: Int, rate: Int) {
    val frames = (rate * seconds).toInt()
    val chunk = rate / 10
    var written = 0
    while (written < frames) {
        val count = minOf(chunk, frames - written)
        val input = ByteBuffer.allocateDirect(count * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) {
            val v = (amplitude * sin(2.0 * PI * 1_000.0 * (written + i) / rate) * 32767.0).toInt().toShort()
            repeat(channels) { input.putShort(v) }
        }
        input.flip()
        queueInput(input)
        while (getOutput().hasRemaining()) Unit
        written += count
    }
}

private fun configured(sampleRateHz: Int, channels: Int): DuckingAudioProcessor =
    DuckingAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(sampleRateHz, channels, C.ENCODING_PCM_16BIT))
        flush()
    }

/** Feed [frames] frames of a constant sample and read back what came out. */
private fun DuckingAudioProcessor.push(frames: Int, sample: Int, channels: Int): List<Int> {
    val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
    repeat(frames * channels) { input.putShort(sample.toShort()) }
    input.flip()

    queueInput(input)
    val output = getOutput()
    val result = ArrayList<Int>(frames * channels)
    while (output.hasRemaining()) result += output.short.toInt()
    return result
}
