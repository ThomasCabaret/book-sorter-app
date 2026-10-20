package org.example.bookduels.data

import org.example.bookduels.core.VoteEvent
import org.example.bookduels.core.TarGzipWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Stable exchange format for desktop analysis: standard USTAR + gzip,
 * SHA-256 checksums over the exact UTF-8 bytes for catalog and events.
 * No ratings imported from Babelio; no personal/account identifiers.
 * The archive is NOT encrypted: keep it in a private location.
 */
object PortableArchive {
    const val FORMAT = "bookduels.bundle.v1"

    private fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    private fun eventJson(e: VoteEvent): JSONObject = JSONObject().apply {
        put("seq", e.seq)
        put("eventId", e.eventId)
        put("timestampMs", e.timestampMs)
        put("kind", e.kind.name)
        putIfNotNull("firstBookId", e.firstBookId)
        putIfNotNull("secondBookId", e.secondBookId)
        putIfNotNull("chosenBookId", e.chosenBookId)
        putIfNotNull("targetSeq", e.targetSeq)
        putIfNotNull("batchId", e.batchId)
    }

    private fun makeEntries(snapshot: ArchiveSnapshot): Map<String, ByteArray> {
        val catalog = JSONArray()
        snapshot.catalog.sortedBy { it.book.id }.forEach { entry ->
            val book = entry.book
            catalog.put(JSONObject().apply {
                put("id", book.id); put("title", book.title); put("author", book.author)
                putIfNotNull("isbn13", book.isbn13)
                putIfNotNull("coverUrl", book.coverUrl)
                put("enabled", book.enabled)
                put("isbnAliases", JSONArray(entry.isbnAliases.sorted()))
                put("externalRefs", JSONArray(entry.externalRefs.sorted()))
            })
        }
        val events = snapshot.events.sortedBy { it.seq }.joinToString(separator = "\n", postfix = if (snapshot.events.isEmpty()) "" else "\n") {
            eventJson(it).toString()
        }
        return linkedMapOf(
            "catalog.json" to (catalog.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
            "events.jsonl" to events.toByteArray(StandardCharsets.UTF_8),
            "README.txt" to ("BookDuels portable export\n" +
                "Format: $FORMAT\n" +
                "catalog.json: stable work IDs, metadata, all edition ISBN aliases.\n" +
                "events.jsonl: chronological complete immutable event journal, including invalidations.\n" +
                "manifest.json: SHA-256 content hashes; validate before analysis.\n" +
                "Votes and jokers are NOT Babelio star ratings.\n" +
                "Export is unencrypted and may reveal reading preferences.\n").toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun write(snapshot: ArchiveSnapshot, output: OutputStream) {
        val payloads = makeEntries(snapshot)
        val files = JSONObject()
        for ((name, data) in payloads) files.put(name, JSONObject().apply {
            put("bytes", data.size)
            put("sha256", sha256(data))
        })
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("createdAtUtc", Instant.now().toString())
            put("books", snapshot.catalog.size)
            put("events", snapshot.events.size)
            put("bookIdentity", "stable-work-id; isbn-is-an-edition-alias")
            put("babelioRatingsImported", false)
            put("files", files)
        }
        val entries = linkedMapOf("manifest.json" to (manifest.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        entries.putAll(payloads)
        TarGzipWriter.write(entries, output)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
