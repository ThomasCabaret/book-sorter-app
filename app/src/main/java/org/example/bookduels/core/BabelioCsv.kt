package org.example.bookduels.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Only 'Lu' rows become books. Rating values are intentionally ignored. */
data class ImportedBook(
    val title: String, val author: String, val isbn13: String?,
    val externalRef: String?, val row: Int,
)
data class ParsedBabelio(val readBooks: List<ImportedBook>, val skippedUnRead: Int)

object BabelioCsv {
    private const val MAX_ROWS = 50_000

    fun validIsbn13(value: String): Boolean = value.length == 13 && value.all { it in '0'..'9' } &&
        value.mapIndexed { index, ch -> ch.digitToInt() * (if (index % 2 == 0) 1 else 3) }.sum() % 10 == 0

    fun decode(bytes: ByteArray): String {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    /** Conservative matching: never merge books based on fuzzy similarity. */
    fun matchKey(input: String): String = Normalizer.normalize(input.trim(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    fun parse(bytes: ByteArray): ParsedBabelio = parseText(decode(bytes))

    fun parseText(text: String): ParsedBabelio {
        require(text.length <= 6_000_000) { "CSV too large" }
        val rows = parseRows(text)
        require(rows.isNotEmpty()) { "Empty CSV" }
        val header = rows.first().map { matchKey(it) }
        fun column(name: String): Int = header.indexOf(name).also {
            require(it >= 0) { "Missing Babelio column: $name" }
        }
        val isbnColumn = column("isbn")
        val titleColumn = column("titre")
        val authorColumn = column("auteur")
        val statusColumn = column("statut")
        val expected = rows.first().size
        val imported = mutableListOf<ImportedBook>()
        var skipped = 0
        for ((index, cells) in rows.drop(1).withIndex()) {
            // A completely empty final line is ignored by parseRows.
            require(cells.size == expected) { "Malformed CSV row ${index + 2}: ${cells.size} instead of $expected fields" }
            if (matchKey(cells[statusColumn]) != "lu") {
                skipped++
                continue
            }
            val title = cells[titleColumn].trim()
            val author = cells[authorColumn].trim()
            require(title.isNotBlank() && author.isNotBlank()) { "Missing book title/author at CSV row ${index + 2}" }
            require(title.length <= 512 && author.length <= 512) { "Oversized title/author at row ${index + 2}" }
            val ref = cells[isbnColumn].filterNot { it == '-' || it.isWhitespace() }.takeIf { it.isNotBlank() }
            require(ref == null || ref.matches(Regex("[A-Za-z0-9_]{1,80}"))) {
                "Invalid book reference at row ${index + 2}"
            }
            val isbn = ref?.takeIf { validIsbn13(it) }
            val externalRef = ref?.takeUnless { validIsbn13(it) }?.let { "babelio:$it" }
            imported += ImportedBook(title, author, isbn, externalRef, index + 2)
        }
        return ParsedBabelio(imported, skipped)
    }

    /** RFC-4180-style double quote handling; semicolon is the Babelio separator. */
    private fun parseRows(text: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0); closedQuote = false }
        fun endRow() {
            endField()
            result.add(row)
            require(result.size <= MAX_ROWS + 1) { "CSV row limit exceeded" }
            row = mutableListOf()
        }
        while (i < text.length) {
            when (val ch = text[i]) {
                '"' -> {
                    if (quoted && i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else if (quoted) {
                        quoted = false
                        closedQuote = true
                    } else {
                        require(field.isEmpty() && !closedQuote) { "Unexpected quote in CSV" }
                        quoted = true
                    }
                }
                ';' -> if (quoted) field.append(ch) else endField()
                '\r', '\n' -> if (quoted) field.append(ch) else {
                    endRow()
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> {
                    require(!closedQuote) { "Unexpected text after CSV quote" }
                    field.append(ch)
                }
            }
            i++
        }
        require(!quoted) { "Unterminated quoted CSV field" }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return result
    }
}
