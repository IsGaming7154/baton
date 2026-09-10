package dev.baton.player

import dev.baton.director.DirectorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * [raisedCosineGain] and [fadeMsFor] are the only parts of
 * [OutputStageContentGain] with no `ExoPlayer` or `Looper` in them, so they are
 * the only parts that can be checked without a device. What the shape draws is
 * not decidable here -- see [DuckingAudioProcessorTest] for the equivalent
 * frame-domain shape, which this mirrors in wall-clock time instead.
 */
class OutputStageContentGainTest : FunSpec({

    val fadeMs = 150L
    val unity = 1.0f
    val minusTwelveDb = 0.251188643f

    /** What `duckFor` asks for on the demo clip, now that the dip is measured rather than fixed. */
    val adaptiveDuck = 10.0.pow(-17.7 / 20.0).toFloat()

    /** The deepest dip the director will ever ask for, so the worst case for step size. */
    val deepestDuck = 10.0.pow(DirectorConfig.DEFAULT_MAX_DUCK_DB / 20.0).toFloat()

    fun worstStepDb(from: Float, to: Float, overMs: Long, tickMs: Long): Double =
        (0..overMs step tickMs)
            .map { raisedCosineGain(from, to, it, overMs) }
            .zipWithNext { a, b -> abs(20.0 * log10(a.toDouble()) - 20.0 * log10(b.toDouble())) }
            .max()

    test("before the fade starts the ramp is still at the starting level") {
        raisedCosineGain(unity, minusTwelveDb, elapsedMs = 0L, fadeMs = fadeMs) shouldBe unity
    }

    test("at or past the fade duration the ramp has reached the target, and no further") {
        raisedCosineGain(unity, minusTwelveDb, elapsedMs = fadeMs, fadeMs = fadeMs) shouldBe minusTwelveDb
        raisedCosineGain(unity, minusTwelveDb, elapsedMs = fadeMs * 10, fadeMs = fadeMs) shouldBe minusTwelveDb
    }

    test("halfway through the ramp, halfway down it") {
        val halfway = raisedCosineGain(unity, minusTwelveDb, elapsedMs = fadeMs / 2, fadeMs = fadeMs)
        abs(halfway - (unity + minusTwelveDb) / 2).toDouble() shouldBeLessThan 0.001
    }

    test("the ramp is monotonic and has no corner at either end") {
        val samples = (0..fadeMs).map { raisedCosineGain(unity, minusTwelveDb, it, fadeMs) }
        val steps = samples.zipWithNext { a, b -> a - b }

        steps.all { it >= 0f } shouldBe true

        // Raised cosine: zero slope where the ramp meets each level, steepest in
        // the middle. A linear fade would make these three step sizes equal.
        val steepest = steps.max()
        steps.first().toDouble() shouldBeLessThan (steepest / 5).toDouble()
        steps.last().toDouble() shouldBeLessThan (steepest / 5).toDouble()
    }

    test("returning to unity ramps back up rather than snapping") {
        val samples = (0..fadeMs).map { raisedCosineGain(minusTwelveDb, unity, it, fadeMs) }
        samples.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        samples.first() shouldBe minusTwelveDb
        samples.last() shouldBe unity
    }

    test("zero or negative fade time lands on the target immediately") {
        raisedCosineGain(unity, minusTwelveDb, elapsedMs = 0L, fadeMs = 0L) shouldBe minusTwelveDb
        raisedCosineGain(unity, minusTwelveDb, elapsedMs = -5L, fadeMs = 150L) shouldBe unity
    }

    test("ducking is an attack, coming back is a release") {
        fadeMsFor(unity, minusTwelveDb, attackMs = 10L, releaseMs = 150L) shouldBe 10L
        fadeMsFor(minusTwelveDb, unity, attackMs = 10L, releaseMs = 150L) shouldBe 150L
    }

    test("a duck that deepens mid-utterance is an attack, and one that lightens is a release") {
        // The film gets louder under a description that is already speaking and
        // duckFor asks for more dip. Nothing started and nothing ended, so a rule
        // written in terms of starting and ending a duck would call this neither;
        // it is a move away from unity, so it is an attack like any other.
        fadeMsFor(minusTwelveDb, deepestDuck, attackMs = 10L, releaseMs = 150L) shouldBe 10L
        fadeMsFor(deepestDuck, minusTwelveDb, attackMs = 10L, releaseMs = 150L) shouldBe 150L
    }

    test("the defaults are asymmetric, inside Netflix's window, and inside the director's guard") {
        val attackMs = OutputStageContentGain.DEFAULT_ATTACK_MS
        val releaseMs = OutputStageContentGain.DEFAULT_RELEASE_MS

        // 2-15 ms is mandated: faster clicks, slower lets the bed mask the
        // narrator's first consonant.
        (attackMs in 2L..15L) shouldBe true
        (releaseMs > attackMs) shouldBe true

        // The guard is the silence held before the next line of dialogue, and it
        // is documented as being there to absorb this fade. A release longer than
        // it would leave the bed down under the next actor's first word.
        (releaseMs <= DirectorConfig.DEFAULT_GUARD_MS) shouldBe true
    }

    test("the release keeps every step inside a decibel and a half, even at the deepest duck") {
        // This bound used to be a decibel, measured against a static -12 dB duck.
        // duckFor now derives the dip from programme loudness and is allowed as
        // far as -19 dB, and the same ramp carrying seven more decibels has
        // proportionally bigger steps: 1.20 dB at the steepest point. Loosened
        // deliberately, to the honest number for the deepest dip the director can
        // ask for, rather than left passing on a depth the product no longer uses.
        val worst = worstStepDb(
            deepestDuck,
            unity,
            OutputStageContentGain.DEFAULT_RELEASE_MS,
            OutputStageContentGain.DEFAULT_TICK_MS,
        )
        worst shouldBeLessThan 1.5
    }

    test("no attack inside the mandated window can keep its steps small, at any tick") {
        // Worth stating plainly rather than hiding behind a loosened bound: at the
        // 10 ms attack and 5 ms tick, a -17.7 dB duck moves nearly 13 dB in one
        // step. Ticking faster does not rescue it -- even a 1 ms tick, finer than
        // this Handler will reliably honour on the app's main thread, still steps
        // more than 3 dB. There is no tick period at which a 2-15 ms attack is
        // step-free, so that is not the property to protect here.
        //
        // What makes it inaudible is downstream, not here: AudioFlinger reads the
        // track's volume once per mix buffer and applies it with RAMP_VOLUME,
        // interpolating linearly across the whole buffer, so a step written at
        // this layer is never a discontinuity at the DAC. Whether the result is
        // actually clean on this hardware is a claim for a listener.
        val attackMs = OutputStageContentGain.DEFAULT_ATTACK_MS
        val atDefaultTick = worstStepDb(unity, adaptiveDuck, attackMs, OutputStageContentGain.DEFAULT_TICK_MS)
        atDefaultTick shouldBeGreaterThan 10.0
        worstStepDb(unity, adaptiveDuck, attackMs, tickMs = 1L) shouldBeGreaterThan 3.0
    }

    test("the attack is drawn as a curve rather than collapsing to a single jump") {
        // Two steps, not one. A 5 ms attack at this tick would write the target
        // and nothing else, which is the platform-volume-control behaviour the
        // original design rejected -- and on an output path that does not get
        // AudioFlinger's own ramp there would be nothing left to smooth it.
        val attackMs = OutputStageContentGain.DEFAULT_ATTACK_MS
        val samples = (0..attackMs step OutputStageContentGain.DEFAULT_TICK_MS)
            .map { raisedCosineGain(unity, adaptiveDuck, it, attackMs) }

        samples.first() shouldBe unity
        samples.last() shouldBe adaptiveDuck
        samples.distinct().size shouldBe 3
        samples.zipWithNext().all { (a, b) -> b < a } shouldBe true
    }
})
