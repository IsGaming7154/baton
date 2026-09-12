package dev.baton.pipeline

import dev.baton.director.Gap

/**
 * The threshold that turns a list of word timings into a map of silence. Settable
 * so that the map is driven by a number a reader can argue with rather than by a
 * constant buried in a loop.
 */
data class GapMapConfig(
    /**
     * Silence shorter than this is not a gap at all, it is the space between two
     * words of the same utterance. Amazon Transcribe times each word
     * individually, so an unbridged complement of the word list would report a
     * "gap" between every pair of words in the film.
     *
     * 300 ms is comfortably longer than the pause inside a phrase and shorter
     * than a beat between lines.
     */
    val dialogueBridgeMs: Long = DEFAULT_DIALOGUE_BRIDGE_MS,
) {
    init {
        require(dialogueBridgeMs >= 0) { "dialogueBridgeMs must be >= 0, was $dialogueBridgeMs" }
    }

    companion object {
        const val DEFAULT_DIALOGUE_BRIDGE_MS: Long = 300L
    }
}

/**
 * Derives the baked gap map from word-level timings.
 *
 * What ships in the track is the *truthful* silence map: every window between
 * two runs of dialogue, however short. Pauses inside an utterance are bridged,
 * because they are not silence between lines, but nothing is discarded for being
 * too short to hold a description.
 *
 * It used to be the *schedulable* map, with windows under the minimum useful gap
 * thrown away, and that gave one field two incompatible meanings (issue #5). The
 * runtime asks the map two questions: how much room a cue has, and whether the
 * film is silent at a position. The second wants the truth. A 400 ms beat between
 * two lines is too short to describe anything in and is still not dialogue, and a
 * map that omitted it asserted dialogue there; one that merged it away would
 * assert silence over the lines either side. What is worth scheduling into is
 * policy, and it now lives with the policy: see [PipelineConfig.minimumUsefulGapMs].
 */
object GapMapper {

    fun derive(
        transcript: Transcript,
        mediaDurationMs: Long,
        config: GapMapConfig = GapMapConfig(),
    ): List<Gap> {
        require(mediaDurationMs >= 0) { "mediaDurationMs must be >= 0, was $mediaDurationMs" }

        val silence = mutableListOf<Gap>()
        var cursorMs = 0L
        for (span in dialogueSpans(transcript, mediaDurationMs, config)) {
            if (span.startMs > cursorMs) silence += Gap(cursorMs, span.startMs)
            cursorMs = maxOf(cursorMs, span.endMs)
        }
        if (cursorMs < mediaDurationMs) silence += Gap(cursorMs, mediaDurationMs)

        return silence
    }

    /**
     * The word list collapsed into runs of continuous speech: sorted, clamped to
     * the media, and joined wherever the silence between two words is too short
     * to be anything but breath.
     */
    fun dialogueSpans(
        transcript: Transcript,
        mediaDurationMs: Long,
        config: GapMapConfig = GapMapConfig(),
    ): List<Gap> {
        val spans = mutableListOf<Gap>()
        val clamped = transcript.words
            .map { Gap(minOf(it.startMs, mediaDurationMs), minOf(it.endMs, mediaDurationMs)) }
            .filter { it.durationMs > 0 }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

        for (word in clamped) {
            val last = spans.lastOrNull()
            if (last != null && word.startMs - last.endMs <= config.dialogueBridgeMs) {
                if (word.endMs > last.endMs) spans[spans.lastIndex] = last.copy(endMs = word.endMs)
            } else {
                spans += word
            }
        }
        return spans
    }
}
