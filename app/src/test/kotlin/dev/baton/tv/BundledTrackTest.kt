package dev.baton.tv

import dev.baton.director.DescriptionTrack
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.DirectorConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * The bundled track, checked here rather than on the device.
 *
 * An authoring mistake in `assets/sample_clip.track.json` -- a cue written over
 * dialogue, a window too short for the line in it -- produces a silent app and a
 * puzzling log, and only on hardware. It is cheaper to find here.
 *
 * The headroom check is the load-bearing one. Every `measuredDurationMs` in the
 * file is not a measurement at all: it is `150 + words * (60000 / 165)`, a
 * 165-words-per-minute speaking-rate estimate with 150 ms of lead, and every
 * value in the file fits that formula exactly. It is replaced on the device by a
 * measurement taken from the engine that will actually speak, and those engines
 * are slower -- the two on the target hardware differ from each other by 25% on
 * the same sentence, and either one can differ from a 165-wpm guess by more than
 * that. So the windows have to hold the line at a rate well below the one the
 * formula assumes, or the tracer will drop cues on the device that fit perfectly
 * here.
 */
class BundledTrackTest : FunSpec({

    val track = load()

    test("the bundled track parses with the shipping codec") {
        track.formatVersion shouldBe DescriptionTrack.CURRENT_FORMAT_VERSION
        track.locale shouldBe "en-US"
        track.synthetic shouldBe true

        // The track is for one specific 89.4 s excerpt -- Tears of Steel from
        // 05:24.000 -- and every timestamp in it is only meaningful against that
        // excerpt. Dropping in a track built for different footage is the mistake
        // this catches, and it is otherwise invisible: the wrong track parses,
        // schedules, and speaks, just over the wrong dialogue.
        track.sourceId shouldBe "tears-of-steel-05m24s"

        // A floor, not the number that happens to be in the file. Counting what
        // is there would pass whatever ships. What has to be true is that there
        // is enough here to exercise the director at all: several cues, spread
        // over the clip, in windows of different sizes, so that choosing a window
        // and choosing a variant are decisions and not foregone conclusions.
        (track.cues.size >= 5) shouldBe true

        // The map is the film's schedulable silence, not the list of slots the
        // cues occupy. Windows with nothing scheduled in them are the evidence
        // that it was derived from the dialogue rather than fitted to the script
        // -- and they are what the runtime falls back on when a cue is late.
        val occupied = track.cues.mapNotNull { track.silence.windowEndAt(it.startMs) }.toSet()
        (track.gapMap.any { it.endMs !in occupied }) shouldBe true

        // Restates GapMapConfig.minimumUsefulGapMs; :app does not depend on
        // :pipeline, so the number cannot be imported. A window below it is one
        // the pipeline would have discarded and nothing can be placed in.
        track.gapMap.forEach { withClue(it) { (it.durationMs >= 1_500) shouldBe true } }

        // SilenceWindows sorts the map and merges anything overlapping or
        // touching. If it had work to do, the hand-authored list was malformed --
        // and windowEndAt, which every other test here leans on, would be
        // answering about a window the file does not actually contain.
        track.silence.windows shouldBe track.gapMap
    }

    test("every cue starts inside a silence window") {
        track.cues.forEach { cue ->
            withClue(cue.id) { track.silence.windowEndAt(cue.startMs) shouldNotBe null }
        }
    }

    test("every cue's longest variant fits its window at the rate it was authored") {
        track.cues.forEach { cue ->
            val windowEndMs = track.silence.windowEndAt(cue.startMs)!!
            val availableMs = windowEndMs - cue.startMs
            val longestMs = cue.variants.first().measuredDuration.millis
            withClue("${cue.id}: $longestMs ms of speech into $availableMs ms of silence") {
                (longestMs + DirectorConfig.DEFAULT_GUARD_MS <= availableMs) shouldBe true
            }
        }
    }

    test("every cue still fits if the device engine is half as fast again") {
        track.cues.forEach { cue ->
            val windowEndMs = track.silence.windowEndAt(cue.startMs)!!
            val availableMs = windowEndMs - cue.startMs
            val slowestMs = cue.variants.first().measuredDuration.millis * 3 / 2
            withClue("${cue.id}: $slowestMs ms at 1.5x into $availableMs ms of silence") {
                (slowestMs + DirectorConfig.DEFAULT_GUARD_MS <= availableMs) shouldBe true
            }
        }
    }

    test("variants are ordered longest first and there is a shorter one to fall back to") {
        track.cues.forEach { cue ->
            withClue(cue.id) {
                (cue.variants.size >= 2) shouldBe true
                cue.variants shouldBe cue.variants.sortedByDescending { it.measuredDuration.millis }
            }
        }
    }

    test("no cue ships pre-rendered audio, because the device has to measure its own") {
        track.cues.flatMap { it.variants }.forEach { it.audioRef shouldBe null }
    }
})

private fun load(): DescriptionTrack {
    val asset = sequenceOf(
        File("src/main/assets/sample_clip.track.json"),
        File("app/src/main/assets/sample_clip.track.json"),
    ).firstOrNull { it.isFile } ?: error("bundled track asset not found from ${File(".").absolutePath}")
    return DescriptionTrackJson.decode(asset.readText())
}
