package org.example.bookduels.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private fun item(
        id: Int,
        factor: Float,
        candidate: Boolean = false,
        workId: Long = id.toLong(),
    ) = RecommendationItem(
        datasetBookId = id,
        workId = workId,
        candidate = candidate,
        bias = 0f,
        datasetAverageRating = 4f,
        datasetRatingCount = 100,
        isbn13 = null,
        title = "External $id",
        authors = "Author $id",
        factors = floatArrayOf(factor),
    )

    @Test
    fun personalVectorRanksCandidateAlignedWithObservedVote() {
        val model = RecommendationModel(
            dimensions = 1,
            items = listOf(
                item(1, 1f), item(2, -1f),
                item(3, 0.8f, candidate = true),
                item(4, -0.8f, candidate = true),
            ),
            localAliases = mapOf("a" to 0, "b" to 1),
        )
        val catalog = listOf(Book("a", "A", "Author A"), Book("b", "B", "Author B"))
        val events = listOf(
            VoteEvent(1, "v1", 1, EventKind.VOTE, "a", "b", "a"),
            VoteEvent(2, "j1", 2, EventKind.JOKER, "a", "b"),
        )

        val report = model.recommend(catalog, events)

        assertEquals(2, report.matchedCatalogBooks)
        assertEquals(1, report.mappedActiveVotes)
        assertEquals(listOf(3, 4), report.books.map { it.datasetBookId })
        assertTrue(report.books[0].score > report.books[1].score)
    }

    @Test
    fun disabledVotesAndCatalogWorksAreNotRecommendations() {
        val model = RecommendationModel(
            dimensions = 1,
            items = listOf(
                item(1, 1f), item(2, -1f),
                item(3, 0.8f, candidate = true, workId = 30),
            ),
            localAliases = mapOf("a" to 0, "b" to 1, "known" to 2),
        )
        val catalog = listOf(
            Book("a", "A", "Author A"),
            Book("b", "B", "Author B", enabled = false),
            Book("known", "Known", "Author K", enabled = false),
        )
        val event = VoteEvent(1, "v1", 1, EventKind.VOTE, "a", "b", "a")

        val report = model.recommend(catalog, listOf(event))

        assertEquals(0, report.mappedActiveVotes)
        assertTrue(report.books.isEmpty())
    }

    @Test
    fun ieeeHalfConversionCoversNormalAndSubnormalValues() {
        assertEquals(0f, RecommendationModel.halfToFloat(0x0000))
        assertEquals(1f, RecommendationModel.halfToFloat(0x3c00))
        assertEquals(-2f, RecommendationModel.halfToFloat(0xc000))
        assertTrue(RecommendationModel.halfToFloat(0x0001) > 0f)
        assertTrue(RecommendationModel.halfToFloat(0x7c00).isInfinite())
    }

    @Test
    fun bundledModelIsStructurallyValidAndCompact() {
        val asset = File("src/main/assets/recommendation-model.bin")
        assertTrue(asset.isFile)
        assertTrue(asset.length() < 3_000_000)
        val model = asset.inputStream().use(RecommendationModel::read)
        assertEquals(10_000, model.itemCount)
        assertEquals(2_296, model.candidateCount)
        assertEquals(74, model.aliasCount)
    }
}
