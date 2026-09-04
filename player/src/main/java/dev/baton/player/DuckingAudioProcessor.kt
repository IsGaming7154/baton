package dev.baton.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.baton.director.ContentDuck
import dev.baton.ports.ContentGain
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow

/**
 * The -12 dB duck, applied where it can be applied per sample: inside the audio
 * render chain, between the decoder and the sink.
 *
 * ## Why here and not on the player's volume
 *
 * `ExoPlayer.setVolume` is a control the platform applies somewhere downstream,
 * on its own schedule, in steps. Ducking under speech has to be a shape, not a
 * step -- a level change that arrives as a discontinuity reads as a fault, and a
 * level change that arrives late reads as the film being clipped. A processor in
 * the chain sees every frame in order and can put the fade exactly where the
 * frame is, so it survives seeks, buffering and pauses for free.
 *
 * ## The shape
 *
 * A raised cosine over [fadeMs], in linear amplitude: `0.5 * (1 - cos(pi * p))`.
 * Zero slope at both ends, so neither the departure from unity nor the arrival at
 * the duck has a corner in it. A straight line would have two.
 *
 * The target is written by the app thread and read by the playback thread. It is
 * one float and it is `@Volatile`; the ramp state around it is touched only by
 * the playback thread, so there is nothing to lock.
 */
class DuckingAudioProcessor(
    private val fadeMs: Long = DEFAULT_FADE_MS,
    private val log: (String) -> Unit = {},
) : BaseAudioProcessor(), ContentGain {

    @Volatile
    private var target: Float = UNITY

    /** The most recent gain actually written onto a sample. Observation, for the log. */
    @Volatile
    var appliedGain: Float = UNITY
        private set

    @Volatile
    var framesProcessed: Long = 0L
        private set

    @Volatile
    var fadesStarted: Int = 0
        private set

    private var fadeFrames = 0
    private var rampFrom = UNITY
    private var rampTo = UNITY
    private var rampPosition = 0
    private var gain = UNITY

    override fun apply(duck: ContentDuck) {
        target = if (duck.db == 0.0) UNITY else 10.0.pow(duck.db / 20.0).toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        fadeFrames = (fadeMs * inputAudioFormat.sampleRate / 1_000L).toInt().coerceAtLeast(1)
        log(
            "duck processor configured rate=${inputAudioFormat.sampleRate} " +
                "channels=${inputAudioFormat.channelCount} fadeFrames=$fadeFrames",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        var position = inputBuffer.position()
        val limit = inputBuffer.limit()
        if (position == limit) return

        val channels = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(limit - position)
        var frames = 0L
        while (position < limit) {
            val frameGain = advanceGain()
            for (channel in 0 until channels) {
                val sample = inputBuffer.getShort(position + 2 * channel)
                output.putShort(scale(sample, frameGain))
            }
            position += 2 * channels
            frames++
        }
        inputBuffer.position(limit)
        output.flip()

        framesProcessed += frames
        appliedGain = gain
    }

    /**
     * A seek flushes the sink, so the frames either side of it are not adjacent.
     * Fading between them would be fading across a cut; land on the target and
     * carry on.
     */
    override fun onFlush() {
        gain = target
        rampTo = target
        rampFrom = target
        rampPosition = fadeFrames
    }

    override fun onReset() {
        gain = UNITY
        rampFrom = UNITY
        rampTo = UNITY
        rampPosition = 0
        framesProcessed = 0L
    }

    private fun advanceGain(): Float {
        val wanted = target
        if (wanted != rampTo) {
            rampFrom = gain
            rampTo = wanted
            rampPosition = 0
            fadesStarted++
            log("duck fade from=${format(rampFrom)} to=${format(rampTo)} overFrames=$fadeFrames")
        }
        gain = if (rampPosition >= fadeFrames) {
            rampTo
        } else {
            val progress = rampPosition.toFloat() / fadeFrames
            rampPosition++
            val shaped = 0.5f * (1f - cos(Math.PI.toFloat() * progress))
            rampFrom + (rampTo - rampFrom) * shaped
        }
        return gain
    }

    private fun scale(sample: Short, frameGain: Float): Short {
        if (frameGain >= UNITY) return sample
        val scaled = (sample * frameGain).toInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun format(value: Float): String = String.format("%.4f", value)

    companion object {
        const val DEFAULT_FADE_MS: Long = 150L
        private const val UNITY = 1.0f
    }
}
