package dev.baton.tv

import androidx.compose.foundation.background
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

/**
 * What the screen says while the track is being prepared.
 *
 * Preparation renders every description, every spoken subtitle, the disclosure
 * and the transport phrases to audio before the film may start, which on a cold
 * cache takes about twenty seconds on the Stick. Until this existed the screen
 * was black and silent for all of it, and a press on Play was refused without a
 * word -- the phrase that would have said so had not been rendered either. An
 * application that leaves a viewer wondering whether it is broken is the failure
 * this project exists to argue against, so it says what it is doing.
 *
 * [invisibleToUser], because Baton speaks this line itself through the engine as
 * soon as one is selected (`TtsSpeechSynth.sayNow`). VoiceView reading it as
 * well would be two voices saying the same sentence, which is the thing the
 * director exists to prevent.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PreparingNotice(modifier: Modifier = Modifier) {
    Text(
        text = TrackPreparation.PREPARING_TEXT,
        modifier = modifier
            .widthIn(max = 480.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { invisibleToUser() },
        color = Color.White,
        fontSize = 20.sp,
    )
}
