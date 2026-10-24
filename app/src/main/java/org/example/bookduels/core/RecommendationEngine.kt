package org.example.bookduels.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

data class RecommendedBook(
    val datasetBookId: Int,
    val workId: Long,
    val goodreadsBookId: Long,
    val title: String,
    val authors: String,
    val isbn13: String?,
    val datasetAverageRating: Float,
    val datasetRatingCount: Int,
    /** Latent utility, useful only for sorting this model's candidates. */
    val score: Float,
) {
    val referenceUrl: String
        get() = "https://www.goodreads.com/book/show/$goodreadsBookId"
}

data class RecommendationReport(
    val books: List<RecommendedBook>,
    val matchedCatalogBooks: Int,
    val mappedActiveVotes: Int,
    val eligibleCandidates: Int,
)

internal data class RecommendationItem(
    val datasetBookId: Int,
    val workId: Long,
    val goodreadsBookId: Long,
    val candidate: Boolean,
    val bias: Float,
    val datasetAverageRating: Float,
    val datasetRatingCount: Int,
    val isbn13: String?,
    val title: String,
    val authors: String,
    val factors: FloatArray,
)

/** Frozen generic factors plus lightweight, local personalization. */
internal class RecommendationModel(
    private val dimensions: Int,
    private val items: List<RecommendationItem>,
    private val localAliases: Map<String, Int>,
) {
    internal val itemCount: Int get() = items.size
    internal val candidateCount: Int get() = items.count { it.candidate }
    internal val aliasCount: Int get() = localAliases.size

    private val uniqueIsbn = uniqueIndex(items.mapIndexedNotNull { index, item ->
        item.isbn13?.let { it to index }
    })
    private val uniqueTitleAuthor = uniqueIndex(items.mapIndexed { index, item ->
        titleAuthorKey(item.title, item.authors) to index
    })

    fun recommend(
        catalog: List<Book>,
        activeEvents: List<VoteEvent>,
        limit: Int = 100,
    ): RecommendationReport {
        require(limit > 0) { "Recommendation limit must be positive" }
        val tentative = catalog.associate { book -> book.id to match(book) }.toMutableMap()
        // Two enabled local records resolving to one abstract work indicate a
        // split/omnibus identity problem. Such records are not training data.
        val collisions = catalog.asSequence()
            .filter { it.enabled }
            .mapNotNull { book -> tentative[book.id]?.let { items[it].workId to book.id } }
            .filter { it.first != 0L }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .values.flatten().toSet()
        collisions.forEach { tentative[it] = null }
        val localToItem = tentative.mapNotNull { (id, item) -> item?.let { id to it } }.toMap()
        val enabledIds = catalog.asSequence().filter { it.enabled }.map { it.id }.toSet()

        val gradient = FloatArray(dimensions)
        var mappedVotes = 0
        for (event in activeEvents) {
            if (event.kind != EventKind.VOTE) continue
            val firstId = event.firstBookId ?: continue
            val secondId = event.secondBookId ?: continue
            if (firstId !in enabledIds || secondId !in enabledIds) continue
            val first = localToItem[firstId] ?: continue
            val second = localToItem[secondId] ?: continue
            if (first == second) continue
            val winner = when (event.chosenBookId) {
                firstId -> first
                secondId -> second
                else -> continue
            }
            val loser = if (winner == first) second else first
            val winnerItem = items[winner]
            val loserItem = items[loser]
            val margin = winnerItem.bias - loserItem.bias
            val multiplier = -logistic(-margin)
            for (dimension in 0 until dimensions) {
                gradient[dimension] += multiplier *
                    (winnerItem.factors[dimension] - loserItem.factors[dimension])
            }
            mappedVotes++
        }

        if (mappedVotes == 0) {
            return RecommendationReport(emptyList(), localToItem.size, 0, 0)
        }
        // This is the exactly reproducible first AdamW personalization step
        // selected by the held-out benchmark. Weight decay is zero at origin.
        val personal = FloatArray(dimensions) { dimension ->
            val averaged = gradient[dimension] / mappedVotes
            -PERSONALIZATION_RATE * averaged / (abs(averaged) + ADAM_EPSILON)
        }

        val catalogWorkIds = localToItem.values.map { items[it].workId }.filter { it != 0L }.toSet()
        val catalogIsbns = catalog.mapNotNull { it.isbn13 }.toSet()
        val catalogTitleAuthors = catalog.map { titleAuthorKey(it.title, it.author) }.toSet()
        val knownSeries = localToItem.values.mapNotNull { seriesInfo(items[it].title)?.first }.toSet()
        val eligible = items.asSequence().filter { item ->
            val series = seriesInfo(item.title)
            item.candidate && item.workId !in catalogWorkIds &&
                (item.isbn13 == null || item.isbn13 !in catalogIsbns) &&
                titleAuthorKey(item.title, item.authors) !in catalogTitleAuthors &&
                (series == null || series.second <= 1.0 || series.first in knownSeries)
        }.map { item ->
            var personalScore = 0f
            for (dimension in 0 until dimensions) {
                personalScore += item.factors[dimension] * personal[dimension]
            }
            item to personalScore
        }.toList()
        if (eligible.isEmpty()) {
            return RecommendationReport(emptyList(), localToItem.size, mappedVotes, 0)
        }
        val meanPersonalScore = eligible.map { it.second }.average().toFloat()
        // A population prior may order plausible personal matches, but it may
        // never rescue a candidate with below-average personal affinity.
        val scored = eligible.asSequence().filter { it.second >= meanPersonalScore }.map { (item, personalScore) ->
            val score = personalScore + POPULATION_PRIOR_WEIGHT * item.bias
            RecommendedBook(
                datasetBookId = item.datasetBookId,
                workId = item.workId,
                goodreadsBookId = item.goodreadsBookId,
                title = item.title,
                authors = item.authors,
                isbn13 = item.isbn13,
                datasetAverageRating = item.datasetAverageRating,
                datasetRatingCount = item.datasetRatingCount,
                score = score,
            )
        }.toList()
        val ordered = scored.sortedWith(
            compareByDescending<RecommendedBook> { it.score }
                .thenBy { it.datasetBookId }
        )
        return RecommendationReport(
            books = ordered.take(limit),
            matchedCatalogBooks = localToItem.size,
            mappedActiveVotes = mappedVotes,
            eligibleCandidates = scored.size,
        )
    }

    private fun match(book: Book): Int? = localAliases[book.id]
        ?: book.isbn13?.let(uniqueIsbn::get)
        ?: uniqueTitleAuthor[titleAuthorKey(book.title, book.author)]

    companion object {
        private const val PERSONALIZATION_RATE = 0.01f
        private const val ADAM_EPSILON = 1e-8f
        private const val POPULATION_PRIOR_WEIGHT = 0.1f

        fun read(source: InputStream): RecommendationModel {
            val input = DataInputStream(BufferedInputStream(source))
            val magic = ByteArray(8).also(input::readFully).decodeToString()
            require(magic == "BDREC002") { "Unsupported recommendation model" }
            val itemCount = input.readInt()
            val dimensions = input.readInt()
            val aliasCount = input.readInt()
            require(itemCount in 1..20_000 && dimensions in 1..128 && aliasCount in 0..20_000) {
                "Invalid recommendation model dimensions"
            }
            val aliases = LinkedHashMap<String, Int>()
            repeat(aliasCount) {
                val id = readText(input)
                val item = input.readInt()
                require(id.isNotBlank() && item in 0 until itemCount && aliases.put(id, item) == null) {
                    "Invalid recommendation alias"
                }
            }
            val items = ArrayList<RecommendationItem>(itemCount)
            repeat(itemCount) { expectedIndex ->
                val datasetBookId = input.readInt()
                val workId = input.readLong()
                val goodreadsBookId = input.readLong()
                val flags = input.readInt()
                val bias = input.readFloat()
                val averageRating = input.readFloat()
                val ratingCount = input.readInt()
                val isbn = readText(input).ifBlank { null }
                val title = readText(input)
                val authors = readText(input)
                val factors = FloatArray(dimensions) { halfToFloat(input.readUnsignedShort()) }
                require(
                    datasetBookId == expectedIndex + 1 && workId >= 0 && goodreadsBookId > 0 &&
                        flags and 1.inv() == 0 &&
                        bias.isFinite() && averageRating.isFinite() && ratingCount >= 0 &&
                        title.isNotBlank() && authors.isNotBlank() && factors.all(Float::isFinite)
                ) { "Invalid recommendation item" }
                items += RecommendationItem(
                    datasetBookId, workId, goodreadsBookId, flags and 1 != 0, bias, averageRating,
                    ratingCount, isbn, title, authors, factors,
                )
            }
            require(input.read() == -1) { "Trailing recommendation model data" }
            return RecommendationModel(dimensions, items, aliases)
        }

        private fun readText(input: DataInputStream): String {
            val length = input.readUnsignedShort()
            return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        }

        internal fun halfToFloat(half: Int): Float {
            val sign = (half ushr 15) and 1
            val exponent = (half ushr 10) and 0x1f
            var fraction = half and 0x3ff
            val bits = when (exponent) {
                0 -> {
                    if (fraction == 0) sign shl 31 else {
                        var adjustedExponent = -14
                        while (fraction and 0x400 == 0) {
                            fraction = fraction shl 1
                            adjustedExponent--
                        }
                        fraction = fraction and 0x3ff
                        (sign shl 31) or ((adjustedExponent + 127) shl 23) or (fraction shl 13)
                    }
                }
                0x1f -> (sign shl 31) or 0x7f800000 or (fraction shl 13)
                else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (fraction shl 13)
            }
            return Float.fromBits(bits)
        }
    }
}

