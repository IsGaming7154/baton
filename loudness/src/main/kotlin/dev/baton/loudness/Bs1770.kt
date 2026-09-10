package dev.baton.loudness

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud something is, by the only definition anyone delivering audio is
 * allowed to use.
 *
 * ## Why not peak
 *
 * Peak amplitude has almost no relationship to how loud a thing sounds. A
 * compressed voice peaking at -3 dBFS and an open, dynamic one peaking at the
 * same -3 dBFS are ten decibels apart to a listener. Normalising to a peak
 * therefore leaves the actual level free to wander with whatever the synthesizer
 * did that day -- which is exactly the failure this replaces: the chain used to
 * end at -3 dBFS peak, which the delivery research names outright as the legacy
 * practice that fails automated quality control.
 *
 * Amazon Prime Video and Netflix both require description narration at
 * **-27 LKFS, +-2 LU**, measured by ITU-R BS.1770-1, with a **-2 dBTP** true-peak
 * ceiling. Amazon's QC rejects anything outside that window automatically. LKFS
 * and LUFS are the same unit under two names.
 *
 * ## The algorithm, and the two gates that make it mean something
 *
 * K-weighting first: a +4 dB high shelf around 1.7 kHz and a high-pass at 38 Hz,
 * which between them approximate how much a human hearing system cares about
 * each part of the spectrum. Then mean square over 400 ms blocks overlapping by
 * 75%, summed across channels.
 *
 * Then two gates, which are the part that stops the number being useless. An
 * **absolute** gate at -70 LUFS drops digital silence, and a **relative** gate
 * 10 LU below the mean of what survived drops the quiet passages. Without them,
 * a programme's loudness is dragged down by however much silence it happens to
 * contain, and two mixes of identical dialogue measure differently because one
 * has longer pauses. With them, the number tracks the foreground.
 *
 * That is also why this is a fair stand-in for the dialogue-gated measurement
 * Netflix and Amazon actually run. Their gate is Dolby's voice-activity
 * detector, isolating speech frames from a full mix; the file being measured
 * here contains nothing but speech to begin with, and the relative gate already
 * discards the gaps between words. There is no music or effects bed in it for a
 * voice detector to separate the speech from.
 *
 * ## Sample rate
 *
 * The standard prints its coefficients for 48 kHz. Used verbatim at another rate
 * they put the shelf in the wrong place and every reading is wrong -- and
 * nothing here runs at 48 kHz: IVONA renders at 24 kHz and Pico at 16 kHz. So
 * the coefficients are derived from the analogue prototype with a bilinear
 * prewarp at whatever rate the audio arrived at, the same way `libebur128` does
 * it. Fed the 48 kHz numbers, the derivation reproduces the standard's table.
 */
object Bs1770 {

    /** Blocks quieter than this are silence, and are never counted. */
    const val ABSOLUTE_GATE_LUFS: Double = -70.0

    /** Blocks this far below the ungated mean are background, and are not counted either. */
    const val RELATIVE_GATE_LU: Double = -10.0

    /** The gating block. 400 ms, overlapping by 75%, so the hop is 100 ms. */
    const val BLOCK_MS: Long = 400L
    const val HOP_MS: Long = 100L

    /**
     * The constant that makes a 1 kHz sine at -23 dBFS in two channels read
     * -23.0 LUFS. It absorbs the K-weighting's gain at 1 kHz; it is not a fudge
     * factor and must not be re-derived.
     */
    internal const val CALIBRATION_DB: Double = -0.691

