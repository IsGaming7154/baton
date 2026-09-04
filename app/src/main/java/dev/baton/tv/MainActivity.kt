package dev.baton.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import dev.baton.director.AudioDirector
import dev.baton.director.DirectorConfig
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = BatonPlayer(this, ::log)
        player.setSource(Uri.parse("android.resource://$packageName/${R.raw.sample_clip}"))

        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                BatonPlayerSurface(
                    exoPlayer = player.exoPlayer,
                    modifier = Modifier.fillMaxSize(),
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
        val prepared = runCatching { TrackPreparation.prepare(this, ::log) }
            .onFailure { log("ERROR track preparation failed: $it") }
            .getOrNull()

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            synth = prepared?.synth

            if (prepared != null) {
                val audioSpeaker = RenderedAudioSpeaker(::log)
                speaker = audioSpeaker
                val running = Tracer(
                    clock = player.clock,
                    gain = player.contentGain,
                    speaker = audioSpeaker,
                    director = AudioDirector(prepared.track, DirectorConfig()),
                    log = ::log,
                )
                tracer = running
                player.onSeek(running::onSeek)
                running.start()
            }

            log("playback starting")
            player.play()
        }
    }

    /**
     * One line a second, so that a run can be read back against what was heard:
     * where the content is, whether it is moving, and what gain the render chain
     * is actually applying to it.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            val gain = player.contentGain
            log(
                "state positionMs=${player.clock.positionMs()} playback=${player.clock.playback()} " +
                    "gain=${"%.4f".format(gain.appliedGain)} fades=${gain.fadesStarted} " +
                    "framesProcessed=${gain.framesProcessed}",
            )
            handler.postDelayed(this, 1_000L)
        }
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
    }
}
