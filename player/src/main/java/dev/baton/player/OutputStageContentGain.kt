package dev.baton.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import dev.baton.director.ContentDuck
import dev.baton.ports.ContentGain
import kotlin.math.cos
import kotlin.math.pow

/**
 * The duck, applied at the output stage instead of in the render chain.
 *
 * ## Why here and not in the sink's processor chain
 *
 * [DuckingAudioProcessor] is sample-accurate, but it sits upstream of
 * `DefaultAudioSink`'s own internal queue -- measured at several hundred
 * milliseconds on the Fire TV Stick (see [DuckingAudioProcessor.writtenPositionMs]).
 * A duck decided now is inaudible until whatever content was already queued
 * ahead of it has played out: not a fade that starts a little late, most of a
 * second of full-volume bed under a sentence that was supposed to be ducked
 * under it from the first word.
 *
 * `ExoPlayer.setVolume` routes to `DefaultAudioSink.setVolume`, which the
 * platform applies to the `AudioTrack` -- after that queue, not before it. A
 * duck applied there is inaudible only for the track's own output latency,
 * which is on the order of the audio HAL's mixing period rather than the
 * sink's whole buffer.
 *
 * ## Why a fast ticker and not `queueInput`
 *
 * There is no per-sample hook at this layer, so the same raised-cosine shape
 * [DuckingAudioProcessor] draws from the frame count is drawn here from a
 * wall-clock ticker instead -- see [raisedCosineGain], the pure function that
 * does the drawing.
 *
 * ## Attack and release are not one number
 *
 * One fade in both directions was the defect, not the 150 in front of it.
 *
 * Netflix's Audio Description Style Guide mandates a side-chain attack of no
 * less than 2 ms and no more than 15 ms. Under 2 ms the gain change clicks; over
 * 15 ms the bed is still on its way down when the narrator's first consonant
 * arrives, and masks it. 150 ms missed that ceiling by an order of magnitude,
 * and the adaptive duck made it worse rather than better: `duckFor` derives the
 * dip from measured programme loudness and asks for around -17.7 dB on the demo
 * clip where the static rule asked for -12, so the same fade now has five more
 * decibels to travel in the same time. A deeper duck through an unchanged ramp
 * is a bigger swoop, not a smaller one.
 *
 * Release is deliberately not the mirror of it. The same guide fixes no release
 * time at all -- its only hard bound is that a transition to or from a dip takes
 * no more than five seconds, and inside that it is judgement traded against
 * pumping: fast enough that the mix punches back through between sentences, slow
 * enough not to surge in the narrator's breath pauses.
 *
 * What binds here is not Netflix's number but ours. `DirectorConfig.guardMs` is
 * the silence the director holds between the end of an utterance and the next
 * line of dialogue, and its own documentation says it exists to absorb this
 * fade; it is 250 ms. A release longer than the guard leaves the bed measurably
 * down under the next actor's first word, which is the guard doing the opposite
 * of its job. Decision-to-audible on this path measured 154 ms on the Fire TV
 * Stick, up to 50 ms of which is the caller's own tick, so something like 145 ms
 * of the guard is left for the ramp itself and 150 ms lands the bed back at
 * unity just as the next line starts. 150 is therefore not a leftover from the
 * symmetric design: it is as slow as the guard permits, which is the side to err
 * on for a release. In the worst case -- the full 154 ms of transport on top of
 * a late tick -- the ramp gets only about 95 ms of the guard and the bed is
 * still 2.5 dB down when the line starts. Whether that is audible is a claim for
 * a listener. If `DirectorConfig.guardMs` moves, this number moves with it.
 *
 * Which of the two applies is the direction of travel, not which call it is:
 * away from unity is an attack, towards unity is a release. A duck that deepens
 * mid-utterance -- the film gets louder, `duckFor` asks for more -- is a move
 * away from unity like any other, so it attacks. See [fadeMsFor].
 *
 * ## What the tick period can and cannot buy
 *
 * At the 5 ms tick a 10 ms attack is two steps, and for a -17.7 dB duck one of
 * them is nearly 13 dB. That is precisely the "suddenly, like a jump" defect
 * platform volume control was rejected for in the original, sample-accurate
 * design, so the obvious response is to tick faster. It would not work, and the
 * reason is worth writing down.
 *
 * `AudioTrack.setVolume` does not reach the mixer when it is called. It packs
 * the gain into the track's shared control block and returns. AudioFlinger's
 * mixer thread reads that block once per mix buffer, in `prepareTracks_l`, and
 * hands the value to `AudioMixer` as `RAMP_VOLUME` -- which interpolates
 * linearly from the previous gain to the new one across the whole of that
 * buffer. Two things follow. Every value written between two of those reads is
 * overwritten before it is ever seen, so a 1 ms ticker and a 5 ms ticker produce
 * identical audio. And no single write can click, however large the jump,
 * because the mixer always spreads it over a buffer.
 *
 * That buffer is not short. AudioFlinger sizes the normal mixer sink to at least
 * 20 ms (`kMinNormalSinkBufferSizeMs`), and to the HAL's own period when that is
 * longer; and this track cannot be a fast one, because `DefaultAudioSink` asks
 * for at least 250 ms of PCM. So the finest attack this layer can deliver is one
 * mix period -- 20 ms or more, outside the mandated window, and not something
 * any ticker can fix.
 *
 * That makes the choice within [2, 15] ms a choice about robustness rather than
 * about sound, because every value in it is shorter than the platform's own
 * ramp. 10 ms because it is two ticks, so the curve is drawn rather than
 * collapsing to the single step a 5 ms attack would be; because it leaves 5 ms
 * of headroom under the ceiling, which matters when a late `postDelayed` moves
 * the end of the ramp out rather than compressing it; and because it is well
 * clear of the 2 ms floor for the output paths that never reach `AudioMixer` at
 * all -- a direct or offloaded track has its volume applied by the HAL, with no
 * promise of a ramp to hide a step behind.
 *
 * And the tick stays at 5 ms. Finer buys nothing the mixer would ever read, and
 * costs a main-thread wake-up and a `PlayerMessage` allocation apiece on a 1 GB
 * device -- `ExoPlayer.setVolume` is not synchronous either, it posts to the
 * playback thread and is applied from there.
 *
 * ## Must run on the player's thread
 *
 * `ExoPlayer.setVolume` has the same threading rule as every other `ExoPlayer`
 * method: the thread that built the player. [apply] and the ticker it starts
 * both assume they are called from that thread -- the [looper] passed in is
 * `ExoPlayer.getApplicationLooper()`, not assumed to be the main looper.
 */
