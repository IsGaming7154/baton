package dev.baton.director

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The only door a stored duration may come back through.
 *
 * [MeasuredDurationMs] has a private constructor precisely so that no code path
 * can invent one from text. Deserialization is the one legitimate way a duration
 * enters the process without being measured here and now, and
 * [MeasuredDurationMs.restoredFromTrack] is the factory that exists for it. This
 * serializer is the single place that calls it, so the exception stays visible.
 */
object MeasuredDurationMsSerializer : KSerializer<MeasuredDurationMs> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.baton.director.MeasuredDurationMs", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: MeasuredDurationMs) {
        encoder.encodeLong(value.millis)
    }

    override fun deserialize(decoder: Decoder): MeasuredDurationMs =
        MeasuredDurationMs.restoredFromTrack(decoder.decodeLong())
}

/**
 * Reads and writes the [DescriptionTrack] wire format.
 *
 * The runtime reads a track; the offline pipeline writes one. Neither may depend
 * on the other, so the codec lives here with the model it encodes -- that is what
 * makes the format a contract rather than a convention.
 *
 * The wire form is a set of surrogate types rather than annotations on the domain
 * model, for two reasons. The domain types carry invariants in `init` blocks and
 * derived state ([DescriptionTrack.silence]) that must not appear on the wire, and
 * keeping the annotations off them means the model stays readable as a model.
 * Decoding therefore runs every domain constructor, so a malformed track fails
 * here rather than three layers into playback.
 */
object DescriptionTrackJson {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Strict on purpose. An unrecognised key means the writer and the reader
        // disagree about the format, and formatVersion is how that is meant to be
        // negotiated -- not by quietly discarding fields we do not understand.
        ignoreUnknownKeys = false
    }

    fun encode(track: DescriptionTrack): String = json.encodeToString(TrackSurrogate.serializer(), track.toSurrogate())

    fun decode(text: String): DescriptionTrack = json.decodeFromString(TrackSurrogate.serializer(), text).toTrack()
}

@Serializable
private data class TrackSurrogate(
    val formatVersion: Int,
    val sourceId: String,
    val locale: String,
    val synthetic: Boolean,
    val gapMap: List<GapSurrogate>,
    val cues: List<CueSurrogate>,
)

@Serializable
private data class GapSurrogate(val startMs: Long, val endMs: Long)

@Serializable
private data class CueSurrogate(
    val id: String,
    val startMs: Long,
    val kind: String,
    val variants: List<VariantSurrogate>,
)

@Serializable
private data class VariantSurrogate(
    val text: String,
    @Serializable(with = MeasuredDurationMsSerializer::class)
    val measuredDurationMs: MeasuredDurationMs,
    val audioRef: String? = null,
)

private fun DescriptionTrack.toSurrogate() = TrackSurrogate(
    formatVersion = formatVersion,
    sourceId = sourceId,
    locale = locale,
    synthetic = synthetic,
    gapMap = gapMap.map { GapSurrogate(it.startMs, it.endMs) },
    cues = cues.map { cue ->
        CueSurrogate(
            id = cue.id.value,
            startMs = cue.startMs,
            kind = cue.kind.name,
            variants = cue.variants.map { VariantSurrogate(it.text, it.measuredDuration, it.audioRef) },
        )
    },
)

private fun TrackSurrogate.toTrack(): DescriptionTrack {
    require(formatVersion <= DescriptionTrack.CURRENT_FORMAT_VERSION) {
        "track declares format version $formatVersion; this build understands up to " +
            "${DescriptionTrack.CURRENT_FORMAT_VERSION}"
    }
    return DescriptionTrack(
        formatVersion = formatVersion,
        sourceId = sourceId,
        locale = locale,
        gapMap = gapMap.map { Gap(it.startMs, it.endMs) },
        cues = cues.map { cue ->
            Cue(
                id = CueId(cue.id),
                startMs = cue.startMs,
                kind = scheduledKind(cue.id, cue.kind),
                variants = cue.variants.map { Variant(it.text, it.measuredDurationMs, it.audioRef) },
            )
        },
        synthetic = synthetic,
    )
}

private fun scheduledKind(cueId: String, name: String): UtteranceKind.Scheduled =
    UtteranceKind.Scheduled.entries.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException(
            "cue $cueId has kind '$name'; a scheduled cue must be one of " +
                UtteranceKind.Scheduled.entries.map { it.name },
        )
