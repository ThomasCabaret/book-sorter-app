package org.example.bookduels.data

import android.content.Context
import org.example.bookduels.core.Book
import org.json.JSONArray

/** Seed data is bundled, but SQLite becomes authoritative after first launch. */
object BookCatalog {
    fun loadBundled(context: Context): List<Book> {
        val json = context.assets.open("books.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val list = JSONArray(json)
        val books = (0 until list.length()).map { index ->
            val obj = list.getJSONObject(index)
            Book(
                id = obj.getString("id").trim(),
                title = obj.getString("title").trim(),
                author = obj.getString("author").trim(),
                isbn13 = obj.optString("isbn13").takeIf { it.isNotBlank() && it != "null" },
                coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                enabled = obj.optBoolean("enabled", true),
            )
        }
        require(books.all { it.id.isNotBlank() && it.title.isNotBlank() && it.author.isNotBlank() }) {
            "Every catalog entry needs a stable ID, title and author"
        }
        require(books.map { it.id }.distinct().size == books.size) { "Duplicate seed IDs" }
        require(books.all { it.isbn13 == null || org.example.bookduels.core.BabelioCsv.validIsbn13(it.isbn13) }) { "Invalid bundled ISBN" }
        return books
    }
}
