package dev.baton.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The frame count is the number every scheduling decision downstream rests on, so
 * it is read out of the file rather than believed from the synthesizer. These
 * tests are about the reading being right, including on the shapes a
 * text-to-speech engine actually produces.
 */
class WavAudioTest : FunSpec({

    test("frames are counted from the data chunk, not from the file length") {
        val file = wav(sampleRateHz = 24_000, channels = 1, frames = 1_200)

        val wav = WavAudio.read(file)
        wav.sampleRateHz shouldBe 24_000
        wav.channelCount shouldBe 1
        wav.bitsPerSample shouldBe 16
        wav.bytesPerFrame shouldBe 2
        wav.frameCount shouldBe 1_200L
    }

    test("a chunk between fmt and data is walked over rather than assumed away") {
        val file = wav(sampleRateHz = 16_000, channels = 1, frames = 800, extraChunk = "LIST" to 23)

        val wav = WavAudio.read(file)
        wav.sampleRateHz shouldBe 16_000
        wav.frameCount shouldBe 800L
    }

    test("a data length the synthesizer never went back and corrected loses to what is on disk") {
        val file = wav(sampleRateHz = 24_000, channels = 1, frames = 1_000, declaredDataBytes = 0)

        WavAudio.read(file).frameCount shouldBe 1_000L
    }

    test("stereo frames are frames, not samples") {
        val file = wav(sampleRateHz = 48_000, channels = 2, frames = 500)

        val wav = WavAudio.read(file)
        wav.bytesPerFrame shouldBe 4
        wav.frameCount shouldBe 500L
    }

    test("the payload comes back without the header") {
        val file = wav(sampleRateHz = 22_050, channels = 1, frames = 64, extraChunk = "LIST" to 10)

        val pcm = WavAudio.read(file).readPcm(file)
        pcm.size shouldBe 128
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).short shouldBe 1234.toShort()
    }
})

/**
 * A RIFF/WAVE file with every field an engine would write. [declaredDataBytes]
 * overrides the length in the data chunk header, which is how a synthesizer that
 * was interrupted leaves the file.
 */
private fun wav(
    sampleRateHz: Int,
    channels: Int,
    frames: Int,
    extraChunk: Pair<String, Int>? = null,
    declaredDataBytes: Int? = null,
): File {
    val bytesPerFrame = channels * 2
    val dataBytes = frames * bytesPerFrame
    val extraBytes = extraChunk?.let { 8 + it.second + (it.second and 1) } ?: 0
    val buffer = ByteBuffer.allocate(44 + extraBytes + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + extraBytes + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRateHz)
    buffer.putInt(sampleRateHz * bytesPerFrame)
    buffer.putShort(bytesPerFrame.toShort())
    buffer.putShort(16)

    extraChunk?.let { (id, size) ->
        buffer.put(id.toByteArray(Charsets.US_ASCII))
        buffer.putInt(size)
        repeat(size + (size and 1)) { buffer.put(0) }
    }

    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(declaredDataBytes ?: dataBytes)
    repeat(frames * channels) { buffer.putShort(1234) }

    val file = File.createTempFile("baton-wav", ".wav").apply { deleteOnExit() }
    file.writeBytes(buffer.array())
    return file
}
