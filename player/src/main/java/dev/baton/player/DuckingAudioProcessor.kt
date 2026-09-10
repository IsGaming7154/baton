package dev.baton.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.baton.director.ContentDuck
import dev.baton.loudness.ShortTermLoudnessMeter
import dev.baton.ports.ContentGain
import dev.baton.ports.ProgrammeLoudness
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
 *
 * ## It also meters the content, and that is why it is still here
 *
 * In production this processor is at unity -- [OutputStageContentGain] does the
 * ducking, because it applies after the sink's queue rather than before it. What
 * keeps this class in the chain is that it is the only place that sees the
 * content's own decoded samples, in order, at a known rate. So it carries the
 * BS.1770 meter whose reading tells the director how far to duck: see
 * [ProgrammeLoudness] and `duckFor`.
 *
 * The meter reads [queueInput]'s **input**, before any gain this class might
 * apply. That is not an implementation convenience. Metering the output would
 * feed the duck its own effect -- a duck lowers the bed, a lower bed reads
 * quieter, a quieter bed asks for a shallower duck -- and the loop would either
 * oscillate or crawl to a level nobody chose.
 *
 * Sitting upstream of the sink's queue makes the meter *early*, by exactly the
 * queue depth the `writtenPositionMs` comment describes. For a duck that is a
 * defect and the reason the gain moved elsewhere; for a meter it is the right
 * way round, because the question being asked is how loud the film is about to
 * be under a description that has not started yet.
 */
@OptIn(UnstableApi::class)
class DuckingAudioProcessor(
    val fadeMs: Long = DEFAULT_FADE_MS,
    private val log: (String) -> Unit = {},
) : BaseAudioProcessor(), ContentGain, ProgrammeLoudness {

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
    private var sampleRateHz: Int = 0

    /** Built in [onConfigure], because until then the rate and channel count are unknown. */
    @Volatile
    private var meter: ShortTermLoudnessMeter? = null

    override fun shortTermLufs(): Double? = meter?.lufs()

    /**
     * How far into the stream the last frame handed to the sink sits, in media
     * time. Diffed against the position the platform reports as audible (which
     * is latency-compensated -- see [dev.baton.ports.PlaybackClock]), this is how
     * you find the sink's buffered latency on a given device instead of assuming
     * one: see the heartbeat log in `MainActivity`.
     */
    fun writtenPositionMs(): Long = if (sampleRateHz == 0) 0L else framesProcessed * 1_000L / sampleRateHz

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
        sampleRateHz = inputAudioFormat.sampleRate
        fadeFrames = (fadeMs * inputAudioFormat.sampleRate / 1_000L).toInt().coerceAtLeast(1)
        meter = ShortTermLoudnessMeter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
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
        val loudness = meter
        var frames = 0L
        while (position < limit) {
            val frameGain = advanceGain()
            for (channel in 0 until channels) {
                val sample = inputBuffer.getShort(position + 2 * channel)
                loudness?.push(channel, sample / 32768.0)
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
        // Same argument as the fade, for the same reason: a window averaged
        // across a cut reports the loudness of a scene that is no longer playing.
        meter?.reset()
    }

    override fun onReset() {
        gain = UNITY
        rampFrom = UNITY
        rampTo = UNITY
        rampPosition = 0
        framesProcessed = 0L
        meter?.reset()
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
