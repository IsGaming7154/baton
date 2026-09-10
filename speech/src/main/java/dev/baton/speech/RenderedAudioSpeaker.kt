package dev.baton.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.baton.director.Speaking
import dev.baton.director.UtteranceId
import dev.baton.ports.Speaker
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays a rendered WAV through an [AudioTrack] and reports when the last frame
 * has actually left for the speaker.
 *
 * ## MODE_STATIC, not a stream
 *
 * The whole file is already read into memory before anything is handed to the
 * track ([WavAudio.readPcm]), so there is nothing to gain by feeding it out in
 * periodic chunks and a real risk in doing so: a chunked writer competes for the
 * CPU with whatever else is running -- ExoPlayer decoding the film concurrently,
 * on a 1 GB device -- and a writer that loses that race for even one chunk
 * starves the track mid-utterance. `MODE_STATIC` loads the entire buffer before
 * `play()` is ever called, so there is no periodic feed left to starve: an
 * underrun here is not merely unlikely, it is structurally impossible.
 *
 * ## Why the end is observed rather than timed
 *
 * The director releases the floor on this report, so it has to mean "the audio
 * stopped being audible" and not "the length we computed has elapsed". Those
 * differ exactly when it matters: a stall on a 1 GB device leaves audio playing
 * long after its nominal duration, and a report on a timer would hand the floor
 * to the next voice while the current one is still in the room.
 *
 * So the end is taken from [AudioTrack.getPlaybackHeadPosition], which counts
 * frames the hardware has consumed. Under `MODE_STREAM` this needed a tail of
 * silence behind the real frames, so that the track was never left running with
 * nothing queued -- an underrun state some implementations reset the head
 * position on, which would have made the very signal this class waits for
 * disappear at the moment it mattered. `MODE_STATIC` has no such state: the
 * buffer that was loaded is the buffer that plays, start to finish, and the head
 * position simply holds at the frame count once it gets there. No tail needed.
 *
 * ## Being cut off is a first-class ending
 *
 * The other way an utterance ends is that the director takes the floor back --
 * because the silence it was speaking into has run out, or because this class
 * stopped reporting. `stop(id)` is that instruction, and it is honoured by
 * bumping the generation, which both ends the poll loop and cancels a job the
 * executor has not reached yet. Nothing is reported through `onSpeechEnded` on
 * that path: the caller decided it, so telling it back would be an echo, and the
 * poll loop exits before the report for exactly that reason.
 *
 * ## No audio focus, deliberately
 *
 * Requesting focus would ask the platform to duck the content, and the content
 * is our own ExoPlayer, which ducks itself sample-accurately in its render chain.
 * Asking twice would either double the attenuation or fight it.
 */
class RenderedAudioSpeaker(
    private val log: (String) -> Unit,
) : Speaker {

    override var onSpeechEnded: ((UtteranceId) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "baton-speaker").apply { priority = Thread.MAX_PRIORITY }
    }

    /** Bumped on every speak and stop, so a late writer knows it has been superseded. */
    private val generation = AtomicLong(0)

    /**
     * What the last accepted [speak] was for, so that an addressed [stop] can tell
     * whether it is about the utterance in the room or one already gone.
     *
     * Set on the caller's thread and not on the executor, because the caller can
     * withdraw an utterance in the same turn it started one -- before the worker
     * has picked the job up -- and a field the worker owned would still be empty
     * at that point, which would read as "nothing of that name is playing" and let
     * the withdrawal fall on the floor.
     */
    @Volatile
    private var speaking: UtteranceId? = null

    @Volatile
    private var track: AudioTrack? = null

    override fun speak(utterance: Speaking.Utterance) {
        val audioRef = utterance.audioRef
        if (audioRef == null) {
            log("speech refused id=${utterance.id} reason=no-rendered-audio")
            return
        }
        speaking = utterance.id
        val mine = generation.incrementAndGet()
        executor.execute {
            runCatching { play(mine, utterance.id, File(audioRef)) }
                .onFailure { log("speech failed id=${utterance.id} error=$it") }
        }
    }

    override fun stop(id: UtteranceId) {
        if (speaking != id) return
        speaking = null
        silence()
    }

    override fun release() {
        speaking = null
        silence()
        executor.shutdown()
    }

    /**
     * Cut whatever this speaker is doing, unconditionally.
     *
     * The generation bump is what actually stops the audio: it ends the poll loop
     * in [play] and tells a job that has not started yet that it has been
     * superseded, so an utterance withdrawn between `speak` and its first frame
     * never becomes audible at all.
     */
    private fun silence() {
        generation.incrementAndGet()
        executor.execute { discardTrack() }
    }

    private fun play(mine: Long, id: UtteranceId, file: File) {
        if (generation.get() != mine) return
        discardTrack()

        val wav = runCatching { WavAudio.read(file) }.getOrElse {
            log("speech failed id=$id file=${file.name} error=$it")
            return
        }
        if (wav.bitsPerSample != 16) {
            log("speech refused id=$id reason=${wav.bitsPerSample}-bit-pcm")
            return
        }
        val pcm = runCatching { wav.readPcm(file) }.getOrElse {
            log("speech failed id=$id file=${file.name} error=$it")
            return
        }
        if (pcm.isEmpty()) {
            log("speech refused id=$id reason=empty-pcm")
            return
        }

        val channelMask =
            if (wav.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wav.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = audioTrack

        if (generation.get() != mine) {
            discardTrack()
            return
        }

        var offset = 0
        while (offset < pcm.size) {
            val written = audioTrack.write(pcm, offset, pcm.size - offset)
            if (written <= 0) {
                log("speech failed id=$id reason=write-returned-$written")
                discardTrack()
                return
            }
            offset += written
        }

        if (generation.get() != mine) {
            discardTrack()
            return
        }

        val startedAtMs = System.currentTimeMillis()
        audioTrack.play()
        log(
            "speech started id=$id file=${file.name} frames=${wav.frameCount} " +
                "rate=${wav.sampleRateHz} channels=${wav.channelCount}",
        )

        val target = wav.frameCount.toInt()
        while (generation.get() == mine) {
            val head = audioTrack.playbackHeadPosition
            if (head >= target) break
            Thread.sleep(POLL_MS)
        }

        log("speech underrunCount id=$id count=${runCatching { audioTrack.underrunCount }.getOrDefault(-1)}")
        if (generation.get() != mine) {
            discardTrack()
            return
        }
        discardTrack()
        // Cleared before the report and not after it, so that a stop() arriving
        // for this id between the two finds nothing to silence -- which is the
        // truth: the last frame has already gone.
        speaking = null
        log("speech ended id=$id afterMs=${System.currentTimeMillis() - startedAtMs}")
        onSpeechEnded?.invoke(id)
    }

    private fun discardTrack() {
        val current = track ?: return
        track = null
        runCatching {
            if (current.state == AudioTrack.STATE_INITIALIZED) {
                current.pause()
                current.flush()
                current.stop()
            }
        }
        runCatching { current.release() }
    }

    private companion object {
        const val POLL_MS = 10L
    }
}
