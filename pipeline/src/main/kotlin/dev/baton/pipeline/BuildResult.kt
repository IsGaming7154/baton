package dev.baton.pipeline

import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.Gap

/**
 * Why the pipeline could not author a cue for a gap.
 *
 * Deliberately not the runtime's `DropReason`. They answer different questions at
 * different times: this one says "nothing could be written that would fit here",
 * decided once, offline, by the author; the runtime's says "the room this cue was
 * written for was not there when playback arrived", decided per viewing. Folding
 * them into one type would make each reason arrive with fields the other case
 * cannot fill, and would let an authoring failure masquerade as a playback one in
 * the drop report the viewer eventually sees.
 */
sealed interface AuthoringDropReason {

    /**
     * The drafter had nothing to offer for this gap at all -- for the live
     * drafter, a scene it declined to describe.
     */
    data object NoDraftOffered : AuthoringDropReason

    /**
     * Drafts came back, but every one of them measured longer than the gap could
     * hold, and the drafter ran out of shorter ones.
     */
    data class NoVariantFits(
        val roomMs: Long,
        val shortestMeasuredMs: Long,
        val attempts: Int,
    ) : AuthoringDropReason

    /**
     * The window survived the gap map but not the arithmetic: lead-in plus guard
     * leaves nothing to speak in. Only reachable with a configuration whose
     * thresholds disagree with each other.
     */
    data class NoRoomAfterGuard(val gapDurationMs: Long, val overheadMs: Long) : AuthoringDropReason
}

/**
 * A gap the pipeline could not describe, reported rather than skipped.
 *
 * Every gap the builder considered ends up either as a cue or as one of these, so
 * a missing description is always legible as a decision with a reason attached.
 */
data class AuthoringDrop(
    val intendedCueId: CueId,
    val gap: Gap,
    val reason: AuthoringDropReason,
)

/** What the fit loop did for one gap. The evidence that the loop ran, and how hard. */
data class FitRecord(
    val cueId: CueId,
    val gap: Gap,
    val roomMs: Long,
    val draftsRequested: Int,
    val draftsOverrun: Int,
    val variantsAuthored: Int,
) {
    /** True when at least one draft had to be shortened before it fit. */
    val converged: Boolean get() = draftsOverrun > 0 && variantsAuthored > 0
}

/**
 * The builder's whole output: the track the runtime consumes unmodified, plus the
 * account of what it cost to produce and what could not be produced at all.
 */
data class BuildResult(
    val track: DescriptionTrack,
    val drops: List<AuthoringDrop>,
    val fits: List<FitRecord>,
) {
    val draftsRequested: Int get() = fits.sumOf { it.draftsRequested }

    fun summary(): String = buildString {
        appendLine("${track.sourceId}: ${track.cues.size} cues, ${drops.size} drops, $draftsRequested drafts requested")
        for (fit in fits) {
            appendLine(
                "  ${fit.cueId} gap ${fit.gap.startMs}-${fit.gap.endMs} room ${fit.roomMs}ms " +
                    "-> ${fit.variantsAuthored} variants in ${fit.draftsRequested} drafts " +
                    "(${fit.draftsOverrun} overran)",
            )
        }
        for (drop in drops) {
            appendLine("  DROP ${drop.intendedCueId} gap ${drop.gap.startMs}-${drop.gap.endMs}: ${drop.reason}")
        }
    }
}
