package org.example.bookduels.core

/** No Android SDK or third-party dependencies needed for these regression checks. */
fun main() {
    val books = listOf(
        Book("a", "Alpha", "Author A"),
        Book("b", "Beta", "Author B"),
        Book("c", "Gamma", "Author C"),
    )
    fun event(seq: Long, first: String, second: String, winner: String? = null): VoteEvent =
        VoteEvent(seq, "event-$seq", seq * 1000, if (winner == null) EventKind.JOKER else EventKind.VOTE,
            first, second, winner)
    val baseline = DuelEngine.analyze(books, emptyList(), 0)
    check(baseline.duel != null && baseline.votes == 0 && baseline.observedPairs == 0)
    check(baseline.connectedComponents == 3 && baseline.adjacentStability == 0.0)
    val wins = listOf(event(1,"a","b","a"), event(2,"a","c","a"), event(3,"b","c","b"))
    val result = DuelEngine.analyze(books, wins, 3)
    check(result.directCondorcetWinner?.id == "a")
    check(result.books.map { it.book.id } == listOf("a", "b", "c"))
    check(result.votes == 3 && result.observedPairs == 3)
    check(result.connectedComponents == 1 && result.cycleEdgesSkipped == 0)

    val cycle = DuelEngine.analyze(books, listOf(event(1,"a","b","a"), event(2,"b","c","b"),
        event(3,"c","a","c")), 3)
    check(cycle.directCondorcetWinner == null && cycle.cycleEdgesSkipped == 1)
    check(cycle.books.map { it.book.id }.toSet().size == 3)

    // A joker does not move preference scores but sharply suppresses that pair.
    val onlyTwo = books.take(2)
    val jokerA = event(1, "a", "b")
    val jokerB = event(2, "a", "b")
    val j = DuelEngine.analyze(onlyTwo, listOf(jokerA, jokerB), 2)
    check(j.jokers == 2 && j.votes == 0 && j.observedPairs == 0)
    val selectedPair = requireNotNull(baseline.duel)
    val oneJoker = event(1, selectedPair.first.id, selectedPair.second.id)
    val afterJoker = DuelEngine.analyze(books, listOf(oneJoker), 1)
    check(setOf(afterJoker.duel?.first?.id, afterJoker.duel?.second?.id) !=
        setOf(selectedPair.first.id, selectedPair.second.id))
    val firstDuel = requireNotNull(DuelEngine.analyze(onlyTwo, emptyList(), 0).duel)
    val secondDuel = requireNotNull(DuelEngine.analyze(onlyTwo,
        listOf(event(1, firstDuel.first.id, firstDuel.second.id, firstDuel.first.id)), 1).duel)
    check(firstDuel.first.id == secondDuel.second.id) // Counterbalanced screen position.

    val events = listOf(
        event(1,"a","b","a"),
        event(2,"b","c","b"),
        VoteEvent(3,"reversal",3000,EventKind.INVALIDATE,targetSeq=2,batchId="batch-1"),
    )
    val reversed = EventReplay.reconstruct(events)
    check(reversed.history.size == 2 && reversed.activeEvents.size == 1)
    check(reversed.restorableBatchId == "batch-1" && reversed.restorableCount == 1)
    check(reversed.history.first().event.seq == 2L && !reversed.history.first().active)
    val restored = EventReplay.reconstruct(events +
        VoteEvent(4,"restore",4000,EventKind.REINSTATE,targetSeq=2,batchId="batch-1"))
    check(restored.activeEvents.size == 2 && restored.restorableCount == 0)
    check(restored.history.first().active && restored.lastSequence == 4L)
    // Windows-1252 Babelio CSV, semicolon quoting, escaped quotes, and status filter.
    val fixture = listOf(
        "\"ISBN\";\"Titre\";\"Auteur\";\"Statut\";\"Note\"",
        "\"9780000000001\";\"L'ete d'un roi\";\"A. Writer\";\"Lu\";\"1.0\"",
        "\"9780000000002\";\"Not read\";\"A. Writer\";\"A lire\";\"5.0\"",
        "\"9780000000003\";\"Quoted \"\"Book\"\"; volume 2\";\"A. Writer\";\"Lu\";\"0.0\"",
    ).joinToString("\r\n", postfix = "\r\n").replace("L'ete", "L'été")
    val parsed = BabelioCsv.parse(fixture.toByteArray(charset("windows-1252")))
    check(parsed.readBooks.size == 2 && parsed.skippedUnRead == 1)
    check(parsed.readBooks[0].title == "L'été d'un roi")
    check(parsed.readBooks[1].title == "Quoted \"Book\"; volume 2")
    check(BabelioCsv.matchKey("Éditions —  La  Mer") == "editions la mer")
    val ebook = "\"ISBN\";\"Titre\";\"Auteur\";\"Statut\"\n" +
        "\"SIE206057_900\";\"Sample Ebook\";\"Author\";\"Lu\"\n"
    val opaque = BabelioCsv.parseText(ebook).readBooks.single()
    check(opaque.isbn13 == null && opaque.externalRef == "babelio:SIE206057_900")
    check(BabelioCsv.validIsbn13("9780306406157"))
    check(!BabelioCsv.validIsbn13("978B0B46P7QV7"))
    // Ratings are not even stored in ImportedBook.
    println("PASS: Condorcet ranking, joker decay, history replay, Babelio CSV encoding and status filtering")
}
