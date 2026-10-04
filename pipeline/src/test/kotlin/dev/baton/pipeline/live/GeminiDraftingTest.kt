package dev.baton.pipeline.live

import dev.baton.director.Gap
import dev.baton.pipeline.BilledTokens
import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.DraftingPrompt
import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.SceneContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** The Gemini adapter, against a fake transport. Nothing here reaches the network. */
class GeminiDraftingTest : FunSpec({

    val ask = DraftRequest(
        SceneContext(MediaRef("clip.mp4"), Gap(12_000, 16_000), "Where is the dragon?", "Over the ridge."),
        targetDurationMs = 3_000,
        attempt = 0,
    )

    test("the request names the model in the URL, sends the style rules as system, the clip inline as mp4, and asks for JSON") {
        val transport = FakeTransport(ok("""{"description": "A woman kneels."}"""))

        GeminiDrafting("gemini-test", clip(byteArrayOf(1, 2, 3)), transport, maxOutputTokens = 512).draft(ask)

        val (url, body) = transport.posts.single()
        url shouldBe "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"
        val sent = Json.parseToJsonElement(body).jsonObject
        sent["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content shouldContain
            DraftingPrompt.STYLE_RULES
        val parts = sent["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        val video = parts[0].jsonObject["inlineData"]!!.jsonObject
        video["mimeType"]!!.jsonPrimitive.content shouldBe "video/mp4"
        video["data"]!!.jsonPrimitive.content shouldBe "AQID"
        val segment = parts[0].jsonObject["videoMetadata"]!!.jsonObject
        segment["startOffset"]!!.jsonPrimitive.content shouldBe "10.0s"
        segment["endOffset"]!!.jsonPrimitive.content shouldBe "16.0s"
        val text = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        text shouldContain "12.0 s to 16.0 s"
        text shouldContain "only 10.0 s to 16.0 s"

        GeminiDrafting("gemini-test", clip(), transport).draft(ask.copy(slot = Gap(13_500, 16_000)))
        val later = Json.parseToJsonElement(transport.posts.last().second).jsonObject["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        later[0].jsonObject["videoMetadata"]!!.jsonObject["startOffset"]!!.jsonPrimitive.content shouldBe "11.5s"
        later[1].jsonObject["text"]!!.jsonPrimitive.content shouldContain "spoken from 13.5 s to at most 16.0 s"
        text shouldContain "at most 3.0 s"
        val config = sent["generationConfig"]!!.jsonObject
        config["maxOutputTokens"]!!.jsonPrimitive.content shouldBe "512"
        config["responseMimeType"]!!.jsonPrimitive.content shouldBe "application/json"
    }

    test("a well-formed reply is a draft, and its tokens, thinking included, are reported and totalled") {
        val gemini = GeminiDrafting("m", clip(), FakeTransport(ok("""{"description": " A woman kneels. "}""", 9_000, 20, 30)))

        gemini.draft(ask) shouldBe Draft("A woman kneels.")
        gemini.lastBilledTokens shouldBe BilledTokens(9_000, 50)
        gemini.draft(ask)
        listOf(gemini.calls, gemini.inputTokens, gemini.outputTokens) shouldBe listOf(2, 18_000L, 100L)
    }

    test("a decline, a malformed reply, a cut-off reply and a blocked prompt are no draft, and logged") {
        val cases = listOf(
            ok("""{"description": null}"""),
            ok("A woman kneels."),
            ok("""{"description": "A wo""", finish = "MAX_TOKENS"),
            ok("", finish = "SAFETY"),
            200 to """{"promptFeedback": {"blockReason": "OTHER"}}""",
        )
        val logs = mutableListOf<String>()
        for (response in cases) {
            GeminiDrafting("m", clip(), FakeTransport(response), log = { logs += it }).draft(ask) shouldBe null
        }
        logs.size shouldBe cases.size - 1
        logs[1] shouldContain "MAX_TOKENS"
        logs.last() shouldContain "blockReason"
    }

    test("a 429 is waited out and retried; a 400 throws with the API's message") {
        val waits = mutableListOf<Long>()
        val transport = FakeTransport(429 to "{}", 429 to "{}", ok("""{"description": "Snow falls."}"""))
        GeminiDrafting("m", clip(), transport, sleep = { waits += it }, log = {}).draft(ask) shouldBe Draft("Snow falls.")
        waits shouldBe listOf(20_000L, 40_000L)

        val bad = FakeTransport(400 to """{"error": {"message": "API key not valid"}}""")
        shouldThrow<IllegalStateException> { GeminiDrafting("m", clip(), bad).draft(ask) }.message shouldContain "API key not valid"

        val busy = FakeTransport(503 to "{}", 503 to "{}")
        shouldThrow<IllegalStateException> { GeminiDrafting("m", clip(), busy, retries = 1, sleep = {}, log = {}).draft(ask) }
    }

    test("the call cap stops a runaway loop before the next call") {
        val gemini = GeminiDrafting("m", clip(), FakeTransport(ok("""{"description": null}""")), maxCalls = 2, log = {})
        gemini.draft(ask)
        gemini.draft(ask)
        shouldThrow<IllegalStateException> { gemini.draft(ask) }.message shouldContain "2 calls"
    }

    test("a clip past the inline limit, or not video, is refused before any call; a missing key is named, not leaked") {
        val big = clip()
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(14_250_000) }
        shouldThrow<IllegalArgumentException> { GeminiDrafting("m", big, FakeTransport()) }.message shouldContain "inline video"
        val text = Files.createTempFile("clip", ".txt").also { Files.write(it, byteArrayOf(1)) }
        shouldThrow<IllegalArgumentException> { GeminiDrafting("m", text, FakeTransport()) }

        val missing = shouldThrow<IllegalArgumentException> { GeminiDrafting.HttpTransport(null).post("https://x.test", "{}") }
        missing.message shouldContain "GEMINI_API_KEY"
        missing.message shouldContain ".env"
        missing.message shouldNotContain "null"
    }

    test("the key is read from a GEMINI_API_KEY line in any case, with spaces and quotes trimmed") {
        val env = Files.createTempFile("baton", ".env")
        Files.writeString(env, "OTHER=1\nGemini_Api_Key = \"k-1.2_3\"\n")
        GeminiDrafting.keyFromEnvFile(env) shouldBe "k-1.2_3"
        Files.writeString(env, "OTHER=1\n")
        GeminiDrafting.keyFromEnvFile(env) shouldBe null
        GeminiDrafting.keyFromEnvFile(env.resolveSibling("absent.env")) shouldBe null
    }
})

private fun clip(bytes: ByteArray = byteArrayOf(0)): Path =
    Files.createTempFile("clip", ".mp4").also { Files.write(it, bytes) }

private fun ok(text: String, prompt: Int = 100, output: Int = 5, thoughts: Int = 0, finish: String = "STOP"): Pair<Int, String> =
    200 to """
    {"candidates": [{"content": {"role": "model", "parts": [{"text": ${JsonPrimitive(text)}}]}, "finishReason": "$finish"}],
     "usageMetadata": {"promptTokenCount": $prompt, "candidatesTokenCount": $output, "thoughtsTokenCount": $thoughts}}
    """.trimIndent()

private class FakeTransport(vararg responses: Pair<Int, String>) : GeminiTransport {
    private val queue = ArrayDeque(responses.toList())
    private val last = responses.lastOrNull()
    val posts = mutableListOf<Pair<String, String>>()

    override fun post(url: String, body: String): Pair<Int, String> {
        posts += url to body
        return queue.removeFirstOrNull() ?: last ?: error("no response queued")
    }
}
