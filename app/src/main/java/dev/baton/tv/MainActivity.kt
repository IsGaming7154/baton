package dev.baton.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.Surface
import dev.baton.director.AudioDirector
import dev.baton.director.DescriptionTrack
import dev.baton.director.DirectorConfig
import dev.baton.director.ReactiveRequest
import dev.baton.director.SpeechLength
import dev.baton.director.UtteranceId
import dev.baton.director.UtteranceKind
import dev.baton.director.Variant
import dev.baton.ports.SpeechSynth
import dev.baton.player.BatonPlayer
import dev.baton.player.BatonPlayerSurface
import dev.baton.speech.RenderedAudioSpeaker

/**
 * The tracer (issue #3): a bundled description track, spoken in the gaps, with
 * the film ducked underneath.
 *
 * Everything this activity does is wiring. The clip plays, three cues load from
 * `assets`, every variant is re-rendered and re-measured on whichever
 * text-to-speech engine can actually speak, and the director is handed the
 * result. The whole timeline is logged under one tag so that what you hear can be
 * checked against what was decided:
 *
 * ```
 * adb logcat -s Baton:V
 * ```
 */
class MainActivity : ComponentActivity() {

    private lateinit var player: BatonPlayer
    private val handler = Handler(Looper.getMainLooper())

    private var tracer: Tracer? = null
    private var speaker: RenderedAudioSpeaker? = null
    private var synth: SpeechSynth? = null

    /**
     * The demo overlay (see `DemoOverlay.kt`): off by default, on with `--ez
     * overlay true`, same launch-extra pattern as `seekMs`/`playForMs`/
     * `staticDuck` below. It exists because a 2026-09-10 listening test could
     * not hear the adaptive duck against the static one on the same scene, so
     * a demo video has to show it instead -- see `FRICTION_LOG.md`.
     */
    private var overlayEnabled = false
    private var overlayState by mutableStateOf(OverlayState())

    /** The transport bar's phrases, rendered at preparation. Empty until then, and empty if no engine can speak. */
    private var narration: Map<String, Variant> = emptyMap()
    private var narrationCount = 0

    /** `playWhenReady`: whether the viewer wants the film moving, which is what the Play/Pause button shows. */
    private var playing by mutableStateOf(false)

    /**
     * Whether preparation has come back, successfully or not. Until then the transport
     * does nothing: a Play press on 2026-09-12 started the film 12 s before the director
     * existed, so the first subtitle was never spoken and the disclosure came late.
     */
    private var trackReady = false

    /**
     * The track whose synthetic-voice notice is on screen. Set when preparation returns it,
     * which is when playback starts, and cleared [NOTICE_MS] later so the notice does not
     * sit over the film for the whole session; the spoken disclosure covers the rest.
     */
    private var noticeTrack by mutableStateOf<DescriptionTrack?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayEnabled = intent.getBooleanExtra("overlay", false)

