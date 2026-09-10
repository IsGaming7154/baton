package dev.baton.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * The one Compose surface that touches ExoPlayer directly. Hosts a Media3
 * [PlayerView] via interop rather than reimplementing transport controls --
 * its built-in controller already answers D-pad play/pause, rewind and
 * fast-forward, which is exactly what this tracer slice needs to prove.
 */
@OptIn(UnstableApi::class)
@Composable
fun BatonPlayerSurface(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
                setShowRewindButton(true)
                setShowFastForwardButton(true)
                setShowPreviousButton(false)
                setShowNextButton(false)
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        },
        onRelease = { it.player = null },
    )
}
