package dev.baton.pipeline

import dev.baton.director.DescriptionTrackJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.ceil

/**
 * What AWS charges for the three services the live pipeline calls, in one place.
 *
 * Every price is from the AWS Price List API, us-east-1, publication of 2026-09-11, as
 * recorded in HANDOFF.md §6. A cap is only as good as these numbers, so re-check them
 * there rather than editing one in passing.
 */
object AwsPrices {
    const val TRANSCRIBE_BATCH_USD_PER_SECOND: Double = 0.0001
    const val POLLY_NEURAL_USD_PER_CHARACTER: Double = 16.0 / 1_000_000
    const val POLLY_STANDARD_USD_PER_CHARACTER: Double = 4.0 / 1_000_000

    val NOVA_1_LITE = NovaPrice("Nova 1 Lite", inputUsdPerMillionTokens = 0.06, outputUsdPerMillionTokens = 0.24)
    val NOVA_1_PRO = NovaPrice("Nova 1 Pro", inputUsdPerMillionTokens = 0.80, outputUsdPerMillionTokens = 3.20)
    val NOVA_2_LITE = NovaPrice("Nova 2 Lite", inputUsdPerMillionTokens = 0.33, outputUsdPerMillionTokens = 2.75)
    val NOVA_2_PRO = NovaPrice("Nova 2 Pro", inputUsdPerMillionTokens = 1.375, outputUsdPerMillionTokens = 11.0)

    /**
     * Not a price, but what the Nova input price is multiplied by: Nova 1's documentation
     * gives 288 tokens per video frame, sampled at 1 fps for clips up to 16 minutes
     * (HANDOFF.md §6). Not established for Nova 2.
     */
    const val NOVA_VIDEO_TOKENS_PER_SECOND: Int = 288
}

@Serializable
data class NovaPrice(
    val tier: String,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
)

/** The caps, and what the estimates are built from. */
data class SpendConfig(
    val perRunCapUsd: Double = 2.00,
    val cumulativeCapUsd: Double = 10.00,
    val novaCallsPerRunCap: Int = 150,
    /** Outside `build/`, which `clean` wipes, and outside the repository. */
    val ledgerPath: Path = Path.of(System.getProperty("user.home"), ".baton", "aws-spend.json"),
    /** Not pinned: the S3 bake-off chooses it. Null until then. */
    val novaModelId: String? = null,
    val novaPrice: NovaPrice = AwsPrices.NOVA_2_LITE,
    /**
     * The `maxTokens` a live drafter sends with each call, so the output side of the
     * estimate is a ceiling the service enforces rather than a guess.
     */
    val novaMaxOutputTokens: Int = 512,
    /** Text tokens allowed for the prompt and dialogue beside the video. An allowance, not a measurement. */
    val novaTextTokenAllowance: Int = 2_000,
    val novaVideoTokensPerSecond: Int = AwsPrices.NOVA_VIDEO_TOKENS_PER_SECOND,
) {
    init {
        require(perRunCapUsd >= 0 && cumulativeCapUsd >= 0) { "caps must be >= 0" }
        require(novaCallsPerRunCap >= 0) { "novaCallsPerRunCap must be >= 0, was $novaCallsPerRunCap" }
        require(novaMaxOutputTokens > 0 && novaTextTokenAllowance >= 0 && novaVideoTokensPerSecond > 0) {
            "Nova token figures must be positive"
        }
    }
}

/** A paid call refused before it was made, or a run refused because its spend so far cannot be known. */
class SpendRefused(message: String) : IllegalStateException(message)

/**
 * A port whose calls cost nothing: a recorded fixture or a simulation. The [SpendGuard]
 * still counts and checks every call to one, and records it at $0 rather than at its
 * estimate.
 */
interface Unbilled

enum class Service { TRANSCRIBE, NOVA, POLLY }

@Serializable
data class ServiceSpend(val calls: Int, val estimatedUsd: Double, val recordedUsd: Double)

/**
 * A hard stop on AWS spend, in code, because a Budgets alert arrives after the money has gone.
 *
 * It wraps the three cloud ports rather than sitting beside them, so no paid call is made
 * that it has not seen. Before each call it prices the worst case, refuses the call if that
 * would cross a cap, and otherwise writes the spend to the ledger and only then makes the
 * call. On disk first, because a call that throws, or a process killed during one, may
 * still have been billed.
 *
 * One guard is one run: the per-run cap and the Nova call count start from zero with each
 * guard, and the cumulative total is read from, and kept in, the ledger.
 *
 * What is recorded is the estimate, since no live port reports its billed usage yet. A port
 * marked [Unbilled] records $0, and is still counted and checked.
 */
