package org.example.bookduels.core

/** The stable ID must never be reassigned to a different book. */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val isbn13: String? = null,
    val coverUrl: String? = null,
    val enabled: Boolean = true,
) {
    fun thumbnailUrl(): String? = coverUrl ?: isbn13?.let {
        "https://covers.openlibrary.org/b/isbn/$it-M.jpg?default=false"
    }
}

enum class EventKind { VOTE, JOKER, INVALIDATE, REINSTATE }

data class VoteEvent(
    val seq: Long,
    val eventId: String,
    val timestampMs: Long,
    val kind: EventKind,
    val firstBookId: String? = null,
    val secondBookId: String? = null,
    val chosenBookId: String? = null,
    val targetSeq: Long? = null,
    val batchId: String? = null,
)

data class Duel(val first: Book, val second: Book)

data class HistoryEntry(val event: VoteEvent, val active: Boolean)

data class ReplayState(
    val history: List<HistoryEntry>,
    val activeEvents: List<VoteEvent>,
    val lastSequence: Long,
    val restorableBatchId: String?,
    val restorableCount: Int,
)

object EventReplay {
    fun reconstruct(events: List<VoteEvent>): ReplayState {
        val ordered = events.sortedBy { it.seq }
        val bases = LinkedHashMap<Long, VoteEvent>()
        val active = mutableMapOf<Long, Boolean>()
        val lastChangeBatch = mutableMapOf<Long, String?>()
        for (e in ordered) {
            when (e.kind) {
                EventKind.VOTE, EventKind.JOKER -> {
                    bases[e.seq] = e
                    active[e.seq] = true
                }
                EventKind.INVALIDATE, EventKind.REINSTATE -> {
                    val target = e.targetSeq ?: continue
                    if (!bases.containsKey(target)) continue
                    active[target] = e.kind == EventKind.REINSTATE
                    lastChangeBatch[target] = e.batchId
                }
            }
        }
        val lastBatch = ordered.asReversed().firstOrNull {
            it.kind == EventKind.INVALIDATE && it.batchId != null &&
                bases.containsKey(it.targetSeq) && active[it.targetSeq] == false &&
                lastChangeBatch[it.targetSeq] == it.batchId
        }?.batchId
        val restorable = if (lastBatch == null) 0 else bases.keys.count {
            active[it] == false && lastChangeBatch[it] == lastBatch
        }
        return ReplayState(
            history = bases.values.map { HistoryEntry(it, active[it.seq] == true) }.asReversed(),
            activeEvents = bases.filterKeys { active[it] == true }.values.toList(),
            lastSequence = ordered.lastOrNull()?.seq ?: 0L,
            restorableBatchId = lastBatch,
            restorableCount = restorable,
        )
    }
}
