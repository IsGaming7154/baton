package dev.baton.speech

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.baton.ports.RenderedSpeech
import dev.baton.ports.SpeechSynth
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What one candidate engine claimed about itself when asked. */
class EngineProbe(
    val packageName: String,
    val initStatus: Int,
    val voiceCount: Int,
    val languageCount: Int,
    val failure: String? = null,
) {
    /**
     * Usable means it has something to speak with, not that it said SUCCESS.
     *
     * On the target hardware the platform default answers SUCCESS to `onInit`
     * and SUCCESS to `synthesizeToFile` while carrying no voice data at all, and
     * then fails asynchronously through a deprecated callback with no error code.
     * Every synchronous signal it gives is a lie; the voice count is not.
     * FRICTION_LOG.md entry 9.
     */
    val usable: Boolean =
        failure == null && initStatus == TextToSpeech.SUCCESS && voiceCount > 0 && languageCount > 0

    override fun toString(): String = "$packageName init=${statusName(initStatus)} voices=$voiceCount " +
        "languages=$languageCount usable=$usable" + (failure?.let { " failure=$it" } ?: "")

    private fun statusName(status: Int): String =
        if (status == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR($status)"
}

/**
 * Renders text to a WAV file with a platform text-to-speech engine, and measures
 * the result.
 *
 * Blocking, and must not be called from the main thread: `TextToSpeech` delivers
 * `onInit` through a `ServiceConnection`, which the platform dispatches on the
 * main looper. Waiting for it there deadlocks.
 */
class TtsSpeechSynth private constructor(
    private val tts: TextToSpeech,
    override val engineId: String,
    private val outputDir: File,
    val localeUsed: Locale?,
    val probes: List<EngineProbe>,
    private val speechRate: Float,
    private val log: (String) -> Unit,
) : SpeechSynth {

    /**
     * Run once, before the first render that is not a cache hit. It is how the
     * app says out loud that it is preparing, and only when there is something
     * to prepare: see `TrackPreparation.PREPARING_TEXT`.
     */
    var onFirstRender: (() -> Unit)? = null

    private val pending = HashMap<String, CountDownLatch>()
    private val failures = HashMap<String, String>()

    init {
        tts.setOnUtteranceProgressListener(Progress())
    }

    /**
     * Both `onError` overloads are implemented, because on the failing path only
     * the deprecated one fires and it carries no error code. An app that
     * implements the modern overload alone hears nothing go wrong.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finish(utteranceId, null)

        override fun onError(utteranceId: String?) = finish(utteranceId, "onError(utteranceId), no code")

        override fun onError(utteranceId: String?, errorCode: Int) =
            finish(utteranceId, "onError(utteranceId, $errorCode)")
    }

    /**
     * Renders [text], or hands back the file a previous launch rendered.
     *
     * The cache is what stands between a viewer and twenty seconds of silence on
     * every start: see [RenderCache]. A hit skips the engine *and* `SpeechClarity`,
     * because the file on disk has already been through both.
     */
    override fun render(key: String, text: String): RenderedSpeech {
        val file = File(outputDir, RenderCache.name(key, RenderCache.fingerprint(engineId, localeUsed, speechRate, text)))
        cached(file)?.let {
            log("speech cached key=$key file=${file.name} frames=${it.frameCount}")
            return it
        }
        onFirstRender?.let {
            onFirstRender = null
            it()
        }
        dropStaleRenders(key, file.name)
        file.delete()

        val latch = CountDownLatch(1)
        synchronized(pending) { pending[key] = latch }

        val queued = tts.synthesizeToFile(text, null, file, key)
        check(queued == TextToSpeech.SUCCESS) { "$engineId refused to queue '$key' (status $queued)" }

        check(latch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "$engineId never reported back on '$key' within ${SYNTHESIS_TIMEOUT_SECONDS}s"
        }
        val failure = synchronized(pending) { failures.remove(key) }
        check(failure == null) { "$engineId failed to render '$key': $failure" }
        check(file.length() > 0) { "$engineId reported success for '$key' and wrote no audio" }

        // Before the file is measured, not after: the chain preserves frame
        // count, but measuring first would mean the number the director trusts
        // came off a different file from the one played, which is the whole
        // thing this class exists to prevent.
        SpeechClarity.apply(file, log = log)

        val wav = WavAudio.read(file)
        check(wav.frameCount > 0) { "$engineId wrote a header and no frames for '$key'" }

        return RenderedSpeech(
            audioRef = file.absolutePath,
            frameCount = wav.frameCount,
            sampleRateHz = wav.sampleRateHz,
            channelCount = wav.channelCount,
        )
    }

    /**
     * The rendered file if it is there and readable, and null for anything else
     * -- missing, truncated by a kill mid-write, or written by a build whose WAV
     * this one cannot read. A cache that cannot be trusted has to fail by
     * rendering again, never by playing a bad file.
     */
    private fun cached(file: File): RenderedSpeech? {
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

    /** Earlier renders of the same key, which nothing will ask for again now the inputs have changed. */
    private fun dropStaleRenders(key: String, current: String) {
        outputDir.listFiles()
            ?.filter { RenderCache.isStaleRender(it.name, key, current) }
            ?.forEach { stale ->
                if (stale.delete()) log("speech cache dropped file=${stale.name} key=$key")
            }
    }

    /**
     * Speaks [text] straight out of the engine, without rendering it to a file
     * or telling the director. For the one thing that has to be said before the
     * director exists: that Baton is preparing. Nothing else is playing at that
     * point, so there is nothing to arbitrate with.
     */
    fun sayNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say-now")
    }

    override fun release() {
        tts.stop()
        tts.shutdown()
    }

    private fun finish(utteranceId: String?, failure: String?) {
        val key = utteranceId ?: return
        synchronized(pending) {
            if (failure != null) failures[key] = failure
            pending.remove(key)
        }?.countDown()
    }

    companion object {
        const val IVONA = "com.ivona.tts.oem"
        const val PICO = "com.svox.pico"

        private const val INIT_TIMEOUT_SECONDS = 10L
        private const val SYNTHESIS_TIMEOUT_SECONDS = 20L

        /**
         * Pick an engine that can actually speak, and bind to it by name.
         *
         * The two-argument `TextToSpeech(context, listener)` constructor is never
         * used here. It resolves to `getDefaultEngine()`, which on this device is
         * an engine with no voice data, and the resulting silence is reported
         * through a deprecated callback with no error code. Enumerating and
         * naming the engine explicitly is the only way to know what is speaking.
         *
         * Preference order is quality: IVONA is the Polly lineage at 24 kHz, Pico
         * is a 2010-era formant synthesizer at 16 kHz that ships its voice data
         * in the system image and is therefore always there. The fallback is not
         * theoretical -- IVONA has a voice only on a device where the screen
         * reader has been enabled at least once.
         */
        fun select(
            context: Context,
            outputDir: File,
            preferredLocale: Locale,
            log: (String) -> Unit,
            speechRate: Float = 1.0f,
        ): TtsSpeechSynth? {
            outputDir.mkdirs()
            val probes = mutableListOf<EngineProbe>()

            for (packageName in candidates(context, log)) {
                var tts: TextToSpeech? = null
                try {
                    val status = CountDownLatch(1)
                    var initStatus = TextToSpeech.ERROR
                    val engine = TextToSpeech(context, { code ->
                        initStatus = code
                        status.countDown()
                    }, packageName)
                    tts = engine

                    if (!status.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        probes += EngineProbe(packageName, TextToSpeech.ERROR, 0, 0, "never called onInit")
                        engine.shutdown()
                        continue
                    }

                    val voices = runCatching { engine.voices?.size ?: 0 }.getOrDefault(0)
                    val languages = runCatching { engine.availableLanguages?.size ?: 0 }.getOrDefault(0)
                    val probe = EngineProbe(packageName, initStatus, voices, languages)
                    probes += probe
                    log("engine probed $probe")

                    if (!probe.usable) {
                        engine.shutdown()
                        continue
                    }

                    val locale = chooseLocale(engine, preferredLocale, log)
                    if (locale == null) {
                        probes[probes.lastIndex] =
                            EngineProbe(packageName, initStatus, voices, languages, "no usable locale")
                        engine.shutdown()
                        continue
                    }

                    // Once, here, before anything is rendered: every render and every
                    // duration measured off it is then at this rate. Set on the instance,
                    // so it is Baton's alone and VoiceView keeps the rate it has.
                    val rateStatus = engine.setSpeechRate(speechRate)
                    log("engine selected $packageName locale=${locale.toLanguageTag()} speechRate=$speechRate status=$rateStatus")
                    return TtsSpeechSynth(engine, packageName, outputDir, locale, probes, speechRate, log)
                } catch (t: Throwable) {
                    probes += EngineProbe(packageName, TextToSpeech.ERROR, 0, 0, t.toString())
                    tts?.shutdown()
                }
            }

            log("engine selection found nothing usable among ${probes.size} candidates: $probes")
            return null
        }

        /**
         * Every installed engine, preferred ones first.
         *
         * `TextToSpeech.getEngines()` needs an instance to call it on, and an
         * instance needs an engine, so the enumeration is done the way that method
         * does it internally -- by asking the package manager who declares the
         * text-to-speech service intent.
         */
        private fun candidates(context: Context, log: (String) -> Unit): List<String> {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val installed = context.packageManager.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
            log("engines installed $installed")
            val preferred = listOf(IVONA, PICO).filter { it in installed }
            return preferred + (installed - preferred.toSet())
        }

        private fun chooseLocale(engine: TextToSpeech, preferred: Locale, log: (String) -> Unit): Locale? {
            val available = runCatching { engine.availableLanguages?.toList() }.getOrNull().orEmpty()
            val ranked = listOfNotNull(
                available.firstOrNull { it == preferred },
                available.firstOrNull { it.language == preferred.language },
                available.firstOrNull(),
            )
            for (locale in ranked) {
                val status = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (status >= TextToSpeech.LANG_AVAILABLE) {
                    if (locale != preferred) {
                        log("engine has no ${preferred.toLanguageTag()}; speaking ${locale.toLanguageTag()} instead")
                    }
                    return locale
                }
            }
            return null
        }
    }
}