    /**
     * Integrated, gated loudness of [samples] -- interleaved, in [-1, 1].
     *
     * Null, rather than a number, when there is nothing to measure: audio
     * shorter than one 400 ms block, or a signal with no block above the
     * absolute gate. Both are real answers. The standard has no partial-block
     * rule, and a meter that invented one would report a figure no other meter
     * would agree with; digital silence has no loudness, and reporting negative
     * infinity as though it were a measurement is how a normaliser ends up
     * multiplying by one over zero.
     */
    fun integratedLufs(samples: DoubleArray, channelCount: Int, sampleRateHz: Int): Double? {
        require(channelCount >= 1) { "channelCount must be >= 1, was $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0, was $sampleRateHz" }

        val hopFrames = (sampleRateHz * HOP_MS / 1_000L).toInt()
        val blockFrames = hopFrames * (BLOCK_MS / HOP_MS).toInt()
        val frames = samples.size / channelCount
        if (hopFrames <= 0 || frames < blockFrames) return null

        val weights = channelWeights(channelCount)
        val hopCount = frames / hopFrames

        // Weighted energy per 100 ms hop, summed over channels. A 400 ms block is
        // then four consecutive hops, which is what makes the 75% overlap free.
        val hopEnergy = DoubleArray(hopCount)
        for (channel in 0 until channelCount) {
            val weight = weights[channel]
            if (weight == 0.0) continue
            val filter = KWeighting(sampleRateHz)
            for (hop in 0 until hopCount) {
                var sum = 0.0
                val start = hop * hopFrames
                for (i in start until start + hopFrames) {
                    val y = filter.process(samples[i * channelCount + channel])
                    sum += y * y
                }
                hopEnergy[hop] += weight * sum
            }
        }

        val blockCount = hopCount - (BLOCK_MS / HOP_MS).toInt() + 1
        if (blockCount <= 0) return null
        val blocks = DoubleArray(blockCount)
        for (b in 0 until blockCount) {
            var sum = 0.0
            for (h in b until b + (BLOCK_MS / HOP_MS).toInt()) sum += hopEnergy[h]
            blocks[b] = sum / blockFrames
        }

        val aboveAbsolute = blocks.filter { it > 0.0 && loudnessOf(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null

        val relativeThreshold = loudnessOf(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val aboveRelative = aboveAbsolute.filter { loudnessOf(it) > relativeThreshold }
        if (aboveRelative.isEmpty()) return null

        return loudnessOf(aboveRelative.average())
    }

    /** Loudness of one weighted mean-square energy. */
    internal fun loudnessOf(energy: Double): Double = CALIBRATION_DB + 10.0 * log10(energy)

    /**
     * The per-channel weights of BS.1770-4 table 3: unity for the three front
     * channels, +1.5 dB for the surrounds, and the LFE excluded entirely.
     *
     * Only mono and stereo occur in this codebase -- a rendered utterance is
     * always mono, and the Fire TV Stick's sink is stereo -- but a decoder handed
     * a 5.1 track must not crash an audio processor, so the surround layout is
     * spelled out rather than approximated. Anything else falls back to unity
     * per channel: a downmix of unknown geometry is better measured slightly
     * wrong than not at all.
     */
    internal fun channelWeights(channelCount: Int): DoubleArray = when (channelCount) {
        1 -> doubleArrayOf(1.0)
        2 -> doubleArrayOf(1.0, 1.0)
        // L R C LFE Ls Rs, the WAVE and Media3 channel order.
        6 -> doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.41, 1.41)
        else -> DoubleArray(channelCount) { 1.0 }
    }
}

/**
 * The K-weighting pair: a high shelf, then the RLB high-pass. One instance per
 * channel, because each carries its own filter state.
 *
 * The two stages are kept separate rather than convolved into one fourth-order
 * section. They are the same filter either way, but cascaded second-order
 * sections are what the standard describes and what stays well-conditioned at
 * 16 kHz, where the high-pass corner sits at 0.0024 of Nyquist and the poles of
 * a single fourth-order form crowd the unit circle.
 *
 * The second stage's numerator is `1, -2, 1` undivided, exactly as printed in
 * the standard's table. That leaves the stage with about +0.04 dB of gain it
 * would not have if it were normalised the usual way -- which is deliberate, and
 * is part of what [Bs1770.CALIBRATION_DB] absorbs.
 */
internal class KWeighting(sampleRateHz: Int) {

    private val shelf: Biquad
    private val highPass: Biquad

    init {
        val shelfHz = 1681.974450955533
        val shelfGainDb = 3.999843853973347
        val shelfQ = 0.7071752369554196
        val ks = tan(PI * shelfHz / sampleRateHz)
        val vh = 10.0.pow(shelfGainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0s = 1.0 + ks / shelfQ + ks * ks
        shelf = Biquad(
            b0 = (vh + vb * ks / shelfQ + ks * ks) / a0s,
            b1 = 2.0 * (ks * ks - vh) / a0s,
            b2 = (vh - vb * ks / shelfQ + ks * ks) / a0s,
            a1 = 2.0 * (ks * ks - 1.0) / a0s,
            a2 = (1.0 - ks / shelfQ + ks * ks) / a0s,
        )

        val hpHz = 38.13547087602444
        val hpQ = 0.5003270373238773
        val kh = tan(PI * hpHz / sampleRateHz)
        val a0h = 1.0 + kh / hpQ + kh * kh
        highPass = Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (kh * kh - 1.0) / a0h,
            a2 = (1.0 - kh / hpQ + kh * kh) / a0h,
        )
    }

    fun process(sample: Double): Double = highPass.process(shelf.process(sample))

    fun reset() {
        shelf.reset()
        highPass.reset()
    }
}

/** Direct form I, coefficients already normalised by `a0`. */
internal class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x0: Double): Double {
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        return y0
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

/**
 * Short-term loudness of a stream that is still arriving: the BS.1770 mean
 * square over a sliding 3 s window, ungated, as EBU Tech 3341 defines it.
 *
 * ## Why short-term and not integrated
 *
 * This meters the film, to decide how far to duck it under a description. The
 * question is how loud the content is *at this cue*, not across the whole title,
 * so the measurement has to be a window rather than a running total. Three
 * seconds is the standard's own short-term window, and it is roughly what a
 * mixer riding a fader is responding to.
 *
 * Ungated on purpose. The gates exist to stop quiet passages dragging down a
 * programme-length average; over three seconds there is no average to protect,
 * and a quiet passage is precisely the case that must be reported as quiet --
 * gating it out would return the loudness of the last thing that was loud, and
 * duck a near-silent scene as though it were an explosion.
 *
 * ## Threading
 *
 * [push] is called from the audio render thread, once per sample, and [lufs]
 * from whatever thread is asking. Nothing is shared between them except one
 * `@Volatile` double that [push] writes at the end of each 100 ms hop and [lufs]
 * reads. There is no lock, and there is nothing to tear.
 */
class ShortTermLoudnessMeter(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0, was $sampleRateHz" }
        require(channelCount >= 1) { "channelCount must be >= 1, was $channelCount" }
        require(windowMs >= Bs1770.HOP_MS) { "window must hold at least one hop, was ${windowMs}ms" }
    }

