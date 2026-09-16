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
 * [PlayerView] via interop for the picture and the subtitles only.
 *
 * The built-in controller is off, and the view takes no focus. Its buttons are
 * Views that announce themselves to VoiceView and never to the audio director,
 * so the transport controls are Compose instead (`TransportBar` in `:app`), where
 * focus is narrated as a voice the director arbitrates. With the controller off
 * the view no longer handles media keys either (PlayerView.java:962-964, Media3
 * 1.4.1); the activity binds Play/Pause itself.
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
                useController = false
                // Lifts captions clear of the two-row TransportBar, which is always on
                // screen; Media3's 0.08 default put them under its buttons on the Stick.
                subtitleView?.setBottomPaddingFraction(CAPTION_BOTTOM_FRACTION)
            }
        },
        onRelease = { it.player = null },
    )
}

/** Calibrated by screenshot on the Stick at 1080p; the bar's top edge sits at 824 px. */
private const val CAPTION_BOTTOM_FRACTION = 0.26f
