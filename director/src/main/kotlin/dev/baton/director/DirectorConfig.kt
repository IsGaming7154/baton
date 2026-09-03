package dev.baton.director

/**
 * The two numbers the arbitration policy is parameterised by.
 *
 * Both have defaults that are the product's stated behaviour; they are settable
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
) {
    init {
        require(guardMs >= 0) { "guardMs must be >= 0, was $guardMs" }
        require(duckDb < 0.0) { "duckDb must attenuate; $duckDb dB does not" }
    }

    companion object {
        const val DEFAULT_GUARD_MS: Long = 250L
        const val DEFAULT_DUCK_DB: Double = -12.0
    }
}
