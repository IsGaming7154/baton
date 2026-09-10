package dev.baton.speech

import dev.baton.loudness.Bs1770
import dev.baton.loudness.TruePeak
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Makes a rendered utterance intelligible under a film soundtrack.
 *
 * ## Why the engine's output is not good enough as it stands
 *
 * The only voice this hardware will provision is IVONA `kajal` (FRICTION_LOG.md
 * entry 9), and measured across the demo renders its usable energy stops around
 * 8 kHz despite a 24 kHz container -- 99% of it below 2.2 kHz -- with a boxy
 * build-up through 250-600 Hz. That is audible as a muffled voice in the clear,
 * and it is worse than muffled under a bed: the consonant band that carries
 * intelligibility is exactly the band the engine is weakest in, and exactly the
 * band a music bed is loudest in. Ducking the film by 12 dB buys nothing if the
 * voice has no energy where the competition is.
 *
 * So the fix is not more level. It is putting energy where speech is understood.
 *
 * ## The chain, and why each stage is here
 *
 * 1. **High-pass at 110 Hz.** Rumble below the voice's fundamental, which costs
 *    headroom and contributes nothing to intelligibility.
 * 2. **Bell cut at 420 Hz.** The boxiness. Kept off the fundamental, which sits
 *    just below it and must survive.
 * 3. **Compression**, 3:1 with an 8 ms attack, so quiet syllables are not lost
 *    under the bed while loud ones stay off the ceiling. Synthetic speech wants
 *    this more than a human voice does: it has none of the natural redundancy a
 *    listener uses to reconstruct a syllable that briefly dips under a masker.
 *    It runs here, between the corrective cuts and the additive stages, for the
 *    reason given at the call site.
 * 4. **Wide, low-Q presence lift centred at 1.6 kHz.** Not where a mixing
 *    engineer's instinct puts it. See below.
 * 5. **Harmonic exciter**, small. Takes the 2.5-6 kHz band that survived,
 *    saturates it to generate harmonics, keeps the 6.5-10.8 kHz slice, and
 *    mixes it back at 6%. It is the one stage that adds rather than shapes --
 *    and it is kept small deliberately, because the band it fills is worth
 *    almost nothing to comprehension. See below.
 * 6. **Normalise to -27 LKFS with a -2 dBTP ceiling**, which is the delivery
 *    specification Amazon Prime Video and Netflix both enforce for description
 *    narration. This stage used to normalise to -3 dBFS peak. That was wrong for
 *    a reason worth keeping: peak amplitude does not track loudness, so a peak
 *    target leaves the level free to wander with whatever the synthesizer did,
 *    and Amazon's quality control rejects anything outside -27 +-2 LU
 *    automatically. See [dev.baton.loudness.Bs1770].
 *
 * ## Why the lift is at 1.6 kHz and the exciter is nearly off
 *
 * Both of those look wrong to anyone who has ever reached for a presence
 * control, and both come from ANSI S3.5-1997, which publishes measured Band
 * Importance Functions: the share of speech intelligibility each frequency band
 * actually carries. The one-third-octave weights peak at **1600 Hz (10.2%)**,
 * with 1250 Hz, 2000 Hz and 2500 Hz just behind. By 4 kHz the contribution has
 * fallen to 6.7%, and at 8 kHz it is **0.84%** -- effectively nothing.
 *
 * So the band an engineer reaches for to make a voice sound clear is not the
 * band that carries whether the words are understood, and the top octave an
 * exciter fabricates is worth under one percent of comprehension. It stays in
 * at 6% mix because it costs nothing and the timbre is nicer; it earns no more
 * than that.
 *
 * Synthetic speech pushes the same way, harder. A TTS engine cannot synthesise
 * the chaotic broadband frication of /s/ and /f/ convincingly, so a listener
 * leans even more heavily on the stable mid-range formants the engine does get
 * right -- which shifts the weights further toward 1-2.5 kHz and makes a
 * high-frequency lift on a synthetic voice an attempt to amplify information
 * that was never generated.
 *
 * ## How this was arrived at, including the wrong turn
 *
 * An earlier version of this chain put +6 dB at 3.8 kHz with a much larger
 * exciter, and won a listening comparison against gentler settings. It was
 * wrong. Every listening test to that point had auditioned dry speech in
 * silence, a condition this voice never occurs in. Scored with ESTOI against
 * the film bed at the signal-to-noise ratios the product actually sees, that
 * version measured no better than doing nothing at all: more impressive alone,
 * worth nothing once the film is playing. Moving the lift from 3.8 kHz to
 * 1.6 kHz and widening it, with everything else held constant, recovered a
 * consistent gain over unprocessed at every level tested.
 *
 * Two lessons worth keeping next to the numbers. For speech that will always be
 * heard against something else, tuning it in isolation optimises the wrong
 * quantity, and the ear will confidently pick the wrong answer. And "sounds
 * clearer" and "is more intelligible" are different measurements that happen to
 * share a word.
 *
 * The stage ordering came from a second opinion rather than from listening: the
 * difference it makes is at the edge of audibility, but it is the difference
 * between a compressor that levels the voice and one that fights the stage
 * trying to make the voice intelligible, and an ordering that is right for a
 * reason outlives a tuning that is right for three sentences.
 *
 * Every stage is a second-order biquad by construction rather than convenience:
 * the reference implementation this is ported from was restricted to shapes that
 * survive the port exactly, and the port was verified sample-for-sample against
 * it, so what was listened to is what runs.
 *
 * ## In place, and why that is safe
 *
 * The chain preserves frame count and format, so the file is rewritten over its
 * own data chunk and whatever header the engine produced is left alone. That
 * matters more than it looks: [TtsSpeechSynth] measures duration from the file
 * after this runs, and the director's whole guarantee is that the duration it
 * schedules against came off the bytes that will be heard. A stage that changed
 * length here would break that quietly. This one cannot.
 */
