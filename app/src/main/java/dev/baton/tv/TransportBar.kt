package dev.baton.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/**
 * The app shell (issue #6): Rewind, Play/Pause and Fast-forward, each of which says
 * what it is when focus lands on it.
 *
 * ## Baton speaks, VoiceView does not
 *
 * Focus is announced through [announce], which the activity turns into a
 * `UI_NARRATION` request to the director -- so the announcement ducks the film,
 * preempts a description, and is itself cut off by the next press, exactly like any
 * other reactive voice. VoiceView would announce the same focus change in its own
 * voice, outside the director and on top of whatever the director let speak, so the
 * buttons are marked [invisibleToUser]. Whether that actually keeps VoiceView quiet is
 * not settled by source: Compose 1.6.8 still sends `TYPE_VIEW_FOCUSED` for a focused
 * node that is invisible to the user (AndroidComposeViewAccessibilityDelegateCompat
 * .android.kt:2678-2685, with no visibility check on that path), so it is a device
 * check.
 *
 * The label is still set as the content description, which is what the
 * composition-tree test reads: every focusable node carries its narration text and
 * its role, and the role is tv-material's own (`Role.Button`, Button.kt:197).
 *
 * The bar is a [Column] holding one [Row] so that the access-services row (issue #7)
 * has a place one D-pad press below it.
 */
@Composable
fun TransportBar(
    playing: Boolean,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onFastForward: () -> Unit,
    announce: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPause = remember { FocusRequester() }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TransportButton(TransportNarration.REWIND, onRewind, announce)
            TransportButton(
                label = if (playing) TransportNarration.PAUSE else TransportNarration.PLAY,
                onClick = onPlayPause,
                announce = announce,
                modifier = Modifier.focusRequester(playPause),
            )
            TransportButton(TransportNarration.FAST_FORWARD, onFastForward, announce)
        }
    }
    LaunchedEffect(Unit) { playPause.requestFocus() }
}

/**
 * [onFocusChanged] sits in the modifier handed to tv-material's `Button`, which puts it
 * ahead of the surface's own `focusable()` (Surface.kt:153, 425), and it observes the
 * first focus target after it (FocusChangedModifier.kt:23-26). Relabelling Play to
 * Pause swaps the callback without re-announcing, because the node only fires on a
 * change of focus state (FocusChangedModifier.kt:42-44, 56-60).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TransportButton(
    label: String,
    onClick: () -> Unit,
    announce: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .semantics {
                contentDescription = label
                invisibleToUser()
            }
            .onFocusChanged { if (it.isFocused) announce(TransportNarration.focused(label)) },
    ) {
        Text(label)
    }
}

/**
 * Every sentence the transport bar can say, fixed ahead of time.
 *
 * Fixed because nothing else can be spoken. The speaker plays only audio that was
 * rendered and measured before it was asked to -- `RenderedAudioSpeaker` refuses an
 * utterance with no `audioRef`, and `SpeechSynth` has no `speak(text)` on purpose --
 * so a phrase missing from [PHRASES] is a phrase the viewer never hears.
 * `TrackPreparation` renders all of them on the selected engine, beside the
 * disclosure.
 */
object TransportNarration {
    const val REWIND = "Rewind"
    const val PLAY = "Play"
    const val PAUSE = "Pause"
    const val FAST_FORWARD = "Fast forward"

    /** What focus landing on a control says: its label, then its role. Every control here is a button. */
    fun focused(label: String): String = "$label, button"

    /** What the viewer is told on return to the app, and after toggling playback. */
    fun state(playing: Boolean): String = if (playing) "Playing" else "Paused"

    val PHRASES: List<String> =
        listOf(REWIND, PLAY, PAUSE, FAST_FORWARD).map(::focused) + state(playing = true) + state(playing = false)
}
