package dev.baton.tv

import dev.baton.director.DescriptionTrack
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.parseSubtitles
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The bundled spoken subtitles, booked exactly the way start-up books them.
 *
 * `SubtitlePlacement.startsFor` runs at start-up over the bundled `.srt` and the
 * bundled track's gap map, and the starts it returns are what the director is
 * asked to admit. The rule allows exactly two outcomes per line: a start inside
 * a silence window, or the line's own end as a fallback that the director will
 * drop and report. A start anywhere else would be a booking the director could
 * neither speak from nor account for, so the rule is checked here against the
 * two files that actually ship.
 *
 * The gap map is the truthful silence map, so it keeps the beats between two
 * lines -- which is exactly where a spoken subtitle wants to start.
 */
class BundledSubtitlesTest : FunSpec({

    val track = loadTrack()
    val lines = parseSubtitles(loadSrt())
    val starts = SubtitlePlacement.startsFor(lines, track.silence)

    test("every placed start is inside a silence window or exactly the line's end") {
        (starts.size == lines.size) shouldBe true
        lines.forEachIndexed { index, line ->
            val start = starts[index]
            withClue("line ${index + 1} '${line.text}' placed at $start") {
                (track.silence.windowEndAt(start) != null || start == line.endMs) shouldBe true
            }
        }
    }

    test("ten of the twelve lines land inside a silence window") {
        // This count characterises this fixture for the demo; it is not a
        // floor. A change to the clip, the subtitles or the gap map is
        // expected to move it.
        val inSilence = lines.indices.count { track.silence.windowEndAt(starts[it]) != null }
        inSilence shouldBe 10
    }
})

private fun loadTrack(): DescriptionTrack {
    val asset = sequenceOf(
        File("src/main/assets/sample_clip.track.json"),
        File("app/src/main/assets/sample_clip.track.json"),
    ).firstOrNull { it.isFile } ?: error("bundled track asset not found from ${File(".").absolutePath}")
    return DescriptionTrackJson.decode(asset.readText())
}

private fun loadSrt(): String {
    val srt = sequenceOf(
        File("src/main/res/raw/sample_clip_en.srt"),
        File("app/src/main/res/raw/sample_clip_en.srt"),
    ).firstOrNull { it.isFile } ?: error("bundled subtitle file not found from ${File(".").absolutePath}")
    return srt.readText()
}
