package dev.baton.pipeline.live

import dev.baton.pipeline.BilledTokens
import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.Drafting
import dev.baton.pipeline.ReportsBilledTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** One HTTP POST: the status code and the body that came back. A seam, so tests never reach the network. */
fun interface GeminiTransport {
    fun post(url: String, body: String): Pair<Int, String>
}

/**
 * The Gemini API's `generateContent`, drafting with the same prompt and reply contract as
 * [NovaDrafting] ([NovaPrompt]), so the two adapters are interchangeable behind [Drafting].
 * Added on 2026-10-01 after AWS declined Bedrock access for this account (friction-log entry 21).
 *
 * Meant for the free tier, which bills nothing unless billing is set up for the key's project
 * (ai.google.dev/gemini-api/docs/rate-limits). Free-tier content is used by Google to improve
 * its products (ai.google.dev/gemini-api/docs/pricing), so only public media belongs here.
 * Because nothing is billed, this is not behind the [dev.baton.pipeline.SpendGuard]; [maxCalls]
 * is the brake on a runaway loop instead, and the reported tokens are informational.
 *
 * The clip goes inline as base64: the video guide allows inline data under 20 MB of total
 * request. A 429 or 503 is waited out and retried [retries] times; any other failure throws,
 * with the API's own message and never the key.
 */
class GeminiDrafting(
    private val modelId: String,
    media: Path,
    private val transport: GeminiTransport = HttpTransport(apiKey()),
    private val maxOutputTokens: Int = 4_096,
    private val maxCalls: Int = 60,
    private val retries: Int = 3,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val log: (String) -> Unit = System.err::println,
) : Drafting, ReportsBilledTokens {

    private val mimeType: String
    private val video: String

    init {
        require(modelId.isNotBlank()) { "a model id is required" }
        require(Files.isRegularFile(media)) { "no media file at $media" }
        val extension = media.fileName.toString().substringAfterLast('.', "").lowercase()
        mimeType = requireNotNull(VIDEO_TYPES[extension]) { "Gemini inline video takes ${VIDEO_TYPES.keys}, not '.$extension'" }
        val size = Files.size(media)
        require(NovaDrafting.base64Length(size) < INLINE_BASE64_LIMIT) {
            "$media is $size bytes, ${NovaDrafting.base64Length(size)} as base64; inline video must encode to under $INLINE_BASE64_LIMIT"
        }
        video = Base64.getEncoder().encodeToString(Files.readAllBytes(media))
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
        check(calls < maxCalls) { "Gemini: $maxCalls calls already made this run; stopping before a runaway loop" }
        lastBilledTokens = null
        val reply = send(requestBody(request))
        calls++
        reply["usageMetadata"]?.jsonObject?.let { usage ->
            val tokens = BilledTokens(usage.int("promptTokenCount"), usage.int("candidatesTokenCount") + usage.int("thoughtsTokenCount"))
            lastBilledTokens = tokens
            inputTokens += tokens.inputTokens
            outputTokens += tokens.outputTokens
        }
        return parse(reply)
    }

    /**
     * Only the cue's own slot, plus [LEAD_IN_MS] before it so the model sees what led into it, is
     * sent as video. Shown all 88 s, flash-lite had to guess where in the clip a silence fell and
     * described events from elsewhere in the film, some that never happen; shown a whole 32 s
     * silence for each of its nine cues, it still wrote a dragon stabbed and killed that is alive
     * on screen (2026-10-01, 2026-10-04).
     */
    internal fun requestBody(request: DraftRequest): String {
        val slot = request.slot
        return requestBody(request, (slot.startMs - LEAD_IN_MS).coerceAtLeast(0), slot.endMs)
    }

    private fun segmentNote(request: DraftRequest, from: Long, to: Long): String =
        "This description is spoken from ${seconds(request.slot.startMs)} s to at most ${seconds(request.slot.endMs)} s. " +
            "The video you are given is only ${seconds(from)} s to ${seconds(to)} s of the clip. " +
            "Describe only what is visible in it at that moment; never guess at anything outside it.\n"

    private fun seconds(ms: Long): String = "%.1f".format(java.util.Locale.ROOT, ms / 1000.0)

    private fun requestBody(request: DraftRequest, from: Long, to: Long): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", NovaPrompt.system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                putJsonObject("inlineData") { put("mimeType", mimeType); put("data", video) }
                                putJsonObject("videoMetadata") { put("startOffset", "${seconds(from)}s"); put("endOffset", "${seconds(to)}s") }
                            },
                        )
                        add(buildJsonObject { put("text", NovaPrompt.user(request) + segmentNote(request, from, to)) })
                    }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", maxOutputTokens)
            put("responseMimeType", "application/json")
        }
    }.toString()

    private fun send(body: String): JsonObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        var attempt = 0
        while (true) {
            val (status, text) = transport.post(url, body)
            if (status == 200) return Json.parseToJsonElement(text).jsonObject
            if ((status == 429 || status == 503) && attempt < retries) {
                attempt++
                val waitMs = 20_000L * attempt
                log("Gemini: HTTP $status, waiting ${waitMs / 1000} s before retry $attempt of $retries")
                sleep(waitMs)
                continue
            }
            throw IllegalStateException("Gemini generateContent on $modelId failed with HTTP $status: ${apiMessage(text)}")
        }
    }

    private fun parse(reply: JsonObject): Draft? {
        val candidate = (reply["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        if (candidate == null) {
            log("Gemini: no draft, no candidate came back (${reply["promptFeedback"] ?: "no feedback"})")
            return null
        }
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        if (finish != "STOP") {
            log("Gemini: no draft, the reply stopped with '$finish'")
            return null
        }
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("").trim()
        return NovaPrompt.parseReply(text) { log("Gemini: no draft, $it") }
    }

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun apiMessage(body: String): String = try {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(300)
    } catch (e: Exception) {
        body.take(300)
    }

    /** The real transport: the key goes in the `x-goog-api-key` header, never in the URL or a log line. */
    class HttpTransport(private val apiKey: String?) : GeminiTransport {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        override fun post(url: String, body: String): Pair<Int, String> {
            require(!apiKey.isNullOrBlank()) { "no Gemini API key: set GEMINI_API_KEY, or add a GEMINI_API_KEY= line to $KEY_FILE" }
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to response.body()
        }
    }

    companion object {
        /** "under 20MB total request size" (ai.google.dev/gemini-api/docs/video-understanding); 1 MB left for the prompt. */
        const val INLINE_BASE64_LIMIT: Long = 19_000_000

        /** Seconds of film sent before the silence, so a description can follow on from what led into it. */
        const val LEAD_IN_MS: Long = 2_000

        private val VIDEO_TYPES = mapOf("mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm")

        /** Beside the spend ledger, outside the repository, so the key is never committed. */
        val KEY_FILE: Path = Path.of(System.getProperty("user.home"), ".baton", ".env")

        fun apiKey(): String? =
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() } ?: keyFromEnvFile(KEY_FILE)

        /** The value of a `GEMINI_API_KEY=` line, any case, spaces and quotes trimmed. */
        fun keyFromEnvFile(file: Path): String? {
            if (!Files.isRegularFile(file)) return null
            return Files.readAllLines(file).asSequence()
                .map { it.removePrefix("﻿").split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0].trim().equals("GEMINI_API_KEY", ignoreCase = true) }
                ?.get(1)?.trim()?.trim('"', '\'')?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
