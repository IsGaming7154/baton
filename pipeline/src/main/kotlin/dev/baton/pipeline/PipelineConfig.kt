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
     *
     * Paid once per window and not once per cue. The second description in a
     * window is separated from the first by [guardMs], which is already longer
     * than the ramp; charging a lead-in there as well would be paying twice for
     * the same 150 ms of fade, and the thing the lead-in is actually holding back
     * from -- the tail of a line of dialogue -- is nowhere near it.
     */
    val cueLeadInMs: Long = DEFAULT_CUE_LEAD_IN_MS,

    /** How many length options to author per cue, when the drafter can supply them. */
    val variantsPerCue: Int = DEFAULT_VARIANTS_PER_CUE,

    /**
     * How far apart two variants of the same cue must measure before the second
     * one is worth keeping. A draft that lands inside this of one already held is
     * discarded and another is asked for.
     *
     * The rejected alternative is what was here before: dedupe by exact text. It
     * catches a drafter that repeats itself verbatim and nothing else, and the
     * failure that matters is not repetition -- it is three genuinely different
     * sentences that happen to take the same time to say. The runtime's only
     * question of a variant list is "which is the longest that fits", so three
     * paraphrases of one length are one option written down three times, and the
     * two that lose have cost a synthesis round trip each to author and a slot each
     * in the shipped track.
     *
     * The number is the runtime's own tolerance, [DirectorConfig.guardMs], and it
     * is that number for a reason rather than by reuse. The band of window sizes in
     * which one variant is chosen over the next longest is exactly the difference
     * between their lengths; a band narrower than the guard is narrower than the
     * slop the admission rule already grants itself, so no window can reliably
     * land in it and the shorter variant is never distinguishably the choice. It
     * corroborates from the other direction too: 250 ms is about two thirds of a
     * word at the 165 wpm this pipeline sizes everything against, so drafts closer
     * together than this are not a word apart and are the same sentence however
     * differently they are phrased.
     *
     * It is a floor and not a target. [variantShrinkFactor] is what actually
     * spaces the variants out; this only refuses the ones that came back the same
     * length anyway. Kept separate from [guardMs] as a field because the two
     * answer different questions -- a build that wanted a coarser editorial spread
     * should be able to ask for one without moving the runtime's admission
     * tolerance underneath it.
     */
    val variantSeparationMs: Long = DirectorConfig.DEFAULT_GUARD_MS,

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
        require(variantSeparationMs > 0) {
            "variantSeparationMs must be > 0, was $variantSeparationMs; at zero two drafts of " +
                "identical length are two variants, which is the thing it exists to refuse"
        }
    }

    /**
     * The least room a cue is worth opening a slot for, and therefore the test a
     * silence window's *leftover* has to pass before the builder tries to put
     * another description in it.
     *
     * Derived rather than declared, because the number already exists.
     * [GapMapConfig.minimumUsefulGapMs] is the shortest window worth shipping, and
     * it was arrived at by adding up a lead-in, the shortest description worth
     * writing -- about three words, roughly 1.1 s at 165 wpm -- and the guard.
     * Subtracting the two overheads back off recovers that description length,
     * which is exactly the quantity being asked about: is there room here to say
     * anything at all? Declaring it separately would let the two numbers drift and
     * produce a builder that opens slots the gap map would have thrown the window
     * away for.
     *
     * Only ever applied to the leftover. The first slot in a window is opened on
     * the weaker test that any room survives the overhead, because the gap map has
     * already certified that window as worth shipping and reversing that decision
     * here would leave the window in the track with nothing to explain its
     * silence.
     */
    val minimumCueRoomMs: Long
        get() = (gapMap.minimumUsefulGapMs - cueLeadInMs - guardMs).coerceAtLeast(1L)

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
