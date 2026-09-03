package dev.baton.director

/**
 * A duration in milliseconds that was **measured**, never estimated.
 *
 * Everything the director promises rests on this type. A scheduled utterance is
 * allowed into a gap only because we know how long it actually is; if that
 * number were a guess derived from character count or words-per-minute, the
 * "never speaks over dialogue" guarantee would be a guess too.
 *
 * The primitive constructor is private, so the only ways to obtain one are the
 * two named factories below. Neither of them accepts text. There is deliberately
 * no path from a string to a duration.
 */
@JvmInline
value class MeasuredDurationMs private constructor(val millis: Long) : Comparable<MeasuredDurationMs> {

    override fun compareTo(other: MeasuredDurationMs): Int = millis.compareTo(other.millis)

    override fun toString(): String = "${millis}ms"

    companion object {

        /**
         * Measure rendered audio: count the frames the synthesizer actually
         * returned and divide by the rate they will be played back at. This is
         * the only way a duration comes into existence for the first time.
         */
        fun ofRenderedAudio(frameCount: Long, sampleRateHz: Int): MeasuredDurationMs {
            require(frameCount >= 0) { "frameCount must be >= 0, was $frameCount" }
            require(sampleRateHz > 0) { "sampleRateHz must be > 0, was $sampleRateHz" }
            return MeasuredDurationMs(frameCount * 1_000L / sampleRateHz)
        }

        /**
         * Read back a duration that an earlier pipeline run already measured --
         * i.e. deserialize one out of a stored [DescriptionTrack].
         *
         * This is the round-trip door and nothing else. It exists so that a
         * measurement survives being written to disk; it is not a way to invent
         * a duration that was never measured.
         */
        fun restoredFromTrack(millis: Long): MeasuredDurationMs {
            require(millis >= 0) { "a restored duration must be >= 0, was $millis" }
            return MeasuredDurationMs(millis)
        }
    }
}

/** Stable identifier for a cue, unique within a [DescriptionTrack]. */
@JvmInline
value class CueId(val value: String) {
    init {
        require(value.isNotBlank()) { "cue id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One length option for a cue. The pipeline emits two or three of these per cue
 * so that the runtime can pick one that fits the gap it actually has.
 */
data class Variant(
    val text: String,
    val measuredDuration: MeasuredDurationMs,
    /** Where the pre-rendered audio lives, when there is any. Null means synthesize at play time. */
    val audioRef: String? = null,
) {
    init {
        require(text.isNotBlank()) { "a variant must have text" }
        require(measuredDuration.millis > 0) { "a variant with no audible length is not a variant" }
    }
}

/**
 * One thing to say, at one point on the content timeline, in a choice of lengths.
 *
 * [variants] are ordered longest first. The director walks them in order and
 * takes the first that fits, which is therefore the longest that fits.
 */
data class Cue(
    val id: CueId,
    val startMs: Long,
    val kind: UtteranceKind.Scheduled,
    val variants: List<Variant>,
) {
    init {
        require(startMs >= 0) { "cue $id starts at $startMs; the timeline begins at 0" }
        require(variants.zipWithNext().all { (a, b) -> a.measuredDuration >= b.measuredDuration }) {
            "cue $id: variants must be ordered longest first, was " +
                variants.map { it.measuredDuration.millis }
        }
    }
}

/** A window of silence in the content: no dialogue between [startMs] and [endMs]. */
data class Gap(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0) { "gap starts at $startMs; the timeline begins at 0" }
        require(endMs >= startMs) { "gap ends at $endMs, before it starts at $startMs" }
    }

    val durationMs: Long get() = endMs - startMs
}

/**
 * The gap map, normalized: sorted, with empty windows discarded and overlapping
 * or touching windows merged. Half-open -- a window is `[startMs, endMs)`.
 *
 * Normalizing here is what lets the director express the whole dialogue question
 * as one lookup: "how much silence is left from where I am?"
 */
class SilenceWindows private constructor(val windows: List<Gap>) {

    /**
     * End of the silence window containing [positionMs], or null if [positionMs]
     * is not inside one -- which is to say, dialogue is playing there.
     *
     * This is the `nextDialogueOnsetMs` of the eligibility rule.
     */
    fun windowEndAt(positionMs: Long): Long? {
        var low = 0
        var high = windows.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (windows[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null
        val window = windows[candidate]
        return if (positionMs < window.endMs) window.endMs else null
    }

    /** True when the content is silent at [positionMs]. */
    fun isSilentAt(positionMs: Long): Boolean = windowEndAt(positionMs) != null

    override fun toString(): String = "SilenceWindows$windows"

    companion object {
        fun of(gaps: List<Gap>): SilenceWindows {
            val merged = mutableListOf<Gap>()
            for (gap in gaps.filter { it.durationMs > 0 }.sortedBy { it.startMs }) {
                val last = merged.lastOrNull()
                if (last != null && gap.startMs <= last.endMs) {
                    if (gap.endMs > last.endMs) merged[merged.lastIndex] = last.copy(endMs = gap.endMs)
                } else {
                    merged += gap
                }
            }
            return SilenceWindows(merged)
        }
    }
}

/**
 * The contract between the offline pipeline and the runtime.
 *
 * [synthetic] is load-bearing rather than decorative: the app reads it to decide
 * whether to make the ACB transparency announcement that the speech the listener
 * is about to hear was machine-generated.
 */
data class DescriptionTrack(
    val formatVersion: Int,
    val sourceId: String,
    /** BCP-47 language tag of the spoken content of this track. */
    val locale: String,
    val gapMap: List<Gap>,
    val cues: List<Cue>,
    val synthetic: Boolean = true,
) {
    init {
        require(formatVersion >= 1) { "formatVersion must be >= 1, was $formatVersion" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(locale.isNotBlank()) { "locale must not be blank" }
        require(cues.distinctBy { it.id }.size == cues.size) { "cue ids must be unique within a track" }
    }

    /** The gap map, normalized for lookup. Derived; not part of the serialized form. */
    val silence: SilenceWindows = SilenceWindows.of(gapMap)

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1

        /** A track for content that has no description at all. The director stays silent. */
        fun empty(sourceId: String, locale: String): DescriptionTrack = DescriptionTrack(
            formatVersion = CURRENT_FORMAT_VERSION,
            sourceId = sourceId,
            locale = locale,
            gapMap = emptyList(),
            cues = emptyList(),
        )
    }
}
