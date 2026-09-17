package dev.baton.pipeline.live

import dev.baton.pipeline.BuildRequest
import dev.baton.pipeline.MediaRef
import dev.baton.pipeline.RunReport
import dev.baton.pipeline.SpendConfig
import dev.baton.pipeline.buildGuarded
import dev.baton.pipeline.mock.RecordedTranscription
import dev.baton.pipeline.mock.SimulatedSynthesis
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val USAGE =
    "usage: gradlew :pipeline:novaSmoke --args=<media file>,<transcript json>,<model id>,<media duration ms>,<out track.json>"

/**
 * A live Nova drafting run over one clip, run by hand, never by the build. The track is built
 * by the real builder from a transcript already on disk (no Transcribe cost) with simulated
 * synthesis (no Polly cost), so the only paid calls are Nova's, each through the spend guard
 * with the default caps and the real ledger. The guard's worst case is printed before the
 * first call; the caps, not a prompt, are the brake.
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

    val spend = SpendConfig(novaModelId = modelId)
    val perCall = spend.novaCallEstimateUsd(durationMs)
    println("model $modelId, priced as ${spend.novaPrice}")
    println("worst case per Nova call ${usd(perCall)}; for the whole run ${spend.novaCallsPerRunCap} calls x that = ${usd(spend.novaCallsPerRunCap * perCall)}")
    println("caps: ${usd(spend.perRunCapUsd)} per run, ${usd(spend.cumulativeCapUsd)} cumulative in ${spend.ledgerPath}, ${spend.novaCallsPerRunCap} Nova calls per run")

    val transcription = RecordedTranscription(transcriptFile.readText())
    val request = BuildRequest(MediaRef(media.toString()), media.fileName.toString().substringBeforeLast('.'), durationMs)
    var failed = false
    NovaDrafting(modelId, media).use { nova ->
        try {
            val result = buildGuarded(request, transcription, nova, SimulatedSynthesis(), out, spend = spend)
            println("wrote $out")
            println("cues ${result.track.cues.size}, drops ${result.drops.size}")
            result.drops.forEach { println("  DROP ${it.intendedCueId}: ${it.reason}") }
            result.track.cues.take(5).forEach { println("  ${it.id} at ${it.startMs} ms: ${it.variants.first().text}") }
            val reportFile = out.resolveSibling(out.fileName.toString().removeSuffix(".json") + ".report.json")
            val report = Json.decodeFromString(RunReport.serializer(), reportFile.readText())
            println("Nova USD: estimated ${usd(report.nova.estimatedUsd)}, recorded ${usd(report.nova.recordedUsd)}, ledger total ${usd(report.caps.cumulativeUsd)}")
        } catch (e: Exception) {
            // A spend refusal or a CueChecks failure: nothing is written, the ledger holds what was spent.
            failed = true
            println("build failed: ${e.message}")
        }
        println("Nova calls ${nova.calls}, tokens in ${nova.inputTokens}, out ${nova.outputTokens}, billed ${usd(spend.novaUsd(nova.inputTokens, nova.outputTokens))}")
    }
    if (failed) exitProcess(1)
}

private fun usd(amount: Double): String = String.format(Locale.ROOT, "\$%.4f", amount)
