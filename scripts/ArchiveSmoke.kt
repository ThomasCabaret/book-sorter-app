package org.example.bookduels.core

import java.io.File

fun main(args: Array<String>) {
    check(args.size == 1)
    File(args[0]).outputStream().use { out ->
        TarGzipWriter.write(linkedMapOf(
            "manifest.json" to "{\"format\":\"test\"}\n".toByteArray(),
            "catalog.json" to "[]\n".toByteArray(),
            "events.jsonl" to ByteArray(0),
        ), out)
    }
}
