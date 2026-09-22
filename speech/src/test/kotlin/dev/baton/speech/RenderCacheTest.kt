package dev.baton.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale

/**
 * The cache is what keeps a viewer from waiting twenty seconds on every launch,
 * and the only harm it can do is hand back a file that no longer matches what
 * was asked for. Every test here is about that: what has to change the name, and
 * what must never be mistaken for a leftover.
 */
class RenderCacheTest : FunSpec({
    val engine = "com.ivona.tts.oem"
    val locale: Locale = Locale.forLanguageTag("en-IN")
    fun print(text: String, engineId: String = engine, voice: Locale? = locale, rate: Float = 1.0f) =
        RenderCache.fingerprint(engineId, voice, rate, text)

    test("the same inputs give the same render") {
        print("She walks into the light.") shouldBe print("She walks into the light.")
    }

    test("different text is a different render") {
        print("She walks into the light.") shouldNotBe print("He walks into the light.")
    }

    test("a different engine is a different voice") {
        print("Same words.") shouldNotBe print("Same words.", engineId = "com.svox.pico")
    }

    test("a different locale is a different voice") {
        print("Same words.") shouldNotBe print("Same words.", voice = Locale.forLanguageTag("en-US"))
    }

    test("a different speaking rate is a different length") {
        print("Same words.") shouldNotBe print("Same words.", rate = 1.2f)
    }

    test("a rate difference far below hearing is the same render") {
        print("Same words.") shouldBe print("Same words.", rate = 1.00001f)
    }

    test("no locale is its own case and not a crash") {
        print("Same words.", voice = null) shouldNotBe print("Same words.")
    }

    test("the name keeps the key the logs read by") {
        RenderCache.name("d7-0", "abcdef12") shouldBe "d7-0-abcdef12.wav"
    }

    test("an earlier render of the same key is stale") {
        RenderCache.isStaleRender("d7-0-00000000.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe true
    }

    test("the current render is not stale") {
        RenderCache.isStaleRender("d7-0-abcdef12.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe false
    }

    test("another cue whose key begins the same way is not stale") {
        // `d7-0` is a prefix of `d7-01`. A prefix test would delete that cue's
        // current audio on every launch and make the cache worse than useless.
        RenderCache.isStaleRender("d7-01-00000000.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe false
    }

    test("a file that is not a render of this key is left alone") {
        RenderCache.isStaleRender("disclosure-00000000.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe false
        RenderCache.isStaleRender("d7-0.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe false
        RenderCache.isStaleRender("d7-0-notahash.wav", "d7-0", "d7-0-abcdef12.wav") shouldBe false
    }
})
