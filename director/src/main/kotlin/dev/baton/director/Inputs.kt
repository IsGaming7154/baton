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

/** Whether the content is running. Level state, reported every tick. */
enum class Playback {
    PLAYING,
    PAUSED,
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
) {
    init {
        require(positionMs >= 0) { "positionMs must be >= 0, was $positionMs" }
    }
}
