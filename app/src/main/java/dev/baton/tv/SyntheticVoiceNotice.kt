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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.baton.director.DescriptionTrack

/**
 * The synthetic-voice notice (issue #12): the disclosure as text, for a sighted viewer.
 *
 * The first line is the spoken disclosure's own sentence, shown on the condition the
 * director speaks it on -- the track is `synthetic`. The second is the track's
 * `syntheticDub`, shown as written: which dub, and whose voices, are facts about the
 * title, so they come with the title.
 *
 * The third line is there only when this title's descriptions are stored off.
 * Baton says that out loud too, but only when the film has left room for it
 * before its first cue -- on the demo clip it has not, so on screen is the only
 * place a viewer learns it. See `MainActivity.announceDescriptionOffHint`.
 *
 * ## Why VoiceView never reads it
 *
 * The director is the only voice, and it already speaks the disclosure; VoiceView reading
 * this would be a second voice saying it again. So every line is [invisibleToUser] on its
 * own node, not on the column. Compose 1.6.8 decides visibility from a node's own
 * semantics alone (AndroidComposeViewAccessibilityDelegateCompat.android.kt:3569-3570) and
 * adds every child to its parent's node info with no visibility check (:830-836), so a
 * [Text] under an invisible column would still be visible to the user. Nothing here can
 * take focus, so the `TYPE_VIEW_FOCUSED` path `TransportBar` leaves to a device check does
 * not arise.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SyntheticVoiceNotice(
    track: DescriptionTrack,
    modifier: Modifier = Modifier,
    descriptionOff: Boolean = false,
) {
    val lines = listOfNotNull(
        TrackPreparation.DISCLOSURE_TEXT.takeIf { track.synthetic },
        track.syntheticDub,
        TransportNarration.DESCRIPTION_OFF_HINT.takeIf { descriptionOff },
    )
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                modifier = Modifier.semantics { invisibleToUser() },
                color = Color.White,
                fontSize = 16.sp,
            )
        }
    }
}
