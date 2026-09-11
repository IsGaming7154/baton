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
        /**
         * The one sentence telling the viewer that the speech on this track is
         * synthetic. Owed once per session by a track marked
         * [DescriptionTrack.synthetic]; see [AudioDirector] on when it is spoken.
         *
         * Scheduled, because nothing about being a disclosure entitles it to talk
         * over an actor: it is held to silence exactly as a cue is. A kind of its
         * own rather than filed under [AUDIO_DESCRIPTION], because every report the
         * director makes names a kind -- a drop's `floorHeldBy`, a withdrawal, the
         * tracer's log -- and under a borrowed name a description squeezed out by
         * the disclosure would read as squeezed out by a description the track does
         * not contain.
         *
         * It is never a cue. A track cannot place it at a moment ([Cue] refuses the
         * kind), because what it discloses is true of the whole session and not of
         * one scene -- so its [priority] is not consulted by the cue contest at all.
         * The number says what the policy does: it goes first.
         */
        SYNTHETIC_SPEECH_DISCLOSURE(0),
        SPOKEN_SUBTITLE(1),
        AUDIO_DESCRIPTION(2),
    }
}
