package dev.baton.director

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.SerializationException

/**
 * The wire format is the contract between the offline pipeline and the runtime,
 * so what is asserted here is that a track survives the trip unchanged and that a
 * track which would violate the model's invariants is rejected at the door rather
 * than deep inside playback.
 */
class TrackJsonTest : FunSpec({

    val track = DescriptionTrack(
        formatVersion = DescriptionTrack.CURRENT_FORMAT_VERSION,
        sourceId = "sintel-clip",
        locale = "en-US",
        gapMap = listOf(Gap(0, 6_000), Gap(13_000, 16_000)),
        cues = listOf(
            cue(
                "ad-0",
                150,
                variants = listOf(
                    variant("A lone figure crosses the snowfield.", 3_000, audioRef = "s3://baton/ad-0-0.pcm"),
                    variant("A figure crosses the snow.", 1_800),
                ),
            ),
            cue(
                "ad-13000",
                13_150,
                kind = UtteranceKind.Scheduled.SPOKEN_SUBTITLE,
                variants = listOf(variant("Where is the dragon?", 1_200)),
            ),
        ),
    )

    test("a track survives the round trip unchanged") {
        DescriptionTrackJson.decode(DescriptionTrackJson.encode(track)) shouldBe track
    }

    test("a restored duration is equal to the measurement it came from") {
        val restored = DescriptionTrackJson.decode(DescriptionTrackJson.encode(track))
        restored.cues.first().variants.first().measuredDuration shouldBe measuredMs(3_000)
    }

    test("durations are written as plain milliseconds, so the format is readable by hand") {
        DescriptionTrackJson.encode(track) shouldContain "\"measuredDurationMs\": 3000"
    }

    test("the synthetic marker is always written, because the app has to announce it") {
        DescriptionTrackJson.encode(track) shouldContain "\"synthetic\": true"
    }

    test("a synthetic dub is optional: absent from a track without one, and round trips on a track with one") {
        DescriptionTrackJson.encode(track) shouldNotContain "syntheticDub"
        val dubbed = track.copy(syntheticDub = "The German dub in this title uses synthetic voices (Amazon Polly).")
        DescriptionTrackJson.decode(DescriptionTrackJson.encode(dubbed)) shouldBe dubbed
    }

    test("an empty track round trips") {
        val empty = DescriptionTrack.empty("nothing", "en-US")
        DescriptionTrackJson.decode(DescriptionTrackJson.encode(empty)) shouldBe empty
    }

    test("a track from the future is refused rather than half understood") {
        val future = DescriptionTrackJson.encode(track).replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        shouldThrow<IllegalArgumentException> { DescriptionTrackJson.decode(future) }
            .message shouldContain "format version 99"
    }

    test("an unknown key is a format disagreement, not something to skip over") {
        val extra = DescriptionTrackJson.encode(track).replaceFirst("{", "{\n  \"loudnessLufs\": -23,")
        shouldThrow<SerializationException> { DescriptionTrackJson.decode(extra) }
    }

    test("a cue kind the runtime cannot schedule is refused by name") {
        val bad = DescriptionTrackJson.encode(track).replace("AUDIO_DESCRIPTION", "VQA_ANSWER")
        shouldThrow<IllegalArgumentException> { DescriptionTrackJson.decode(bad) }
            .message shouldContain "VQA_ANSWER"
    }

    test("model invariants are enforced on decode, not just on construction") {
        val outOfOrder = DescriptionTrackJson.encode(track)
            .replace("\"measuredDurationMs\": 3000", "\"measuredDurationMs\": 900")
        shouldThrow<IllegalArgumentException> { DescriptionTrackJson.decode(outOfOrder) }
            .message shouldContain "longest first"
    }

    test("a negative duration cannot be smuggled in through the round-trip door") {
        val negative = DescriptionTrackJson.encode(track).replace("\"measuredDurationMs\": 3000", "\"measuredDurationMs\": -1")
        shouldThrow<IllegalArgumentException> { DescriptionTrackJson.decode(negative) }
    }
})
