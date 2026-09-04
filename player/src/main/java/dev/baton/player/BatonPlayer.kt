package dev.baton.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.baton.director.Playback
import dev.baton.ports.ContentGain
import dev.baton.ports.PlaybackClock

/**
 * The ExoPlayer adapter, and only that.
 *
 * It implements two ports -- [PlaybackClock] and [ContentGain] -- and holds no
 * policy: it cannot tell you whether a cue may speak, only where the content is
 * and how loud it currently is. Everything that decides anything lives in
 * `:director`, on the other side of `:ports`, with no Android on its classpath.
 *
 * Every method here must be called on the thread that built the player, which is
 * ExoPlayer's rule and not ours.
 */
class BatonPlayer(context: Context, log: (String) -> Unit = {}) {

    /** The [ContentGain] port. Also the audio processor that implements it. */
    val contentGain: DuckingAudioProcessor = DuckingAudioProcessor(log = log)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory(context, contentGain)).build()

    val clock: PlaybackClock = object : PlaybackClock {
        override fun positionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

        // Buffering is not playing: the content position is not advancing, so a
        // cue admitted on it would be admitted against a clock that has stopped.
        override fun playback(): Playback = if (exoPlayer.isPlaying) Playback.PLAYING else Playback.PAUSED
    }

    fun setSource(uri: Uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.playWhenReady = true
    }

    /** Notifies [onSeek] whenever playback jumps, which is what the director rebaselines on. */
    fun onSeek(onSeek: () -> Unit) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) onSeek()
            }
        })
    }

    fun release() {
        exoPlayer.release()
    }

    private companion object {

        /**
         * The duck is a `DefaultAudioSink` processor rather than a volume call, so
         * the fade is applied to the samples themselves. Building the sink by hand
         * is the only way to get a processor into the chain.
         */
        fun renderersFactory(context: Context, gain: DuckingAudioProcessor): DefaultRenderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gain))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
    }
}
