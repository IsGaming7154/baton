package dev.baton.loudness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * The peak of the waveform, not the peak of the samples.
 *
 * ## Why the distinction is not pedantry
 *
 * Samples are points on a curve, and the curve goes between them. A signal whose
 * largest sample sits at -0.5 dBFS can reconstruct, at the converter, to a
 * waveform that goes above 0 dBFS -- and clips somewhere no meter reading the
 * samples ever saw. The gap is not small: a sine at a quarter of the sampling
 * rate, sampled 45 degrees off its crest, never once touches its own peak and
 * reads exactly 3.01 dB low.
 *
 * Amazon and Netflix both cap description narration at **-2 dBTP**, and dBTP
 * means this measurement and not the sample peak. A file that passes on sample
 * peak and fails on true peak is the ordinary case, not an edge case.
 *
 * ## How it is measured
 *
 * Upsample by four with a 49-tap Hann-windowed sinc, arranged as four polyphase
 * branches so each output phase costs thirteen multiply-accumulates rather than
 * forty-nine, and take the largest magnitude over every channel. This is
 * `libebur128`'s interpolator, kept tap-for-tap: BS.1770-4 Annex 2 specifies at
 * least 4x oversampling for a 48 kHz source, and at the 16 and 24 kHz this code
 * actually sees, 4x lands at 64 and 96 kHz -- a higher ratio to Nyquist than the
 * standard asks for at 48.
 *
 * The interpolator is not perfect and does not claim to be: a windowed sinc has
 * a finite transition band, so a peak right at Nyquist reads a fraction of a
 * decibel low. That error is one-sided and small, and the ceiling it feeds has
 * two decibels of room in it.
 */
object TruePeak {

    private const val TAPS = 49
    private const val FACTOR = 4

    /** Below this there is nothing to report; returned instead of negative infinity. */
    const val FLOOR_DBTP: Double = -200.0

    /**
     * True peak of [samples] -- interleaved, in [-1, 1] -- in dBTP.
     *
     * The loudest channel wins, because the ceiling is a per-channel limit: a
     * converter clips one channel at a time.
     */
    fun dbtp(samples: DoubleArray, channelCount: Int, sampleRateHz: Int): Double {
        require(channelCount >= 1) { "channelCount must be >= 1, was $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0, was $sampleRateHz" }

        val frames = samples.size / channelCount
        if (frames == 0) return FLOOR_DBTP

        var peak = 0.0
        for (channel in 0 until channelCount) {
            val interpolator = Interpolator()
            for (i in 0 until frames) {
                val magnitude = interpolator.push(samples[i * channelCount + channel])
                if (magnitude > peak) peak = magnitude
            }
        }
        return if (peak > 0.0) 20.0 * log10(peak) else FLOOR_DBTP
    }

    /**
     * Four-times polyphase upsampler. Holds the delay line for one channel, and
     * returns the largest magnitude among the four samples each input produces.
     */
    private class Interpolator {

        private val z = DoubleArray(DELAY)
        private var cursor = 0

        fun push(sample: Double): Double {
            z[cursor] = sample
            var peak = 0.0
            for (phase in 0 until FACTOR) {
                val coefficients = COEFFICIENTS[phase]
                val indices = INDICES[phase]
                var acc = 0.0
                for (t in coefficients.indices) {
                    var i = cursor - indices[t]
                    if (i < 0) i += DELAY
                    acc += z[i] * coefficients[t]
                }
                val magnitude = abs(acc)
                if (magnitude > peak) peak = magnitude
            }
            cursor++
            if (cursor == DELAY) cursor = 0
            return peak
        }
    }

    private val DELAY = (TAPS + FACTOR - 1) / FACTOR

    private val COEFFICIENTS: Array<DoubleArray>
    private val INDICES: Array<IntArray>

    init {
        val coefficients = Array(FACTOR) { mutableListOf<Double>() }
        val indices = Array(FACTOR) { mutableListOf<Int>() }
        for (j in 0 until TAPS) {
            val m = j - (TAPS - 1) / 2.0
            var c = if (m == 0.0) 1.0 else sin(m * PI / FACTOR) / (m * PI / FACTOR)
            c *= 0.5 * (1.0 - cos(2.0 * PI * j / (TAPS - 1)))
            if (c == 0.0) continue
            val phase = j % FACTOR
            coefficients[phase] += c
            indices[phase] += j / FACTOR
        }
        COEFFICIENTS = Array(FACTOR) { coefficients[it].toDoubleArray() }
        INDICES = Array(FACTOR) { indices[it].toIntArray() }
    }
}
