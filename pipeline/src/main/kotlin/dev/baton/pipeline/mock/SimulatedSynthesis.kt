package dev.baton.pipeline.mock

import dev.baton.pipeline.RenderedAudio
import dev.baton.pipeline.Synthesis
import dev.baton.pipeline.VoiceSpec

/**
 * How many audio frames a synthesizer would emit for a piece of text.
 *
 * Frames, not milliseconds, and the distinction is the whole point. The product's
 * central guarantee is that no duration the director trusts was ever derived from
 * text, which is enforced by `MeasuredDurationMs` refusing to be constructed from
 * anything but rendered audio. A stand-in for Polly that returned a duration
 * would either need that door widened or would be lying about where its number
 * came from. So this returns a frame count -- the same thing a real synthesizer's
 * output buffer length tells you -- and the measurement happens afterwards, on
 * the buffer, exactly as it will when the buffer is real.
 *
 * The rate model is deliberately crude. It is a stand-in, and its job is to be
 * deterministic and roughly right, not to predict Polly.
 */
data class SpeakingRate(
    /** 165 wpm is the middle of the range professional describers read at. */
    val wordsPerMinute: Int = DEFAULT_WORDS_PER_MINUTE,
    /** Lead-in silence Polly puts in front of an utterance. */
    val leadInMs: Long = DEFAULT_LEAD_IN_MS,
    /** Extra breath at each sentence boundary. */
    val sentencePauseMs: Long = DEFAULT_SENTENCE_PAUSE_MS,
) {
    init {
        require(wordsPerMinute > 0) { "wordsPerMinute must be > 0, was $wordsPerMinute" }
        require(leadInMs >= 0) { "leadInMs must be >= 0, was $leadInMs" }
        require(sentencePauseMs >= 0) { "sentencePauseMs must be >= 0, was $sentencePauseMs" }
    }

    fun frameCountFor(text: String, sampleRateHz: Int): Long {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0, was $sampleRateHz" }
        val framesPerWord = sampleRateHz.toLong() * SECONDS_PER_MINUTE / wordsPerMinute
        return leadInMs.framesAt(sampleRateHz) +
            wordCount(text) * framesPerWord +
            sentenceCount(text) * sentencePauseMs.framesAt(sampleRateHz)
    }

    private fun Long.framesAt(sampleRateHz: Int): Long = this * sampleRateHz / MILLIS_PER_SECOND

    private fun wordCount(text: String): Long = text.split(WHITESPACE).count { it.isNotBlank() }.toLong()

    private fun sentenceCount(text: String): Long =
        maxOf(1L, text.count { it in SENTENCE_ENDINGS }.toLong())

    companion object {
        const val DEFAULT_WORDS_PER_MINUTE: Int = 165
        const val DEFAULT_LEAD_IN_MS: Long = 120L
        const val DEFAULT_SENTENCE_PAUSE_MS: Long = 220L

        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val SENTENCE_ENDINGS = ".!?"
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Stands in for Amazon Polly. Renders a buffer of the length the voice would have
 * produced and hands it back to be measured.
 *
 * The buffer is silence, and allocating it looks wasteful until you notice what it
 * buys: the frame count the caller measures comes off `bytes.size`, so the code
 * path from here to a `MeasuredDurationMs` is byte-for-byte the one the live
 * synthesizer will take. When [dev.baton.pipeline.Synthesis] is swapped for the
 * real Polly client, the only thing that changes is where the bytes came from.
 */
class SimulatedSynthesis(
    private val rate: SpeakingRate = SpeakingRate(),
    /** Where a real synthesizer would have stored this audio. */
    private val audioRefFor: (text: String) -> String? = { null },
) : Synthesis {

    override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio {
        val frameCount = rate.frameCountFor(text, voice.sampleRateHz)
        val pcm = ByteArray((frameCount * RenderedAudio.BYTES_PER_PCM16_MONO_FRAME).toInt())
        return RenderedAudio.ofPcm16Mono(pcm, voice.sampleRateHz, audioRefFor(text))
    }
}