        player = BatonPlayer(this, ::log)
        // The second URI is the clip's English SubRip sidecar.
        player.setSource(
            Uri.parse("android.resource://$packageName/${R.raw.sample_clip}"),
            Uri.parse("android.resource://$packageName/${R.raw.sample_clip_en}"),
        )
        player.exoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
        })

        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                BatonPlayerSurface(
                    exoPlayer = player.exoPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
                if (overlayEnabled) {
                    DemoOverlay(
                        state = overlayState,
                        modifier = Modifier.align(Alignment.TopStart).padding(32.dp),
                    )
                }
                noticeTrack?.let {
                    SyntheticVoiceNotice(it, Modifier.align(Alignment.TopEnd).padding(32.dp))
                }
                TransportBar(
                    playing = playing,
                    onRewind = { if (transportReady("rewind")) player.exoPlayer.seekBack() },
                    onPlayPause = ::togglePlayback,
                    onFastForward = { if (transportReady("fast-forward")) player.exoPlayer.seekForward() },
                    announce = ::announce,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                )
            }
        }

        // Rendering blocks on TextToSpeech, whose onInit arrives via a
        // ServiceConnection dispatched on this very looper. Waiting for it here
        // would deadlock, so preparation happens off the main thread and playback
        // starts once there is a track worth playing against.
        Thread({ prepareThenPlay() }, "baton-prepare").start()
        handler.post(heartbeat)
    }

    private fun prepareThenPlay() {
        // Listening tests / demo runs: `--ez descriptions false` and
        // `--ez spokenSubtitles false` turn each voice off at the source, so a
        // run with subtitles withheld is one that never rendered them. Both
        // default to on; the on-screen subtitle track is unaffected by either.
        val prepared = runCatching {
            TrackPreparation.prepare(
                this,
                descriptions = intent.getBooleanExtra("descriptions", true),
                spokenSubtitles = intent.getBooleanExtra("spokenSubtitles", true),
                log = ::log,
            )
        }
            .onFailure { log("ERROR track preparation failed: $it") }
            .getOrNull()

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            synth = prepared?.synth
            narration = prepared?.narration.orEmpty()
            noticeTrack = prepared?.track
            handler.postDelayed({ noticeTrack = null }, NOTICE_MS)

            if (prepared != null) {
                val audioSpeaker = RenderedAudioSpeaker(::log)
                speaker = audioSpeaker
                val running = Tracer(
                    clock = player.clock,
                    gain = player.contentGain,
                    // The processor that is not ducking is the one that meters:
                    // it sees the content's own samples before any gain is
                    // applied to them. See DuckingAudioProcessor.
                    //
                    // Listening tests: `--ez staticDuck true` never measures, so
                    // the director falls back to the static duck -- the "before"
                    // half of an adaptive A/B on the same scene.
                    programmeLoudness = if (intent.getBooleanExtra("staticDuck", false)) {
                        object : dev.baton.ports.ProgrammeLoudness {
                            override fun shortTermLufs(): Double? = null
                        }
                    } else {
                        player.sampleLevelGain
                    },
                    speaker = audioSpeaker,
                    director = AudioDirector(prepared.track, DirectorConfig(), prepared.disclosure),
                    log = ::log,
                    onDuckChanged = if (overlayEnabled) {
                        { duck -> overlayState = overlayState.copy(duckTargetDb = duck.db) }
                    } else {
                        {}
                    },
                    onSpeakingChanged = if (overlayEnabled) {
                        { utterance -> overlayState = overlayState.copy(speaking = utterance) }
                    } else {
                        {}
                    },
                )
                tracer = running
                player.onSeek(running::onSeek)
                running.start()
            }

            // Listening tests: `am start ... --el seekMs 39000 --el playForMs 9500`
            // plays one snippet and stops, so a single cue can be heard on its own.
            // Leave six seconds before the cue -- the meter resets on a seek and a
            // cue that arrives before it refills is ducked by the static fallback.
            val seekMs = intent.getLongExtra("seekMs", 0L)
            if (seekMs > 0L) player.exoPlayer.seekTo(seekMs)
            trackReady = true
            log("playback starting")
            player.play()
            val playForMs = intent.getLongExtra("playForMs", 0L)
            if (playForMs > 0L) handler.postDelayed({ player.exoPlayer.playWhenReady = false }, playForMs)
        }
    }

    /**
     * One line a second, so that a run can be read back against what was heard:
     * where the content is, whether it is moving, and what gain the render chain
     * is actually applying to it.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            val gain = player.sampleLevelGain
            val positionMs = player.clock.positionMs()
            log(
                "state positionMs=$positionMs playback=${player.clock.playback()} " +
                    "volume=${"%.4f".format(player.exoPlayer.volume)} " +
                    "gain=${"%.4f".format(gain.appliedGain)} fades=${gain.fadesStarted} " +
                    "framesProcessed=${gain.framesProcessed} " +
                    // writtenPositionMs is where the sink's write pointer sits;
                    // positionMs is where the platform reports as audible right
                    // now (see PlaybackClock). The gap between them is the
                    // sink's buffered latency -- the depth the old,
                    // sample-level duck would have had to travel through, kept
                    // here as the comparison point for the output-stage duck.
                    "sinkLatencyMs=${gain.writtenPositionMs() - positionMs} " +
                    "programmeLufs=${gain.shortTermLufs()?.let { "%.1f".format(it) } ?: "unmeasured"}",
            )
            // The overlay's other two readings ride this same once-a-second
            // heartbeat rather than a clock of their own -- both are continuous
            // measurements with no discrete "changed" moment to hook, unlike the
            // duck target and the speaking text, which Tracer pushes the instant
            // they change (see Tracer.onDuckChanged / onSpeakingChanged).
            if (overlayEnabled) {
                overlayState = overlayState.copy(
                    shortTermLufs = gain.shortTermLufs(),
                    appliedVolume = player.exoPlayer.volume,
                )
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Coming back to the app: restart the loop [onStop] stopped, and say where playback
     * stands. Null on the first start, which is a launch and not a return -- preparation
     * posts the tracer back to this looper only after `onCreate`, `onStart` and
     * `onResume` have run.
     */
    override fun onStart() {
        super.onStart()
        val running = tracer ?: return
        running.start()
        announce(TransportNarration.state(player.exoPlayer.playWhenReady))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemoteBindings.togglesPlayback(event.keyCode, event.action, event.repeatCount)) {
            togglePlayback()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Read back rather than assumed: audio focus can refuse the play, and the state is
     * masked synchronously (ExoPlayerImpl.java:2818-2830, Media3 1.4.1), so the director
     * sees the pause and the "Paused" request on the same tick -- the pause edge clears
     * the floor first and the request then takes it (AudioDirector.advance).
     */
    private fun togglePlayback() {
        if (!transportReady("play-pause")) return
        val exo = player.exoPlayer
        exo.playWhenReady = !exo.playWhenReady
        announce(TransportNarration.state(exo.playWhenReady))
    }

    /** Refused silently before [trackReady]: nothing is rendered yet to say it with. The log line is the trace. */
    private fun transportReady(control: String): Boolean {
        if (!trackReady) log("transport refused control=$control reason=not-prepared")
        return trackReady
    }

    /**
     * Ask the director for one of the transport bar's pre-rendered phrases.
     *
     * A new id every time, never one derived from the phrase: the director releases the
     * floor on a `SpeechEnded` matched by id alone, so a late report from an earlier
     * "Play, button" would otherwise silence this one.
     */
    private fun announce(phrase: String) {
        val running = tracer
        val variant = narration[phrase]
        if (running == null || variant == null) {
            val reason = if (running == null) "not-prepared" else "not-rendered"
            log("reactive unavailable kind=${UtteranceKind.Reactive.UI_NARRATION} phrase=\"$phrase\" reason=$reason")
            return
        }
        narrationCount += 1
        running.request(
            ReactiveRequest(
                id = UtteranceId("ui-$narrationCount"),
                kind = UtteranceKind.Reactive.UI_NARRATION,
                text = phrase,
                length = SpeechLength.Measured(variant.measuredDuration),
                audioRef = variant.audioRef,
            ),
        )
    }

    override fun onStop() {
        super.onStop()
        tracer?.stop()
        player.exoPlayer.playWhenReady = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        tracer?.stop()
        speaker?.release()
        synth?.release()
        player.release()
        super.onDestroy()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        /** One tag for the whole timeline. `adb logcat -s Baton:V`. */
        const val TAG = "Baton"

        /** How long the synthetic-voice notice stays on screen once playback starts. */
        const val NOTICE_MS = 10_000L
    }
}
