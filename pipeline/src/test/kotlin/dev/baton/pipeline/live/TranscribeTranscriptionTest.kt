package dev.baton.pipeline.live

import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.mock.RecordedTranscription
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.transcribe.TranscribeClient
import software.amazon.awssdk.services.transcribe.model.DeleteTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.DeleteTranscriptionJobResponse
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobResponse
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobResponse
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus
import java.nio.file.Files

/** The live Transcribe adapter, against fake S3 and Transcribe clients. Nothing here reaches AWS. */
class TranscribeTranscriptionTest : FunSpec({

    val output = RecordedTranscription::class.java.getResource("/fixtures/sintel-clip.transcribe.json")!!.readText()
    val clip = Files.createTempFile("baton-clip", ".mp4").also { it.toFile().deleteOnExit() }

    fun adapter(s3: FakeS3, transcribe: FakeTranscribe, clock: FakeClock = FakeClock(), logs: MutableList<String> = mutableListOf()) =
        TranscribeTranscription(
            bucket = "baton-test",
            s3 = s3,
            transcribe = transcribe,
            pollIntervalMs = 5_000,
            timeoutMs = 60_000,
            nowMs = clock::now,
            sleep = clock::sleep,
            newId = { "id1" },
            log = { logs += it },
        )

    test("the media is uploaded, and the job names it, its format, its language and an output key in the same bucket") {
        val s3 = FakeS3(output)
        val transcribe = FakeTranscribe(TranscriptionJobStatus.COMPLETED)
        adapter(s3, transcribe).transcribe(MediaRef(clip.toString()))

        s3.puts.map { it.bucket() to it.key() } shouldBe listOf("baton-test" to "baton/id1.mp4")
        val start = transcribe.starts.single()
        listOf(
            start.transcriptionJobName(), start.media().mediaFileUri(), start.mediaFormatAsString(),
            start.languageCodeAsString(), start.outputBucketName(), start.outputKey(),
        ) shouldBe listOf("baton-id1", "s3://baton-test/baton/id1.mp4", "mp4", "de-DE", "baton-test", "baton/id1.json")
        s3.gets.map { it.bucket() to it.key() } shouldBe listOf("baton-test" to "baton/id1.json")
    }

    test("it polls until the job completes, sleeping between polls, and parses the output as the recorded parser does") {
        val clock = FakeClock()
        val transcribe = FakeTranscribe(TranscriptionJobStatus.QUEUED, TranscriptionJobStatus.IN_PROGRESS, TranscriptionJobStatus.COMPLETED)
        val adapter = adapter(FakeS3(output), transcribe, clock)

        val transcript = adapter.transcribe(MediaRef(clip.toString()))

        transcribe.polls shouldBe 3
        clock.sleeps shouldBe listOf(5_000L, 5_000L)
        transcript shouldBe RecordedTranscription.parse(output)
        transcript.words.size shouldBe 35
        adapter.lastOutputJson shouldBe output
    }

    test("a failed job throws with its reason, and everything is still cleaned up") {
        val s3 = FakeS3(output)
        val transcribe = FakeTranscribe(TranscriptionJobStatus.FAILED, failureReason = "Unsupported media format")

        shouldThrow<IllegalStateException> { adapter(s3, transcribe).transcribe(MediaRef(clip.toString())) }
            .message shouldContain "Unsupported media format"

        s3.gets shouldBe emptyList()
        s3.deletes.map { it.key() } shouldBe listOf("baton/id1.mp4", "baton/id1.json")
        transcribe.deletes.map { it.transcriptionJobName() } shouldBe listOf("baton-id1")
    }

    test("a job that never finishes times out") {
        val clock = FakeClock()
        val transcribe = FakeTranscribe(TranscriptionJobStatus.IN_PROGRESS)

        shouldThrow<IllegalStateException> { adapter(FakeS3(output), transcribe, clock).transcribe(MediaRef(clip.toString())) }
            .message shouldContain "still IN_PROGRESS"

        clock.now() shouldBe 60_000L
        transcribe.deletes.size shouldBe 1
    }

    test("on success the media, the output and the job are deleted, and a failed deletion is logged, not thrown") {
        val s3 = FakeS3(output, failDeletes = true)
        val transcribe = FakeTranscribe(TranscriptionJobStatus.COMPLETED)
        val logs = mutableListOf<String>()

        adapter(s3, transcribe, logs = logs).transcribe(MediaRef(clip.toString())).words.size shouldBe 35

        s3.deletes.map { it.bucket() to it.key() } shouldBe listOf("baton-test" to "baton/id1.mp4", "baton-test" to "baton/id1.json")
        transcribe.deletes.map { it.transcriptionJobName() } shouldBe listOf("baton-id1")
        logs.size shouldBe 2
        logs[0] shouldContain "s3://baton-test/baton/id1.mp4"
    }

    test("media Transcribe cannot take is refused before anything is uploaded") {
        val s3 = FakeS3(output)
        val mkv = Files.createTempFile("baton-clip", ".mkv").also { it.toFile().deleteOnExit() }
        shouldThrow<IllegalArgumentException> { adapter(s3, FakeTranscribe(TranscriptionJobStatus.COMPLETED)).transcribe(MediaRef(mkv.toString())) }
        s3.puts shouldBe emptyList()
    }

    test("closing the adapter closes both clients") {
        val s3 = FakeS3(output)
        val transcribe = FakeTranscribe(TranscriptionJobStatus.COMPLETED)
        adapter(s3, transcribe).close()
        s3.closed shouldBe true
        transcribe.closed shouldBe true
    }
})

