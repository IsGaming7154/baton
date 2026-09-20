package dev.baton.tv

import android.content.Context

/**
 * The shipped twin of the debug [OutputMeter]: it does nothing, and exists so
 * `MainActivity` can call the meter unconditionally while the release variant
 * carries neither `android.media.audiofx.Visualizer` nor RECORD_AUDIO. A meter is
 * a measuring tool for us, not a feature for a viewer.
 */
object OutputMeter {
    fun start(context: Context, log: (String) -> Unit) = Unit

    fun stop() = Unit
}
