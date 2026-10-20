package org.example.bookduels.core

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Minimal USTAR writer. Paths are fixed ASCII file names, never user-provided. */
object TarGzipWriter {
    fun write(entries: Map<String, ByteArray>, output: OutputStream) {
        require(entries.isNotEmpty())
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { (name, bytes) -> writeEntry(gzip, name, bytes) }
            gzip.write(ByteArray(1024))
            gzip.finish()
        }
    }

    private fun octal(dst: ByteArray, offset: Int, count: Int, value: Long) {
        require(value >= 0)
        val digits = value.toString(8)
        require(digits.length <= count - 1) { "TAR size exceeds header field" }
        val padded = digits.padStart(count - 1, '0').toByteArray(StandardCharsets.US_ASCII)
        padded.copyInto(dst, destinationOffset = offset)
        dst[offset + count - 1] = 0
    }

    private fun writeEntry(out: OutputStream, name: String, content: ByteArray) {
        val bytes = name.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size in 1..100 && name.matches(Regex("[A-Za-z0-9._-]+")))
        val header = ByteArray(512)
        bytes.copyInto(header)
        octal(header, 100, 8, 420) // 0644
        octal(header, 108, 8, 0)
        octal(header, 116, 8, 0)
        octal(header, 124, 12, content.size.toLong())
        octal(header, 136, 12, System.currentTimeMillis() / 1000)
        for (i in 148..155) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 257)
        "00".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 263)
        val checksum = header.sumOf { it.toInt() and 0xff }
        val digits = checksum.toString(8).padStart(6, '0').toByteArray(StandardCharsets.US_ASCII)
        require(digits.size == 6)
        digits.copyInto(header, 148)
        header[154] = 0
        header[155] = ' '.code.toByte()
        out.write(header)
        out.write(content)
        val padding = (512 - content.size % 512) % 512
        if (padding != 0) out.write(ByteArray(padding))
    }
}
