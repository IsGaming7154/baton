package dev.baton.pipeline.live

import dev.baton.pipeline.GapMapper
import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.PipelineConfig
import dev.baton.pipeline.SpendConfig
import dev.baton.pipeline.SpendGuard
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE =
    "usage: gradlew :pipeline:transcribeSmoke --args=<media file>,<bucket>,<out transcript.json>,<media duration ms>"

/**
 * The one live Transcribe job, run by hand, never by the build. It goes through the spend guard
 * with the default caps and the real ledger, priced from the media duration given on the
 * command line (88600 for the demo clip). The bucket is not created here: it must already
 * exist, in us-east-1.
 */
fun main(rawArgs: Array<String>) {
    // Commas, not spaces: from a checkout whose path has a space, gradlew.bat cannot be handed
    // a quoted --args at all under Git Bash ("'C:\Claude' is not recognized").
    val args = rawArgs.singleOrNull()?.split(',') ?: rawArgs.toList()
    require(args.size == 4) { USAGE }
    val media = Path.of(args[0]).toAbsolutePath()
    val bucket = args[1]
    val out = Path.of(args[2]).toAbsolutePath()
    val durationMs = requireNotNull(args[3].toLongOrNull()) { USAGE }
    require(Files.isRegularFile(media)) { "no media file at $media" }

    val guard = SpendGuard(SpendConfig(), mediaDurationMs = durationMs)
    val transcript = TranscribeTranscription(bucket).use { adapter ->
        // Checked before the guard charges anything: a missing bucket would fail the upload anyway.
        try {
            adapter.s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        } catch (e: NoSuchBucketException) {
            System.err.println("bucket '$bucket' does not exist. Create it first: aws s3 mb s3://$bucket --region us-east-1 --profile baton")
            exitProcess(2)
        }
        guard.guarded(adapter).transcribe(MediaRef(media.toString())).also {
            Files.createDirectories(out.parent)
            Files.writeString(out, adapter.lastOutputJson!!)
        }
    }

    val words = transcript.words
    val spans = GapMapper.dialogueSpans(transcript, durationMs)
    val gaps = GapMapper.derive(transcript, durationMs)
    val spend = guard.report(out, PipelineConfig()).transcribe
    println("wrote $out")
    println("words ${words.size}, locale ${transcript.locale}")
    println("first word ${words.firstOrNull()?.let { "'${it.text}' at ${it.startMs} ms" }}, last word ${words.lastOrNull()?.let { "'${it.text}' ending ${it.endMs} ms" }}")
    println("dialogue spans ${spans.size}, gap windows ${gaps.size}")
    println("USD: estimated ${spend.estimatedUsd}, ledger total ${guard.cumulativeUsd}")
}
