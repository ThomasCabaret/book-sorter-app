package org.example.bookduels.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.example.bookduels.core.BabelioCsv
import org.example.bookduels.core.Book
import org.example.bookduels.core.EventKind
import org.example.bookduels.core.EventReplay
import org.example.bookduels.core.ImportedBook
import org.example.bookduels.core.ReplayState
import org.example.bookduels.core.VoteEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent ID is a work-level key. ISBN aliases identify editions, not votes. */
data class CatalogImportResult(
    val added: Int,
    val existing: Int,
    val editionAliasesAdded: Int,
    val ambiguous: Int,
    val skippedUnread: Int,
)

data class CatalogRecord(
    val book: Book, val isbnAliases: List<String>, val externalRefs: List<String>,
)
data class ArchiveSnapshot(val catalog: List<CatalogRecord>, val events: List<VoteEvent>)

/** No UPDATE or DELETE is ever permitted on the events table. */
class EventStore(context: Context) : SQLiteOpenHelper(context, "bookduels.db", null, 3) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createEventTable(db)
        createCatalogTables(db)
        createAppMetadataTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..2 && newVersion == 3) {
            "Manual nondestructive migration required: $oldVersion -> $newVersion"
        }
        // V1 -> V2 only ADDS catalog tables; all event sequences and payloads survive.
        if (oldVersion < 2) {
            createCatalogTables(db)
            preserveUnknownV1BookIds(db)
        }
        // V3 adds only a marker table for one-time, explicit catalog curations.
        createAppMetadataTable(db)
    }

    /** V1 had asset-only books: preserve references even if metadata was never stored. */
    private fun preserveUnknownV1BookIds(db: SQLiteDatabase) {
        val ids = mutableSetOf<String>()
        db.rawQuery(
            "SELECT first_book_id FROM events WHERE first_book_id IS NOT NULL " +
                "UNION SELECT second_book_id FROM events WHERE second_book_id IS NOT NULL", null
        ).use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getString(0)) }
        ids.forEach { id ->
            db.insertOrThrow("books", null, ContentValues().apply {
                put("book_id", id)
                put("title", "Legacy book (metadata unavailable): $id")
                put("author", "Unknown")
                putNull("isbn13")
                putNull("cover_url")
                put("enabled", 0)
                put("source", "V1_PLACEHOLDER")
                put("created_at_ms", System.currentTimeMillis())
            })
        }
    }

    private fun createEventTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                 seq INTEGER PRIMARY KEY AUTOINCREMENT,
                 event_id TEXT NOT NULL UNIQUE,
                 timestamp_ms INTEGER NOT NULL,
                 kind TEXT NOT NULL CHECK (kind IN ('VOTE','JOKER','INVALIDATE','REINSTATE')),
                 first_book_id TEXT,
                 second_book_id TEXT,
                 chosen_book_id TEXT,
                 target_seq INTEGER,
                 batch_id TEXT
               )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_target ON events(target_seq)")
        db.execSQL("CREATE INDEX idx_events_batch ON events(batch_id)")
    }

    private fun createCatalogTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books (
                book_id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                isbn13 TEXT,
                cover_url TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                source TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE book_isbns (
                isbn13 TEXT PRIMARY KEY NOT NULL,
                book_id TEXT NOT NULL REFERENCES books(book_id) ON DELETE RESTRICT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_book_isbns_book ON book_isbns(book_id)")
        db.execSQL(
            """CREATE TABLE book_external_refs (
                source_ref TEXT PRIMARY KEY NOT NULL,
                book_id TEXT NOT NULL REFERENCES books(book_id) ON DELETE RESTRICT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_book_refs_book ON book_external_refs(book_id)")
    }

    private fun createAppMetadataTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE app_metadata (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    fun allBooks(): List<Book> = readBooks(readableDatabase).map { it.book }

    private fun readBooks(db: SQLiteDatabase): List<CatalogRecord> {
        val aliases = mutableMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT isbn13,book_id FROM book_isbns ORDER BY isbn13", null).use { cursor ->
            while (cursor.moveToNext()) aliases.getOrPut(cursor.getString(1)) { mutableListOf() }.add(cursor.getString(0))
        }
        val refs = mutableMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT source_ref,book_id FROM book_external_refs ORDER BY source_ref", null).use { cursor ->
            while (cursor.moveToNext()) refs.getOrPut(cursor.getString(1)) { mutableListOf() }.add(cursor.getString(0))
        }
        val records = mutableListOf<CatalogRecord>()
        db.rawQuery("SELECT book_id,title,author,isbn13,cover_url,enabled FROM books ORDER BY title COLLATE NOCASE,book_id", null).use { cursor ->
            while (cursor.moveToNext()) {
                fun nullable(i: Int) = if (cursor.isNull(i)) null else cursor.getString(i)
                val book = Book(
                    id = cursor.getString(0), title = cursor.getString(1), author = cursor.getString(2),
                    isbn13 = nullable(3), coverUrl = nullable(4), enabled = cursor.getInt(5) == 1,
                )
                records += CatalogRecord(book, aliases[book.id].orEmpty(), refs[book.id].orEmpty())
            }
        }
        return records
    }

    /** On every launch, add only missing bundled entries; NEVER overwrite user edits or vote history. */
    fun mergeBundled(books: List<Book>): CatalogImportResult = mergeBooks(
        books.map { IncomingBook(it.title, it.author, it.isbn13,
            it.id.takeIf { id -> id.startsWith("babelio:") }, it.id, it.coverUrl, it.enabled) }, "BUNDLED", 0
    )

    fun importBabelio(rows: List<ImportedBook>, skippedUnread: Int): CatalogImportResult = mergeBooks(
        rows.map { IncomingBook(it.title, it.author, it.isbn13, it.externalRef) }, "BABELIO", skippedUnread
    )

    /** Returns a non-destructive duplicate summary if an existing entry matches. */
    fun addManually(title: String, author: String, isbn13: String?): CatalogImportResult = mergeBooks(
        listOf(IncomingBook(title.trim(), author.trim(), isbn13?.trim()?.takeIf { it.isNotBlank() })),
        "MANUAL", 0
    )

    private data class IncomingBook(
        val title: String,
        val author: String,
        val isbn: String?,
        val externalRef: String? = null,
        val preferredId: String? = null,
        val coverUrl: String? = null,
        val enabled: Boolean = true,
    )

    private fun mergeBooks(rows: List<IncomingBook>, origin: String, skippedUnread: Int): CatalogImportResult {
        require(rows.size <= 50_000) { "Too many books" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readBooks(db).map { it.book }
            val byId = existing.associateBy { it.id }.toMutableMap()
            val byIsbn = mutableMapOf<String, String>()
            db.rawQuery("SELECT isbn13,book_id FROM book_isbns", null).use { cursor ->
                while (cursor.moveToNext()) byIsbn[cursor.getString(0)] = cursor.getString(1)
            }
            val byExternal = mutableMapOf<String, String>()
            db.rawQuery("SELECT source_ref,book_id FROM book_external_refs", null).use { cursor ->
                while (cursor.moveToNext()) byExternal[cursor.getString(0)] = cursor.getString(1)
            }
            val byTitleAuthor = mutableMapOf<String, MutableSet<String>>()
            fun key(title: String, author: String) = BabelioCsv.matchKey(title) + "|" + BabelioCsv.matchKey(author)
            existing.forEach { byTitleAuthor.getOrPut(key(it.title, it.author)) { mutableSetOf() }.add(it.id) }
            var added = 0
            var duplicate = 0
            var aliases = 0
            var ambiguous = 0
            for (row in rows) {
                require(row.title.isNotBlank() && row.author.isNotBlank()) { "Book title and author are required" }
                require(row.title.length <= 512 && row.author.length <= 512) { "Title/author too long" }
                require(row.isbn == null || BabelioCsv.validIsbn13(row.isbn)) { "Invalid ISBN-13 checksum" }
                require(row.externalRef == null || row.externalRef.length <= 128) { "External reference too long" }
                require(row.coverUrl == null || row.coverUrl.startsWith("https://")) { "HTTPS cover URLs only" }
                val isbnHit = row.isbn?.let { byIsbn[it] }
                val refHit = row.externalRef?.let { byExternal[it] }
                if (isbnHit != null || refHit != null) {
                    require(isbnHit == null || refHit == null || isbnHit == refHit) {
                        "ISBN and external reference refer to different books"
                    }
                    duplicate++
                    continue
                }
                val identityMatches = byTitleAuthor[key(row.title, row.author)].orEmpty()
                val idHit = row.preferredId?.takeIf { byId.containsKey(it) }
                val matchId = when {
                    idHit != null -> idHit
                    identityMatches.size == 1 -> identityMatches.first()
                    identityMatches.size > 1 -> { ambiguous++; continue }
                    else -> null
                }
                if (matchId != null) {
                    var hasNewAlias = false
                    if (row.isbn != null) {
                        db.insertOrThrow("book_isbns", null, ContentValues().apply {
                            put("isbn13", row.isbn); put("book_id", matchId)
                        })
                        byIsbn[row.isbn] = matchId
                        hasNewAlias = true
                    }
                    if (row.externalRef != null) {
                        db.insertOrThrow("book_external_refs", null, ContentValues().apply {
                            put("source_ref", row.externalRef); put("book_id", matchId)
                        })
                        byExternal[row.externalRef] = matchId
                        hasNewAlias = true
                    }
                    // Never replace primary title/author/ISBN; aliases refer to the same work.
                    if (hasNewAlias) aliases++ else duplicate++
                    continue
                }
                val id = row.preferredId ?: row.isbn?.let { "isbn:$it" } ?: row.externalRef ?: "manual:${UUID.randomUUID()}"
                // An ID collision with different metadata must NEVER recycle that ID.
                require(id !in byId) { "Book ID collision: $id" }
                val book = Book(id, row.title, row.author, row.isbn, row.coverUrl, row.enabled)
                db.insertOrThrow("books", null, ContentValues().apply {
                    put("book_id", book.id); put("title", book.title); put("author", book.author)
                    put("isbn13", book.isbn13); put("cover_url", book.coverUrl)
                    put("enabled", if (book.enabled) 1 else 0)
                    put("source", origin); put("created_at_ms", System.currentTimeMillis())
                })
                if (row.isbn != null) {
                    db.insertOrThrow("book_isbns", null, ContentValues().apply {
                        put("isbn13", row.isbn); put("book_id", id)
                    })
                    byIsbn[row.isbn] = id
                }
                if (row.externalRef != null) {
                    db.insertOrThrow("book_external_refs", null, ContentValues().apply {
                        put("source_ref", row.externalRef); put("book_id", id)
                    })
                    byExternal[row.externalRef] = id
                }
                byId[id] = book
                byTitleAuthor.getOrPut(key(book.title, book.author)) { mutableSetOf() }.add(id)
                added++
            }
            db.setTransactionSuccessful()
            return CatalogImportResult(added, duplicate, aliases, ambiguous, skippedUnread)
        } finally { db.endTransaction() }
    }

    /**
     * Applies the user's explicit saga reconciliation once, after the bundled
     * catalog has been merged. Stable IDs and every event row are preserved.
     */
    fun applySagaCatalogCurationV1() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val alreadyApplied = db.rawQuery(
                "SELECT 1 FROM app_metadata WHERE key=?", arrayOf(SAGA_CURATION_V1)
            ).use { it.moveToFirst() }
            if (alreadyApplied) {
                db.setTransactionSuccessful()
                return
            }

            val canonicalTitles = mapOf(
                "isbn:9782221252055" to "Dune",
                "isbn:9782221255728" to "Le Messie de Dune",
                "isbn:9782221090268" to "Hypérion",
                "isbn:9782221090275" to "La Chute d’Hypérion",
                "isbn:9782070415717" to "Fondation et Empire",
            )
            canonicalTitles.forEach { (id, title) ->
                val updated = db.update("books", ContentValues().apply {
                    put("title", title)
                    put("enabled", 1)
                }, "book_id=? AND source<>?", arrayOf(id, "V1_PLACEHOLDER"))
                // Unknown V1 IDs remain disabled placeholders until a future,
                // evidence-backed merge flow; this curation never remaps them.
                if (updated == 0) {
                    val isPlaceholder = db.rawQuery(
                        "SELECT 1 FROM books WHERE book_id=? AND source=?",
                        arrayOf(id, "V1_PLACEHOLDER"),
                    ).use { it.moveToFirst() }
                    require(isPlaceholder) { "Missing canonical saga book: $id" }
                }
            }

            val setAsideIds = listOf(
                "isbn:9782221913291", // Dune 1+2 omnibus
                "isbn:9782221252338", // unread Dune novel
                "isbn:9782266064774", // split Hypérion volume
                "isbn:9782266172493", // split La Chute d'Hypérion volume 1
                "isbn:9782266152938", // split La Chute d'Hypérion volume 2
                "isbn:9782070396405", // Foundation five-book box
                "isbn:9782070416462", // unread Foundation novel
            )
            setAsideIds.forEach { id ->
                val updated = db.update("books", ContentValues().apply { put("enabled", 0) },
                    "book_id=?", arrayOf(id))
                require(updated == 1) { "Missing redundant saga entry: $id" }
            }

            db.insertOrThrow("app_metadata", null, ContentValues().apply {
                put("key", SAGA_CURATION_V1)
                put("value", System.currentTimeMillis().toString())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Corrects reviewed catalog metadata without changing IDs, visibility, or events. */
    fun applyCatalogMetadataCurationV2() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val alreadyApplied = db.rawQuery(
                "SELECT 1 FROM app_metadata WHERE key=?", arrayOf(CATALOG_METADATA_CURATION_V2)
            ).use { it.moveToFirst() }
            if (alreadyApplied) {
                db.setTransactionSuccessful()
                return
            }
            val updated = db.update("books", ContentValues().apply {
                put("title", "La Fille-flûte et autres fragments de futurs brisés")
            }, "book_id=? AND source<>?", arrayOf("isbn:9782846267984", "V1_PLACEHOLDER"))
            if (updated == 0) {
                val isPlaceholder = db.rawQuery(
                    "SELECT 1 FROM books WHERE book_id=? AND source=?",
                    arrayOf("isbn:9782846267984", "V1_PLACEHOLDER"),
                ).use { it.moveToFirst() }
                require(isPlaceholder) { "Missing Bacigalupi collection for metadata correction" }
            }
            db.insertOrThrow("app_metadata", null, ContentValues().apply {
                put("key", CATALOG_METADATA_CURATION_V2)
                put("value", System.currentTimeMillis().toString())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Hiding a book does not modify any historical event. */
    fun setEnabled(id: String, enabled: Boolean) {
        val updated = writableDatabase.update("books", ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "book_id=?", arrayOf(id))
        require(updated == 1) { "Book no longer exists" }
    }

    private companion object {
        const val SAGA_CURATION_V1 = "saga_catalog_curation_v1"
        const val CATALOG_METADATA_CURATION_V2 = "catalog_metadata_curation_v2"
    }

    fun events(): List<VoteEvent> = readEvents(readableDatabase)
    fun state(): ReplayState = EventReplay.reconstruct(events())

    /** Same database snapshot for both catalog and full journal. */
    fun snapshot(): ArchiveSnapshot {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = ArchiveSnapshot(readBooks(db), readEvents(db))
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun readEvents(db: SQLiteDatabase): List<VoteEvent> {
        val result = mutableListOf<VoteEvent>()
        db.rawQuery("SELECT seq,event_id,timestamp_ms,kind,first_book_id,second_book_id,chosen_book_id,target_seq,batch_id FROM events ORDER BY seq ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                fun nullable(i: Int) = if (cursor.isNull(i)) null else cursor.getString(i)
                result.add(VoteEvent(
                    seq = cursor.getLong(0), eventId = cursor.getString(1), timestampMs = cursor.getLong(2),
                    kind = EventKind.valueOf(cursor.getString(3)), firstBookId = nullable(4), secondBookId = nullable(5),
                    chosenBookId = nullable(6), targetSeq = if (cursor.isNull(7)) null else cursor.getLong(7),
                    batchId = nullable(8),
                ))
            }
        }
        return result
    }

    private fun insert(db: SQLiteDatabase, kind: EventKind, first: String? = null, second: String? = null,
        winner: String? = null, target: Long? = null, batchId: String? = null): Long {
        val values = ContentValues().apply {
            put("event_id", UUID.randomUUID().toString())
            put("timestamp_ms", System.currentTimeMillis())
            put("kind", kind.name); put("first_book_id", first); put("second_book_id", second)
            put("chosen_book_id", winner)
            if (target != null) put("target_seq", target) else putNull("target_seq")
            put("batch_id", batchId)
        }
        return db.insertOrThrow("events", null, values)
    }

    fun record(first: String, second: String, winner: String?): Long {
        require(first != second)
        require(winner == null || winner == first || winner == second)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = readBooks(db).map { it.book }.filter { it.enabled }.map { it.id }.toSet()
            require(first in ids && second in ids) { "One of the books is no longer active" }
            val sequence = insert(db, if (winner == null) EventKind.JOKER else EventKind.VOTE, first, second, winner)
            db.setTransactionSuccessful()
            return sequence
        } finally { db.endTransaction() }
    }

    /** Fast session undo: compensate only the latest active base event; never rewrite history. */
    fun invalidateLatest(targetSeq: Long, expectedRevision: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val replay = EventReplay.reconstruct(readEvents(db))
            require(replay.lastSequence == expectedRevision) { "History changed; refresh before undo" }
            val latestActive = replay.history.firstOrNull { it.active }?.event?.seq
            require(latestActive == targetSeq) { "Only the latest active decision can be undone here" }
            insert(db, EventKind.INVALIDATE, target = targetSeq, batchId = UUID.randomUUID().toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun invalidateFrom(fromSeq: Long, expectedRevision: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val replay = EventReplay.reconstruct(readEvents(db))
            require(replay.lastSequence == expectedRevision) { "History changed; refresh before rollback" }
            require(replay.history.any { it.event.seq == fromSeq }) { "Unknown history entry" }
            val targets = replay.history.filter { it.active && it.event.seq >= fromSeq }
            require(targets.isNotEmpty()) { "No active votes or jokers in selected range" }
            val batch = UUID.randomUUID().toString()
            targets.forEach { insert(db, EventKind.INVALIDATE, target = it.event.seq, batchId = batch) }
            db.setTransactionSuccessful()
            return targets.size
        } finally { db.endTransaction() }
    }

    fun reinstateLatestBatch(expectedRevision: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val replay = EventReplay.reconstruct(readEvents(db))
            require(replay.lastSequence == expectedRevision) { "History changed; refresh before restore" }
            val batch = replay.restorableBatchId ?: error("Nothing to restore")
            val changes = mutableMapOf<Long, VoteEvent>()
            for (event in readEvents(db)) {
                if (event.kind == EventKind.INVALIDATE || event.kind == EventKind.REINSTATE) {
                    event.targetSeq?.let { changes[it] = event }
                }
            }
            val targets = replay.history.filter { entry ->
                !entry.active && changes[entry.event.seq]?.let {
                    it.kind == EventKind.INVALIDATE && it.batchId == batch
                } == true
            }
            require(targets.isNotEmpty()) { "Nothing to restore" }
            targets.forEach { insert(db, EventKind.REINSTATE, target = it.event.seq, batchId = batch) }
            db.setTransactionSuccessful()
            return targets.size
        } finally { db.endTransaction() }
    }
}
