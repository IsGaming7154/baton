package dev.baton.pipeline.live

import dev.baton.pipeline.PipelineConfig
import dev.baton.pipeline.VoiceSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The live Polly adapter, against a fake client. Nothing here reaches AWS. */
class PollySynthesisTest : FunSpec({

    test("the request is plain text, PCM, and the voice's id, engine and rate") {
        val polly = FakePolly(ByteArray(2))
        val synthesis = PollySynthesis(polly)

        synthesis.synthesize("A door opens.", PipelineConfig.DEFAULT_VOICE)
        synthesis.synthesize("Snow falls.", VoiceSpec("Vicki", "standard", 8_000))

        polly.requests.map {
            listOf(it.text(), it.textTypeAsString(), it.outputFormatAsString(), it.voiceIdAsString(), it.engineAsString(), it.sampleRate())
        } shouldBe listOf(
            listOf("A door opens.", "text", "pcm", "Joanna", "neural", "16000"),
            listOf("Snow falls.", "text", "pcm", "Vicki", "standard", "8000"),
        )
    }

    test("the PCM that comes back is measured from its length, and carries Polly's billed characters") {
        // One second of 16-bit mono at 16 kHz.
        val audio = PollySynthesis(FakePolly(ByteArray(32_000), requestCharacters = 13))
            .synthesize("A door opens.", PipelineConfig.DEFAULT_VOICE)

        audio.frameCount shouldBe 16_000
        audio.sampleRateHz shouldBe 16_000
        audio.duration.millis shouldBe 1_000
        audio.requestCharacters shouldBe 13
    }

    test("a response without billed characters says so, rather than claiming zero") {
        PollySynthesis(FakePolly(ByteArray(2), requestCharacters = null))
            .synthesize("A door opens.", PipelineConfig.DEFAULT_VOICE)
            .requestCharacters shouldBe null
    }

    test("an odd-length buffer is refused rather than measured") {
        shouldThrow<IllegalArgumentException> {
            PollySynthesis(FakePolly(ByteArray(3))).synthesize("A door opens.", PipelineConfig.DEFAULT_VOICE)
        }
    }

    test("closing the adapter closes the client") {
        val polly = FakePolly(ByteArray(2))
        PollySynthesis(polly).close()
        polly.closed shouldBe true
    }

    test("the smoke call's sentence stays short, and its WAV header describes the PCM behind it") {
        SMOKE_SENTENCE.length shouldBe 56
        val pcm = ByteArray(32_000)
        val header = ByteBuffer.wrap(wav(pcm, 16_000)).order(ByteOrder.LITTLE_ENDIAN)

        header.capacity() shouldBe 44 + pcm.size
        String(header.array(), 0, 4) shouldBe "RIFF"
        header.getInt(4) shouldBe 36 + pcm.size
        header.getShort(22) shouldBe 1.toShort()
        header.getInt(24) shouldBe 16_000
        header.getInt(28) shouldBe 32_000
        header.getShort(34) shouldBe 16.toShort()
        header.getInt(40) shouldBe pcm.size
    }
})

private class FakePolly(private val pcm: ByteArray, private val requestCharacters: Int? = 1) : PollyClient {
    val requests = mutableListOf<SynthesizeSpeechRequest>()
    var closed = false

    override fun synthesizeSpeechAsBytes(request: SynthesizeSpeechRequest): ResponseBytes<SynthesizeSpeechResponse> {
        requests += request
        val response = SynthesizeSpeechResponse.builder().requestCharacters(requestCharacters).build()
        return ResponseBytes.fromByteArray(response, pcm)
    }

    override fun serviceName(): String = "polly"

    override fun close() {
        closed = true
    }
}
