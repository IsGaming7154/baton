package dev.baton.ports

import dev.baton.director.ContentDuck
import dev.baton.director.MeasuredDurationMs
import dev.baton.director.Playback
import dev.baton.director.Speaking
import dev.baton.director.UtteranceId

/**
 * Where the content is and whether it is moving.
 *
 * Two facts rather than one, because a paused player still has a position and
 * the director has to be able to tell the difference.
 */
interface PlaybackClock {
    fun positionMs(): Long
    fun playback(): Playback
}

/**
 * How loud the content is allowed to be.
 *
 * The director decides the number; this decides the shape of getting there. The
 * fade is deliberately not in the interface -- it belongs to whichever
 * implementation can apply it sample-accurately, and a caller that could specify
 * it would be a caller that could get it wrong.
 */
interface ContentGain {
    fun apply(duck: ContentDuck)
}

/**
 * The voice that is not the content's.
 *
 * [speak] is idempotent in the sense the director needs: it is called once per
 * utterance instance, and the caller diffs the directive to decide when that is.
 */
interface Speaker {

    /**
     * Reports the moment the audio handed to [speak] stopped being audible.
     *
     * This is what releases the floor, so it has to be an observation and not a
     * calculation -- an implementation that fires this on a timer has quietly put
     * the prediction back that the director exists to avoid. Called on whatever
     * thread noticed; the caller is responsible for getting it to the director.
     */
    var onSpeechEnded: ((UtteranceId) -> Unit)?

    fun speak(utterance: Speaking.Utterance)

    /** Silence whatever is playing. Does not report an end; the caller already knows. */
    fun stop()

    fun release()
}

/**
 * Audio the runtime rendered, and the frame count it was rendered to.
 *
 * The frame count is the whole point. [duration] is derived from it here rather
 * than accepted from a caller, so that the only durations in the system are ones
 * measured off bytes that exist.
 */
class RenderedSpeech(
    val audioRef: String,
    val frameCount: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    val duration: MeasuredDurationMs = MeasuredDurationMs.ofRenderedAudio(frameCount, sampleRateHz)

    override fun toString(): String =
        "RenderedSpeech($audioRef, $frameCount frames, ${sampleRateHz}Hz, ${channelCount}ch, $duration)"
}

/**
 * Turns text into audio on disk, and tells you exactly how much of it there is.
 *
 * There is no `speak(text)` here on purpose. Anything the director schedules must
 * be rendered before it is scheduled, because the number the director admits it
 * on has to have been measured from the same bytes the viewer will hear -- and on
 * this hardware the two installed engines disagree about the length of the same
 * sentence by 918 ms, which is nearly four guard intervals. See FRICTION_LOG.md
 * entry 9.
 */
interface SpeechSynth {

    /** Which engine the audio came from. For the record, and for the log. */
    val engineId: String

    /**
     * Render [text] and measure it. [key] names the output file, so the same key
     * twice is the same file twice.
     */
    fun render(key: String, text: String): RenderedSpeech

    fun release()
}
