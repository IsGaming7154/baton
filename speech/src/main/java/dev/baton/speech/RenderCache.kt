package dev.baton.speech

import dev.baton.ports.RenderedSpeech
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Where a rendered utterance is kept between launches, and how a stale one is
 * recognised.
 *
 * Rendering is the whole of Baton's cold start: the Stick spends about twenty
 * seconds turning twelve spoken subtitles, six descriptions, the disclosure and
 * the transport phrases into WAV files before the film can begin, and until this
 * existed it did that again on every single launch. The audio is a pure function
 * of the text and of how it is spoken, so the second launch has no reason to
 * wait at all.
 *
 * The name carries a [fingerprint] of everything that decides what the file
 * sounds like. Change the text, the engine, the voice or the speaking rate, and
 * the name changes with it, so a stale file is never played -- it is simply a
 * file nothing asks for any more, and [isStaleRender] finds it for deletion.
 */
object RenderCache {
    /**
     * Bumped by hand when the render chain changes what a file sounds like
     * without changing any of the inputs below -- a `SpeechClarity` re-tune, or
     * a different way of writing the WAV. Every cached file is invalidated.
     */
    private const val VERSION = 1

    private const val FINGERPRINT_LENGTH = 8

    /** Beside the renders, and never mistaken for one: [isStaleRender] only matches `.wav`. */
    private const val IDENTITY_FILE = "engine"

    /** The engine and voice a render was made with -- the two inputs a warm start cannot know without binding. */
    data class Identity(val engineId: String, val locale: Locale?)

    /**
     * The file name for one render: the caller's own key, which is what the logs
     * read by, plus the fingerprint that makes it specific.
     */
    fun name(key: String, fingerprint: String): String = "$key-$fingerprint.wav"

    /** Everything that decides what the file sounds like, as eight hex characters. */
    fun fingerprint(engineId: String, locale: Locale?, speechRate: Float, text: String): String {
        val material = listOf(
            VERSION.toString(),
            engineId,
            locale?.toLanguageTag() ?: "none",
            // Rounded to three places, because the rate is a float and a
            // difference far below what anyone can hear should not throw the
            // cache away.
            String.format(Locale.ROOT, "%.3f", speechRate),
            text,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)
    }

    /**
     * Records which engine and voice the files here were rendered with, so the next
     * launch can find them before the engine is bound. Binding is most of what is
     * left of a warm start: about three seconds on the Stick, spent only to learn
     * two strings the last launch already knew.
     */
    fun remember(dir: File, identity: Identity) {
        File(dir, IDENTITY_FILE).writeText("${identity.engineId}\n${identity.locale?.toLanguageTag().orEmpty()}")
    }

    /**
     * What [remember] last wrote, or null if nothing usable was -- in which case
     * there is no warm start to attempt, and the engine is bound first as before.
     * A file cut short by a kill mid-write reads as an identity that names no
     * cached file, so it costs renders, never a wrong one.
     */
    fun remembered(dir: File): Identity? = runCatching {
        val lines = File(dir, IDENTITY_FILE).readLines()
        Identity(lines[0], lines.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag))
    }.getOrNull()?.takeIf { it.engineId.isNotBlank() }

    /**
     * The rendered file if it is there and readable, and null for anything else
     * -- missing, truncated by a kill mid-write, or written by a build whose WAV
     * this one cannot read. A cache that cannot be trusted has to fail by
     * rendering again, never by playing a bad file.
     */
    fun read(file: File): RenderedSpeech? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val wav = WavAudio.read(file)
            check(wav.frameCount > 0)
            RenderedSpeech(
                audioRef = file.absolutePath,
                frameCount = wav.frameCount,
                sampleRateHz = wav.sampleRateHz,
                channelCount = wav.channelCount,
            )
        }.getOrNull()
    }

    /**
     * A previous render of the same [key] that no longer matches its inputs.
     *
     * Matched exactly rather than by prefix: the cue key `d7-0` is a prefix of
     * the cue key `d7-01`, so a prefix test would delete another cue's current
     * audio on every launch and make the cache worse than useless.
     */
    fun isStaleRender(fileName: String, key: String, current: String): Boolean =
        fileName != current && fileName.matches(Regex("${Regex.escape(key)}-[0-9a-f]{$FINGERPRINT_LENGTH}\\.wav"))
}
