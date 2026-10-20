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
}
