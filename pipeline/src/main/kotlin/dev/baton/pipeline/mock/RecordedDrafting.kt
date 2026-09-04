package dev.baton.pipeline.mock

import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.Drafting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recorded model output, replayed as a conversation.
 *
 * Each gap has a list of candidate descriptions in the order the model produced
 * them under successive "shorter, please" turns, so `attempt` indexes straight
 * into it and running out of candidates is exactly the model having nothing
 * shorter to offer. That makes the replay stateless: two builds over the same
 * fixture ask the same questions and get the same answers, which is what makes
 * the golden file meaningful.
 *
 * The recorded drafter ignores [DraftRequest.targetDurationMs], and it must. A
 * fixture that consulted the target could pick a candidate it "knew" would fit
 * and the fit loop would never run -- which is precisely the thing this slice
 * exists to prove works. A live drafter reads the target and does better; the
 * loop is what catches it when it does not.
 */
class RecordedDrafting(draftsJson: String) : Drafting {

    private val byGapStartMs: Map<Long, List<String>> =
        json.decodeFromString(RecordedDrafts.serializer(), draftsJson)
            .drafts
            .associate { it.gapStartMs to it.candidates }

    override fun draft(request: DraftRequest): Draft? {
        val candidates = byGapStartMs[request.scene.gap.startMs] ?: return null
        return candidates.getOrNull(request.attempt)?.let(::Draft)
    }

    companion object {
        fun fromResource(path: String): RecordedDrafting = RecordedDrafting(readResource(path))

        private val json = Json { ignoreUnknownKeys = false }
    }
}

@Serializable
private data class RecordedDrafts(val drafts: List<RecordedGapDrafts>)

@Serializable
private data class RecordedGapDrafts(val gapStartMs: Long, val candidates: List<String>)
