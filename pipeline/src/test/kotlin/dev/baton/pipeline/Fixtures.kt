package dev.baton.pipeline

import dev.baton.director.AudioDirector
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.DirectorInputs
import dev.baton.director.DroppedCue
import dev.baton.director.Gap
import dev.baton.director.InputEvent
import dev.baton.director.Speaking
import dev.baton.pipeline.mock.RecordedDrafting
import dev.baton.pipeline.mock.RecordedTranscription
import dev.baton.pipeline.mock.SimulatedSynthesis

/**
 * The recorded clip every test in this module builds against: forty seconds of
 * Sintel with five lines of dialogue, chosen so that one window takes the first
 * draft, three have to converge, one can hold nothing at all, and one is too
 * short to ship.
 */
object Fixtures {

    const val TRANSCRIBE_JSON = "/fixtures/sintel-clip.transcribe.json"
    const val DRAFTS_JSON = "/fixtures/sintel-clip.drafts.json"
    const val MEDIA_DURATION_MS: Long = 40_000L

    val media = MediaRef("file:fixtures/sintel-clip.mp4")

    val request = BuildRequest(media = media, sourceId = "sintel-clip", mediaDurationMs = MEDIA_DURATION_MS)

    fun transcription(): Transcription = RecordedTranscription.fromResource(TRANSCRIBE_JSON)

    fun drafting(): Drafting = RecordedDrafting.fromResource(DRAFTS_JSON)

    fun builder(
        config: PipelineConfig = PipelineConfig(),
        drafting: Drafting = drafting(),
        synthesis: Synthesis = SimulatedSynthesis(),
    ): DescriptionTrackBuilder = DescriptionTrackBuilder(transcription(), drafting, synthesis, config)

    fun build(config: PipelineConfig = PipelineConfig()): BuildResult = builder(config).build(request)
}

/**
 * Forty seconds with two short lines in it and thirty seconds of silence between
 * them: the passage a describer would fill with several observations and the
 * builder used to answer with one sentence.
 *
 * Separate from [Fixtures] rather than folded into it, because the sintel clip is
 * carrying the opposite case. Every window there is short enough to hold exactly
 * one description, which is what makes it worth keeping unchanged -- it is the
 * regression evidence that packing a window did not disturb the windows that were
 * never packed.
 */
object LongGapFixtures {

    const val TRANSCRIBE_JSON = "/fixtures/long-gap-clip.transcribe.json"
    const val DRAFTS_JSON = "/fixtures/long-gap-clip.drafts.json"
    const val MEDIA_DURATION_MS: Long = 40_000L

    /** The thirty-second window, and the one that follows it. */
    val longGap: Gap = Gap(2_000, 32_000)
    val shortGap: Gap = Gap(34_000, 40_000)

    val media = MediaRef("file:fixtures/long-gap-clip.mp4")

    val request = BuildRequest(media = media, sourceId = "long-gap-clip", mediaDurationMs = MEDIA_DURATION_MS)

    fun builder(
        config: PipelineConfig = PipelineConfig(),
        drafting: Drafting = RecordedDrafting.fromResource(DRAFTS_JSON),
        synthesis: Synthesis = SimulatedSynthesis(),
    ): DescriptionTrackBuilder = DescriptionTrackBuilder(
        RecordedTranscription.fromResource(TRANSCRIBE_JSON),
        drafting,
        synthesis,
        config,
    )

    fun build(config: PipelineConfig = PipelineConfig()): BuildResult = builder(config).build(request)
}

/** A drafter that always returns the same text, however many times it is asked. */
class BrokenRecord(private val text: String) : Drafting {
    override fun draft(request: DraftRequest): Draft = Draft(text)
}

/** A drafter with nothing to say about anything. */
object SilentDrafter : Drafting {
    override fun draft(request: DraftRequest): Draft? = null
}

/**
 * A drafter working from a fixed script, one candidate per attempt, and with
 * nothing at all to say past the first cue of a window.
 *
 * The second half of that is what keeps a test about variant lengths from also
 * being a test about window packing: a script replayed at every slot would fill
 * the window with copies of itself, and the assertion would be reading the
 * builder's appetite for cues rather than its taste in variants.
 */
class ScriptedDrafter(private vararg val candidates: String) : Drafting {
    override fun draft(request: DraftRequest): Draft? = when {
        request.cueOrdinal > 0 -> null
        else -> candidates.getOrNull(request.attempt)?.let(::Draft)
    }
}

/** A drafter that records what it was asked, so the fit loop's conversation is visible. */
class RecordingDrafter(private val delegate: Drafting) : Drafting {
    val asks: MutableList<DraftRequest> = mutableListOf()

    override fun draft(request: DraftRequest): Draft? {
        asks += request
        return delegate.draft(request)
    }
}

/** What the runtime did with a track: which cues spoke, and which it refused. */
data class Playthrough(val spoken: List<CueId>, val drops: List<DroppedCue>)

/**
 * Play a track the way the device would -- tick the director across the whole
 * clip and see what comes out. This is the only assertion that proves the two
 * halves of the system actually agree, rather than agreeing about a number.
 *
 * The speaker reports, because the device's does. The floor is released by a
 * `SpeechEnded` event and by nothing else, so a harness that never sent one was
 * modelling the speaker that goes silent and never comes back -- every utterance
 * then squatted on the floor for a further `speechTimeoutSlackMs` before a
 * withdrawal prised it off. On a track with one cue per window nothing downstream
 * could tell; on a window packed with several, two seconds of phantom occupancy
 * per cue is enough to push the last one out of its slot, and the test would have
 * reported a builder fault that only the harness had.
 */
fun DescriptionTrack.playedThrough(durationMs: Long, stepMs: Long = 100L): Playthrough {
    val director = AudioDirector(this)
    val spoken = mutableListOf<CueId>()
    val drops = mutableListOf<DroppedCue>()
    var last: Speaking.ScheduledUtterance? = null

    var positionMs = 0L
    while (positionMs <= durationMs) {
        val finished = last?.takeIf { positionMs >= it.endsAtPositionMs }
        val events = finished?.let { listOf(InputEvent.SpeechEnded(it.id)) } ?: emptyList()

        val directive = director.advance(positionMs, DirectorInputs(positionMs, events = events))
        drops += directive.drops
        val utterance = directive.speaking as? Speaking.ScheduledUtterance
        if (utterance != null && (last == null || last.id != utterance.id || last.startedAtMs != utterance.startedAtMs)) {
            spoken += utterance.cueId
        }
        last = utterance
        positionMs += stepMs
    }
    return Playthrough(spoken, drops)
}
