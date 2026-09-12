package dev.baton.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.baton.director.AudioDirector
import dev.baton.director.ContentDuck
import dev.baton.director.Directive
import dev.baton.director.DirectorInputs
import dev.baton.director.InputEvent
import dev.baton.director.ReactiveRequest
import dev.baton.director.Speaking
import dev.baton.ports.ContentGain
import dev.baton.ports.PlaybackClock
import dev.baton.ports.ProgrammeLoudness
import dev.baton.ports.Speaker
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The loop that turns a [Directive] into things happening.
 *
 * It contains no policy whatsoever -- it reads the clock, hands the director
 * whatever has happened since the last tick, and then does exactly what it is
 * told: duck to this level, start this utterance, stop that one. Every decision
 * it looks like it is making was made in `:director`, which has no Android on its
 * classpath and is tested without a device.
 *
 * The directive is level-triggered, so the work here is a diff: an utterance is
 * started when the pair (id, startedAtMs) changes, which distinguishes a cue that
 * legitimately plays twice after a seek from one that never stopped.
 *
 * Runs on the main thread, because the ExoPlayer it reads is single-threaded and
 * insists on it. The speaker reports back from its own thread, so those reports
 * are queued and drained here rather than reaching the director directly.
 */
class Tracer(
    private val clock: PlaybackClock,
    private val gain: ContentGain,
    private val programmeLoudness: ProgrammeLoudness,
    private val speaker: Speaker,
    private val director: AudioDirector,
    private val log: (String) -> Unit,
    /**
     * Fired on the same tick [duck] itself changes -- never polled -- so a demo
     * overlay can show the number without adding a clock of its own. No-op by
     * default, so the tracer's own behaviour cannot depend on anyone listening.
     */
    private val onDuckChanged: (ContentDuck) -> Unit = {},
    /**
     * Fired on the same tick [speaking] itself changes, with `null` when the
     * floor goes quiet. Event-triggered for the same reason as
     * [onDuckChanged]: a description reaches an overlay the instant it starts,
     * not whenever the overlay's own poll next happens to run.
     */
    private val onSpeakingChanged: (Speaking.Utterance?) -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())
    private val inbox = ConcurrentLinkedQueue<InputEvent>()

    private var speaking: Speaking.Utterance? = null
    private var duck: ContentDuck = ContentDuck.Unity
    private var running = false

    init {
        speaker.onSpeechEnded = { id -> inbox += InputEvent.SpeechEnded(id) }
    }

    /** Called by the player when playback jumps. */
    fun onSeek() {
        inbox += InputEvent.Seeked
    }

    /**
     * A voice the viewer asked for. Queued like every other event, so it reaches the
     * director on the next tick in the order it happened -- behind a seek that came
     * first, ahead of one that came after. Whether it speaks, and over what, is the
     * director's call.
     */
    fun request(request: ReactiveRequest) {
        log("reactive requested id=${request.id} kind=${request.kind} phrase=\"${request.text}\"")
        inbox += InputEvent.ReactiveRequested(request)
    }

    fun start() {
        if (running) return
        running = true
        gain.apply(ContentDuck.Unity)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        speaking?.let { speaker.stop(it.id) }
        speaking = null
        gain.apply(ContentDuck.Unity)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            advance()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun advance() {
        val events = mutableListOf<InputEvent>()
        while (true) events += inbox.poll() ?: break

        val positionMs = clock.positionMs()
        val playback = clock.playback()
        val programmeLufs = programmeLoudness.shortTermLufs()
        val directive = director.advance(
            SystemClock.elapsedRealtime(),
            DirectorInputs(
                positionMs = positionMs,
                playback = playback,
                events = events,
                programmeLufs = programmeLufs,
            ),
        )

        directive.drops.forEach {
            log("cue dropped id=${it.cueId} kind=${it.kind} atPositionMs=${it.atPositionMs} reason=${it.reason}")
        }
        // Logged with both numbers rather than one, because the pair is the price
        // of the policy: heldForMs against measuredMs is how far past its own
        // length the utterance had run when it was cut, and a policy whose price
        // never reaches the log is one nobody can argue with afterwards.
        directive.withdrawal?.let {
            log(
                "ANOMALY floor withdrawn id=${it.id} kind=${it.kind} reason=${it.reason} " +
                    "measuredMs=${it.measuredDurationMs ?: "unmeasured"} heldForMs=${it.heldForMs}",
            )
        }
        // Nothing is done about it -- the floor and the duck are held, which is
        // the director's decision and not this loop's. The line exists so that a
        // description heard over a frozen picture is legible afterwards as the
        // network event it was.
        directive.stall?.let {
            log(
                "ANOMALY content stalled atPositionMs=${it.atPositionMs} stalledForMs=${it.stalledForMs} " +
                    "thresholdMs=${it.thresholdMs} floorHeldBy=${it.floorHeldBy ?: "nothing"}",
            )
        }

        applySpeech(directive, events, positionMs)
        applyDuck(directive, programmeLufs)
    }

    private fun applySpeech(directive: Directive, events: List<InputEvent>, positionMs: Long) {
        val next = directive.speaking as? Speaking.Utterance
        val current = speaking
        val sameInstance = next != null && current != null &&
            next.id == current.id && next.startedAtMs == current.startedAtMs
        if (sameInstance) return

        if (current != null) {
            log("floor released id=${current.id} by=${releaseCause(directive, events, current)}")
            // Unconditionally, and named. The old shape stopped the speaker only
            // when nothing followed, which left the one case that matters --
            // handing the floor straight to another voice -- relying on the
            // speaker to work out for itself that the previous utterance was over.
            // A speaker that got that wrong would put two voices in the room, and
            // this is the loop whose entire job is that it cannot.
            speaker.stop(current.id)
        }
        if (next != null) {
            if (next is Speaking.ScheduledUtterance) {
                log(
                    "cue start id=${next.id} kind=${next.kind} cue=${next.cueId ?: "none"} variant=${next.variantIndex} " +
                        "atPositionMs=$positionMs measuredMs=${next.duration.millis} endsAtMs=${next.endsAtPositionMs}",
                )
            } else {
                log("cue start id=${next.id} kind=${next.kind} atPositionMs=$positionMs")
            }
            speaker.speak(next)
        }
        speaking = next
        onSpeakingChanged(next)
    }

    /**
     * The duck depth is logged with the measurement it was computed from, because
     * on its own a depth is unreadable: -6 dB is either correct or badly wrong
     * depending on how loud the film was, and the log is the only place that can
     * be checked after the fact against what was heard in the room.
     */
    private fun applyDuck(directive: Directive, programmeLufs: Double?) {
        // Only on a change. Re-applying an unchanged depth is not free: the gain
        // reads the player's current volume and starts a fresh ramp from it, so a
        // tick that says nothing new still restarts the fade.
        if (directive.contentDuck == duck) return
        duck = directive.contentDuck
        val measured = programmeLufs?.let { "%.1f".format(it) } ?: "unmeasured"
        log("duck target=${"%.1f".format(duck.db)}dB programmeLufs=$measured")
        gain.apply(directive.contentDuck)
        onDuckChanged(duck)
    }

    private fun releaseCause(directive: Directive, events: List<InputEvent>, held: Speaking.Utterance): String = when {
        directive.withdrawal?.id == held.id -> "WITHDRAWN"
        events.any { it is InputEvent.SpeechEnded && it.id == held.id } -> "speaker"
        events.any { it is InputEvent.Seeked } -> "seek"
        events.any { it is InputEvent.ReactiveRequested } -> "preempted"
        else -> "pause"
    }

    private companion object {
        /**
         * 50 ms. Fast enough that a cue starts within a fifth of the guard
         * interval of its start time, slow enough to be free on a 1 GB device --
         * the director's work per tick is a linear scan of a three-cue list.
         */
        const val TICK_MS = 50L
    }
}