private class FakeClock {
    private var nowMs = 0L
    val sleeps = mutableListOf<Long>()
    fun now(): Long = nowMs
    fun sleep(ms: Long) {
        sleeps += ms
        nowMs += ms
    }
}

private class FakeS3(private val outputJson: String, private val failDeletes: Boolean = false) : S3Client {
    val puts = mutableListOf<PutObjectRequest>()
    val gets = mutableListOf<GetObjectRequest>()
    val deletes = mutableListOf<DeleteObjectRequest>()
    var closed = false

    override fun putObject(request: PutObjectRequest, body: RequestBody): PutObjectResponse {
        puts += request
        return PutObjectResponse.builder().build()
    }

    override fun getObjectAsBytes(request: GetObjectRequest): ResponseBytes<GetObjectResponse> {
        gets += request
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), outputJson.toByteArray())
    }

    override fun deleteObject(request: DeleteObjectRequest): DeleteObjectResponse {
        deletes += request
        if (failDeletes) error("access denied")
        return DeleteObjectResponse.builder().build()
    }

    override fun serviceName(): String = "s3"

    override fun close() {
        closed = true
    }
}

/** Answers each poll with the next status, repeating the last one. */
private class FakeTranscribe(
    private vararg val statuses: TranscriptionJobStatus,
    private val failureReason: String? = null,
) : TranscribeClient {
    val starts = mutableListOf<StartTranscriptionJobRequest>()
    val deletes = mutableListOf<DeleteTranscriptionJobRequest>()
    var polls = 0
    var closed = false

    override fun startTranscriptionJob(request: StartTranscriptionJobRequest): StartTranscriptionJobResponse {
        starts += request
        return StartTranscriptionJobResponse.builder().build()
    }

    override fun getTranscriptionJob(request: GetTranscriptionJobRequest): GetTranscriptionJobResponse {
        val status = statuses[minOf(polls++, statuses.lastIndex)]
        val job = TranscriptionJob.builder()
            .transcriptionJobName(request.transcriptionJobName())
            .transcriptionJobStatus(status)
            .failureReason(failureReason)
            .build()
        return GetTranscriptionJobResponse.builder().transcriptionJob(job).build()
    }

    override fun deleteTranscriptionJob(request: DeleteTranscriptionJobRequest): DeleteTranscriptionJobResponse {
        deletes += request
        return DeleteTranscriptionJobResponse.builder().build()
    }

    override fun serviceName(): String = "transcribe"

    override fun close() {
        closed = true
    }
}
