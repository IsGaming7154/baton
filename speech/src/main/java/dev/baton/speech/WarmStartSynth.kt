package dev.baton.speech

import dev.baton.ports.RenderedSpeech
import dev.baton.ports.SpeechSynth
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Serves renders from the cache without waiting for the engine, and waits for
 * the engine only when something actually has to be rendered.
 *
 * On a warm start every file the film needs is already on disk, and playback is
 * an `AudioTrack` over those files -- the engine is not involved. Binding it
 * still cost about three seconds on the Stick (`engines installed` to `engine
 * probed`, 2026-09-16 logs), spent only to learn the engine and voice the cache
 * key is built from. The last bind wrote those down ([RenderCache.remember]),
 * so this looks the files up under that [identity] while the engine binds
 * alongside it, and a launch with nothing new to render never waits for it.
 *
 * ponytail: if the engine or voice changed since the last launch, this one
 * plays the old voice's files -- still measured off the bytes it plays, so no
 * duration is ever wrong -- and the bind logs the change and writes the new
 * identity, so the next start re-renders. Only an engine swap reaches this, and
 * on the Stick that means the screen reader's voice data disappearing.
 */
class WarmStartSynth(
    private val outputDir: File,
    private val identity: RenderCache.Identity,
    private val speechRate: Float,
    private val engine: CompletableFuture<out SpeechSynth?>,
    private val log: (String) -> Unit,
) : SpeechSynth {

    override val engineId: String get() = identity.engineId

    override fun render(key: String, text: String): RenderedSpeech {
        val print = RenderCache.fingerprint(identity.engineId, identity.locale, speechRate, text)
        val file = File(outputDir, RenderCache.name(key, print))
        RenderCache.read(file)?.let {
            log("speech cached key=$key file=${file.name} frames=${it.frameCount}")
            return it
        }
        val bound = checkNotNull(engine.get()) { "no text-to-speech engine could be bound to render '$key', and it is not cached" }
        return bound.render(key, text)
    }

    /**
     * Never waits. This is called on the main thread, and the bind cannot finish
     * without the main looper -- `onInit` arrives through it -- so waiting here
     * for a bind still in flight would deadlock. The release is handed on instead.
     */
    override fun release() {
        engine.thenAccept { it?.release() }
    }
}
