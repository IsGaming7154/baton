package dev.baton.tv

import dev.baton.director.DescriptionTrack
import dev.baton.director.DescriptionTrackJson
import dev.baton.director.DirectorConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
 * The headroom check is the load-bearing one. Every duration in the file was
 * measured off a desktop synthesizer at authoring time and every one of them is
 * replaced on the device by a measurement taken from the engine that will
 * actually speak. Those engines are slower: the two on the target hardware differ
 * from each other by 25% on the same sentence. So the windows have to hold the
 * line at a rate well below the one it was authored at, or the tracer will drop
 * cues on the device that fit perfectly here.
 */
class BundledTrackTest : FunSpec({

    val track = load()

    test("the bundled track parses with the shipping codec") {
        track.formatVersion shouldBe DescriptionTrack.CURRENT_FORMAT_VERSION
        track.sourceId shouldBe "sample-clip"
        track.synthetic shouldBe true
        track.cues shouldHaveSize 3
        track.gapMap shouldHaveSize 3
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
