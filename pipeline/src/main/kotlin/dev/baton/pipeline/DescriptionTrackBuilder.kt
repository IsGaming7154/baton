package dev.baton.pipeline

import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.Gap
import dev.baton.director.UtteranceKind
import dev.baton.director.Variant

/** What to build a track for. */
data class BuildRequest(
    val media: MediaRef,
    val sourceId: String,
    /** Length of the content, which is what closes the final silence window. */
    val mediaDurationMs: Long,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(mediaDurationMs > 0) { "mediaDurationMs must be > 0, was $mediaDurationMs" }
    }
}

/**
 * Turns a video into a [DescriptionTrack]: transcribe it, find the silence, write
 * something for each window, and prove each thing written fits by measuring the
 * audio of it.
 *
 * ## The fit loop
 *
 * The loop is the reason this class is not three function calls in a row.
 * `amazon:max-duration` constrains synthesis length only on Polly's Standard
 * voices, not on Neural or Long-Form -- and Standard is not the voice quality
 * this product wants in a blind viewer's ear. So the length of a description
 * cannot be specified; it can only be discovered, by rendering the text and
 * counting the frames that came back. Draft, synthesize, measure, and if it
 * overran, ask for a shorter one and measure again.
 *
 * Nothing in the loop knows whether the drafter is a recorded fixture or a live
 * model. Swapping one for the other changes which [Drafting] instance is passed
 * in and nothing else -- which is the point, because the live drafter is the part
 * that costs money and the loop is the part that has to be right.
 *
 * ## What it guarantees about its output
 *
 * Every emitted variant has been rendered and counted, so no cue in the output
 * has a length that is a guess. Every emitted cue starts inside a window that is
 * in the track's own gap map, and is short enough that the runtime's admission
 * rule will let it speak. Every window that produced no cue is in
 * [BuildResult.drops] with a reason.
 */
