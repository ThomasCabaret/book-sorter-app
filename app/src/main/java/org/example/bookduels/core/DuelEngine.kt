package org.example.bookduels.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.exp

private fun logistic(x: Double): Double {
    return if (x >= 0.0) 1.0 / (1.0 + exp(-x)) else exp(x) / (1.0 + exp(x))
}

private data class PairKey(val a: String, val b: String) {
    companion object {
        fun of(x: String, y: String): PairKey =
            if (x < y) PairKey(x, y) else PairKey(y, x)
    }
}

private data class MajorityEdge(val winner: Int, val loser: Int, val margin: Int, val totalVotes: Int)

private data class PairStats(
    var winsA: Int = 0,
    var winsB: Int = 0,
    var jokers: Int = 0,
    var shownAFirst: Int = 0,
    var shownBFirst: Int = 0,
) {
    val votes get() = winsA + winsB
}

data class RankedBook(
    val book: Book,
    val score: Double,
    val standardErrorProxy: Double,
    val votes: Int,
)

data class RankingReport(
    val books: List<RankedBook>,
    val duel: Duel?,
    val votes: Int,
    val jokers: Int,
    val observedPairs: Int,
    val possiblePairs: Int,
    val involvedBooks: Int,
    val connectedComponents: Int,
    val cycleEdgesSkipped: Int,
    /** Heuristic score, not a calibrated posterior probability. */
    val adjacentStability: Double,
    /** Strict majority winner from observed direct comparisons with every other book, if any. */
    val directCondorcetWinner: Book?,
)

/**
 * Regularized Bradley-Terry ranking + an active duel selection heuristic.
 *
 * The fitted model presumes a latent transitive preference order; cyclic Condorcet
 * majorities are NOT guaranteed to agree with it. Joker events influence only
 * duel acquisition, not the ranking fit.
 */
object DuelEngine {
    private const val PRIOR_PRECISION = 1.5

    fun analyze(catalog: List<Book>, activeEvents: List<VoteEvent>, revision: Long): RankingReport {
        val books = catalog.filter { it.enabled }.sortedBy { it.id }
        val ids = books.map { it.id }
        val byId = ids.withIndex().associate { it.value to it.index }
        val n = books.size
        val pairs = mutableMapOf<PairKey, PairStats>()
        val comparisons = mutableListOf<Pair<Int, Int>>()
        val appearances = IntArray(n)
        var jokerCount = 0

        for (e in activeEvents) {
            val first = e.firstBookId ?: continue
            val second = e.secondBookId ?: continue
            val fi = byId[first] ?: continue
            val si = byId[second] ?: continue
            if (fi == si) continue
            val key = PairKey.of(first, second)
            val stats = pairs.getOrPut(key) { PairStats() }
            if (e.kind == EventKind.VOTE || e.kind == EventKind.JOKER) {
                if (first == key.a) stats.shownAFirst++ else stats.shownBFirst++
            }
            when (e.kind) {
                EventKind.VOTE -> {
                    val winner = e.chosenBookId
                    if (winner != first && winner != second) continue
                    val wi = byId[winner] ?: continue
                    val li = if (wi == fi) si else fi
                    comparisons.add(wi to li)
                    appearances[wi]++
                    appearances[li]++
                    if (winner == key.a) stats.winsA++ else stats.winsB++
                }
                EventKind.JOKER -> { stats.jokers++; jokerCount++ }
                else -> Unit
            }
        }

        val theta = DoubleArray(n)
        val curvature = DoubleArray(n)
        // Damped Jacobi diagonal Newton steps. L2 regularization makes the model
        // identifiable even when the comparison graph is disconnected.
        for (iteration in 0 until 300) {
            val grad = DoubleArray(n) { -PRIOR_PRECISION * theta[it] }
            val hess = DoubleArray(n) { PRIOR_PRECISION }
            for ((w, l) in comparisons) {
                val p = logistic(theta[w] - theta[l])
                val residual = 1.0 - p
                val information = p * (1.0 - p)
                grad[w] += residual
                grad[l] -= residual
                hess[w] += information
                hess[l] += information
            }
            var change = 0.0
            for (i in 0 until n) {
                val delta = 0.6 * grad[i] / hess[i]
                theta[i] += delta
                change = max(change, abs(delta))
            }
            if (change < 1e-8) break
        }
        for (i in 0 until n) curvature[i] = PRIOR_PRECISION
        for ((w, l) in comparisons) {
            val p = logistic(theta[w] - theta[l])
            val info = p * (1 - p)
            curvature[w] += info
            curvature[l] += info
        }
        val std = DoubleArray(n) { 1.0 / sqrt(curvature[it]) }
        // Ranked Pairs (Tideman): lock pairwise majority edges from strongest to
        // weakest, rejecting edges that would create a directed cycle. A
        // topological sort with Bradley-Terry tie-breaking completes the ranking
        // when most direct comparisons have not yet been observed.
        val majorityEdges = pairs.flatMap { (key, stats) ->
            val margin = abs(stats.winsA - stats.winsB)
            if (margin == 0) emptyList() else {
                val winnerId = if (stats.winsA > stats.winsB) key.a else key.b
                val loserId = if (winnerId == key.a) key.b else key.a
                listOf(MajorityEdge(byId.getValue(winnerId), byId.getValue(loserId), margin, stats.votes))
            }
        }.sortedWith(compareByDescending<MajorityEdge> { it.margin }
            .thenByDescending { it.totalVotes }
            .thenBy { books[it.winner].id }
            .thenBy { books[it.loser].id })
        val locked = Array(n) { mutableListOf<Int>() }
        fun reaches(from: Int, to: Int): Boolean {
            val seen = BooleanArray(n)
            val stack = ArrayDeque<Int>()
            stack.add(from)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (current == to) return true
                if (seen[current]) continue
                seen[current] = true
                locked[current].forEach { stack.add(it) }
            }
            return false
        }
        var skipped = 0
        for (edge in majorityEdges) {
            if (reaches(edge.loser, edge.winner)) skipped++
            else locked[edge.winner].add(edge.loser)
        }
        val indegree = IntArray(n)
        for (i in 0 until n) locked[i].forEach { indegree[it]++ }
        val ready = (0 until n).filter { indegree[it] == 0 }.toMutableList()
        val ranks = mutableListOf<Int>()
        while (ready.isNotEmpty()) {
            val best = ready.sortedWith(compareByDescending<Int> { theta[it] }.thenBy { books[it].title }).first()
            ready.remove(best)
            ranks.add(best)
            for (next in locked[best]) {
                indegree[next]--
                if (indegree[next] == 0) ready.add(next)
            }
        }
        check(ranks.size == n) { "Ranked Pairs must produce an acyclic order" }
        val ranked = ranks.map { RankedBook(books[it], theta[it], std[it], appearances[it]) }

