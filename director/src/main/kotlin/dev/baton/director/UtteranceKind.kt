package dev.baton.director

/**
 * What kind of voice wants the floor.
 *
 * The split is not cosmetic: the class an utterance belongs to determines its
 * entire relationship with the content audio, so it is a type distinction rather
 * than a flag on a single enum.
 */
sealed interface UtteranceKind {

    /**
     * The user just pressed a button and is waiting to be answered. Reactive
     * speech ducks the content immediately, whether or not dialogue is playing,
     * and preempts anything already speaking.
     */
    enum class Reactive : UtteranceKind {
        UI_NARRATION,
        VQA_ANSWER,
    }

    /**
     * The app decided to say this. Scheduled speech may only occupy silence, is
     * measured to fit before it starts, and is dropped rather than allowed to run
     * into the next line of dialogue.
     *
     * [priority] orders the class against itself; lower wins. A spoken subtitle
     * outranks a description because a subtitle carries dialogue the viewer would
     * otherwise lose entirely, while a description carries scenery.
     */
    enum class Scheduled(val priority: Int) : UtteranceKind {
        SPOKEN_SUBTITLE(0),
        AUDIO_DESCRIPTION(1),
    }
}