class DescriptionTrackBuilder(
    private val transcription: Transcription,
    private val drafting: Drafting,
    private val synthesis: Synthesis,
    private val config: PipelineConfig = PipelineConfig(),
) {

    fun build(request: BuildRequest): BuildResult {
        val transcript = transcription.transcribe(request.media)
        val gaps = GapMapper.derive(transcript, request.mediaDurationMs, config.gapMap)

        val cues = mutableListOf<Cue>()
        val drops = mutableListOf<AuthoringDrop>()
        val fits = mutableListOf<FitRecord>()

        for (gap in gaps) {
            val cueId = CueId("ad-${gap.startMs}")
            val startMs = gap.startMs + config.cueLeadInMs
            val overheadMs = config.cueLeadInMs + config.guardMs
            val roomMs = gap.durationMs - overheadMs
            if (roomMs <= 0) {
                drops += AuthoringDrop(cueId, gap, AuthoringDropReason.NoRoomAfterGuard(gap.durationMs, overheadMs))
                continue
            }

            val outcome = fit(sceneAt(gap, transcript, request.media), roomMs)
            fits += FitRecord(
                cueId = cueId,
                gap = gap,
                roomMs = roomMs,
                draftsRequested = outcome.draftsRequested,
                draftsOverrun = outcome.draftsOverrun,
                variantsAuthored = outcome.variants.size,
            )

            if (outcome.variants.isEmpty()) {
                drops += AuthoringDrop(cueId, gap, outcome.dropReason(roomMs))
            } else {
                cues += Cue(
                    id = cueId,
                    startMs = startMs,
                    kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                    // Longest first is the order the runtime's variant walk depends on.
                    // The loop tends to produce it anyway; sorting makes it true rather
                    // than likely, since a drafter is free to come back longer.
                    variants = outcome.variants.sortedByDescending { it.measuredDuration },
                )
            }
        }

        val track = DescriptionTrack(
            formatVersion = DescriptionTrack.CURRENT_FORMAT_VERSION,
            sourceId = request.sourceId,
            locale = transcript.locale,
            gapMap = gaps,
            cues = cues,
            synthetic = true,
        )

        assertRuntimeWillAdmit(track)
        return BuildResult(track, drops, fits)
    }

    /**
     * The contract with the runtime, checked where the track is created rather
     * than discovered at play time. A cue that does not start inside a window of
     * the track's own gap map, or whose longest variant would cross the dialogue
     * that closes it, is one the director will refuse on sight -- and a silent
     * description that reaches the device is far more expensive to diagnose than
     * a failed build.
     */
    private fun assertRuntimeWillAdmit(track: DescriptionTrack) {
        for (cue in track.cues) {
            val windowEndMs = checkNotNull(track.silence.windowEndAt(cue.startMs)) {
                "cue ${cue.id} starts at ${cue.startMs}, which is not inside any window of the gap map"
            }
            val longestMs = cue.variants.first().measuredDuration.millis
            check(cue.startMs + longestMs + config.guardMs <= windowEndMs) {
                "cue ${cue.id} runs ${longestMs}ms from ${cue.startMs} and would cross dialogue at $windowEndMs"
            }
        }
    }

    /**
     * Converge on a set of length options for one window.
     *
     * The ask shrinks on every turn, but for two different reasons that deserve to
     * stay distinguishable: after an overrun it drops back below the room, and
     * after a fit it drops below what just fitted, so the next variant is
     * genuinely a shorter option rather than a paraphrase of the same length.
     */
    private fun fit(scene: SceneContext, roomMs: Long): FitOutcome {
        val variants = mutableListOf<Variant>()
        val seenText = mutableSetOf<String>()
        var draftsRequested = 0
        var draftsOverrun = 0
        var lastMeasuredMs: Long? = null
        var shortestMeasuredMs: Long? = null
        var previousText: String? = null

        while (variants.size < config.variantsPerCue && draftsRequested < config.maxDraftAttempts) {
            val request = DraftRequest(
                scene = scene,
                targetDurationMs = nextTargetMs(roomMs, lastMeasuredMs),
                attempt = draftsRequested,
                previousText = previousText,
            )
            val draft = drafting.draft(request) ?: break
            draftsRequested++
            previousText = draft.text

            val audio = synthesis.synthesize(draft.text, config.voice)
            val measured = audio.duration
            lastMeasuredMs = measured.millis
            shortestMeasuredMs = minOf(shortestMeasuredMs ?: measured.millis, measured.millis)

            if (measured.millis > roomMs) {
                draftsOverrun++
                continue
            }
            if (measured.millis > 0 && seenText.add(draft.text)) {
                // The audio has already been rendered to measure it. If the
                // synthesizer kept it, the variant points at it, and the device
                // plays back Polly rather than falling back to on-device speech.
                variants += Variant(text = draft.text, measuredDuration = measured, audioRef = audio.audioRef)
            }
        }

        return FitOutcome(variants, draftsRequested, draftsOverrun, shortestMeasuredMs)
    }

    private fun nextTargetMs(roomMs: Long, lastMeasuredMs: Long?): Long = when {
        lastMeasuredMs == null -> roomMs
        lastMeasuredMs > roomMs -> (roomMs * config.overrunBackoff).toLong()
        else -> (lastMeasuredMs * config.variantShrinkFactor).toLong()
    }.coerceAtLeast(1L)

    private fun sceneAt(gap: Gap, transcript: Transcript, media: MediaRef): SceneContext = SceneContext(
        media = media,
        gap = gap,
        precedingDialogue = transcript.words
            .filter { it.endMs <= gap.startMs }
            .takeLast(config.contextWords)
            .joinToString(" ") { it.text },
        followingDialogue = transcript.words
            .filter { it.startMs >= gap.endMs }
            .take(config.contextWords)
            .joinToString(" ") { it.text },
    )

    private class FitOutcome(
        val variants: List<Variant>,
        val draftsRequested: Int,
        val draftsOverrun: Int,
        val shortestMeasuredMs: Long?,
    ) {
        fun dropReason(roomMs: Long): AuthoringDropReason = when (shortestMeasuredMs) {
            null -> AuthoringDropReason.NoDraftOffered
            else -> AuthoringDropReason.NoVariantFits(roomMs, shortestMeasuredMs, draftsRequested)
        }
    }
}
