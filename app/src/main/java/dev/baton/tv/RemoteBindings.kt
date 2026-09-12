package dev.baton.tv

import android.view.KeyEvent

/**
 * The remote keys the app binds itself, which is one. The whole table -- the gestures
 * VoiceView reserves, what the shell uses, and why the rest is left alone -- is the
 * "Remote bindings" section of README.md.
 *
 * D-pad and Select are not here because the app does not bind them: they move and
 * activate focus through Compose and tv-material, which already answer them.
 * Rewind and Fast-forward are left unbound on purpose. Bound a key at a time, as
 * Media3's own controller does (PlayerControlView.java:1651-1660), both would fire on
 * a Rewind+Fast-Forward chord, which VoiceView owns. The on-screen buttons seek instead.
 */
object RemoteBindings {

    /**
     * A short press of Play/Pause, acted on at key down. A held key repeats with
     * `repeatCount > 0` and toggles nothing more.
     */
    fun togglesPlayback(keyCode: Int, action: Int, repeatCount: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && action == KeyEvent.ACTION_DOWN && repeatCount == 0
}
