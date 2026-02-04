package com.example.c2wdemo.registry

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Parser for eStargz (extensible Stargz) compressed layers.
 *
 * eStargz is an OCI-compatible image format that enables lazy pulling:
 * - Each file in the tar is individually gzip-compressed
 * - A Table of Contents (TOC) at the end of the blob indexes all files
 * - The TOC offset is stored in a 51-byte footer at the very end
 * - HTTP Range requests can fetch individual files by offset
 *
 * Format structure:
 *   [gzip member 1: file1.tar.entry][gzip member 2: file2.tar.entry]...[gzip: TOC][footer]
 *
 * Footer (51 bytes):
 *   - gzip header (10 bytes)
 *   - Extra field containing hex-encoded TOC offset: "%016xSTARGZ"
 *   - flate header (5 bytes)
 *   - gzip footer (8 bytes)
 *
 * Reference: https://github.com/containerd/stargz-snapshotter/blob/main/docs/estargz.md
 */
class EstargzParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    companion object {
        /** Size of the eStargz footer in bytes. */
        const val FOOTER_SIZE = 51

        /** Magic string suffix in the footer extra field. */
        const val STARGZ_MAGIC = "STARGZ"

        /** Gzip magic number. */
        const val GZIP_MAGIC_1: Byte = 0x1f.toByte()
        const val GZIP_MAGIC_2: Byte = 0x8b.toByte()
    }

    /**
     * Parse the TOC offset from an eStargz footer.
     *
     * @param footer The last 51 bytes of the compressed blob
     * @return The byte offset where the TOC gzip member begins, or null if not eStargz
     */
    fun parseTocOffset(footer: ByteArray): Long? {
        if (footer.size < FOOTER_SIZE) return null

        val footerStart = footer.size - FOOTER_SIZE
        val footerBytes = footer.copyOfRange(footerStart, footer.size)

        // Verify gzip magic
        if (footerBytes[0] != GZIP_MAGIC_1 || footerBytes[1] != GZIP_MAGIC_2) {
            return null
        }

        // Check FLG byte for FEXTRA (bit 2)
        val flg = footerBytes[3].toInt() and 0xFF
        if (flg and 0x04 == 0) return null

        // XLEN is 2 bytes little-endian at offset 10
        val xlen = (footerBytes[10].toInt() and 0xFF) or
            ((footerBytes[11].toInt() and 0xFF) shl 8)

        if (xlen < 22) return null

        // Extra field data starts at offset 12
        val extraField = footerBytes.copyOfRange(12, 12 + xlen)
        val extraStr = String(extraField, Charsets.ISO_8859_1)

        // Look for the STARGZ magic in the extra field
        val magicIdx = extraStr.indexOf(STARGZ_MAGIC)
        if (magicIdx < 0) return null
        if (magicIdx < 16) return null

        val hexOffset = extraStr.substring(magicIdx - 16, magicIdx)
        return try {
            java.lang.Long.parseUnsignedLong(hexOffset, 16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Parse the TOC JSON from the raw gzip-compressed TOC data.
     *
     * @param tocGzipData The gzip-compressed TOC data (from tocOffset to end-of-footer)
     * @return Parsed TOC, or null if parsing fails
     */
    fun parseToc(tocGzipData: ByteArray): EstargzToc? {
        return try {
            val decompressed = decompressFirstGzipMember(tocGzipData)
            val tocJson = decompressed.decodeToString()
            json.decodeFromString<EstargzToc>(tocJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a file index from TOC entries for efficient lookup.
     */
    fun buildFileIndex(toc: EstargzToc): EstargzFileIndex {
        val fileMap = mutableMapOf<String, MutableList<EstargzTocEntry>>()

        for (entry in toc.entries) {
            val name = normalizePath(entry.name)
            when (entry.type) {
                "reg", "chunk" -> {
                    fileMap.getOrPut(name) { mutableListOf() }.add(entry)
                }
                "dir", "symlink", "hardlink" -> {
                    fileMap.getOrPut(name) { mutableListOf() }.add(entry)
                }
            }
        }

        val compressedSizes = calculateCompressedSizes(toc)

        return EstargzFileIndex(
            entries = fileMap,
            compressedSizes = compressedSizes,
            toc = toc,
        )
    }

    /**
     * Calculate compressed sizes of each gzip member.
     */
    private fun calculateCompressedSizes(toc: EstargzToc): Map<Long, Long> {
        val sizes = mutableMapOf<Long, Long>()

        val offsets = toc.entries
            .filter { it.offset > 0 }
            .map { it.offset }
            .distinct()
            .sorted()

        for (i in 0 until offsets.size - 1) {
            sizes[offsets[i]] = offsets[i + 1] - offsets[i]
        }

        return sizes
    }

    /**
     * Decompress only the first gzip member from a byte array
     * that may contain multiple concatenated gzip members.
     */
    private fun decompressFirstGzipMember(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        val gis = GZIPInputStream(bais)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (gis.read(buf).also { n = it } >= 0) {
            baos.write(buf, 0, n)
        }
        gis.close()
        return baos.toByteArray()
    }

    private fun normalizePath(path: String): String {
        var p = path
        if (p.startsWith("./")) p = p.substring(2)
        if (p.startsWith("/")) p = p.substring(1)
        if (p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }
}

/**
 * Index of files within an eStargz layer, built from the TOC.
 */
data class EstargzFileIndex(
    val entries: Map<String, List<EstargzTocEntry>>,
    val compressedSizes: Map<Long, Long>,
    val toc: EstargzToc,
) {
    fun getFile(path: String): List<EstargzTocEntry>? {
        val normalized = path.removePrefix("./").removePrefix("/").removeSuffix("/")
        return entries[normalized]
    }

    fun getCompressedRange(entry: EstargzTocEntry): LongRange? {
        val size = compressedSizes[entry.offset] ?: return null
        return entry.offset until (entry.offset + size)
    }

    fun listFiles(): Set<String> = entries.keys

    fun listDirectory(dirPath: String): List<String> {
        val normalized = dirPath.removePrefix("./").removePrefix("/").removeSuffix("/")
        val prefix = if (normalized.isEmpty()) "" else "$normalized/"
        return entries.keys
            .filter { it.startsWith(prefix) && it != normalized }
            .map { it.removePrefix(prefix).split("/").first() }
            .distinct()
    }

    val fileCount: Int get() = entries.size

    val prefetchEntries: List<EstargzTocEntry>
        get() = toc.prefetchEntries()

    val prefetchSize: Long
        get() {
            val entries = prefetchEntries
            if (entries.isEmpty()) return 0
            val minOffset = entries.minOf { it.offset }
            val maxEntry = entries.maxByOrNull { it.offset } ?: return 0
            val maxSize = compressedSizes[maxEntry.offset] ?: 0
            return (maxEntry.offset + maxSize) - minOffset
        }
}
