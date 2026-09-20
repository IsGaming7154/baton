package dev.baton.tv

import android.content.Context
import android.media.audiofx.Visualizer
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Reads the level of what the Stick actually puts out, so a duck can be measured
 * rather than trusted. Everything Baton says about its own ducking so far comes
 * from Baton's own arithmetic; this is the one number that does not.
 *
 * [Visualizer] on session 0 meters the output mix -- every app's audio after the
 * mixer -- which is the programme leaving the device. It is a level meter, not a
 * recorder: it hands back a peak and an RMS, and an 8-bit waveform too coarse to
 * be anything else.
 *
 * Off unless launched with `--ez meter true`, and debug-only: the release variant
 * compiles the no-op twin in `src/release`, so neither this class nor RECORD_AUDIO
 * reaches a shipped APK.
 *
 * What it logs, every [PERIOD_MS]:
 *  - `peakmB`/`rmsmB`, the platform's own measurement in millibels (1/100 dB)
 *    relative to full scale, [SILENCE_MB] meaning silence.
 *  - `waveDb`, RMS computed from the waveform. Coarser, and its floor sits around
 *    -42 dB, but it is a second opinion on the same instant.
 */
object OutputMeter {
    private const val PERIOD_MS = 100L

    /** The platform's reading for silence, in millibels: -96.00 dB. */
    private const val SILENCE_MB = -9600

    @Volatile private var running = false

    /**
     * Starts metering on its own thread. [log] is `MainActivity.log`, so the meter
     * lands in the same timeline as `output duck` and `speech started`, and the
     * three can be read against each other by timestamp.
     */
    fun start(context: Context, log: (String) -> Unit) {
        if (running) return
        running = true
        thread(name = "baton-meter", isDaemon = true) {
            val visualizer = try {
                Visualizer(0)
            } catch (t: Throwable) {
                // No RECORD_AUDIO, or the effect is unavailable: say so and stop,
                // rather than leave a silent thread that looks like a working meter.
                log("meter unavailable reason=$t")
                running = false
                return@thread
            }
            try {
                visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]
                visualizer.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                visualizer.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                visualizer.enabled = true
                log("meter started rate=${visualizer.samplingRate} captureSize=${visualizer.captureSize}")
                val measurement = Visualizer.MeasurementPeakRms()
                val wave = ByteArray(visualizer.captureSize)
                while (running) {
                    visualizer.getMeasurementPeakRms(measurement)
                    visualizer.getWaveForm(wave)
                    log(
                        "meter peakmB=${measurement.mPeak} rmsmB=${measurement.mRms}" +
                            " waveDb=${format(waveDb(wave))} silent=${measurement.mRms <= SILENCE_MB}",
                    )
                    Thread.sleep(PERIOD_MS)
                }
            } catch (t: Throwable) {
                log("meter stopped reason=$t")
            } finally {
                visualizer.release()
            }
        }
    }

    fun stop() {
        running = false
    }

    /** RMS of one capture, in dB relative to full scale. The samples are unsigned 8-bit, 128 being zero. */
    private fun waveDb(wave: ByteArray): Double {
        var sum = 0.0
        for (byte in wave) {
            val sample = ((byte.toInt() and 0xff) - 128) / 128.0
            sum += sample * sample
        }
        return 20 * log10(sqrt(sum / wave.size) + 1e-9)
    }

    private fun format(value: Double): String = String.format("%.1f", value)
}