object SpeechClarity {

    /** Butterworth alignment for a second-order section: 12 dB/oct, no ripple. */
    private const val Q_BUTTERWORTH = 0.70710678

    data class Settings(
        val highPassHz: Double = 110.0,
        val boxCutHz: Double = 420.0,
        val boxCutDb: Double = -4.0,
        val boxCutQ: Double = 1.0,
        val presenceHz: Double = 1600.0,
        val presenceDb: Double = 6.5,
        val presenceQ: Double = 0.4,
        val exciterSourceLowHz: Double = 2500.0,
        val exciterSourceHighHz: Double = 6000.0,
        val exciterOutputHighPassHz: Double = 6500.0,
        val exciterOutputLowPassHz: Double = 10800.0,
        val exciterDrive: Double = 1.8,
        val exciterMix: Double = 0.06,
        val compressorRatio: Double = 3.0,
        val compressorAttackMs: Double = 8.0,
        val compressorReleaseMs: Double = 80.0,
        val compressorThresholdDb: Double = -20.0,
        /** Integrated loudness to deliver at. Amazon and Netflix both require -27 LKFS, +-2 LU. */
        val targetLkfs: Double = -27.0,
        /** True-peak ceiling. Both platforms cap description narration at -2 dBTP. */
        val maxTruePeakDbtp: Double = -2.0,
        /**
         * Peak target used only when the audio is too short or too quiet to have a
         * loudness at all. Not a second opinion about level -- a last resort, so
         * that a degenerate utterance still comes out audible instead of untouched.
         */
        val fallbackPeakDb: Double = -3.0,
    )

    /**
     * Applies the chain to [file] in place. Returns false, having changed
     * nothing, for audio the chain is not defined over -- rather than producing
     * a mangled utterance from a format assumption that did not hold.
     */
    fun apply(file: File, settings: Settings = Settings(), log: (String) -> Unit = {}): Boolean {
        val wav = WavAudio.read(file)
        if (wav.bitsPerSample != 16) {
            log("clarity skipped file=${file.name} reason=${wav.bitsPerSample}-bit")
            return false
        }
        if (wav.frameCount <= 0) {
            log("clarity skipped file=${file.name} reason=no-frames")
            return false
        }

        val pcm = wav.readPcm(file)
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val channels = wav.channelCount
        val frames = wav.frameCount.toInt()

        var peakBefore = 0.0
        val processed = Array(channels) { channel ->
            val lane = DoubleArray(frames)
            for (i in 0 until frames) {
                val v = samples.get(i * channels + channel) / 32768.0
                lane[i] = v
                val magnitude = abs(v)
                if (magnitude > peakBefore) peakBefore = magnitude
            }
            process(lane, wav.sampleRateHz, settings)
        }

        // Interleaved, because that is what a loudness meter measures: the
        // channels of a frame are summed, not weighed against each other.
        val interleaved = DoubleArray(frames * channels)
        var peakAfter = 0.0
        for (i in 0 until frames) {
            for (channel in 0 until channels) {
                val v = processed[channel][i]
                interleaved[i * channels + channel] = v
                val magnitude = abs(v)
                if (magnitude > peakAfter) peakAfter = magnitude
            }
        }

        // One gain for every channel, computed across all of them, so the image
        // is not moved by normalising each lane to its own level.
        val gain = normalisation(interleaved, channels, wav.sampleRateHz, peakAfter, settings, log)

        val out = ByteBuffer.allocate(pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (channel in 0 until channels) {
                val v = (interleaved[i * channels + channel] * gain).coerceIn(-1.0, 1.0)
                out.putShort((v * 32767.0).toInt().toShort())
            }
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(wav.dataOffset)
            raf.write(out.array())
        }

        log(
            "clarity applied file=${file.name} frames=$frames rate=${wav.sampleRateHz} " +
                "channels=$channels peakBeforeDb=${db(peakBefore)} peakAfterDb=${db(peakAfter * gain)}",
        )
        return true
    }

