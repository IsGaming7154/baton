package dev.baton.director

/**
 * The numbers the arbitration policy is parameterised by.
 *
 * All have defaults that are the product's stated behaviour; they are settable
 * so that tests can show the policy is driven by the numbers rather than by
 * coincidence.
 */
data class DirectorConfig(
    /**
     * Silence that must remain between the end of a scheduled utterance and the
     * next line of dialogue. Absorbs the ducking fade and clock jitter, and keeps
     * a description from finishing on top of an actor drawing breath.
     */
    val guardMs: Long = DEFAULT_GUARD_MS,
    /** Attenuation applied to the content while any non-content voice speaks. */
    val duckDb: Double = DEFAULT_DUCK_DB,
    /**
     * How far past its measured length an utterance may run before the director
     * stops waiting for the speaker and takes the floor back itself.
     *
     * This is a backstop, not a schedule. The floor is released by the speaker
     * reporting completion; this number only bounds how long a speaker that
     * never reports can wedge the machine. It must exceed [guardMs], because
     * anything inside the guard is ordinary jitter the guard already absorbs --
     * a timeout has to mean "something went wrong", or it means nothing.
     */
    val speechTimeoutSlackMs: Long = DEFAULT_SPEECH_TIMEOUT_SLACK_MS,
) {
    init {
        require(guardMs >= 0) { "guardMs must be >= 0, was $guardMs" }
        require(duckDb < 0.0) { "duckDb must attenuate; $duckDb dB does not" }
        require(speechTimeoutSlackMs > guardMs) {
            "speechTimeoutSlackMs ($speechTimeoutSlackMs) must exceed guardMs ($guardMs); " +
                "a timeout inside the guard would fire on ordinary jitter"
        }
    }

    companion object {
        const val DEFAULT_GUARD_MS: Long = 250L
        const val DEFAULT_DUCK_DB: Double = -12.0

        /**
         * Eight times the guard. Sized above the largest cross-engine timing
         * divergence measured on the target hardware -- 918 ms between the two
         * installed text-to-speech engines, FRICTION_LOG.md entry 9 -- so that a
         * timeout is evidence of a stall rather than of a slow synthesiser.
         */
        const val DEFAULT_SPEECH_TIMEOUT_SLACK_MS: Long = 2_000L
    }
}
