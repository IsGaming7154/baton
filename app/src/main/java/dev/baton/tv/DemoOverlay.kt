package dev.baton.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.baton.director.Speaking
import dev.baton.director.UtteranceKind

/**
 * Everything [DemoOverlay] draws, gathered in one place because it is read
 * from two different clocks: [shortTermLufs] and [appliedVolume] are sampled
 * about once a second (see the heartbeat in `MainActivity`, which already
 * reads both for the log), while [duckTargetDb] and [speaking] are pushed the
 * moment `Tracer` decides them, because a description has to appear the
 * instant it starts rather than up to a second late.
 */
data class OverlayState(
    val shortTermLufs: Double? = null,
    val duckTargetDb: Double = 0.0,
    val appliedVolume: Float = 1.0f,
    val speaking: Speaking.Utterance? = null,
)

/**
 * The demo overlay: on with `am start -n dev.baton.tv/.MainActivity --ez
 * overlay true` (see `MainActivity`), for a sighted viewer of the demo video
 * to read the duck a 2026-09-10 listening test could not hear -- see
 * `FRICTION_LOG.md`. Never in the shipping product's own path; `MainActivity`
 * only composes this when the launch extra asks for it.
 *
 * ## Why a blind viewer never hears this
 *
 * [Modifier.semantics] with [invisibleToUser] marks the whole subtree present
 * on screen but absent from the accessibility tree Fire TV's VoiceView reads
 * from -- not the same thing as leaving off a content description, which
 * would still stop VoiceView here and announce an unlabeled element. This is
 * "does not exist" to VoiceView rather than "exists and has nothing to say."
 *
 * Nothing here can take D-pad focus either, independently of that: every
 * element is a plain [Text], and only a component built to be clickable or
 * explicitly marked `Modifier.focusable()` can take focus in Compose -- this
 * composable does neither, so there is nothing for focus to land on even
 * before VoiceView is asked to skip it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DemoOverlay(state: OverlayState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .semantics { invisibleToUser() }
            .widthIn(max = 480.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "LUFS ${state.shortTermLufs?.let { "%.1f".format(it) } ?: "measuring…"}" +
                "   duck ${"%.1f".format(state.duckTargetDb)}dB" +
                "   vol ${"%.2f".format(state.appliedVolume)}",
            color = Color.White,
            fontSize = 18.sp,
        )
        state.speaking?.let { utterance ->
            Text(
                text = "${utterance.kind.displayName}: ${utterance.text}",
                color = Color(0xFFFFD54F),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Both branches are enums with their own `.name`; `UtteranceKind` itself has no shared one. */
private val UtteranceKind.displayName: String
    get() = when (this) {
        is UtteranceKind.Scheduled -> name
        is UtteranceKind.Reactive -> name
    }
