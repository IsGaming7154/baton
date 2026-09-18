package dev.baton.pipeline.live

import dev.baton.director.Gap
import dev.baton.pipeline.BilledTokens
import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.DraftingPrompt
import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.SceneContext
import dev.baton.pipeline.ServiceRefused
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.StopReason
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** The live Nova adapter, against a fake Bedrock Runtime client. Nothing here reaches AWS. */
class NovaDraftingTest : FunSpec({

    val ask = DraftRequest(
        SceneContext(MediaRef("clip.mp4"), Gap(12_000, 16_000), "Where is the dragon?", "Over the ridge."),
        targetDurationMs = 3_000,
        attempt = 0,
    )

    test("the request names the model, sends the style rules as system, the clip as mp4 bytes, and the token ceiling") {
        val clip = clip(byteArrayOf(1, 2, 3))
        val bedrock = FakeBedrock(reply("""{"description": "A woman kneels."}"""))

        NovaDrafting("us.amazon.nova-2-lite-v1:0", clip, bedrock, maxTokens = 512).draft(ask)

        val sent = bedrock.requests.single()
        sent.modelId() shouldBe "us.amazon.nova-2-lite-v1:0"
        sent.system().single().text() shouldContain DraftingPrompt.STYLE_RULES
        sent.inferenceConfig().maxTokens() shouldBe 512
        val message = sent.messages().single()
        message.role() shouldBe ConversationRole.USER
        val (video, text) = message.content()
        video.video().formatAsString() shouldBe "mp4"
        video.video().source().bytes().asByteArray().toList() shouldBe listOf<Byte>(1, 2, 3)
        text.text() shouldContain "12.0 s to 16.0 s"
        text.text() shouldContain "Where is the dragon?"
        text.text() shouldContain "Over the ridge."
        text.text() shouldContain "at most 3.0 s"
    }

    test("the prompt holds the style rules, and tells a retry from the next description in a window") {
        NovaPrompt.system shouldContain DraftingPrompt.STYLE_RULES
        NovaPrompt.user(ask.copy(attempt = 2, previousText = "A long line.")) shouldContain "Write a shorter one"
        NovaPrompt.user(ask.copy(cueOrdinal = 1, previousText = "A woman kneels.")) shouldContain "Describe what happens next"
    }

    test("a well-formed reply is a draft, and its billed tokens are reported and totalled") {
        val nova = NovaDrafting("m", clip(), FakeBedrock(reply("""{"description": " A woman kneels. "}""", 27_000, 20)))

        nova.draft(ask) shouldBe Draft("A woman kneels.")
        nova.lastBilledTokens shouldBe BilledTokens(27_000, 20)
        nova.draft(ask)
        listOf(nova.calls, nova.inputTokens, nova.outputTokens) shouldBe listOf(2, 54_000L, 40L)
    }

    test("a fenced reply is still read") {
        NovaDrafting("m", clip(), FakeBedrock(reply("```json\n{\"description\": \"Snow falls.\"}\n```"))).draft(ask) shouldBe Draft("Snow falls.")
    }

    test("a decline, a malformed reply, and a refusal are no draft, logged, and still billed") {
        val cases = listOf(
            reply("""{"description": null}"""),
            reply("A woman kneels."),
            reply("""{"description": "A woman kneels.", "note": "x"}"""),
            reply("""{"description": ""}"""),
            reply("""{"description": 3}"""),
            reply(""),
            reply("""{"description": "A wo""", stop = StopReason.MAX_TOKENS),
            reply("", stop = StopReason.CONTENT_FILTERED),
        )
        val logs = mutableListOf<String>()
        for (response in cases) {
            val nova = NovaDrafting("m", clip(), FakeBedrock(response), log = { logs += it })
            nova.draft(ask) shouldBe null
            nova.lastBilledTokens shouldBe BilledTokens(100, 5)
        }
        logs.size shouldBe cases.size - 1
        logs.last() shouldContain "content_filtered"
    }

    test("a clip that would encode past the inline limit, or is not video, is refused before any call") {
        NovaDrafting.base64Length(18_750_000) shouldBe 25_000_000
        val big = clip()
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(18_750_000) }
        shouldThrow<IllegalArgumentException> { NovaDrafting("m", big, FakeBedrock(reply(""))) }.message shouldContain "inline video"
        val text = Files.createTempFile("clip", ".txt").also { Files.write(it, byteArrayOf(1)) }
        shouldThrow<IllegalArgumentException> { NovaDrafting("m", text, FakeBedrock(reply(""))) }
    }

    test("only a 4xx that proves the request never ran becomes ServiceRefused; the rest pass through as they came") {
        for (status in listOf(400, 403, 404)) {
            val rejection = AwsServiceException.builder().statusCode(status).message("Operation not allowed").build()
            withClue("status $status") {
                shouldThrow<ServiceRefused> { NovaDrafting("m", clip(), FakeBedrock(reply(""), rejection)).draft(ask) }.cause shouldBe rejection
            }
        }
        // 408 and 424 are raised while the model runs, 429 is throttling, 5xx is the service's own failure.
        for (status in listOf(408, 424, 429, 500, 503)) {
            val failure = AwsServiceException.builder().statusCode(status).message("failed").build()
            withClue("status $status") {
                shouldThrow<AwsServiceException> { NovaDrafting("m", clip(), FakeBedrock(reply(""), failure)).draft(ask) } shouldBe failure
            }
        }
    }

    test("closing the adapter closes the client") {
        val bedrock = FakeBedrock(reply(""))
        NovaDrafting("m", clip(), bedrock).close()
        bedrock.closed shouldBe true
    }
})

private fun clip(bytes: ByteArray = byteArrayOf(0)): Path =
    Files.createTempFile("clip", ".mp4").also { Files.write(it, bytes) }

private fun reply(text: String, input: Int = 100, output: Int = 5, stop: StopReason = StopReason.END_TURN): ConverseResponse =
    ConverseResponse.builder()
        .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(ContentBlock.fromText(text)).build()))
        .stopReason(stop)
        .usage(TokenUsage.builder().inputTokens(input).outputTokens(output).totalTokens(input + output).build())
        .build()

private class FakeBedrock(private val response: ConverseResponse, private val failure: RuntimeException? = null) : BedrockRuntimeClient {
    val requests = mutableListOf<ConverseRequest>()
    var closed = false

    override fun converse(request: ConverseRequest): ConverseResponse {
        requests += request
        if (failure != null) throw failure
        return response
    }

    override fun serviceName(): String = "bedrock"

    override fun close() {
        closed = true
    }
}
