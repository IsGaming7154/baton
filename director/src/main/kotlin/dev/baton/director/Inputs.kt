package dev.baton.director

/** Identifies one utterance instance -- one thing the speaker was asked to say. */
@JvmInline
value class UtteranceId(val value: String) {
    init {
        require(value.isNotBlank()) { "utterance id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * How long a piece of speech will last.
 *
 * Scheduled speech is always [Measured] -- that is what makes it schedulable.
 * Reactive speech may be either: a pre-rendered answer has a measured length,
 * while a live text-to-speech utterance has none until the engine says it is
 * done, so it runs until the speaker reports back or something preempts it.
 */
sealed interface SpeechLength {

    /** Ends by itself after a duration measured off rendered audio. */
    data class Measured(val duration: MeasuredDurationMs) : SpeechLength {
        init {
            require(duration.millis > 0) { "an utterance of zero length is not an utterance" }
        }
    }

    /** Ends only when the speaker reports completion, or when something preempts it. */
    data object UntilSpeakerReports : SpeechLength
}

/** A voice the user asked for by pressing a button. */
data class ReactiveRequest(
    val id: UtteranceId,
    val kind: UtteranceKind.Reactive,
    val text: String,
    val length: SpeechLength = SpeechLength.UntilSpeakerReports,
    val audioRef: String? = null,
) {
    init {
        require(text.isNotBlank()) { "a reactive request must have text" }
    }
}

/**
 * Whether the content is running, and if it is not, whose doing that is. Level
 * state, reported every tick.
 *
 * [PAUSED] and [STALLED] both mean the picture has stopped, and this enum used to
 * collapse them into one value. That cost a description every time a stream
 * hiccupped: a single tick of buffering read as a pause, the floor was cleared,
 * and the sentence was cut in half with nothing reported, because from the
 * director's seat nothing had gone wrong -- somebody paused.
 *
 * They are opposite kinds of fact. A pause is a **decision**: the viewer stopped
 * watching, so speech stops and the cue does not come back. A stall is an
 * **interruption**: nobody chose it, the viewer is still sitting there, and the
 * right answer is to hold everything exactly where it is until frames arrive.
 *
 * Three values rather than two facts side by side, because side by side they
 * would be representable in combinations that cannot happen -- paused *and*
 * stalled, playing *and* stalled -- and each of those would need a precedence
 * rule that somebody eventually gets wrong. The same argument the [Speaking] type
 * already makes: the states are mutually exclusive, so say so in the type and
 * there is nothing left to arbitrate.
 *
 * The distinction is given to the director, never inferred by it. From inside,
 * timing cannot tell the two apart -- both are a position that has stopped
 * advancing -- while the adapter that reads the player has the answer for free.
 */
enum class Playback {

    /** Frames are being presented; the content position is advancing. */
    PLAYING,

    /** Somebody decided to stop: the viewer, or the system taking the audio away. */
    PAUSED,

    /**
     * Nobody decided anything; the content ran out of data. The viewer is still
     * watching, at a frozen picture, and expects the film to carry on from where
     * it stopped -- so the director holds rather than tidies up.
     */
    STALLED,
}

/**
 * Something discrete that happened since the previous
 * [AudioDirector.advance]. Edge-triggered, and ordered: the events in one
 * advance are applied in the order they occurred.
 */
sealed interface InputEvent {

    /** The user pressed a button that has to be answered by speech. */
    data class ReactiveRequested(val request: ReactiveRequest) : InputEvent

    /**
     * The speaker finished, or was stopped, and says so. Only meaningful for an
     * utterance whose length was not measured up front; harmless otherwise, and
     * ignored if it names an utterance that is not the one holding the floor.
     */
    data class SpeechEnded(val id: UtteranceId) : InputEvent

    /**
     * Playback jumped. The queue is cleared, whatever was speaking stops, and the
     * director rebaselines to the new position.
     */
    data object Seeked : InputEvent
}

/**
 * Everything the director is told on one tick.
 *
 * [positionMs] is where we are on the *content* timeline; the `nowMs` argument to
 * [AudioDirector.advance] is a monotonic wall clock. Both are needed and they are
 * not interchangeable: a cue's eligibility is a fact about the content, whereas
 * how far through an answer the speaker has got is a fact about elapsed real
 * time -- and the two diverge the moment the viewer pauses.
 */
data class DirectorInputs(
    val positionMs: Long,
    val playback: Playback = Playback.PLAYING,
    val events: List<InputEvent> = emptyList(),
    /**
     * How loud the content itself is right now, as ITU-R BS.1770 short-term
     * loudness, or null when nothing has been measured yet.
     *
     * A measurement and not a setting: it comes from a meter reading the decoded
     * content in the render chain, and it is what turns the duck from a fixed
     * number into a number that fits the scene. Null on the first few ticks and
     * after a seek, which is a real state and not an error -- the meter needs a
     * full three-second window before it has anything honest to say, and the
     * frames either side of a seek are not adjacent. [duckFor] handles it.
     */
    val programmeLufs: Double? = null,
) {
    init {
        require(positionMs >= 0) { "positionMs must be >= 0, was $positionMs" }
    }
}