class SpendGuard(private val config: SpendConfig, mediaDurationMs: Long) {

    init {
        require(mediaDurationMs > 0) { "mediaDurationMs must be > 0, was $mediaDurationMs" }
    }

    // Whole seconds rounded up: the worst case for audio billed per second and video sampled at 1 fps.
    private val mediaSeconds: Long = ceil(mediaDurationMs / 1_000.0).toLong()

    private val spend: MutableMap<Service, ServiceSpend> =
        Service.values().associateWith { ServiceSpend(0, 0.0, 0.0) }.toMutableMap()

    /** Spend across every run so far, this one included, as the ledger holds it. */
    var cumulativeUsd: Double = readLedger()
        private set

    val runUsd: Double get() = spend.values.sumOf { it.recordedUsd }

    /** The dialogue's locale as transcription reported it, for the report; null until it has run. */
    private var transcriptLocale: String? = null

    fun guarded(port: Transcription): Transcription = object : Transcription {
        override fun transcribe(media: MediaRef): Transcript {
            charge(Service.TRANSCRIBE, mediaSeconds * AwsPrices.TRANSCRIBE_BATCH_USD_PER_SECOND, port)
            return port.transcribe(media).also { transcriptLocale = it.locale }
        }
    }

    /** Every draft call sends the whole clip (see [SceneContext.media]), so each is priced for all of it. */
    fun guarded(port: Drafting): Drafting = object : Drafting {
        override fun draft(request: DraftRequest): Draft? {
            val inputTokens = mediaSeconds * config.novaVideoTokensPerSecond + config.novaTextTokenAllowance
            val price = config.novaPrice
            val estimateUsd = (inputTokens * price.inputUsdPerMillionTokens +
                config.novaMaxOutputTokens * price.outputUsdPerMillionTokens) / 1_000_000
            charge(Service.NOVA, estimateUsd, port)
            return port.draft(request)
        }
    }

    fun guarded(port: Synthesis): Synthesis = object : Synthesis {
        override fun synthesize(text: String, voice: VoiceSpec): RenderedAudio {
            val perCharacter = when (voice.engine) {
                "neural" -> AwsPrices.POLLY_NEURAL_USD_PER_CHARACTER
                "standard" -> AwsPrices.POLLY_STANDARD_USD_PER_CHARACTER
                else -> refuse("no Polly price is on record for the '${voice.engine}' engine, so its calls cannot be estimated")
            }
            charge(Service.POLLY, text.length * perCharacter, port)
            return port.synthesize(text, voice)
        }
    }

    /** What this run spent and where the caps stand, for the report beside [trackFile]. */
    fun report(trackFile: Path, pipeline: PipelineConfig): RunReport = RunReport(
        trackPath = trackFile.toAbsolutePath().toString(),
        novaModelId = config.novaModelId,
        novaPrice = config.novaPrice,
        voice = pipeline.voice,
        descriptionLocale = pipeline.descriptionLocale,
        transcriptLocale = transcriptLocale,
        transcribe = spend.getValue(Service.TRANSCRIBE),
        nova = spend.getValue(Service.NOVA),
        polly = spend.getValue(Service.POLLY),
        caps = CapState(
            perRunCapUsd = config.perRunCapUsd,
            runUsd = runUsd,
            runRemainingUsd = config.perRunCapUsd - runUsd,
            cumulativeCapUsd = config.cumulativeCapUsd,
            cumulativeUsd = cumulativeUsd,
            cumulativeRemainingUsd = config.cumulativeCapUsd - cumulativeUsd,
            novaCallsPerRunCap = config.novaCallsPerRunCap,
            novaCalls = spend.getValue(Service.NOVA).calls,
        ),
    )

