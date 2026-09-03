package dev.baton.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Thin wrapper around ExoPlayer. Owns no arbitration policy -- it exists so
 * playback setup lives in one place instead of scattered through composables.
 *
 * Later slices adapt this surface to the `PlaybackClock` / `ContentGain`
 * ports described in the PRD; this tracer slice only proves the toolchain.
 */
class BatonPlayer(context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    fun setSource(uri: Uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.playWhenReady = true
    }

    fun release() {
        exoPlayer.release()
    }
}