    /**
     * The single scalar that puts the utterance at the delivery target.
     *
     * Two constraints, and the tighter one wins. The loudness target says how far
     * to move the level so the narration arrives at -27 LKFS; the true-peak
     * ceiling says how far it may be moved before the waveform -- not the samples,
     * the waveform between them -- would go past -2 dBTP.
     *
     * When the ceiling is the tighter of the two, the loudness target is missed,
     * and it is missed deliberately. Netflix's guidance is to limit rather than
     * turn down, which is right for a human-voiced mix where the alternative is a
     * quiet narrator. It is not right here: a limiter is a nonlinearity, this
     * chain was tuned and scored without one, and the utterance has already been
     * through a 3:1 compressor that leaves it with a crest factor around 12 dB --
     * so at -27 LKFS the true peak lands near -15 dBTP and the ceiling has some
     * thirteen decibels of room it will never use. If it ever does bind, the log
     * says so, which is worth more than silently distorting the voice to hit a
     * number.
     */
    private fun normalisation(
        interleaved: DoubleArray,
        channels: Int,
        sampleRateHz: Int,
        peak: Double,
        settings: Settings,
        log: (String) -> Unit,
    ): Double {
        val measuredLkfs = Bs1770.integratedLufs(interleaved, channels, sampleRateHz)
        if (measuredLkfs == null) {
            // No measurable loudness: shorter than one 400 ms gating block, or
            // nothing above the -70 LUFS absolute gate. Neither is normalisable,
            // and both are worth seeing in the log rather than silently passing.
            val fallback = if (peak > 0.0) 10.0.pow(settings.fallbackPeakDb / 20.0) / peak else 1.0
            log(
                "clarity loudness unmeasurable frames=${interleaved.size / channels} " +
                    "reason=too-short-or-silent fallbackPeakDb=${settings.fallbackPeakDb}",
            )
            return fallback
        }

        val truePeakDbtp = TruePeak.dbtp(interleaved, channels, sampleRateHz)
        val toTargetDb = settings.targetLkfs - measuredLkfs
        val toCeilingDb = settings.maxTruePeakDbtp - truePeakDbtp
        val gainDb = minOf(toTargetDb, toCeilingDb)

        if (toCeilingDb < toTargetDb) {
            log(
                "clarity WARNING true-peak ceiling bound the gain: " +
                    "wantedDb=${fmt(toTargetDb)} allowedDb=${fmt(toCeilingDb)} " +
                    "deliveredLkfs=${fmt(measuredLkfs + gainDb)} targetLkfs=${settings.targetLkfs}",
            )
        }
        log(
            "clarity loudness measuredLkfs=${fmt(measuredLkfs)} truePeakDbtp=${fmt(truePeakDbtp)} " +
                "gainDb=${fmt(gainDb)} deliveredLkfs=${fmt(measuredLkfs + gainDb)} " +
                "deliveredDbtp=${fmt(truePeakDbtp + gainDb)}",
        )
        return 10.0.pow(gainDb / 20.0)
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)

