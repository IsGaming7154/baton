package dev.baton.pipeline

import dev.baton.director.DirectorConfig

/**
 * Everything the builder is parameterised by.
 *
 * The numbers that also govern the runtime are taken from [DirectorConfig] rather
 * than restated here. The pipeline's job is to author cues the director will
 * actually admit, so the two must agree about the guard interval by construction
 * and not by two constants that happen to match today.
 */
data class PipelineConfig(
    val gapMap: GapMapConfig = GapMapConfig(),

    /** The runtime's admission rule, applied at authoring time. */
    val guardMs: Long = DirectorConfig.DEFAULT_GUARD_MS,

    /**
     * How far into a silence window a cue starts. The content duck ramps over
     * 150 ms, so starting the voice at the very edge of the window would put the
     * first syllable underneath a still-falling film.
     */
    val cueLeadInMs: Long = DEFAULT_CUE_LEAD_IN_MS,

    /** How many length options to author per cue, when the drafter can supply them. */
    val variantsPerCue: Int = DEFAULT_VARIANTS_PER_CUE,

    /** Hard ceiling on drafts requested for one cue. Bounds the loop and, live, the bill. */
    val maxDraftAttempts: Int = DEFAULT_MAX_DRAFT_ATTEMPTS,

    /**
     * After a draft fits, the next one is asked for at this fraction of its
     * measured length. Small enough that the variants are meaningfully different
     * lengths rather than three phrasings of the same sentence.
     */
    val variantShrinkFactor: Double = DEFAULT_VARIANT_SHRINK_FACTOR,

    /**
     * After a draft overruns, the next one is asked for at this fraction of the
     * room available. Aiming at the room exactly would converge on the boundary
     * and overrun again on the next measurement.
     */
    val overrunBackoff: Double = DEFAULT_OVERRUN_BACKOFF,

    val voice: VoiceSpec = DEFAULT_VOICE,

    /** How many words of dialogue either side of a gap the drafter is shown. */
    val contextWords: Int = DEFAULT_CONTEXT_WORDS,
) {
    init {
        require(guardMs >= 0) { "guardMs must be >= 0, was $guardMs" }
        require(cueLeadInMs >= 0) { "cueLeadInMs must be >= 0, was $cueLeadInMs" }
        require(variantsPerCue >= 1) { "a cue needs at least one variant, asked for $variantsPerCue" }
        require(maxDraftAttempts >= variantsPerCue) {
            "maxDraftAttempts ($maxDraftAttempts) cannot be below variantsPerCue ($variantsPerCue)"
        }
        require(variantShrinkFactor in 0.0..1.0) { "variantShrinkFactor must be in (0, 1], was $variantShrinkFactor" }
        require(overrunBackoff in 0.0..1.0) { "overrunBackoff must be in (0, 1], was $overrunBackoff" }
        require(contextWords >= 0) { "contextWords must be >= 0, was $contextWords" }
    }

    companion object {
        const val DEFAULT_CUE_LEAD_IN_MS: Long = 150L
        const val DEFAULT_VARIANTS_PER_CUE: Int = 3
        const val DEFAULT_MAX_DRAFT_ATTEMPTS: Int = 6
        const val DEFAULT_VARIANT_SHRINK_FACTOR: Double = 0.65
        const val DEFAULT_OVERRUN_BACKOFF: Double = 0.85
        const val DEFAULT_CONTEXT_WORDS: Int = 12

        /** Polly's neural Joanna at 24 kHz, which is what the live slice will ask for. */
        val DEFAULT_VOICE: VoiceSpec = VoiceSpec(voiceId = "Joanna", engine = "neural", sampleRateHz = 24_000)
    }
}
