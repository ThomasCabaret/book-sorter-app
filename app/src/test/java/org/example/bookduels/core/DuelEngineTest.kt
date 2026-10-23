package org.example.bookduels.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelEngineTest {
    private val books = listOf(Book("a", "A", "X"), Book("b", "B", "X"), Book("c", "C", "X"))
    private fun vote(n: Long, first: String, second: String, chosen: String): VoteEvent =
        VoteEvent(n, "id-$n", n, EventKind.VOTE, first, second, chosen)

    private fun repeatedVotes(
        start: Long,
        count: Int,
        first: String,
        second: String,
        chosen: (Int) -> String,
    ): List<VoteEvent> = (0 until count).map { offset ->
        vote(start + offset, first, second, chosen(offset))
    }

    @Test fun directCondorcetWinnerMustTopRankedPairs() {
        val votes = listOf(vote(1,"a","b","a"),vote(2,"a","c","a"),vote(3,"b","c","b"))
        val report = DuelEngine.analyze(books, votes, 3)
        assertEquals("a", report.directCondorcetWinner?.id)
        assertEquals("a", report.books.first().book.id)
    }

    @Test fun cycleMustNotInventCondorcetWinner() {
        val report = DuelEngine.analyze(books,
            listOf(vote(1,"a","b","a"),vote(2,"b","c","b"),vote(3,"c","a","c")), 3)
        assertNull(report.directCondorcetWinner)
        assertEquals(1, report.cycleEdgesSkipped)
    }

    @Test fun replayRetainsInvalidatedEvents() {
        val a = vote(1,"a","b","a")
        val state = EventReplay.reconstruct(listOf(a,
            VoteEvent(2,"invalidate",2,EventKind.INVALIDATE,targetSeq=1,batchId="x")))
        assertEquals(0, state.activeEvents.size)
        assertEquals(1, state.history.size)
        assertTrue(!state.history.first().active)
    }

    @Test fun jokerIsNotAVoteAndSuppressesImmediateRematch() {
        val initial = DuelEngine.analyze(books, emptyList(), 0).duel
        assertNotNull(initial)
        initial!!
        val joker = VoteEvent(1, "joker", 1, EventKind.JOKER, initial.first.id, initial.second.id)
        val report = DuelEngine.analyze(books, listOf(joker), 1)
        assertEquals(0, report.votes)
        assertEquals(1, report.jokers)
        assertEquals(0, report.observedPairs)
        assertNotEquals(setOf(initial.first.id, initial.second.id),
            setOf(report.duel?.first?.id, report.duel?.second?.id))
    }

    @Test fun reinstatementReactivatesWithoutRemovingHistory() {
        val original = vote(1, "a", "b", "a")
        val invalidation = VoteEvent(2, "invalidate", 2, EventKind.INVALIDATE,
            targetSeq = 1, batchId = "batch")
        val reinstatement = VoteEvent(3, "reinstate", 3, EventKind.REINSTATE,
            targetSeq = 1, batchId = "batch")
        val state = EventReplay.reconstruct(listOf(original, invalidation, reinstatement))
        assertEquals(1, state.activeEvents.size)
        assertEquals(1, state.history.size)
        assertTrue(state.history.single().active)
        assertEquals(3, state.lastSequence)
    }

    @Test fun setAsideBookAndItsHistoricalDuelsAreExcludedUntilReenabled() {
        val historicalVotes = listOf(vote(1, "a", "c", "c"), vote(2, "a", "b", "a"))
        val setAside = books.map { if (it.id == "c") it.copy(enabled = false) else it }

        val withoutC = DuelEngine.analyze(setAside, historicalVotes, 2)
        assertEquals(listOf("a", "b"), withoutC.books.map { it.book.id })
        assertEquals(1, withoutC.votes)

        val restored = DuelEngine.analyze(books, historicalVotes, 2)
        assertEquals(3, restored.books.size)
        assertEquals(2, restored.votes)
    }

    @Test fun ordinaryLaneUsesAnUnseenPairInsteadOfRepeating() {
        val events = repeatedVotes(1, 18, "a", "b") { "a" }

        val report = DuelEngine.analyze(books, events, 18, consistencyCheckRate = 0.0)

        assertTrue(!report.isConsistencyCheck)
        assertNotEquals(setOf("a", "b"), setOf(report.duel?.first?.id, report.duel?.second?.id))
    }

    @Test fun configuredConsistencyLaneRechecksAPreviouslySeenPair() {
        val events = repeatedVotes(1, 7, "a", "b") { "a" }

        val report = DuelEngine.analyze(
            books, events, 7, consistencyCheckRate = 1.0, selectionEntropy = 42
        )

        assertTrue(report.isConsistencyCheck)
        assertEquals(setOf("a", "b"), setOf(report.duel?.first?.id, report.duel?.second?.id))
    }

    @Test fun consistencyLanePrefersConflictedPairOverTrivialPair() {
        val fourBooks = books + Book("d", "D", "X")
        val obvious = repeatedVotes(1, 10, "a", "b") { "a" }
        val conflicted = repeatedVotes(11, 9, "c", "d") { offset ->
            if (offset < 5) "c" else "d"
        }

        val report = DuelEngine.analyze(
            fourBooks, obvious + conflicted, 19,
            consistencyCheckRate = 1.0,
            selectionEntropy = 42,
        )

        assertTrue(report.isConsistencyCheck)
        assertEquals(setOf("c", "d"), setOf(report.duel?.first?.id, report.duel?.second?.id))
    }

    @Test fun repeatedVotesProduceAggregateConsistencyStatistics() {
        val events = listOf(
            vote(1, "a", "b", "a"),
            vote(2, "a", "b", "a"),
            vote(3, "a", "b", "b"),
            vote(4, "a", "c", "a"),
        )

        val report = DuelEngine.analyze(books, events, 4, consistencyCheckRate = 0.0)

        assertEquals(1, report.repeatedVotePairs)
        assertEquals(2, report.repeatedPreferenceVotes)
        assertEquals(2.0 / 3.0, report.pairwiseConsistency!!, 1e-9)
    }
}
