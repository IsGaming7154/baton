package dev.baton.tv

import dev.baton.director.Cue
import dev.baton.director.CueId
import dev.baton.director.MeasuredDurationMs
import dev.baton.director.UtteranceKind.Scheduled
import dev.baton.director.Variant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The descriptions toggle's filter, the one pure piece of it.
 *
 * Off has to mean absent rather than muted: a disabled kind never reaches the
 * director, so it never books a window, and the surviving kinds are checked here
 * rather than on the device because that is the property a listener depends on --
 * `--ez spokenSubtitles false` is worth nothing if it also silences the
 * descriptions.
 */
class DescriptionsToggleTest : FunSpec({

    val description = cue("d1", Scheduled.AUDIO_DESCRIPTION, 3_000)
    val subtitle = cue("s1", Scheduled.SPOKEN_SUBTITLE, 12_000)
    val cues = listOf(description, subtitle)

    test("descriptions on leaves the cue list untouched") {
        TrackPreparation.descriptionsWhenEnabled(cues, enabled = true) shouldBe cues
    }

    test("descriptions off removes only descriptions") {
        TrackPreparation.descriptionsWhenEnabled(cues, enabled = false) shouldBe listOf(subtitle)
    }

    test("descriptions off leaves an empty list empty") {
        TrackPreparation.descriptionsWhenEnabled(emptyList<Cue>(), enabled = false) shouldBe emptyList()
    }
})

private fun cue(id: String, kind: Scheduled, startMs: Long) = Cue(
    id = CueId(id),
    startMs = startMs,
    kind = kind,
    variants = listOf(
        Variant(
            text = "a line of speech",
            measuredDuration = MeasuredDurationMs.ofRenderedAudio(frameCount = 48_000, sampleRateHz = 48_000),
        ),
    ),
)