        // Connected components of the observed voting graph (joker-only pairs
        // do not provide a ranking relation).
        val edges = Array(n) { mutableListOf<Int>() }
        for ((key, stats) in pairs) {
            if (stats.votes == 0) continue
            val a = byId.getValue(key.a)
            val b = byId.getValue(key.b)
            edges[a].add(b)
            edges[b].add(a)
        }
        val visited = BooleanArray(n)
        var components = 0
        for (start in 0 until n) {
            if (visited[start]) continue
            components++
            val stack = ArrayDeque<Int>()
            stack.add(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                for (next in edges[stack.removeLast()]) {
                    if (!visited[next]) { visited[next] = true; stack.add(next) }
                }
            }
        }

        val directWinner = if (n < 2) null else books.firstOrNull { book ->
            books.filter { it.id != book.id }.all { opponent ->
                val key = PairKey.of(book.id, opponent.id)
                val s = pairs[key] ?: return@all false
                if (book.id == key.a) s.winsA > s.winsB else s.winsB > s.winsA
            }
        }

        // Adjacent-order certainty proxy: diagonal-Hessian approximation, not
        // a posterior guarantee. Penalized by disconnected subgraphs.
        val stability = if (n < 2) 0.0 else {
            val adjacent = ranks.zipWithNext().map { (a, b) ->
                val z = abs(theta[a] - theta[b]) / sqrt(std[a] * std[a] + std[b] * std[b])
                2.0 * (logistic(1.702 * z) - 0.5)
            }.average()
            adjacent * ((n - components).toDouble() / (n - 1).toDouble())
        }

        var duel: Duel? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (a in 0 until n) {
            for (b in a + 1 until n) {
                val stats = pairs[PairKey.of(books[a].id, books[b].id)] ?: PairStats()
                val p = logistic(theta[a] - theta[b])
                val uncertainty = p * (1.0 - p)
                val approxGain = 0.5 * ln(1.0 + uncertainty * (std[a] * std[a] + std[b] * std[b]))
                // Multiple jokers suppress recurrence sharply, without permanently
                // forbidding a pair. Previously compared pairs are downweighted.
                val jokerFactor = 1.0 / ((stats.jokers + 1.0) * (stats.jokers + 1.0) * (stats.jokers + 1.0))
                val repeatFactor = 1.0 / sqrt(1.0 + stats.votes.toDouble())
                val connectivityBonus = 1.0 + (if (appearances[a] == 0) 0.2 else 0.0) +
                    (if (appearances[b] == 0) 0.2 else 0.0)
                // Stable deterministic tie breaking across restarts. Changing the
                // revision rotates ties; no random choice stored only in memory.
                val tie = (((books[a].id + ":" + books[b].id).hashCode().toLong() xor revision)
                    .and(0xffffL)).toDouble() / 65535.0
                val score = approxGain * jokerFactor * repeatFactor * connectivityBonus *
                    (1.0 + tie * 0.0001)
                if (score > bestScore) {
                    bestScore = score
                    val showAFirst = when {
                        stats.shownAFirst < stats.shownBFirst -> true
                        stats.shownAFirst > stats.shownBFirst -> false
                        else -> (((books[a].id + ":" + books[b].id).hashCode().toLong() xor revision)
                            .and(1L)) == 0L
                    }
                    duel = if (showAFirst) Duel(books[a], books[b]) else Duel(books[b], books[a])
                }
            }
        }

        return RankingReport(
            books = ranked,
            duel = duel,
            votes = comparisons.size,
            jokers = jokerCount,
            observedPairs = pairs.values.count { it.votes > 0 },
            possiblePairs = n * (n - 1) / 2,
            involvedBooks = appearances.count { it > 0 },
            connectedComponents = components,
            cycleEdgesSkipped = skipped,
            adjacentStability = stability,
            directCondorcetWinner = directWinner,
        )
    }
}
