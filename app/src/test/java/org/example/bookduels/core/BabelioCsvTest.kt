package org.example.bookduels.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BabelioCsvTest {
    @Test fun parsesWindows1252QuotedFieldsAndFiltersUnreadRows() {
        val csv = listOf(
            "\"ISBN\";\"Titre\";\"Auteur\";\"Statut\";\"Note\"",
            "\"9780000000001\";\"L'été d'un roi\";\"A. Writer\";\"Lu\";\"1.0\"",
            "\"9780000000002\";\"Not read\";\"A. Writer\";\"A lire\";\"5.0\"",
            "\"9780000000003\";\"Quoted \"\"Book\"\"; volume 2\";\"A. Writer\";\"Lu\";\"0.0\"",
        ).joinToString("\r\n", postfix = "\r\n")

        val parsed = BabelioCsv.parse(csv.toByteArray(charset("windows-1252")))

        assertEquals(2, parsed.readBooks.size)
        assertEquals(1, parsed.skippedUnRead)
        assertEquals("L'été d'un roi", parsed.readBooks[0].title)
        assertEquals("Quoted \"Book\"; volume 2", parsed.readBooks[1].title)
    }

    @Test fun keepsOpaqueBabelioReferencesOutOfTheIsbnField() {
        val csv = "\"ISBN\";\"Titre\";\"Auteur\";\"Statut\"\n" +
            "\"SIE206057_900\";\"Sample Ebook\";\"Author\";\"Lu\"\n"

        val book = BabelioCsv.parseText(csv).readBooks.single()

        assertNull(book.isbn13)
        assertEquals("babelio:SIE206057_900", book.externalRef)
        assertTrue(BabelioCsv.validIsbn13("9780306406157"))
        assertFalse(BabelioCsv.validIsbn13("978B0B46P7QV7"))
        assertEquals("editions la mer", BabelioCsv.matchKey("Éditions —  La  Mer"))
    }
}
