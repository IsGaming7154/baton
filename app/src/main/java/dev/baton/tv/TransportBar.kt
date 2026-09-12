package dev.baton.tv

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
