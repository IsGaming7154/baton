package dev.baton.speech

import dev.baton.ports.RenderedSpeech
import dev.baton.ports.SpeechSynth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * A warm start exists to reach the film without binding the text-to-speech
 * engine first, which costs about three seconds on the Stick. So the one thing
 * that must never happen is a cached render waiting on the bind, and the one
 * thing that must never be played is a file rendered for a different voice.
 */
class WarmStartSynthTest : FunSpec({
    val identity = RenderCache.Identity("com.ivona.tts.oem", Locale.forLanguageTag("en-IN-kajal"))
    val rate = 1.0f

    fun dir(): File = Files.createTempDirectory("baton-warm").toFile().apply { deleteOnExit() }

    /** Puts a real WAV where a render of [text] under [who] would have left it. */
    fun cache(dir: File, key: String, text: String, frames: Int, who: RenderCache.Identity = identity) {
        val name = RenderCache.name(key, RenderCache.fingerprint(who.engineId, who.locale, rate, text))
        wav(sampleRateHz = 24_000, channels = 1, frames = frames).copyTo(File(dir, name))
    }

    test("a cached render never waits for the engine") {
        val dir = dir()
        cache(dir, "d2-0", "Sparks glow.", frames = 21_840)
        val synth = WarmStartSynth(dir, identity, rate, NeverConsulted(), log = {})

        val rendered = synth.render("d2-0", "Sparks glow.")

        rendered.frameCount shouldBe 21_840L
        rendered.sampleRateHz shouldBe 24_000
    }

    test("anything not cached is rendered by the engine once it is bound") {
        val engine = FakeEngine()
        val synth = WarmStartSynth(dir(), identity, rate, CompletableFuture.completedFuture(engine), log = {})

        synth.render("s1-0", "A new line.")

        engine.rendered shouldBe listOf("s1-0" to "A new line.")
    }

    test("a file rendered for a different voice is not played") {
        val dir = dir()
        val pico = RenderCache.Identity("com.svox.pico", Locale.US)
        cache(dir, "d2-0", "Sparks glow.", frames = 100, who = pico)
        val engine = FakeEngine()
        val synth = WarmStartSynth(dir, identity, rate, CompletableFuture.completedFuture(engine), log = {})

        synth.render("d2-0", "Sparks glow.")

        engine.rendered shouldBe listOf("d2-0" to "Sparks glow.")
    }

    test("a changed speaking rate is not served from the old rate's file") {
        val dir = dir()
        cache(dir, "d2-0", "Sparks glow.", frames = 100)
        val engine = FakeEngine()
        val synth = WarmStartSynth(dir, identity, 1.2f, CompletableFuture.completedFuture(engine), log = {})

        synth.render("d2-0", "Sparks glow.")

        engine.rendered shouldBe listOf("d2-0" to "Sparks glow.")
    }

    test("a miss with no usable engine says which render could not be made") {
        val synth = WarmStartSynth(dir(), identity, rate, CompletableFuture.completedFuture(null), log = {})

        shouldThrow<IllegalStateException> { synth.render("s1-0", "A new line.") }
            .message shouldContain "s1-0"
    }

    // release() runs on the main thread, and the bind needs the main looper to
    // finish, so waiting for it there would deadlock. It has to hand the release on.
    test("released before the bind finishes, the engine is released when it arrives") {
        val engine = FakeEngine()
        val bind = CompletableFuture<SpeechSynth?>()
        val synth = WarmStartSynth(dir(), identity, rate, bind, log = {})

        synth.release()
        engine.released shouldBe false
        bind.complete(engine)

        engine.released shouldBe true
    }
})

/** A bind that fails the test if anything waits on it. */
private class NeverConsulted : CompletableFuture<SpeechSynth?>() {
    override fun get(): SpeechSynth? = throw AssertionError("a cached render waited for the engine")
    override fun join(): SpeechSynth? = throw AssertionError("a cached render waited for the engine")
}

private class FakeEngine : SpeechSynth {
    val rendered = mutableListOf<Pair<String, String>>()
    var released = false

    override val engineId = "fake"

    override fun render(key: String, text: String): RenderedSpeech {
        rendered += key to text
        return RenderedSpeech("/fake/$key.wav", frameCount = 24_000, sampleRateHz = 24_000, channelCount = 1)
    }

    override fun release() {
        released = true
    }
}
