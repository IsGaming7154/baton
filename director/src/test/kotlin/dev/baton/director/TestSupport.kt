package dev.baton.director

/**
 * A measured duration, the way the pipeline would obtain one: by counting frames
 * of rendered audio. 48 frames per millisecond at 48 kHz, so the arithmetic is
 * exact and the tests can still talk in milliseconds.
 */
fun measuredMs(millis: Long): MeasuredDurationMs =
    MeasuredDurationMs.ofRenderedAudio(frameCount = millis * 48L, sampleRateHz = 48_000)

fun variant(text: String, millis: Long, audioRef: String? = null): Variant =
    Variant(text = text, measuredDuration = measuredMs(millis), audioRef = audioRef)

fun cue(
    id: String,
    startMs: Long,
    kind: UtteranceKind.Scheduled = UtteranceKind.Scheduled.AUDIO_DESCRIPTION,
    variants: List<Variant>,
): Cue = Cue(id = CueId(id), startMs = startMs, kind = kind, variants = variants)

fun trackOf(gaps: List<Gap> = emptyList(), cues: List<Cue> = emptyList()): DescriptionTrack =
    DescriptionTrack(
        formatVersion = DescriptionTrack.CURRENT_FORMAT_VERSION,
        sourceId = "test-fixture",
        locale = "en-US",
        gapMap = gaps,
        cues = cues,
    )

fun uiNarration(id: String, text: String = "Play", millis: Long? = null): ReactiveRequest =
    ReactiveRequest(
        id = UtteranceId(id),
        kind = UtteranceKind.Reactive.UI_NARRATION,
        text = text,
        length = millis?.let { SpeechLength.Measured(measuredMs(it)) } ?: SpeechLength.UntilSpeakerReports,
    )

fun vqaAnswer(id: String, text: String = "A dragon fills the doorway.", millis: Long? = null): ReactiveRequest =
    ReactiveRequest(
        id = UtteranceId(id),
        kind = UtteranceKind.Reactive.VQA_ANSWER,
        text = text,
        length = millis?.let { SpeechLength.Measured(measuredMs(it)) } ?: SpeechLength.UntilSpeakerReports,
    )

/**
 * Drives an [AudioDirector] the way the player would: a wall clock, a content
 * position that only moves while playing, and a level playback state.
 *
 * The tests below only ever look at the [Directive] values this returns. Nothing
 * here reaches into the director.
 */
class Session(
    val track: DescriptionTrack,
    val config: DirectorConfig = DirectorConfig(),
) {
    private val director = AudioDirector(track, config)

    var nowMs: Long = 0L
        private set

    var positionMs: Long = 0L
        private set

    var playback: Playback = Playback.PLAYING
        private set

    val history: MutableList<Directive> = mutableListOf()

    /** Advance both clocks by [stepMs] (the content only if it is playing) and tick. */
    fun tick(stepMs: Long = DEFAULT_STEP_MS, events: List<InputEvent> = emptyList()): Directive {
        require(stepMs >= 0)
        nowMs += stepMs
        if (playback == Playback.PLAYING) positionMs += stepMs
        return advance(events)
    }

    /** Tick repeatedly until the content position reaches [targetPositionMs]. */
    fun playTo(targetPositionMs: Long, stepMs: Long = DEFAULT_STEP_MS): Directive {
        check(playback == Playback.PLAYING) { "playTo requires playback" }
        var last: Directive? = null
        while (positionMs < targetPositionMs) {
            last = tick(minOf(stepMs, targetPositionMs - positionMs))
        }
        return last ?: advance()
    }

    /** A button press, delivered on a zero-length tick so the timing stays exact. */
    fun press(request: ReactiveRequest): Directive =
        advance(listOf(InputEvent.ReactiveRequested(request)))

    fun speakerReports(id: UtteranceId): Directive = advance(listOf(InputEvent.SpeechEnded(id)))

    fun pause(): Directive {
        playback = Playback.PAUSED
        return advance()
    }

    fun resume(): Directive {
        playback = Playback.PLAYING
        return advance()
    }

    fun seekTo(targetPositionMs: Long): Directive {
        positionMs = targetPositionMs
        return advance(listOf(InputEvent.Seeked))
    }

    private fun advance(events: List<InputEvent> = emptyList()): Directive {
        val directive = director.advance(nowMs, DirectorInputs(positionMs, playback, events))
        history += directive
        return directive
    }

    companion object {
        const val DEFAULT_STEP_MS: Long = 100L
    }
}

/** Every cue the director actually started, in the order it started them. */
fun List<Directive>.spokenCueIds(): List<CueId> =
    mapNotNull { (it.speaking as? Speaking.ScheduledUtterance) }
        .fold(mutableListOf<Speaking.ScheduledUtterance>()) { acc, utterance ->
            val previous = acc.lastOrNull()
            if (previous == null || previous.id != utterance.id || previous.startedAtMs != utterance.startedAtMs) {
                acc += utterance
            }
            acc
        }
        .map { it.cueId }

fun List<Directive>.allDrops(): List<DroppedCue> = flatMap { it.drops }