    private fun process(input: DoubleArray, sampleRateHz: Int, s: Settings): DoubleArray {
        val nyquist = sampleRateHz / 2.0

        var x = biquad(input, highPass(sampleRateHz, s.highPassHz, Q_BUTTERWORTH))
        x = biquad(x, peaking(sampleRateHz, s.boxCutHz, s.boxCutDb, s.boxCutQ))

        // Corrective cuts before the compressor, everything additive after it.
        // A presence lift ahead of the compressor makes the compressor key off
        // the exact band the lift exists to protect: it boosts the consonants,
        // the detector sees them, and the gain reduction takes them straight
        // back off. The compressor should be levelling the voice, not arguing
        // with the intelligibility stage.
        x = compress(x, sampleRateHz, s)

        if (s.presenceHz < nyquist * 0.95) {
            x = biquad(x, peaking(sampleRateHz, s.presenceHz, s.presenceDb, s.presenceQ))
        }

        // An exciter can only add air a sample rate has room for, and `tanh`
        // is a memoryless nonlinearity run without oversampling, so everything
        // it generates above Nyquist folds back down. At 24 kHz the third
        // harmonic of the source band reaches 18 kHz and folds to around
        // 6-12 kHz, roughly 26-30 dB below the band that produced it -- audible
        // as grit on exposed fricatives, mostly masked under a bed. Oversampling
        // the saturator would remove it and costs more than it is worth on this
        // hardware; band-limiting the result is nearly free and takes the top of
        // it off. At 16 kHz -- Pico's rate, and Pico is the fallback engine --
        // there would be barely a kilohertz above the corner and the fold would
        // be most of what is in it, so the stage requires half an octave of real
        // headroom and is skipped rather than run into a ceiling it cannot clear.
        if (s.exciterMix > 0.0 && nyquist >= s.exciterOutputHighPassHz * 1.5) {
            var band = biquad(x, highPass(sampleRateHz, s.exciterSourceLowHz, Q_BUTTERWORTH))
            band = biquad(band, lowPass(sampleRateHz, minOf(s.exciterSourceHighHz, nyquist * 0.9), Q_BUTTERWORTH))
            for (i in band.indices) band[i] = tanh(band[i] * s.exciterDrive)
            var air = biquad(band, highPass(sampleRateHz, s.exciterOutputHighPassHz, Q_BUTTERWORTH))
            if (s.exciterOutputLowPassHz > 0.0 && s.exciterOutputLowPassHz < nyquist * 0.95) {
                air = biquad(air, lowPass(sampleRateHz, s.exciterOutputLowPassHz, Q_BUTTERWORTH))
            }
            for (i in x.indices) x[i] += s.exciterMix * air[i]
        }

        return x
    }

    /** `b0, b1, b2, a1, a2`, already normalised by `a0`. */
    private fun highPass(sampleRateHz: Int, f0: Double, q: Double): DoubleArray {
        val w0 = 2.0 * PI * f0 / sampleRateHz
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return doubleArrayOf(
            (1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0,
            (-2.0 * c) / a0, (1.0 - alpha) / a0,
        )
    }

    private fun lowPass(sampleRateHz: Int, f0: Double, q: Double): DoubleArray {
        val w0 = 2.0 * PI * f0 / sampleRateHz
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return doubleArrayOf(
            (1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0,
            (-2.0 * c) / a0, (1.0 - alpha) / a0,
        )
    }

    private fun peaking(sampleRateHz: Int, f0: Double, gainDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRateHz
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / a0, (-2.0 * c) / a0, (1.0 - alpha * a) / a0,
            (-2.0 * c) / a0, (1.0 - alpha / a) / a0,
        )
    }

    private fun biquad(input: DoubleArray, coefficients: DoubleArray): DoubleArray {
        val (b0, b1, b2, a1, a2) = coefficients
        val out = DoubleArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            out[i] = y0
        }
        return out
    }

    /**
     * Feed-forward compressor with the envelope followed in decibels, so the
     * attack and release times mean the same thing regardless of level.
     */
    private fun compress(input: DoubleArray, sampleRateHz: Int, s: Settings): DoubleArray {
        val attack = exp(-1.0 / (sampleRateHz * s.compressorAttackMs / 1000.0))
        val release = exp(-1.0 / (sampleRateHz * s.compressorReleaseMs / 1000.0))
        val slope = 1.0 - 1.0 / s.compressorRatio

        val out = DoubleArray(input.size)
        var envelope = 0.0
        for (i in input.indices) {
            val levelDb = 20.0 * log10(abs(input[i]) + 1e-9)
            val target = maxOf(levelDb - s.compressorThresholdDb, 0.0) * slope
            val coefficient = if (target > envelope) attack else release
            envelope = coefficient * envelope + (1.0 - coefficient) * target
            out[i] = input[i] * exp(-envelope * LN10_OVER_20)
        }
        return out
    }

    private val LN10_OVER_20 = ln(10.0) / 20.0

    private fun db(magnitude: Double): String =
        String.format("%.2f", 20.0 * log10(magnitude + 1e-12))
}
