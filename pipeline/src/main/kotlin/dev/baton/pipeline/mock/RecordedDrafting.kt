package dev.baton.pipeline.mock

import dev.baton.pipeline.Draft
import dev.baton.pipeline.DraftRequest
import dev.baton.pipeline.Drafting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recorded model output, replayed as a conversation.
 *
 * Each entry names a gap and a position within it, and carries a list of candidate
 * descriptions in the order the model produced them under successive "shorter,
 * please" turns, so `attempt` indexes straight into it and running out of
 * candidates is exactly the model having nothing shorter to offer. That makes the
 * replay stateless: two builds over the same fixture ask the same questions and
 * get the same answers, which is what makes the golden file meaningful.
 *
 * The position within the gap is a second key rather than a longer list under the
 * first, because a window can hold several descriptions and they are not
 * interchangeable. Indexing one flat list by a running attempt count would replay
 * the second sentence of a window as though it were a shorter retry of the first,
 * and the fixture would be quietly asserting that the model has nothing new to say
 * about a thirty-second sequence -- which is the exact claim the multi-cue work
 * exists to disprove. `cueOrdinal` defaults to zero on the wire, so a fixture
 * recorded before windows could hold more than one still reads.
 *
 * The recorded drafter ignores [DraftRequest.targetDurationMs], and it must. A
 * fixture that consulted the target could pick a candidate it "knew" would fit
 * and the fit loop would never run -- which is precisely the thing this slice
 * exists to prove works. A live drafter reads the target and does better; the
 * loop is what catches it when it does not.
 */
class RecordedDrafting(draftsJson: String) : Drafting {

    private val bySlot: Map<Pair<Long, Int>, List<String>> =
        json.decodeFromString(RecordedDrafts.serializer(), draftsJson)
            .drafts
            .associate { (it.gapStartMs to it.cueOrdinal) to it.candidates }

    override fun draft(request: DraftRequest): Draft? {
        val candidates = bySlot[request.scene.gap.startMs to request.cueOrdinal] ?: return null
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
private data class RecordedGapDrafts(
    val gapStartMs: Long,
    val candidates: List<String>,
    val cueOrdinal: Int = 0,
)
