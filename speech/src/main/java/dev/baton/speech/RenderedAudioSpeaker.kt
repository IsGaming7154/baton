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
 * ## Why the end is observed rather than timed
 *
 * The director releases the floor on this report, so it has to mean "the audio
 * stopped being audible" and not "the length we computed has elapsed". Those
 * differ exactly when it matters: a stall on a 1 GB device leaves audio playing
 * long after its nominal duration, and a report on a timer would hand the floor
 * to the next voice while the current one is still in the room.
 *
 * So the end is taken from [AudioTrack.getPlaybackHeadPosition], which counts
 * frames the hardware has consumed. A tail of silence is appended so that the
 * real frames drain while the track is still running -- the head position is
 * documented to reset on `stop()`, and a signal that resets at the moment you
 * want to read it is not a signal.
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

    @Volatile
    private var track: AudioTrack? = null

    override fun speak(utterance: Speaking.Utterance) {
        val audioRef = utterance.audioRef
        if (audioRef == null) {
            log("speech refused id=${utterance.id} reason=no-rendered-audio")
            return
        }
        val mine = generation.incrementAndGet()
        executor.execute {
            runCatching { play(mine, utterance.id, File(audioRef)) }
                .onFailure { log("speech failed id=${utterance.id} error=$it") }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        executor.execute { discardTrack() }
    }

    override fun release() {
        stop()
        executor.shutdown()
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

        val channelMask =
            if (wav.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(wav.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBytes, wav.bytesPerFrame * wav.sampleRateHz / 4)

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
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack

        val startedAtMs = System.currentTimeMillis()
        audioTrack.play()
        log(
            "speech started id=$id file=${file.name} frames=${wav.frameCount} " +
                "rate=${wav.sampleRateHz} channels=${wav.channelCount}",
        )

        var offset = 0
        while (offset < pcm.size && generation.get() == mine) {
            val written = audioTrack.write(pcm, offset, minOf(CHUNK_BYTES, pcm.size - offset))
            if (written <= 0) {
                log("speech failed id=$id reason=write-returned-$written")
                discardTrack()
                return
            }
            offset += written
        }

        // Silence behind the real frames, so the last of them is consumed while
        // the track is still playing and the head position stays readable.
        val tail = ByteArray(wav.bytesPerFrame * (wav.sampleRateHz / 5))
        var tailOffset = 0
        while (tailOffset < tail.size && generation.get() == mine) {
            val written = audioTrack.write(tail, tailOffset, tail.size - tailOffset)
            if (written <= 0) break
            tailOffset += written
        }

        val target = wav.frameCount.toInt()
        while (generation.get() == mine) {
            val head = audioTrack.playbackHeadPosition
            if (head >= target) break
            Thread.sleep(POLL_MS)
        }

        if (generation.get() != mine) {
            discardTrack()
            return
        }
        discardTrack()
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
        const val CHUNK_BYTES = 8 * 1024
        const val POLL_MS = 10L
    }
}