    private fun charge(service: Service, estimateUsd: Double, port: Any) {
        val before = spend.getValue(service)
        if (service == Service.NOVA && before.calls >= config.novaCallsPerRunCap) {
            refuse("Nova call ${before.calls + 1} would pass the per-run cap of ${config.novaCallsPerRunCap} calls")
        }
        if (runUsd + estimateUsd > config.perRunCapUsd) {
            refuse(
                "$service call estimated at ${usd(estimateUsd)} would take this run to ${usd(runUsd + estimateUsd)}, " +
                    "past its per-run cap of ${usd(config.perRunCapUsd)}",
            )
        }
        if (cumulativeUsd + estimateUsd > config.cumulativeCapUsd) {
            refuse(
                "$service call estimated at ${usd(estimateUsd)} would take the total in ${config.ledgerPath} " +
                    "to ${usd(cumulativeUsd + estimateUsd)}, past its cumulative cap of ${usd(config.cumulativeCapUsd)}",
            )
        }
        val recordedUsd = if (port is Unbilled) 0.0 else estimateUsd
        spend[service] = ServiceSpend(before.calls + 1, before.estimatedUsd + estimateUsd, before.recordedUsd + recordedUsd)
        if (recordedUsd > 0) {
            cumulativeUsd += recordedUsd
            writeLedger()
        }
    }

    /** A missing ledger is nothing spent. One that cannot be read is refused, never taken as $0. */
    private fun readLedger(): Double {
        val path = config.ledgerPath
        if (!Files.exists(path)) return 0.0
        val spentUsd = try {
            Json.decodeFromString(Ledger.serializer(), path.readText()).spentUsd
        } catch (e: Exception) {
            refuse("the spend ledger at $path cannot be read (${e.message}); repair it by hand rather than let it count as \$0")
        }
        if (!spentUsd.isFinite() || spentUsd < 0) refuse("the spend ledger at $path holds $spentUsd, which is not a spend")
        return spentUsd
    }

    // ponytail: one writer at a time. Two runs sharing a ledger concurrently can lose each other's writes.
    private fun writeLedger() {
        val path = config.ledgerPath.toAbsolutePath()
        Files.createDirectories(path.parent)
        val staging = path.resolveSibling("${path.fileName}.tmp")
        staging.writeText(Json.encodeToString(Ledger.serializer(), Ledger(cumulativeUsd)))
        // Replaced in one step, so a crash mid-write leaves the old total rather than a torn file.
        Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * A run's report, written as JSON beside its track. `recordedUsd` is what went into the
 * ledger: $0 for an [Unbilled] port, otherwise the estimate, since no live port reports
 * billed usage yet.
 */
@Serializable
data class RunReport(
    val trackPath: String,
    val novaModelId: String?,
    val novaPrice: NovaPrice,
    val voice: VoiceSpec,
    val descriptionLocale: String,
    val transcriptLocale: String?,
    val transcribe: ServiceSpend,
    val nova: ServiceSpend,
    val polly: ServiceSpend,
    val caps: CapState,
)

@Serializable
data class CapState(
    val perRunCapUsd: Double,
    val runUsd: Double,
    val runRemainingUsd: Double,
    val cumulativeCapUsd: Double,
    val cumulativeUsd: Double,
    val cumulativeRemainingUsd: Double,
    val novaCallsPerRunCap: Int,
    val novaCalls: Int,
)

/**
 * Build a track with every cloud port behind one [SpendGuard], write it to [trackFile], and
 * write the run's report beside it as `<name>.report.json`. A refused call throws
 * [SpendRefused] out of here before either file is written; the ledger already holds
 * whatever was spent up to it.
 */
fun buildGuarded(
    request: BuildRequest,
    transcription: Transcription,
    drafting: Drafting,
    synthesis: Synthesis,
    trackFile: Path,
    config: PipelineConfig = PipelineConfig(),
    spend: SpendConfig = SpendConfig(),
): BuildResult {
    val guard = SpendGuard(spend, request.mediaDurationMs)
    val result = DescriptionTrackBuilder(
        guard.guarded(transcription),
        guard.guarded(drafting),
        guard.guarded(synthesis),
        config,
    ).build(request)

    Files.createDirectories(trackFile.toAbsolutePath().parent)
    trackFile.writeText(DescriptionTrackJson.encode(result.track))
    val reportFile = trackFile.resolveSibling(trackFile.fileName.toString().removeSuffix(".json") + ".report.json")
    reportFile.writeText(reportJson.encodeToString(RunReport.serializer(), guard.report(trackFile, config)))
    return result
}

private val reportJson = Json { prettyPrint = true }

@Serializable
private data class Ledger(val spentUsd: Double)

private fun refuse(reason: String): Nothing = throw SpendRefused("AWS spend guard: $reason. The run stops here.")

private fun usd(amount: Double): String = String.format(Locale.ROOT, "\$%.4f", amount)
