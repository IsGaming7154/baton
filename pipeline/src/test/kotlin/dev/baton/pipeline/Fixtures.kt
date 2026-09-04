package dev.baton.pipeline

import dev.baton.director.AudioDirector
import dev.baton.director.CueId
import dev.baton.director.DescriptionTrack
import dev.baton.director.DirectorInputs
import dev.baton.director.DroppedCue
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

/** A drafter that always returns the same text, however many times it is asked. */
class BrokenRecord(private val text: String) : Drafting {
    override fun draft(request: DraftRequest): Draft = Draft(text)
}

/** A drafter with nothing to say about anything. */
object SilentDrafter : Drafting {
    override fun draft(request: DraftRequest): Draft? = null
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
 */
fun DescriptionTrack.playedThrough(durationMs: Long, stepMs: Long = 100L): Playthrough {
    val director = AudioDirector(this)
    val spoken = mutableListOf<CueId>()
    val drops = mutableListOf<DroppedCue>()
    var last: Speaking.ScheduledUtterance? = null

    var positionMs = 0L
    while (positionMs <= durationMs) {
        val directive = director.advance(positionMs, DirectorInputs(positionMs))
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