private fun logistic(value: Float): Float =
    if (value >= 0f) 1f / (1f + exp(-value)) else exp(value) / (1f + exp(value))

private fun normalize(value: String): String {
    val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    return decomposed.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex(" +"), " ")
}

private fun normalizeAuthor(value: String): String = normalize(value).split(' ')
    .filter(String::isNotBlank).sorted().joinToString(" ")

private fun titleAuthorKey(title: String, author: String): String =
    "${normalize(title)}\u0000${normalizeAuthor(author)}"

private val seriesPattern = Regex("\\(([^()]*)#\\s*([0-9]+(?:\\.[0-9]+)?)[^()]*\\)")

private fun seriesInfo(title: String): Pair<String, Double>? {
    val match = seriesPattern.find(title) ?: return null
    val series = normalize(match.groupValues[1].trim().trimEnd(','))
    val number = match.groupValues[2].toDoubleOrNull() ?: return null
    return series.takeIf(String::isNotBlank)?.let { it to number }
}

private fun uniqueIndex(entries: List<Pair<String, Int>>): Map<String, Int> {
    val grouped = entries.groupBy({ it.first }, { it.second })
    return grouped.mapNotNull { (key, values) ->
        values.distinct().singleOrNull()?.let { key to it }
    }.toMap()
}
