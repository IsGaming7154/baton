package dev.baton.tv

/**
 * The shipped twin of the debug [OutputMeter]: it does nothing, and exists so
 * `MainActivity` can call the meter unconditionally while the release variant
 * carries neither `android.media.audiofx.Visualizer` nor RECORD_AUDIO. A meter is
 * a measuring tool for us, not a feature for a viewer.
 */
object OutputMeter {
    @Suppress("UNUSED_PARAMETER")
    fun start(log: (String) -> Unit) = Unit

    fun stop() = Unit
}
