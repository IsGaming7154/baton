package dev.baton.tv

import android.content.Context
import dev.baton.director.Cue
import dev.baton.director.DescriptionTrack
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.Variant
import dev.baton.ports.SpeechSynth
import dev.baton.speech.TtsSpeechSynth
import java.io.File
import java.util.Locale

/** A track the director may act on, and the synthesizer whose audio it was measured from. */
class PreparedTrack(val track: DescriptionTrack, val synth: SpeechSynth?)

/**
 * Turns the authored track in the APK into one the director is allowed to trust.
 *
 * ## What the bundled JSON is, and is not
 *
 * `assets/sample_clip.track.json` carries the cue texts, their positions, their
 * kinds and the gap map. Its `measuredDurationMs` values were measured -- off
 * WAVs rendered by a desktop synthesizer while the track was being authored --
 * but they were measured from audio nobody on the device will ever hear, and its
 * `audioRef`s are null because there is no pre-rendered audio in the APK.
 *
 * That is exactly the hole the runtime has to close. Two text-to-speech engines
 * on this hardware differ by 918 ms on the same sentence, roughly four guard
 * intervals (FRICTION_LOG.md entry 9), so a duration measured anywhere other than
 * on the engine that will speak is not evidence of anything. Admitting a cue into
 * a gap on such a number is how a description ends up on top of an actor.
 *
 * So every variant is re-rendered here, on the selected engine, to a file; the
 * frame count is read back out of that file; and the duration the director sees
 * is derived from those frames. The bundled number is logged beside the measured
 * one and then discarded. The file that was measured is the file that is played.
 *
 * If no engine can speak, the cues are withheld rather than scheduled on a
 * duration nothing will honour, and the reason is logged.
 */
object TrackPreparation {

    private const val ASSET = "sample_clip.track.json"

    fun prepare(context: Context, log: (String) -> Unit): PreparedTrack {
        val authored = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            .let(DescriptionTrackJson::decode)
        log(
            "track loaded asset=$ASSET source=${authored.sourceId} locale=${authored.locale} " +
                "cues=${authored.cues.size} gaps=${authored.gapMap.size} synthetic=${authored.synthetic}",
        )

        val synth = TtsSpeechSynth.select(
            context = context,
            outputDir = File(context.cacheDir, "speech"),
            preferredLocale = Locale.forLanguageTag(authored.locale),
            log = log,
        )
        if (synth == null) {
            log(
                "ERROR no text-to-speech engine reported a usable voice; " +
                    "withholding ${authored.cues.size} cues rather than scheduling audio that does not exist",
            )
            return PreparedTrack(authored.copy(cues = emptyList()), null)
        }

        val startedAtMs = System.currentTimeMillis()
        val measured = authored.copy(cues = authored.cues.map { measure(it, synth, log) })
        log("track prepared engine=${synth.engineId} inMs=${System.currentTimeMillis() - startedAtMs}")

        check(measured.cues.all { cue -> cue.variants.all { it.audioRef != null } }) {
            "a scheduled variant reached the director without pre-rendered audio"
        }
        return PreparedTrack(measured, synth)
    }

    private fun measure(cue: Cue, synth: SpeechSynth, log: (String) -> Unit): Cue {
        val variants = cue.variants.mapIndexed { index, variant ->
            val rendered = synth.render("${cue.id.value}-$index", variant.text)
            log(
                "variant measured cue=${cue.id} variant=$index engine=${synth.engineId} " +
                    "frames=${rendered.frameCount} rate=${rendered.sampleRateHz}Hz " +
                    "measuredMs=${rendered.duration.millis} authoredMs=${variant.measuredDuration.millis} " +
                    "file=${File(rendered.audioRef).name}",
            )
            Variant(text = variant.text, measuredDuration = rendered.duration, audioRef = rendered.audioRef)
        }

        // Longest first is a precondition of the cue, and re-measuring on a
        // different engine can reorder two variants that were close together.
        val ordered = variants.sortedByDescending { it.measuredDuration.millis }
        if (ordered != variants) log("variant order changed after measurement cue=${cue.id}")
        return cue.copy(variants = ordered)
    }
}
