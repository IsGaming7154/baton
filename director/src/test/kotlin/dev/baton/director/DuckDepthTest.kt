package dev.baton.director

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The invariant is the loudness difference, not the dip. Every test here is
 * ultimately checking the same thing -- that the narration ends up the configured
 * number of loudness units above the bed -- and the published dip values are
 * checked as a consequence of that rather than as the thing being implemented.
 */
class DuckDepthTest : FunSpec({

    val config = DirectorConfig()

    test("the narration ends up exactly the target loudness difference above the bed") {
        // The property the whole rule exists for, swept across the band where
        // neither clamp is in play: -39.5 LUFS, where the dip reaches zero, up to
        // -20.5, where it reaches the -19 dB floor. In between the difference has
        // to land on the target exactly, not approximately.
        for (programmeLufs in listOf(-20.5, -24.0, -26.6, -30.0, -33.0, -36.0, -39.0)) {
            val duck = duckFor(programmeLufs, config)
            val bedAfterDuck = programmeLufs + duck.db

            (config.narrationLkfs - bedAfterDuck) shouldBe
                (config.targetLoudnessDifferenceLu plusOrMinus 1e-9)
        }
    }

    // The three rows of the published dip matrix. Each is the value the rule
    // returns somewhere inside that band -- a check that the rule lands in the
    // same range as the listener testing, not a reproduction of the table, since
    // the study does not publish where its band boundaries fall.

    test("loud programme audio gets the deep dip the matrix calls for") {
        duckFor(-20.5, config).db shouldBe (-19.0 plusOrMinus 1e-9)
    }

    test("normal programme audio gets the middle dip") {
        duckFor(-30.5, config).db shouldBe (-9.0 plusOrMinus 1e-9)
    }

    test("quiet programme audio gets a gentle dip rather than a hole") {
        duckFor(-36.5, config).db shouldBe (-3.0 plusOrMinus 1e-9)
    }

    test("near-silent content is not ducked at all") {
        // There is nothing there to duck, and a viewer who cannot see the screen
        // would hear the mechanism working on nothing.
        duckFor(-48.0, config) shouldBe ContentDuck.Unity
        duckFor(-60.0, config) shouldBe ContentDuck.Unity
    }

    test("the rule crosses into no-duck on its own, without a threshold constant") {
        // narrationLkfs - targetLoudnessDifferenceLu = -39.5 LUFS. Above it the
        // bed needs pulling down; below it the bed is already far enough under.
        duckFor(-39.4, config).db shouldBe (-0.1 plusOrMinus 1e-9)
        duckFor(-39.5, config) shouldBe ContentDuck.Unity
        duckFor(-39.6, config) shouldBe ContentDuck.Unity
    }

    test("the dip never gets deeper than the clamp, however loud the content is") {
        duckFor(-15.0, config).db shouldBe config.maxDuckDb
        duckFor(0.0, config).db shouldBe config.maxDuckDb
    }

    test("a louder bed always gets at least as deep a dip") {
        var previous = 0.0
        var programmeLufs = -60.0
        while (programmeLufs <= 0.0) {
            val db = duckFor(programmeLufs, config).db
            check(db <= previous) { "dip got shallower at $programmeLufs LUFS: $db after $previous" }
            previous = db
            programmeLufs += 0.5
        }
    }

    test("with nothing measured yet, the static fallback is used") {
        duckFor(null, config).db shouldBe config.duckDb
    }

    test("the target loudness difference is the knob, and it moves the dip one for one") {
        val deeper = config.copy(targetLoudnessDifferenceLu = 15.0)

        val before = duckFor(-30.0, config).db
        val after = duckFor(-30.0, deeper).db

        (before - after) shouldBe (2.5 plusOrMinus 1e-9)
    }

    test("moving the narration anchor moves every dip with it") {
        // If SpeechClarity's target and the director's belief about it ever drift
        // apart, every duck is wrong by the difference. This is what that failure
        // looks like, pinned so it cannot happen quietly.
        val quieterNarration = config.copy(narrationLkfs = -30.0)

        (duckFor(-30.0, config).db - duckFor(-30.0, quieterNarration).db) shouldBe (3.0 plusOrMinus 1e-9)
    }

    test("the first dip of an utterance arrives at full depth, undamped") {
        // Coming from Unity there is nothing to be steady about, and the duck has
        // to be all the way down before the narrator's first word.
        duckFor(-21.8, config, ContentDuck.Unity) shouldBe duckFor(-21.8, config)
    }

    test("a change too small to hear does not move the duck") {
        val settled = duckFor(-26.0, config)
        // A quarter of a decibel of meter wobble. Under the 1 dB deadband, so the
        // dip that is already applied stays exactly where it is -- the same
        // instance, not merely an equal one, because downstream a different number
        // is a different fade.
        duckFor(-26.25, config, settled) shouldBe settled
        duckFor(-25.75, config, settled) shouldBe settled
    }

    test("a real scene change does move it, in both directions") {
        val settled = duckFor(-26.0, config)

        duckFor(-30.0, config, settled).db shouldBe (duckFor(-30.0, config).db plusOrMinus 1e-9)
        duckFor(-22.0, config, settled).db shouldBe (duckFor(-22.0, config).db plusOrMinus 1e-9)
    }

    test("a meter jittering around one level produces exactly one duck change") {
        // This is the defect the deadband exists for, and it was measured on
        // hardware before it was written down: the ramp restarting about thirteen
        // times a second for the whole of an utterance, because a subtraction
        // reproduces every wobble of what it subtracts.
        val jitter = listOf(-21.80, -21.83, -21.78, -21.81, -21.79, -21.84, -21.77, -21.82)

        var current: ContentDuck = ContentDuck.Unity
        var changes = 0
        for (lufs in jitter) {
            val next = duckFor(lufs, config, current)
            if (next != current) changes++
            current = next
        }

        changes shouldBe 1
    }

    test("a deadband of zero is the raw rule, tick by tick") {
        val raw = config.copy(duckDeadbandDb = 0.0)
        val settled = duckFor(-26.0, raw)

        duckFor(-26.01, raw, settled).db shouldBe (duckFor(-26.01, raw).db plusOrMinus 1e-9)
    }

    test("the deadband never holds the film down once the bed is quiet enough to need no dip") {
        // The rule reaches zero dip at -39.5 LUFS. Crossing that from a duck must
        // return Unity outright: a deadband able to swallow this would leave the
        // film quiet with nothing speaking over it, which is far worse than the
        // pumping it exists to prevent.
        val settled = duckFor(-39.0, config)

        duckFor(-39.6, config, settled) shouldBe ContentDuck.Unity
    }

    test("an unmeasured programme still falls back to the static dip") {
        duckFor(null, config) shouldBe ContentDuck.Ducked(config.duckDb)
    }
})
