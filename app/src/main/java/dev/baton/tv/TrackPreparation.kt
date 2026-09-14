package dev.baton.tv

import android.content.Context
import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.SubtitleLine
import dev.baton.director.UtteranceKind.Scheduled
import dev.baton.director.Variant
import dev.baton.director.parseSubtitles
import dev.baton.ports.SpeechSynth
import dev.baton.speech.TtsSpeechSynth
import java.io.File
import java.util.Locale

/**
 * A track the director may act on, the disclosure sentence it may owe, the transport
 * bar's phrases keyed by their text, and the synthesizer whose audio all of them were
 * measured from.
 */
class PreparedTrack(
    val track: DescriptionTrack,
    val synth: SpeechSynth?,
    val disclosure: Variant?,
    val narration: Map<String, Variant>,
)

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
 * ## Spoken subtitles
 *
 * The clip's English SubRip sidecar is parsed alongside the asset (see
 * `SubtitleFile.kt`), each line is booked into a silence by [SubtitlePlacement],
 * and each booked line is rendered and measured through the very same path as a
 * description variant -- same engine, same file-naming, same frames-to-duration
 * derivation -- because the director admits a subtitle on its length exactly as
 * it admits a description, and a length measured anywhere else is not evidence
 * there either.
 *
 * ## Toggles
 *
 * Both kinds can be turned off at launch, independently, and off means absent:
 * a disabled kind is filtered out before a line of it is rendered or measured,
 * so the arbitration never sees it and a window the disabled kind would have
 * booked stays open. This is a real toggle, not a mute.
 *
 * If no engine can speak, the cues are withheld rather than scheduled on a
 * duration nothing will honour, and the reason is logged.
 */
object TrackPreparation {

    private const val ASSET = "sample_clip.track.json"

    /**
     * The pure half of the descriptions toggle: what is left of a cue list once
     * [enabled] has been applied. Outside `prepare` so it can be tested without
     * a context, and applied there before anything is rendered, so that off
     * also means unrendered.
     */
    fun descriptionsWhenEnabled(cues: List<Cue>, enabled: Boolean): List<Cue> =
        if (enabled) cues else cues.filter { it.kind != Scheduled.AUDIO_DESCRIPTION }

    fun prepare(
        context: Context,
        descriptions: Boolean,
        spokenSubtitles: Boolean,
        log: (String) -> Unit,
    ): PreparedTrack {
        log("toggles descriptions=$descriptions spokenSubtitles=$spokenSubtitles")

        val authored = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            .let(DescriptionTrackJson::decode)
        log(
            "track loaded asset=$ASSET source=${authored.sourceId} locale=${authored.locale} " +
                "cues=${authored.cues.size} gaps=${authored.gapMap.size} synthetic=${authored.synthetic}",
        )

        val subtitleLines = context.resources.openRawResource(R.raw.sample_clip_en)
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .let(::parseSubtitles)

        val synth = TtsSpeechSynth.select(
            context = context,
            outputDir = File(context.cacheDir, "speech"),
            preferredLocale = Locale.forLanguageTag(authored.locale),
            log = log,
        )
        if (synth == null) {
            log(
                "ERROR no text-to-speech engine reported a usable voice; " +
                    "withholding ${authored.cues.size} cues and ${subtitleLines.size} subtitle lines " +
                    "rather than scheduling audio that does not exist",
            )
            return PreparedTrack(authored.copy(cues = emptyList()), null, null, emptyMap())
        }

        val startedAtMs = System.currentTimeMillis()
        val descriptionCues = descriptionsWhenEnabled(authored.cues, descriptions).map { measure(it, synth, log) }
        val subtitleCues = if (spokenSubtitles) {
            buildSubtitleCues(subtitleLines, authored, synth, log)
        } else {
            emptyList()
        }
        val disclosure = measureDisclosure(synth, log)
        val narration = measureNarration(synth, log)

        // Concatenated, not sorted: the director's validation asks only that ids
        // be unique, and both its linear cue walk and the rooms it derives treat
        // the list as a set of starts rather than a sequence.
        val cues = descriptionCues + subtitleCues
        log(
            "track prepared engine=${synth.engineId} inMs=${System.currentTimeMillis() - startedAtMs} " +
                "descriptions=${descriptionCues.size} spokenSubtitles=${subtitleCues.size}",
        )

        check(cues.all { cue -> cue.variants.all { it.audioRef != null } }) {
            "a scheduled variant reached the director without pre-rendered audio"
        }
        return PreparedTrack(authored.copy(cues = cues), synth, disclosure, narration)
    }

