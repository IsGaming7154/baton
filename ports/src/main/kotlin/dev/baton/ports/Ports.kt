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
 *
 * [playback] answers with three states and not two, for the same reason there are
 * two methods here and not one. A player that has run out of data has also
 * stopped, and an implementation that folded that into "paused" would be telling
 * the director somebody decided to stop watching -- a claim only the adapter is
 * in a position to check, and one the director cannot recover from timing. See
 * [Playback].
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
 * How loud the content itself is, right now.
 *
 * The other half of the ducking decision. [ContentGain] is told a number; this
 * is where the number comes from -- without it the director can only apply a
 * fixed attenuation and hope the film happens to be at the level that fixed
 * attenuation was chosen for.
 *
 * Deliberately not a `Double`. Null is a real and frequent answer: a loudness
 * measurement needs a window of audio behind it, so there is nothing to report
 * for the first few seconds of playback, or for the first few seconds after a
 * seek. A port that returned a number regardless would be inventing one exactly
 * when the truth is that nobody knows yet.
 */
interface ProgrammeLoudness {

    /**
     * ITU-R BS.1770 short-term loudness of the content, in LUFS, or null before
     * there is a complete window to measure.
     */
    fun shortTermLufs(): Double?
}

/**
 * The voice that is not the content's.
 *
 * [speak] is idempotent in the sense the director needs: it is called once per
 * utterance instance, and the caller diffs the directive to decide when that is.
 *
 * The pairing is what matters: **every [speak] is ended either by the speaker
 * reporting through [onSpeechEnded] or by the caller calling [stop], and never by
 * both and never by neither.** The director decides which, and it can now decide
 * against an utterance that is still going -- that is what [stop] is for.
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

    /**
     * Silence [id] now, if it is what is playing, and do nothing if it is not.
     *
     * Addressed, and that is the whole of the change from an unaddressed "stop
     * whatever is playing". This is how the director withdraws the floor from
     * speech that has run out of silence, so it is issued about a *particular*
     * utterance while another may already have been handed over; a bare stop would
     * be a race that silences the wrong voice, and the wrong voice here is the one
     * that is allowed to be talking. Naming the target makes the mistake
     * impossible rather than merely unlikely, and an implementation that has
     * already moved on can say so by doing nothing.
     *
     * Does not report an end. A withdrawal is the caller taking the floor back,
     * not the speaker giving it up, and the caller already knows -- an
     * [onSpeechEnded] fired from here would be a calculation dressed as an
     * observation, which is exactly what that callback may not be.
     */
    fun stop(id: UtteranceId)

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
