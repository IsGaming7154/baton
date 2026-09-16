package dev.baton.pipeline

import dev.baton.director.Gap
import dev.baton.director.MeasuredDurationMs
import kotlinx.serialization.Serializable

/**
 * Where a piece of media lives. A local path while the pipeline runs against
 * fixtures; an `s3://` URI once it runs against Transcribe and Nova, both of
 * which take the object by reference rather than by upload.
 */
data class MediaRef(val uri: String) {
    init {
        require(uri.isNotBlank()) { "a media reference must name something" }
    }
}

/** One spoken word, located on the content timeline. */
data class Word(val text: String, val startMs: Long, val endMs: Long) {
    init {
        require(text.isNotBlank()) { "a word must have content" }
        require(startMs >= 0) { "word '$text' starts at $startMs; the timeline begins at 0" }
        require(endMs >= startMs) { "word '$text' ends at $endMs, before it starts at $startMs" }
    }

    val durationMs: Long get() = endMs - startMs
}

/**
 * Word-level timings for the content's own dialogue.
 *
 * Words, not sentences: the gap map is the complement of this list, and sentence
 * boundaries are too coarse to tell a breath from a pause worth describing in.
 */
data class Transcript(val locale: String, val words: List<Word>) {
    init {
        require(locale.isNotBlank()) { "a transcript must declare its locale" }
    }
}

/** Audio in, word-level timings out. Amazon Transcribe in the live pipeline. */
interface Transcription {
    fun transcribe(media: MediaRef): Transcript
}

/**
 * Everything the drafter is told about one moment in the film: which silence it
 * is writing for, and what is said either side of it.
 *
 * [media] travels with the context because the live drafter is a video model --
 * Nova is given the whole clip by URI and the transcript separately as text,
 * since the video channel does not carry audio.
 */
data class SceneContext(
    val media: MediaRef,
    val gap: Gap,
    val precedingDialogue: String,
    val followingDialogue: String,
)

/**
 * One ask. [targetDurationMs] is what we would like the text to come out at; it
 * is a request, not a constraint, which is the whole reason the fit loop exists.
 *
 * [attempt] counts from zero within a single cue, and [previousText] carries the
 * draft we are asking the drafter to beat. Together they are the "and now a
 * shorter one" turn of the conversation, expressed without making the port
 * stateful.
 *
 * [cueOrdinal] is the other conversation, and it is a different one: not "say that
 * again in less time" but "now say what happens next". A silence window holds
 * several descriptions, and the drafter has to be able to tell the second sentence
 * of a window from a retry of the first -- they want opposite things from it. The
 * two counters are kept apart rather than folded into one running total for
 * exactly that reason; a drafter reading a single number could not tell a fourth
 * observation from a fourth failed attempt at the first one.
 */
data class DraftRequest(
    val scene: SceneContext,
    val targetDurationMs: Long,
    val attempt: Int,
    /** Which description within [SceneContext.gap] this is, counting from zero. */
    val cueOrdinal: Int = 0,
    val previousText: String? = null,
) {
    init {
        require(targetDurationMs > 0) { "a draft target of $targetDurationMs ms asks for nothing" }
        require(attempt >= 0) { "attempt must be >= 0, was $attempt" }
        require(cueOrdinal >= 0) { "cueOrdinal must be >= 0, was $cueOrdinal" }
    }
}

/** Candidate description text for one gap. */
data class Draft(val text: String) {
    init {
        require(text.isNotBlank()) { "a draft must say something" }
    }
}

/**
 * Scene context and a target duration in, candidate text out. Amazon Nova in the
 * live pipeline.
 *
 * A null return means the drafter has nothing shorter left to offer, and is how
 * the fit loop learns it has run out of road rather than guessing from an
 * attempt count.
 */
interface Drafting {
    fun draft(request: DraftRequest): Draft?
}

/** What a live [Drafting] adapter tells the model, beside the [DraftRequest] itself. */
object DraftingPrompt {

