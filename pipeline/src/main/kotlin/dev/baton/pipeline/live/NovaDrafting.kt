package dev.baton.pipeline.live

import dev.baton.pipeline.BilledTokens
import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.Drafting
import dev.baton.pipeline.DraftingPrompt
import dev.baton.pipeline.ReportsBilledTokens
import dev.baton.pipeline.SpendConfig
import dev.baton.pipeline.mock.SpeakingRate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.StopReason
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.VideoBlock
import software.amazon.awssdk.services.bedrockruntime.model.VideoFormat
import software.amazon.awssdk.services.bedrockruntime.model.VideoSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Amazon Bedrock, through `Converse`, drafting with a Nova model. Every call is billed, so it
 * belongs behind a [dev.baton.pipeline.SpendGuard].
 *
 * The whole clip goes inline with every call, as bytes, beside text naming the silence window
 * and the dialogue either side of it. The reply is one JSON object holding one description,
 * or null when the model has nothing (shorter, or new) to offer -- the same null [Drafting]
 * uses to end the fit loop. A refusal, a truncated reply or one that is not exactly that
 * object is also null, logged with why; never a crash, because the call has already been
 * paid for and the builder records the empty slot as a drop.
 *
 * What each call billed is kept in [lastBilledTokens], draft or not, for the guard to settle.
 *
 * [modelId] is a parameter, never a default: which Nova is the S3 bake-off's decision.
 * [maxTokens] defaults to [SpendConfig.novaMaxOutputTokens], so the ceiling the guard prices
 * is the one the service enforces. [client] is a parameter so tests can hand in a fake; by
 * default it is us-east-1 with the `baton` profile's credentials.
 */
class NovaDrafting(
    private val modelId: String,
    media: Path,
    private val client: BedrockRuntimeClient = BedrockRuntimeClient.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(ProfileCredentialsProvider.create("baton"))
        .build(),
    private val maxTokens: Int = SpendConfig().novaMaxOutputTokens,
    private val log: (String) -> Unit = System.err::println,
) : Drafting, ReportsBilledTokens, AutoCloseable {

    private val format: VideoFormat
    private val video: SdkBytes

    init {
        require(modelId.isNotBlank()) { "a model id is required" }
        require(Files.isRegularFile(media)) { "no media file at $media" }
        val extension = media.fileName.toString().substringAfterLast('.', "").lowercase()
        format = VideoFormat.fromValue(extension)
        require(format in VideoFormat.knownValues()) { "Converse takes ${VideoFormat.knownValues()} video, not '.$extension'" }
        // Checked here, before the guard can charge for a call the service would refuse.
        val size = Files.size(media)
        require(base64Length(size) < INLINE_VIDEO_BASE64_LIMIT) {
            "$media is $size bytes, ${base64Length(size)} as base64; inline video must encode to under $INLINE_VIDEO_BASE64_LIMIT"
        }
        video = SdkBytes.fromByteArray(Files.readAllBytes(media))
    }

    override var lastBilledTokens: BilledTokens? = null
        private set

    var calls: Int = 0
        private set
    var inputTokens: Long = 0
        private set
    var outputTokens: Long = 0
        private set

    override fun draft(request: DraftRequest): Draft? {
        lastBilledTokens = null
        val response = refusalsTranslated("Bedrock Converse on $modelId") { client.converse(converseRequest(request)) }
        calls++
        response.usage()?.let { usage ->
            val billed = BilledTokens(usage.inputTokens(), usage.outputTokens())
            lastBilledTokens = billed
            inputTokens += billed.inputTokens
            outputTokens += billed.outputTokens
        }
        return parse(response)
    }

    internal fun converseRequest(request: DraftRequest): ConverseRequest = ConverseRequest.builder()
        .modelId(modelId)
        .system(SystemContentBlock.fromText(NovaPrompt.system))
        .messages(
            Message.builder()
                .role(ConversationRole.USER)
                .content(
                    ContentBlock.fromVideo(VideoBlock.builder().format(format).source(VideoSource.fromBytes(video)).build()),
                    ContentBlock.fromText(NovaPrompt.user(request)),
                )
                .build(),
        )
        .inferenceConfig(InferenceConfiguration.builder().maxTokens(maxTokens).build())
        .build()

    private fun parse(response: ConverseResponse): Draft? {
        if (response.stopReason() != StopReason.END_TURN) {
            log("Nova: no draft, the reply stopped with '${response.stopReasonAsString()}'")
            return null
        }
        val text = response.output()?.message()?.content().orEmpty().mapNotNull { it.text() }.joinToString("").trim()
        return NovaPrompt.parseReply(text) { log("Nova: no draft, $it") }
    }

    override fun close() = client.close()

    companion object {
        /**
         * Bedrock Runtime's model, `VideoSource`: "a base64-encoded string as long as the encoded
         * file is less than 25MB" (aws-sdk-java-v2 2.54.19, bedrockruntime service-2.json:5090).
         * Read as 25,000,000 bytes, the smaller reading of "MB".
         */
        const val INLINE_VIDEO_BASE64_LIMIT: Long = 25_000_000

        fun base64Length(bytes: Long): Long = (bytes + 2) / 3 * 4
    }
}

/** Everything Nova is told, and how its reply is read. The one place the prompt text lives. */
object NovaPrompt {

    val system: String = DraftingPrompt.STYLE_RULES + """

You are given a video clip and one silence in its dialogue. Write one audio description to be spoken in that silence.
Reply with exactly one JSON object and nothing else, no code fence: {"description": "<text>"}
If there is nothing new to describe, or nothing shorter to offer when asked, reply {"description": null}"""

    fun user(request: DraftRequest): String = buildString {
        val gap = request.scene.gap
        val words = maxOf(1L, request.targetDurationMs * SpeakingRate.DEFAULT_WORDS_PER_MINUTE / 60_000)
        appendLine("The silence runs from ${seconds(gap.startMs)} s to ${seconds(gap.endMs)} s of the clip.")
        appendLine("Dialogue before it: ${request.scene.precedingDialogue.ifBlank { "(none)" }}")
        appendLine("Dialogue after it: ${request.scene.followingDialogue.ifBlank { "(none)" }}")
        appendLine("The description must take at most ${seconds(request.targetDurationMs)} s to speak: about $words words.")
        val previous = request.previousText
        when {
            previous != null && request.attempt > 0 ->
                appendLine("The last version was too long or needs a shorter alternative: \"$previous\". Write a shorter one.")
            previous != null ->
                appendLine("Already described in this silence: \"$previous\". Describe what happens next, without repeating it.")
        }
    }

    /**
     * Exactly `{"description": "<text>"}` or `{"description": null}`. A surrounding markdown
     * fence is tolerated, since models add one despite being told not to; anything else is
     * reported through [reject] and read as no draft.
     */
    fun parseReply(reply: String, reject: (String) -> Unit): Draft? {
        val body = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (obj == null || obj.keys != setOf("description")) {
            reject("the reply is not {\"description\": ...}: ${reply.take(200)}")
            return null
        }
        return when (val description = obj.getValue("description")) {
            JsonNull -> null
            is JsonPrimitive -> if (description.isString && description.content.isNotBlank()) {
                Draft(description.content.trim())
            } else {
                reject("the description is not text: $description")
                null
            }
            else -> {
                reject("the description is not text: $description")
                null
            }
        }
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1_000.0)
}
