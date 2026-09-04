package dev.baton.pipeline.mock

import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.Transcript
import dev.baton.pipeline.Transcription
import dev.baton.pipeline.Word
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * A recorded Amazon Transcribe transcription result, replayed from disk.
 *
 * The fixture is stored in Transcribe's own response shape rather than a
 * convenient one of our own. It costs nothing here and it means [parse] is the
 * response parser the live slice needs: when the job runs for real, the only
 * thing that changes is that the JSON arrives from S3 instead of from a resource.
 * A fixture in a private format would have to be thrown away at that point, and
 * would have proved nothing about our ability to read what Transcribe emits.
 */
class RecordedTranscription(
    private val responseJson: String,
    /** Used when the recorded job did not run language identification. */
    private val fallbackLocale: String = "en-US",
) : Transcription {

    override fun transcribe(media: MediaRef): Transcript = parse(responseJson, fallbackLocale)

    companion object {
        fun fromResource(path: String, fallbackLocale: String = "en-US"): RecordedTranscription =
            RecordedTranscription(readResource(path), fallbackLocale)

        /**
         * Read word timings out of a Transcribe result.
         *
         * Only `pronunciation` items carry timings; `punctuation` items have none,
         * because punctuation is not spoken and occupies no time on the timeline.
         * Transcribe reports times as seconds in a string, to three decimals.
         */
        fun parse(responseJson: String, fallbackLocale: String = "en-US"): Transcript {
            val response = json.decodeFromString(TranscribeResult.serializer(), responseJson)
            val words = response.results.items
                .filter { it.type == PRONUNCIATION }
                .map { item ->
                    val content = item.alternatives.firstOrNull()?.content
                        ?: error("a pronunciation item in the transcript has no alternatives")
                    Word(
                        text = content,
                        startMs = requireNotNull(item.startTime) { "word '$content' has no start_time" }.toMillis(),
                        endMs = requireNotNull(item.endTime) { "word '$content' has no end_time" }.toMillis(),
                    )
                }
            return Transcript(locale = response.languageCode ?: fallbackLocale, words = words)
        }

        private const val PRONUNCIATION = "pronunciation"

        // Transcribe results carry job metadata and per-word confidences we have no
        // use for, so unknown keys are skipped here -- unlike the track format,
        // where an unknown key means the two halves of our own system disagree.
        private val json = Json { ignoreUnknownKeys = true }

        private fun String.toMillis(): Long = (toDouble() * 1_000).roundToLong()
    }
}

@Serializable
private data class TranscribeResult(
    @SerialName("language_code") val languageCode: String? = null,
    val results: TranscribeResults,
)

@Serializable
private data class TranscribeResults(val items: List<TranscribeItem>)

@Serializable
private data class TranscribeItem(
    val type: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val alternatives: List<TranscribeAlternative> = emptyList(),
)

@Serializable
private data class TranscribeAlternative(val content: String)

internal fun readResource(path: String): String =
    RecordedTranscription::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
        ?: error("fixture resource not found on the classpath: $path")
