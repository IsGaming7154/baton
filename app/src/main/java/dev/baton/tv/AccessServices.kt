package dev.baton.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/**
 * The controls the access-services row offers, in the order they sit on screen.
 *
 * One enum rather than five parameters, because every one of them is the same
 * thing from the row's point of view -- a labelled two-state control that says its
 * label, its role and its state -- and the row's whole job is to render them
 * identically. What each one actually does is `MainActivity`'s business; see
 * `MainActivity.toggle`.
 *
 * [SUPPRESS] is not [DESCRIPTION] with a different name. The first is a moment --
 * "not in front of these people, right now" -- and the second is the setting
 * [TitleDescriptions] persists per title (issue #13). They are two independent facts
 * with one derived consequence (`MainActivity.silencedKinds`), which is exactly the
 * shape the acceptance criteria "suppress does not alter the persisted per-title
 * setting" and "muted without disabling the feature" ask for: neither button can
 * write the other's state, and only [DESCRIPTION] reaches storage.
 *
 * [DESCRIPTION_LENGTH] is two-state too, with Short and Long for its words instead
 * of on and off: in the set means Short.
 */
enum class AccessService(val label: String, private val onWord: String = "on", private val offWord: String = "off") {
    DESCRIPTION("Description"),
    DESCRIPTION_LENGTH("Description length", onWord = "short", offWord = "long"),
    SPOKEN_SUBTITLES("Spoken subtitles"),
    CAPTIONS("Captions"),
    SUPPRESS("Suppress description"),
    SCREEN_CURTAIN("Screen curtain"),
    ;

    /** The state word the control says and shows: "on"/"off", or "short"/"long". */
    fun state(on: Boolean): String = if (on) onWord else offWord
}

/**
 * Whether description is on, per title (issue #13), in `SharedPreferences` so it
 * survives a restart. Keyed by [dev.baton.director.DescriptionTrack.sourceId]: it is
 * the one identity a track carries for the source it was built for, and it is what
 * the pipeline was told the title is (`BuildRequest.sourceId`).
 *
 * A title never stored is off. That is the fix for the Fire TV defect #13 cites,
 * where description switched on once stays on for every title: here there is no
 * global switch to stay on, only this title's.
 */
object TitleDescriptions {
    private const val PREFS = "title_descriptions"

    fun isOn(context: Context, titleId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(titleId, false)

    fun set(context: Context, titleId: String, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(titleId, on).apply()
    }
}

/**
 * The access-services row (issue #7): the controls EN 301 549 clause 7.3 requires
 * to sit at the same prominence as play and pause, which is why they are one
 * D-pad press below the transport bar rather than inside a settings tree.
 *
 * It is the second row of `TransportBar`'s [androidx.compose.foundation.layout.Column],
 * so Down from a transport button lands here and Up goes back; Compose's
 * two-dimensional focus search does the moving, and the app binds no key for it
 * (see `RemoteBindings`).
 *
 * Narration and VoiceView follow `TransportBar` exactly, for the reasons its
 * docstring gives: the label is the content description, focus is spoken through
 * the director as a `UI_NARRATION` request, and each node is marked
 * [invisibleToUser] on its own node so VoiceView does not say the same thing in a
 * second voice. Whether VoiceView honours that is still a device check.
 *
 * Focus landing says the state; pressing does not re-announce from here, because
 * [onFocusChanged] only fires on a change of focus state
 * (FocusChangedModifier.kt:42-44, 56-60) and focus does not move when a toggle
 * flips. The press is announced by the caller that owns the state, the way
 * `MainActivity.togglePlayback` already announces Play/Pause.
 *
 * A [FlowRow], not a Row: six labels are wider than a 1920 px screen, and on the
 * Stick a Row squeezed "Screen curtain: off" into a one-letter-wide column. Wrapping
 * keeps every label whole; Down still reaches the first line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessServicesRow(
    on: Set<AccessService>,
    onToggle: (AccessService) -> Unit,
    announce: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (service in AccessService.entries) {
            AccessToggle(service, service in on, onToggle, announce)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AccessToggle(
    service: AccessService,
    on: Boolean,
    onToggle: (AccessService) -> Unit,
    announce: (String) -> Unit,
) {
    val spoken = TransportNarration.toggle(service, on)
    Button(
        onClick = { onToggle(service) },
        modifier = Modifier
            .semantics {
                contentDescription = spoken
                invisibleToUser()
            }
            .onFocusChanged { if (it.isFocused) announce(spoken) },
    ) {
        Text("${service.label}: ${service.state(on)}")
    }
}

/**
 * The screen curtain: black over everything, with the audio untouched.
 *
 * It is a [Box] and nothing else, which is the whole design. Playback continues
 * because there is no code path from here to the player -- no pause, no volume,
 * no surface detach -- so "audio continues uninterrupted" is a property of what
 * this composable does not contain rather than of anything it does. `MainActivity`
 * draws it last inside the same box as the video, so it covers the picture,
 * the subtitles and the controls.
 *
 * Nothing here is clickable or [androidx.compose.ui.focus.focusable], so focus
 * stays exactly where it was: the viewer turned the curtain on by pressing Select
 * on the Screen curtain toggle, that toggle still holds focus underneath, and
 * pressing Select again turns it off -- with both presses spoken. That is the
 * "entered and exited without sighted assistance" criterion, and it is why the
 * curtain must not steal focus.
 *
 * [invisibleToUser] for the reason `DemoOverlay` gives: the director is the only
 * voice, and a black rectangle has nothing to say.
 *
 * ponytail: black pixels, not a backlight. A Fire TV Stick drives someone else's
 * panel over HDMI and cannot dim it; if a future target has a backlight, that is
 * where the real curtain goes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScreenCurtain(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { invisibleToUser() },
    )
}