    /**
     * The description style rules of issue #12, as prompt text. The ones a machine
     * can check are also enforced on the output by [CueChecks]; the prompt is where
     * the rest of them are asked for.
     */
    const val STYLE_RULES: String = """Write audio description for a blind viewer. Follow every rule:
- Use active voice in the present tense.
- Narrate in the third person, omniscient.
- Choose one precise verb over a verb with a qualifier: "trudges", not "walks slowly".
- Describe only what is seen. Do not editorialise, interpret or judge.
- Leave characters unnamed until the film names them in dialogue.
- Describe all characters with equal specificity.
- Use person-first language for disability.
- Never speak over dialogue.
- Treat song lyrics as dialogue.
- Do not describe what the viewer can already hear.
- Never refer to yourself, this prompt, the generation process, other descriptions or description numbers."""
}

/**
 * Which voice to synthesize with. Polly's voice id, engine and output rate.
 *
 * The rate is a PCM rate, because the pipeline measures PCM ([RenderedAudio.ofPcm16Mono]),
 * and Polly offers PCM at two rates only. A rate outside them would be refused by the
 * service on the first paid call, or would label a 16 kHz buffer as something else and
 * mis-measure every duration, so it is refused here, before anything is spent.
 */
@Serializable
data class VoiceSpec(
    val voiceId: String,
    val engine: String,
    val sampleRateHz: Int,
) {
    init {
        require(sampleRateHz in POLLY_PCM_SAMPLE_RATES_HZ) {
            "Polly renders PCM at $POLLY_PCM_SAMPLE_RATES_HZ Hz only, was asked for $sampleRateHz"
        }
    }

    companion object {
        /**
         * botocore's Polly model, `SynthesizeSpeechInput.SampleRate`: "Valid values for pcm
         * are "8000" and "16000"" (AWS CLI 2.36.44,
         * `awscli/botocore/data/polly/2016-06-10/service-2.json`). Matches the real neural
         * renders of 2026-09-12 (HANDOFF.md §6).
         */
        val POLLY_PCM_SAMPLE_RATES_HZ: Set<Int> = setOf(8_000, 16_000)
    }
}

/**
 * Audio that has actually been rendered, and therefore has a length that can be
 * counted rather than guessed.
 *
 * [duration] is computed at construction, so there is no such thing as a
 * RenderedAudio whose length is unknown -- and it is computed by the one factory
 * that measures, which is what keeps text out of the duration path.
 */
class RenderedAudio(
    val frameCount: Long,
    val sampleRateHz: Int,
    val bytes: ByteArray? = null,
    /** Where the audio was stored, when the synthesizer stored it. */
    val audioRef: String? = null,
    /**
     * The characters the synthesizer reports it billed for this audio -- Polly's
     * `x-amzn-RequestCharacters` -- or null when it reports none. [SpendGuard]
     * settles its pre-call estimate against this.
     */
    val requestCharacters: Int? = null,
) {
    init {
        require(requestCharacters == null || requestCharacters >= 0) {
            "requestCharacters must be >= 0, was $requestCharacters"
        }
    }

    val duration: MeasuredDurationMs = MeasuredDurationMs.ofRenderedAudio(frameCount, sampleRateHz)

    companion object {
        const val BYTES_PER_PCM16_MONO_FRAME: Int = 2

        /**
         * Measure a buffer of signed 16-bit mono PCM -- the shape Polly returns
         * for `OutputFormat=pcm`. The frame count comes off the buffer's length,
         * so the number is a property of the audio and of nothing else.
         */
        fun ofPcm16Mono(
            bytes: ByteArray,
            sampleRateHz: Int,
            audioRef: String? = null,
            requestCharacters: Int? = null,
        ): RenderedAudio {
            require(bytes.size % BYTES_PER_PCM16_MONO_FRAME == 0) {
                "a 16-bit mono buffer has an even length; ${bytes.size} bytes is half a frame short"
            }
            return RenderedAudio(
                frameCount = bytes.size.toLong() / BYTES_PER_PCM16_MONO_FRAME,
                sampleRateHz = sampleRateHz,
                bytes = bytes,
                audioRef = audioRef,
                requestCharacters = requestCharacters,
            )
        }
    }
}

/** Text in, rendered audio out. Amazon Polly in the live pipeline. */
interface Synthesis {
    fun synthesize(text: String, voice: VoiceSpec): RenderedAudio
}