class OutputStageContentGain(
    private val exoPlayer: ExoPlayer,
    looper: Looper,
    private val attackMs: Long = DEFAULT_ATTACK_MS,
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = {},
) : ContentGain {

    private val handler = Handler(looper)

    private var target: Float = UNITY
    private var rampFrom = UNITY
    private var rampTo = UNITY
    private var rampMs = releaseMs
    private var rampStartedAtMs = 0L
    private var ticking = false

    override fun apply(duck: ContentDuck) {
        val wanted = if (duck.db == 0.0) UNITY else 10.0.pow(duck.db / 20.0).toFloat()
        if (wanted == target) return
        target = wanted
        // Where the gain actually is, not where it was last told to go: a duck
        // that arrives mid-ramp travels from here, and it is that direction --
        // not which of the two calls this is -- that decides the fade.
        rampFrom = exoPlayer.volume
        rampTo = wanted
        rampMs = fadeMsFor(rampFrom, wanted, attackMs, releaseMs)
        rampStartedAtMs = nowMs()
        log("output duck fade from=${format(rampFrom)} to=${format(rampTo)} overMs=$rampMs")
        if (!ticking) {
            ticking = true
            tick.run()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val elapsedMs = nowMs() - rampStartedAtMs
            exoPlayer.volume = raisedCosineGain(rampFrom, rampTo, elapsedMs, rampMs)
            if (elapsedMs >= rampMs) {
                ticking = false
                log("output duck reached target=${format(rampTo)}")
            } else {
                handler.postDelayed(this, tickMs)
            }
        }
    }

    private fun format(value: Float): String = String.format("%.4f", value)

    companion object {
        /** Netflix mandates 2-15 ms. Why 10 and not either end of that: see the class doc. */
        const val DEFAULT_ATTACK_MS: Long = 10L

        /** As slow as `DirectorConfig.guardMs` permits. See the class doc. */
        const val DEFAULT_RELEASE_MS: Long = 150L

        const val DEFAULT_TICK_MS: Long = 5L
        private const val UNITY = 1.0f
    }
}

/**
 * Which of the two fades a move from [from] to [to] takes.
 *
 * Direction of travel, not call site. Away from unity is an attack -- starting a
 * duck, and equally a duck that deepens while a description is already speaking,
 * which a rule phrased in terms of "beginning" and "ending" a duck would get
 * wrong. Towards unity is a release, including a duck that only lightens.
 *
 * Free, like [raisedCosineGain], so the asymmetry can be checked without an
 * `ExoPlayer` or a `Looper`.
 */
fun fadeMsFor(from: Float, to: Float, attackMs: Long, releaseMs: Long): Long =
    if (to < from) attackMs else releaseMs

/**
 * The raised cosine [DuckingAudioProcessor] uses, redone in wall-clock time
 * instead of frames: `0.5 * (1 - cos(pi * p))`, zero slope at both ends. Free,
 * like [fadeMsFor], so the shape can be tested without an `ExoPlayer` or a
 * `Looper`.
 */
fun raisedCosineGain(from: Float, to: Float, elapsedMs: Long, fadeMs: Long): Float {
    if (fadeMs <= 0L || elapsedMs >= fadeMs) return to
    if (elapsedMs <= 0L) return from
    val progress = elapsedMs.toFloat() / fadeMs
    val shaped = 0.5f * (1f - cos(Math.PI.toFloat() * progress))
    return from + (to - from) * shaped
}