    private val weights = Bs1770.channelWeights(channelCount)
    private val filters = Array(channelCount) { KWeighting(sampleRateHz) }
    private val hopFrames = (sampleRateHz * Bs1770.HOP_MS / 1_000L).toInt().coerceAtLeast(1)
    private val hops = (windowMs / Bs1770.HOP_MS).toInt()
    private val window = DoubleArray(hops)

    private var cursor = 0
    private var filled = 0
    private var hopEnergy = 0.0
    private var framesInHop = 0

    @Volatile
    private var current = Double.NaN

    /**
     * Feed one sample of one channel. Channels of a frame must be pushed in
     * order, because the last channel of a frame is what advances the clock.
     */
    fun push(channel: Int, value: Double) {
        val y = filters[channel].process(value)
        hopEnergy += weights[channel] * y * y
        if (channel != channelCount - 1) return

        framesInHop++
        if (framesInHop < hopFrames) return

        window[cursor] = hopEnergy
        cursor = (cursor + 1) % hops
        if (filled < hops) filled++
        hopEnergy = 0.0
        framesInHop = 0

        // Only once the window is genuinely full. A partial window reports the
        // loudness of however much has arrived, which right after a seek is a
        // fraction of a second of whatever the film cut to -- a number that looks
        // like a measurement and is not one.
        if (filled < hops) return
        var sum = 0.0
        for (v in window) sum += v
        val energy = sum / (hopFrames.toDouble() * hops)
        current = if (energy > 0.0) Bs1770.loudnessOf(energy) else Double.NaN
    }

    /** The last complete window, or null if there has not been one since the last [reset]. */
    fun lufs(): Double? = current.takeUnless { it.isNaN() }

    /**
     * Forget everything. Called on a seek: the frames either side of a
     * discontinuity are not adjacent, and averaging across the cut would report
     * the loudness of a scene that is no longer playing.
     */
    fun reset() {
        for (filter in filters) filter.reset()
        window.fill(0.0)
        cursor = 0
        filled = 0
        hopEnergy = 0.0
        framesInHop = 0
        current = Double.NaN
    }

    companion object {
        /** EBU Tech 3341's short-term window. */
        const val DEFAULT_WINDOW_MS: Long = 3_000L
    }
}
