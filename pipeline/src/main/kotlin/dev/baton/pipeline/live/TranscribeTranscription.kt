package dev.baton.pipeline.live

import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.Transcript
import dev.baton.pipeline.Transcription
import dev.baton.pipeline.mock.RecordedTranscription
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.transcribe.TranscribeClient
import software.amazon.awssdk.services.transcribe.model.DeleteTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.Media
import software.amazon.awssdk.services.transcribe.model.MediaFormat
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Amazon Transcribe, batch. Every job is billed per second of media, so it belongs behind a
 * [dev.baton.pipeline.SpendGuard].
 *
 * [MediaRef.uri] is a local file. It is uploaded to `s3://<bucket>/baton/<id>.<ext>`, and the
 * job writes its output to `baton/<id>.json` in the same bucket (`OutputBucketName` plus an
 * `OutputKey` ending in `.json` is the exact output path), so the result is read back through
 * S3 with the same credentials rather than through the service-managed presigned URI. The
 * output is Transcribe's own JSON, parsed by [RecordedTranscription.parse], the parser the
 * recorded fixtures already exercise; the raw JSON is kept in [lastOutputJson].
 *
 * Whatever happens, the uploaded media, the output object and the job are deleted afterwards.
 * A failed deletion is logged, never thrown over the real result.
 *
 * The clients, clock and sleeper are parameters so tests run offline and without waiting; by
 * default it is us-east-1 with the `baton` profile's credentials. The bucket must be in the
 * same region as the job.
 */
class TranscribeTranscription(
    private val bucket: String,
    private val languageCode: String = "de-DE",
    internal val s3: S3Client =S3Client.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(ProfileCredentialsProvider.create("baton"))
        .build(),
    private val transcribe: TranscribeClient = TranscribeClient.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(ProfileCredentialsProvider.create("baton"))
        .build(),
    private val pollIntervalMs: Long = 5_000,
    private val timeoutMs: Long = 10 * 60_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = System.err::println,
) : Transcription, AutoCloseable {

    /** The raw output JSON of the last completed job, for keeping as a fixture. */
    var lastOutputJson: String? = null
        private set

    override fun transcribe(media: MediaRef): Transcript {
        val file = Path.of(media.uri)
        require(Files.isRegularFile(file)) { "no media file at $file" }
        val extension = file.fileName.toString().substringAfterLast('.', "").lowercase()
        val format = MediaFormat.fromValue(extension)
        require(format in MediaFormat.knownValues()) {
            "Transcribe takes ${MediaFormat.knownValues()} media, not '.$extension'"
        }

        val id = newId()
        val mediaKey = "baton/$id.$extension"
        val outputKey = "baton/$id.json"
        val jobName = "baton-$id"
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(mediaKey).build(), RequestBody.fromFile(file))
            transcribe.startTranscriptionJob(
                StartTranscriptionJobRequest.builder()
                    .transcriptionJobName(jobName)
                    .media(Media.builder().mediaFileUri("s3://$bucket/$mediaKey").build())
                    .mediaFormat(format)
                    .languageCode(languageCode)
                    .outputBucketName(bucket)
                    .outputKey(outputKey)
                    .build(),
            )
            awaitCompletion(jobName)
            val json = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(outputKey).build()).asUtf8String()
            lastOutputJson = json
            return RecordedTranscription.parse(json, fallbackLocale = languageCode)
        } finally {
            bestEffort("delete s3://$bucket/$mediaKey") {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(mediaKey).build())
            }
            bestEffort("delete s3://$bucket/$outputKey") {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(outputKey).build())
            }
            bestEffort("delete transcription job $jobName (if it was still running, its output may yet land at s3://$bucket/$outputKey)") {
                transcribe.deleteTranscriptionJob(DeleteTranscriptionJobRequest.builder().transcriptionJobName(jobName).build())
            }
        }
    }

    private fun awaitCompletion(jobName: String): TranscriptionJob {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val job = transcribe.getTranscriptionJob(
                GetTranscriptionJobRequest.builder().transcriptionJobName(jobName).build(),
            ).transcriptionJob()
            when (job.transcriptionJobStatus()) {
                TranscriptionJobStatus.COMPLETED -> return job
                TranscriptionJobStatus.FAILED -> error("transcription job $jobName failed: ${job.failureReason()}")
                else -> if (nowMs() >= deadline) {
                    error("transcription job $jobName is still ${job.transcriptionJobStatusAsString()} after $timeoutMs ms; giving up")
                }
            }
            sleep(pollIntervalMs)
        }
    }

    private fun bestEffort(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log("cleanup failed, $what: $e")
        }
    }

    override fun close() {
        s3.close()
        transcribe.close()
    }
}
