package dev.baton.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough RIFF/WAVE to answer the only question that matters: how many frames
 * are in this file, and at what rate will they be played back.
 *
 * The frame count is read from the file rather than taken from what the
 * synthesizer said it would produce, because the whole guarantee downstream is
 * that the duration was measured off the bytes that will be heard.
 *
 * Chunks are walked rather than assumed at fixed offsets: engines vary, and at
 * least one on this device writes a `LIST` chunk between `fmt ` and `data`.
 */
class WavAudio(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)
    val frameCount: Long = if (bytesPerFrame == 0) 0L else dataBytes / bytesPerFrame

    /** The PCM payload, without the header. */
    fun readPcm(file: File): ByteArray {
        val pcm = ByteArray(dataBytes.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(dataOffset)
            raf.readFully(pcm)
        }
        return pcm
    }

    override fun toString(): String =
        "WavAudio(${sampleRateHz}Hz ${channelCount}ch ${bitsPerSample}bit frames=$frameCount bytes=$dataBytes)"

    companion object {

        fun read(file: File): WavAudio {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(12)
                raf.readFully(riff)
                require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF") { "$file is not a RIFF file" }
                require(String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") { "$file is not a WAVE file" }

                var sampleRateHz = 0
                var channelCount = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataBytes = 0L

                val header = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(header)
                    val id = String(header, 0, 4, Charsets.US_ASCII)
                    val declared = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                        0xFFFFFFFFL
                    val body = raf.filePointer

                    when (id) {
                        "fmt " -> {
                            val fmt = ByteArray(minOf(declared, 16L).toInt())
                            raf.readFully(fmt)
                            val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.short // audio format code, unused: the engines here all write PCM
                            channelCount = buffer.short.toInt()
                            sampleRateHz = buffer.int
                            buffer.int // byte rate, derivable
                            buffer.short // block align, derivable
                            bitsPerSample = buffer.short.toInt()
                        }

                        "data" -> {
                            dataOffset = body
                            // A synthesizer that was interrupted can leave a length
                            // it never went back and corrected. What is on disk wins.
                            val available = raf.length() - body
                            dataBytes = if (declared in 1..available) declared else available
                        }
                    }

                    if (dataOffset >= 0 && sampleRateHz > 0) break
                    raf.seek(body + declared + (declared and 1L))
                }

                require(sampleRateHz > 0 && channelCount > 0 && bitsPerSample > 0) {
                    "$file has no usable fmt chunk"
                }
                require(dataOffset >= 0) { "$file has no data chunk" }
                return WavAudio(sampleRateHz, channelCount, bitsPerSample, dataOffset, dataBytes)
            }
        }
    }
}
