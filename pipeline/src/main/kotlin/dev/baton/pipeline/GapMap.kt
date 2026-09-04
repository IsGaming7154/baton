package dev.baton.pipeline

import dev.baton.director.DirectorConfig
import dev.baton.director.Gap

/**
 * The two thresholds that turn a list of word timings into a map of describable
 * silence. Both are settable so that the map is driven by numbers a reader can
 * argue with rather than by constants buried in a loop.
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

    /**
     * The shortest window worth shipping. A cue cannot fit in 200 ms, and a map
     * full of windows nothing can be placed in costs the runtime memory it does
     * not have on a 1 GB device.
     *
     * Derivation: the shortest description worth writing is about three words; at
     * the 165 wpm the synthesizer speaks at that is roughly 1.1 s. The runtime
     * additionally refuses to start a cue unless [DirectorConfig.guardMs] of
     * clear air remains after it, and the pipeline holds a lead-in at the start
     * of the window so the duck ramp completes before the voice arrives. 1.5 s
     * is the smallest window in which all three still leave room to speak.
     */
    val minimumUsefulGapMs: Long = DEFAULT_MINIMUM_USEFUL_GAP_MS,
) {
    init {
        require(dialogueBridgeMs >= 0) { "dialogueBridgeMs must be >= 0, was $dialogueBridgeMs" }
        require(minimumUsefulGapMs > 0) { "minimumUsefulGapMs must be > 0, was $minimumUsefulGapMs" }
    }

    companion object {
        const val DEFAULT_DIALOGUE_BRIDGE_MS: Long = 300L
        const val DEFAULT_MINIMUM_USEFUL_GAP_MS: Long = 1_500L
    }
}

/**
 * Derives the baked gap map from word-level timings.
 *
 * What ships in the track is the *schedulable* silence map, not a raw voice
 * activity trace: windows too short to hold anything have already been discarded,
 * and pauses inside an utterance have already been bridged. That is the map the
 * runtime wants, because the only question it ever asks of it is "how much room
 * is left in the window this cue was written for".
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

        return silence.filter { it.durationMs >= config.minimumUsefulGapMs }
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
