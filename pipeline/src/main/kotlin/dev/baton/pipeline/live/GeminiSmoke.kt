package dev.baton.pipeline.live

import dev.baton.director.DescriptionTrackJson
import dev.baton.pipeline.BuildRequest
import dev.baton.pipeline.DescriptionTrackBuilder
import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.PipelineConfig
import dev.baton.pipeline.mock.RecordedTranscription
import dev.baton.pipeline.mock.SimulatedSynthesis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val USAGE =
    "usage: gradlew :pipeline:geminiSmoke --args=<media file>,<transcript json>,<model id>,<media duration ms>,<out track.json>"

/**
 * A live Gemini drafting run over one clip, run by hand, never by the build: [NovaSmoke] with
 * Gemini's free tier in Nova's place. The transcript comes from disk and synthesis is simulated,
 * so Gemini is the only service called. Free tier, so no spend guard; [GeminiDrafting]'s call
 * cap is the brake.
 */
fun main(rawArgs: Array<String>) {
    // Commas, not spaces: see TranscribeSmoke.kt.
    val args = rawArgs.singleOrNull()?.split(',') ?: rawArgs.toList()
    require(args.size == 5) { USAGE }
    val media = Path.of(args[0]).toAbsolutePath()
    val transcriptFile = Path.of(args[1]).toAbsolutePath()
    val modelId = args[2]
    val durationMs = requireNotNull(args[3].toLongOrNull()) { USAGE }
    val out = Path.of(args[4]).toAbsolutePath()

    println("model $modelId on the Gemini free tier; media $media")
    val gemini = GeminiDrafting(modelId, media, maxCalls = 120)
    // A live model fills whatever room it is offered; 4.5 s is the longest cue in the hand-written demo track.
    val config = PipelineConfig(maxCueMs = 4_500)
    val request = BuildRequest(MediaRef(media.toString()), media.fileName.toString().substringBeforeLast('.'), durationMs)
    var failed = false
    try {
        val result = DescriptionTrackBuilder(RecordedTranscription(transcriptFile.readText()), gemini, SimulatedSynthesis(), config).build(request)
        Files.createDirectories(out.parent)
        out.writeText(DescriptionTrackJson.encode(result.track))
        println("wrote $out")
        println("cues ${result.track.cues.size}, drops ${result.drops.size}")
        result.drops.forEach { println("  DROP ${it.intendedCueId}: ${it.reason}") }
        result.track.cues.forEach { cue -> println("  ${cue.id} at ${cue.startMs} ms: ${cue.variants.joinToString(" | ") { it.text }}") }
    } catch (e: Exception) {
        // A CueChecks failure or an API error: nothing is written.
        failed = true
        println("build failed: ${e.message}")
    }
    println("Gemini calls ${gemini.calls}, tokens in ${gemini.inputTokens}, out ${gemini.outputTokens} (free tier, nothing billed)")
    if (failed) exitProcess(1)
}