    /**
     * The transport bar's phrases ([TransportNarration.PHRASES]), keyed by their text.
     *
     * Rendered and measured here for the reasons the disclosure is: the speaker plays
     * only rendered files, and a reactive utterance with a measured length is held to
     * that length plus the slack rather than to the fifteen-second ceiling for speech
     * nothing measured. A key with no dash cannot collide with a cue's
     * "<cue>-<variant>" file.
     */
    private fun measureNarration(synth: SpeechSynth, log: (String) -> Unit): Map<String, Variant> =
        TransportNarration.PHRASES.withIndex().associate { (index, phrase) ->
            val rendered = synth.render("narration$index", phrase)
            log(
                "narration measured phrase=\"$phrase\" engine=${synth.engineId} frames=${rendered.frameCount} " +
                    "rate=${rendered.sampleRateHz}Hz measuredMs=${rendered.duration.millis} " +
                    "file=${File(rendered.audioRef).name}",
            )
            phrase to Variant(text = phrase, measuredDuration = rendered.duration, audioRef = rendered.audioRef)
        }

    /**
     * One [Cue] per subtitle line: a `SPOKEN_SUBTITLE` booking with a single
     * variant, the line's own text, rendered and measured here so the cue is
     * built from a duration that exists. Ids are `s1`...`sN` in file order; the
     * asset's description ids are checked against them and a collision fails
     * here rather than three layers into playback, where `DescriptionTrack`
     * would otherwise reject the merged track with nothing naming why.
     */
    private fun buildSubtitleCues(
        lines: List<SubtitleLine>,
        authored: DescriptionTrack,
        synth: SpeechSynth,
        log: (String) -> Unit,
    ): List<Cue> {
        val ids = lines.indices.map { CueId("s${it + 1}") }
        val collision = authored.cues.map { it.id }.firstOrNull { id -> id in ids }
        require(collision == null) {
            "cue id $collision is used by both the bundled track and a spoken subtitle; " +
                "subtitle ids must not collide with the asset's"
        }

        val starts = SubtitlePlacement.startsFor(lines, authored.silence)
        val cues = lines.mapIndexed { index, line ->
            val id = ids[index]
            // Cue files are named "<key>", and a description renders as
            // "d<i>-<j>"; the s prefix cannot collide with that, so this line's
            // file is its own.
            val rendered = synth.render("${id.value}-0", line.text)
            log(
                "subtitle measured cue=$id lineStartMs=${line.startMs} lineEndMs=${line.endMs} " +
                    "placedMs=${starts[index]} engine=${synth.engineId} frames=${rendered.frameCount} " +
                    "rate=${rendered.sampleRateHz}Hz measuredMs=${rendered.duration.millis} " +
                    "file=${File(rendered.audioRef).name}",
            )
            Cue(
                id = id,
                startMs = starts[index],
                kind = Scheduled.SPOKEN_SUBTITLE,
                variants = listOf(Variant(line.text, rendered.duration, rendered.audioRef)),
            )
        }
        log("subtitles prepared lines=${lines.size} cues=${cues.size}")
        return cues
    }

    /**
     * The sentence that tells the viewer the speech is synthetic.
     *
     * Rendered to a file and measured off its frames, on the same engine and through
     * the same path as every cue, for the reason they are: the director admits it
     * into a silence on its length, and a length measured anywhere else is not
     * evidence. It is also the file that gets played.
     *
     * Rendered whatever the track says about itself. Whether the session owes it is
     * the director's decision, taken from `synthetic`; rendering it only for
     * synthetic tracks would be taking that decision a second time, here, where the
     * two could come to disagree. One render is the whole cost.
     */
    private fun measureDisclosure(synth: SpeechSynth, log: (String) -> Unit): Variant {
        // Cue files are named "<cue>-<variant>", so this key cannot collide with one.
        val rendered = synth.render("disclosure", DISCLOSURE_TEXT)
        log(
            "disclosure measured engine=${synth.engineId} frames=${rendered.frameCount} " +
                "rate=${rendered.sampleRateHz}Hz measuredMs=${rendered.duration.millis} " +
                "file=${File(rendered.audioRef).name}",
        )
        return Variant(text = DISCLOSURE_TEXT, measuredDuration = rendered.duration, audioRef = rendered.audioRef)
    }

    /**
     * The project's own wording. The guidance PRD.md cites requires that the
     * audience be told the speech is synthetic; the repository quotes no sentence or
     * timing from it, so this one is chosen to be plain and short -- short, because
     * the director can only admit it into a silence at least this long plus the
     * guard, and a longer sentence reaches the viewer later.
     *
     * ponytail: English only, because the only bundled track is en-US and the
     * sentence is spoken in that track's voice. Choose the wording by track locale
     * when a track in another language ships.
     *
     * Public because `SyntheticVoiceNotice` shows this same sentence on screen, so what
     * is said and what is shown cannot drift apart.
     */
    const val DISCLOSURE_TEXT = "Descriptions in this title use a synthetic voice."

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
