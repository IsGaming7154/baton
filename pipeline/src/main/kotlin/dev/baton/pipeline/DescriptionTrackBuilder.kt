package dev.baton.pipeline

import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.Gap
import dev.baton.director.UtteranceKind
import dev.baton.director.Variant
import kotlin.math.abs

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
 * Turns a video into a [DescriptionTrack]: transcribe it, find the silence, fill
 * each window with as much as it will hold, and prove each thing written fits by
 * measuring the audio of it.
 *
 * ## Filling a window, not labelling one
 *
 * A window used to get one sentence, whatever its length, and a thirty-second
 * silent sequence is the case that shows what that costs: the passage with the
 * most room and the most to say got a single line, and the rest of the silence
 * went out empty. A describer would put three or four observations in there.
 *
 * So the builder walks a cursor through each window instead of naming a point in
 * it. Each cue is placed at the cursor, and the cursor advances past that cue's
 * *longest* variant plus the guard -- the longest, because the runtime is free to
 * choose it, and reserving anything less would author the next cue into silence
 * its neighbour is entitled to. The walk stops when what is left could not hold a
 * description worth writing (see [PipelineConfig.minimumCueRoomMs]) or when the
 * drafter runs out of things to say.
 *
 * Only the first slot of a window can produce an [AuthoringDrop]. Running out of
 * material after three descriptions is a window that got filled, not a window that
 * failed, and reporting it as a drop would put a line in the report for every long
 * gap in the film -- burying the drops that mean something, which is a window that
 * ended up silent. What every slot does produce is a [FitRecord], so the cost of
 * the attempt is still visible even when the attempt came to nothing.
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
 * has a length that is a guess, and no two variants of one cue are lengths the
 * runtime could not choose between. Every emitted cue starts inside a window that
 * is in the track's own gap map, and is short enough that the runtime's admission
 * rule will let it speak -- including the half of that rule that reserves room for
 * the cue behind it. Every window the builder authored into that produced no cue
 * at all is in [BuildResult.drops] with a reason; a window shorter than
 * [PipelineConfig.minimumUsefulGapMs] is not authored into, stays in the gap map
 * because the silence is real, and is explained by that threshold rather than by
 * a drop.
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
            // The map keeps every silence; what is worth writing into is decided here.
            if (gap.durationMs < config.minimumUsefulGapMs) continue
            val scene = sceneAt(gap, transcript, request.media)
            var cursorMs = gap.startMs + config.cueLeadInMs
            var ordinal = 0
            var previousText: String? = null

            while (true) {
                val cueId = cueIdFor(gap, ordinal)
                val roomMs = gap.endMs - config.guardMs - cursorMs
                if (ordinal == 0) {
                    val overheadMs = config.cueLeadInMs + config.guardMs
                    if (roomMs <= 0) {
                        drops += AuthoringDrop(
                            cueId,
                            gap,
                            AuthoringDropReason.NoRoomAfterGuard(gap.durationMs, overheadMs),
                        )
                        break
                    }
                } else if (roomMs < config.minimumCueRoomMs) {
                    break
                }

                val outcome = fit(scene, roomMs, ordinal, previousText)
                fits += FitRecord(
                    cueId = cueId,
                    gap = gap,
                    roomMs = roomMs,
                    draftsRequested = outcome.draftsRequested,
                    draftsOverrun = outcome.draftsOverrun,
                    variantsAuthored = outcome.variants.size,
                )

                if (outcome.variants.isEmpty()) {
                    if (ordinal == 0) drops += AuthoringDrop(cueId, gap, outcome.dropReason(roomMs))
                    break
                }

                // Longest first is the order the runtime's variant walk depends on.
                // The loop tends to produce it anyway; sorting makes it true rather
                // than likely, since a drafter is free to come back longer.
                val variants = outcome.variants.sortedByDescending { it.measuredDuration }
                cues += Cue(
                    id = cueId,
                    startMs = cursorMs,
                    kind = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
                    variants = variants,
                )

                // The *longest* variant, because the runtime is free to choose it.
                // Advancing on anything shorter would reserve room the cue might
                // take anyway, and the next cue would be authored into silence its
                // neighbour is entitled to.
                previousText = variants.first().text
                cursorMs += variants.first().measuredDuration.millis + config.guardMs
                ordinal++
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
     *
     * The room a cue is checked against is [CueRooms]' and not the window's, which
     * is to say it is the runtime's own admission arithmetic evaluated at the
     * cue's start time. Checking against the window instead would pass a track
     * whose descriptions were packed on top of each other -- every one of them
     * inside the silence, every one of them refused at play time by the cue in
     * front of it -- and the packing is exactly what this loop has just decided,
     * so it is exactly what wants checking.
     */
    private fun assertRuntimeWillAdmit(track: DescriptionTrack) {
        for (cue in track.cues) {
            val windowEndMs = checkNotNull(track.silence.windowEndAt(cue.startMs)) {
                "cue ${cue.id} starts at ${cue.startMs}, which is not inside any window of the gap map"
            }
            val roomEndMs = track.rooms.roomEndFor(cue.id) ?: windowEndMs
            val longestMs = cue.variants.first().measuredDuration.millis
            check(cue.startMs + longestMs + config.guardMs <= roomEndMs) {
                val crosses = if (roomEndMs < windowEndMs) {
                    "the cue that follows it in the same window, at $roomEndMs"
                } else {
                    "dialogue at $windowEndMs"
                }
                "cue ${cue.id} runs ${longestMs}ms from ${cue.startMs} and would cross $crosses"
            }
        }
    }

    /**
     * The window's first cue keeps the window's own name, and later ones are
     * suffixed.
     *
     * A uniform `ad-<window>-<ordinal>` would be tidier and it would rename every
     * cue in every track ever built, for a feature that only ever *adds* cues.
     * Cue ids travel: they are what a drop report names, and what the bundled demo
     * track on the device is keyed by. A description that was called `ad-13000`
     * before this change is the same description afterwards, and should still be
     * called that.
     */
    private fun cueIdFor(gap: Gap, ordinal: Int): CueId =
        CueId(if (ordinal == 0) "ad-${gap.startMs}" else "ad-${gap.startMs}-$ordinal")

    /**
     * Converge on a set of length options for one cue.
     *
     * The ask shrinks on every turn, but for two different reasons that deserve to
     * stay distinguishable: after an overrun it drops back below the room, and
     * after a fit it drops below what just fitted, so the next variant is
     * genuinely a shorter option rather than a paraphrase of the same length.
     *
     * [priorText] is the description already placed earlier in this window, when
     * there is one. It seeds the same slot the previous draft occupies, so the
     * first ask of a window's second cue arrives carrying what its first cue said
     * -- which is the one thing a drafter needs in order not to describe the same
     * moment twice.
     */
    private fun fit(scene: SceneContext, roomMs: Long, cueOrdinal: Int, priorText: String?): FitOutcome {
        val variants = mutableListOf<Variant>()
        var draftsRequested = 0
        var draftsOverrun = 0
        var lastMeasuredMs: Long? = null
        var shortestMeasuredMs: Long? = null
        var previousText: String? = priorText

        while (variants.size < config.variantsPerCue && draftsRequested < config.maxDraftAttempts) {
            val request = DraftRequest(
                scene = scene,
                targetDurationMs = nextTargetMs(roomMs, lastMeasuredMs),
                attempt = draftsRequested,
                cueOrdinal = cueOrdinal,
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
            if (measured.millis > 0 && variants.none { it.tooCloseTo(measured.millis) }) {
                // The audio has already been rendered to measure it. If the
                // synthesizer kept it, the variant points at it, and the device
                // plays back Polly rather than falling back to on-device speech.
                variants += Variant(text = draft.text, measuredDuration = measured, audioRef = audio.audioRef)
            }
        }

        return FitOutcome(variants, draftsRequested, draftsOverrun, shortestMeasuredMs)
    }

    /**
     * Whether a draft that measured [measuredMs] would be this variant over again
     * as far as the runtime is concerned. See [PipelineConfig.variantSeparationMs].
     */
    private fun Variant.tooCloseTo(measuredMs: Long): Boolean =
        abs(measuredDuration.millis - measuredMs) < config.variantSeparationMs

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
