package dev.baton.pipeline.live

import dev.baton.pipeline.RenderedAudio
import dev.baton.pipeline.Synthesis
import dev.baton.pipeline.VoiceSpec
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.OutputFormat
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest
import software.amazon.awssdk.services.polly.model.TextType

/**
 * Amazon Polly. Every call is billed, so it belongs behind a [dev.baton.pipeline.SpendGuard].
 *
 * Asks for `pcm`, which Polly returns as signed 16-bit mono little-endian at the requested
 * rate, so the buffer goes straight to [RenderedAudio.ofPcm16Mono] and is measured like the
 * simulated one. The characters Polly reports billing ride back on the audio for the guard
 * to settle against.
 *
 * [client] is a parameter so tests can hand in a fake; by default it is us-east-1 with the
 * `baton` profile's credentials.
 */
class PollySynthesis(
    private val client: PollyClient = PollyClient.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(ProfileCredentialsProvider.create("baton"))
        .build(),
) : Synthesis, AutoCloseable {

    override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio {
        val request = SynthesizeSpeechRequest.builder()
            .text(text)
            .textType(TextType.TEXT)
            .outputFormat(OutputFormat.PCM)
            .voiceId(voice.voiceId)
            .engine(voice.engine)
            .sampleRate(voice.sampleRateHz.toString())
            .build()
        val response = client.synthesizeSpeechAsBytes(request)
        return RenderedAudio.ofPcm16Mono(
            response.asByteArray(),
            voice.sampleRateHz,
            requestCharacters = response.response().requestCharacters(),
        )
    }

    override fun close() = client.close()
}
