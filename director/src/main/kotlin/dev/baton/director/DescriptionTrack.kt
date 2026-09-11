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
 *
 * That walk is the only thing this type enforces about the variants, and it is
 * deliberately not the only thing that is true of them. The pipeline additionally
 * refuses to author two variants whose lengths sit closer together than the
 * runtime could act on -- see `PipelineConfig.variantSeparationMs` -- but that is
 * an authoring rule and not a structural one. Making it a `require` here would
 * mean this runtime rejects a track built by an older pipeline, on an editorial
 * complaint, at the moment a viewer pressed play; a variant that is merely
 * redundant costs nothing at play time, because the walk simply never reaches it.
 */
data class Cue(
    val id: CueId,
    val startMs: Long,
    val kind: UtteranceKind.Scheduled,
    val variants: List<Variant>,
) {
    init {
        require(startMs >= 0) { "cue $id starts at $startMs; the timeline begins at 0" }
        // Refused at load rather than at play time: a cue of this kind would reach
        // the director carrying a cue id, which a disclosure utterance cannot have,
        // and fail mid-film instead of here.
        require(kind != UtteranceKind.Scheduled.SYNTHETIC_SPEECH_DISCLOSURE) {
            "cue $id: the synthetic-speech disclosure is owed once per session and scheduled " +
                "by the director; a track cannot place it at a moment"
        }
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
 * Where each cue's room stops, which is not the same question as where its silence
 * stops.
 *
 * While a window held one description the two questions had one answer, and
 * [SilenceWindows] gave it. They come apart the moment a window can hold several.
 * A thirty-second gap with four cues written into it, arbitrated against the end
 * of the window, is a gap with *one* description in it: the first cue takes the
 * longest variant that fits the whole remaining silence, and the three behind it
 * find nothing left. Nothing downstream can see that this went wrong, either --
 * the utterance ended inside the silence, so every rule was kept. The quantity was
 * simply the wrong one. What is left of the window is not what a cue may have;
 * what is left *before the next cue* is.
 *
 * Derived once, when the track is constructed, rather than looked for on each
 * tick. The arbitration already walks the cue list once per tick, and hunting for
 * the next cue in the same window inside that walk would make it quadratic on a
 * device with 1 GB of memory to spend. The stronger reason is that the answer
 * cannot change while the track is loaded: it is a property of the track, and it
 * belongs beside [DescriptionTrack.silence], which is there for the same reason.
 *
 * **Cues sharing a start time do not reserve room from each other.** They are two
 * voices contesting one moment, not two moments in one window, and which of them
 * speaks is a question the arbitration answers at play time against a floor that
 * may or may not be free -- the loser then takes what is left of the window, which
 * is the behaviour a spoken subtitle and a description sharing a beat have always
 * had. Reserving between them would starve both for a conflict that resolves
 * itself.
 */
class CueRooms private constructor(private val roomEndByCueId: Map<CueId, Long>) {

    /**
     * The content position past which [cueId]'s room belongs to something else --
     * the next cue written into the same silence window, or, when it is the last
     * one there, the dialogue that closes the window.
     *
     * Null when the cue does not start inside a window at all. That is the same
     * condition [SilenceWindows.windowEndAt] answers null to, and it stays a
     * separate refusal rather than being folded in here: a cue written over
     * dialogue is a fault in the track, and the drop it earns says so.
     */
    fun roomEndFor(cueId: CueId): Long? = roomEndByCueId[cueId]

    companion object {
        fun of(cues: List<Cue>, silence: SilenceWindows): CueRooms {
            // Normalized windows are disjoint, so a window's end identifies it --
            // which is what lets the cues be grouped by a single lookup each
            // rather than by searching the window list per pair.
            val byWindowEnd = mutableMapOf<Long, MutableList<Cue>>()
            for (cue in cues) {
                val windowEndMs = silence.windowEndAt(cue.startMs) ?: continue
                byWindowEnd.getOrPut(windowEndMs) { mutableListOf() } += cue
            }

            val roomEnds = mutableMapOf<CueId, Long>()
            for ((windowEndMs, inWindow) in byWindowEnd) {
                val startsMs = inWindow.map { it.startMs }.distinct().sorted()
                for (cue in inWindow) {
                    roomEnds[cue.id] = startsMs.firstOrNull { it > cue.startMs } ?: windowEndMs
                }
            }
            return CueRooms(roomEnds)
        }
    }
}

/**
 * The contract between the offline pipeline and the runtime.
 *
 * [synthetic] is load-bearing rather than decorative: the director reads it to
 * decide whether the session owes the ACB transparency announcement -- one spoken
 * sentence telling the listener that the speech they are hearing is
 * machine-generated. See [AudioDirector].
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

    /** Where each cue's room stops. Derived; not part of the serialized form. */
    val rooms: CueRooms = CueRooms.of(cues, silence)

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
