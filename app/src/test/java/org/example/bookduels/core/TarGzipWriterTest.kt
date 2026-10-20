package org.example.bookduels.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarGzipWriterTest {
    @Test fun writesReadableUstarEntriesAndEndMarkers() {
        val expected = linkedMapOf(
            "manifest.json" to "{\"format\":\"test\"}\n".toByteArray(),
            "catalog.json" to "[]\n".toByteArray(),
            "events.jsonl" to ByteArray(0),
        )
        val output = ByteArrayOutputStream()

        TarGzipWriter.write(expected, output)
        val tar = GZIPInputStream(output.toByteArray().inputStream()).use { it.readBytes() }

        var offset = 0
        expected.forEach { (expectedName, expectedContent) ->
            val header = tar.copyOfRange(offset, offset + 512)
            val name = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }
                .toByteArray().toString(Charsets.US_ASCII)
            val sizeText = header.copyOfRange(124, 136).takeWhile { it != 0.toByte() }
                .toByteArray().toString(Charsets.US_ASCII).trim()
            val size = sizeText.toInt(8)
            assertEquals(expectedName, name)
            assertEquals(expectedContent.size, size)
            assertEquals("ustar", header.copyOfRange(257, 262).toString(Charsets.US_ASCII))
            val contentStart = offset + 512
            assertArrayEquals(expectedContent, tar.copyOfRange(contentStart, contentStart + size))
            offset = contentStart + ((size + 511) / 512) * 512
        }
        assertTrue(tar.copyOfRange(offset, offset + 1024).all { it == 0.toByte() })
    }
}
