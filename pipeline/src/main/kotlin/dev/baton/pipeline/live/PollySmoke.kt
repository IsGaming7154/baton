package dev.baton.pipeline.live

import dev.baton.pipeline.PipelineConfig
import dev.baton.pipeline.SpendConfig
import dev.baton.pipeline.SpendGuard
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** 56 characters: about $0.0009 at Polly's neural price. */
const val SMOKE_SENTENCE: String = "A lone rider crosses the frozen ridge as the wind rises."

/**
 * The one live Polly call, run by hand (`gradlew :pipeline:pollySmoke --args=<out.wav>`),
 * never by the build. It goes through the spend guard with the default caps and the real
 * ledger, like any live run.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: gradlew :pipeline:pollySmoke --args=<out.wav>" }
    val out = Path.of(args[0]).toAbsolutePath()
    val pipeline = PipelineConfig()
    // Polly is priced per character; the media duration only prices Transcribe and Nova.
    val guard = SpendGuard(SpendConfig(), mediaDurationMs = 1)

    val audio = PollySynthesis().use { guard.guarded(it).synthesize(SMOKE_SENTENCE, pipeline.voice) }

    Files.createDirectories(out.parent)
    Files.write(out, wav(audio.bytes!!, audio.sampleRateHz))
    val polly = guard.report(out, pipeline).polly
    println("wrote $out")
    println("frames ${audio.frameCount} at ${audio.sampleRateHz} Hz, measured ${audio.duration.millis} ms")
    println("characters: estimated ${SMOKE_SENTENCE.length}, billed ${audio.requestCharacters ?: "not reported"}")
    println("USD: estimated ${polly.estimatedUsd}, recorded ${polly.recordedUsd}, ledger total ${guard.cumulativeUsd}")
}

/** A 44-byte RIFF header in front of 16-bit mono PCM, so the render can be listened to. */
fun wav(pcm: ByteArray, sampleRateHz: Int): ByteArray =
    ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(sampleRateHz).putInt(sampleRateHz * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        .array()
