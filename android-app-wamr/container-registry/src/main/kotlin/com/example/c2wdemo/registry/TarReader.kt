package com.example.c2wdemo.registry

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Minimal tar archive reader for extracting files from OCI layer tarballs.
 *
 * Supports:
 *   - POSIX/GNU tar format
 *   - Regular files, directories, symlinks
 *   - Whiteout markers (.wh. prefix) for overlay filesystems
 *
 * Does NOT support:
 *   - Sparse files
 *   - Extended headers (pax)
 *   - Multi-volume archives
 */
object TarReader {

    private const val BLOCK_SIZE = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155

    /**
     * Read a specific file from a tar input stream.
     *
     * @param input Uncompressed tar data stream
     * @param targetPath Path to extract (e.g. "etc/passwd")
     * @return File contents, or null if not found
     */
    fun readFile(input: InputStream, targetPath: String): ByteArray? {
        val normalizedTarget = targetPath.removePrefix("./").removePrefix("/")
        val header = ByteArray(BLOCK_SIZE)

        while (true) {
            val headerRead = readFully(input, header)
            if (headerRead < BLOCK_SIZE) break

            if (header.all { it == 0.toByte() }) break

            val name = extractName(header)
            val size = extractSize(header)
            val typeFlag = header[TYPE_OFFSET]

            val normalizedName = name.removePrefix("./").removePrefix("/").removeSuffix("/")

            if (normalizedName == normalizedTarget && (typeFlag == '0'.code.toByte() || typeFlag == 0.toByte())) {
                val content = ByteArray(size.toInt())
                readFully(input, content)
                return content
            }

            val dataBlocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
            skipFully(input, dataBlocks * BLOCK_SIZE)
        }

        return null
    }

    /**
     * List all entries in a tar stream.
     */
    fun listEntries(input: InputStream): List<TarEntry> {
        val entries = mutableListOf<TarEntry>()
        val header = ByteArray(BLOCK_SIZE)

        while (true) {
            val headerRead = readFully(input, header)
            if (headerRead < BLOCK_SIZE) break
            if (header.all { it == 0.toByte() }) break

            val name = extractName(header)
            val size = extractSize(header)
            val typeFlag = header[TYPE_OFFSET]

            val type = when (typeFlag.toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '1' -> TarEntryType.HARDLINK
                '2' -> TarEntryType.SYMLINK
                '5' -> TarEntryType.DIRECTORY
                else -> TarEntryType.OTHER
            }

            entries.add(TarEntry(
                name = name.removePrefix("./").removePrefix("/"),
                size = size,
                type = type,
            ))

            val dataBlocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
            skipFully(input, dataBlocks * BLOCK_SIZE)
        }

        return entries
    }

    /**
     * Extract all regular files from a tar stream into a map.
     */
    fun extractAll(input: InputStream): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        val header = ByteArray(BLOCK_SIZE)

        while (true) {
            val headerRead = readFully(input, header)
            if (headerRead < BLOCK_SIZE) break
            if (header.all { it == 0.toByte() }) break

            val name = extractName(header)
            val size = extractSize(header)
            val typeFlag = header[TYPE_OFFSET]

            val normalizedName = name.removePrefix("./").removePrefix("/")

            if (typeFlag == '0'.code.toByte() || typeFlag == 0.toByte()) {
                val content = ByteArray(size.toInt())
                readFully(input, content)
                files[normalizedName] = content

                val remainder = size % BLOCK_SIZE
                if (remainder > 0) {
                    skipFully(input, BLOCK_SIZE - remainder)
                }
            } else {
                val dataBlocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
                skipFully(input, dataBlocks * BLOCK_SIZE)
            }
        }

        return files
    }

    private fun extractName(header: ByteArray): String {
        val prefix = extractString(header, PREFIX_OFFSET, PREFIX_LENGTH)
        val name = extractString(header, NAME_OFFSET, NAME_LENGTH)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun extractSize(header: ByteArray): Long {
        val sizeStr = extractString(header, SIZE_OFFSET, SIZE_LENGTH).trim()
        if (sizeStr.isEmpty()) return 0

        if (header[SIZE_OFFSET].toInt() and 0x80 != 0) {
            var size = 0L
            for (i in SIZE_OFFSET + 1 until SIZE_OFFSET + SIZE_LENGTH) {
                size = (size shl 8) or (header[i].toLong() and 0xFF)
            }
            return size
        }

        return try {
            sizeStr.toLong(8)
        } catch (e: NumberFormatException) {
            0
        }
    }

    private fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = header.indexOf(0.toByte(), offset).let {
            if (it < 0 || it >= offset + length) offset + length else it
        }
        return String(header, offset, end - offset, Charset.forName("UTF-8")).trim('\u0000')
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val toRead = remaining.coerceAtMost(8192).toInt()
                val buf = ByteArray(toRead)
                val read = input.read(buf)
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }
}

data class TarEntry(
    val name: String,
    val size: Long,
    val type: TarEntryType,
)

enum class TarEntryType {
    FILE, DIRECTORY, SYMLINK, HARDLINK, OTHER
}
