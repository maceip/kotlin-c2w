package com.example.c2wdemo.registry

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EstargzParserTest {

    private val parser = EstargzParser()

    /**
     * Build a valid eStargz footer with the given TOC offset.
     *
     * Footer structure (51 bytes):
     *   - gzip header (10 bytes): 1f 8b 08 04 00 00 00 00 00 ff
     *   - XLEN (2 bytes LE): 0x1a 0x00 = 26
     *   - Extra field (26 bytes): SI1 SI2 LEN(2) + 22 bytes of payload
     *     payload = 16-char hex TOC offset + "STARGZ"
     *   - flate block (5 bytes): 01 00 00 ff ff
     *   - CRC32 (4 bytes): 00 00 00 00
     *   - ISIZE (4 bytes): 00 00 00 00
     */
    private fun buildFooter(tocOffset: Long): ByteArray {
        val footer = ByteArray(51)

        // Gzip header
        footer[0] = 0x1f.toByte()
        footer[1] = 0x8b.toByte()
        footer[2] = 0x08.toByte() // CM = deflate
        footer[3] = 0x04.toByte() // FLG = FEXTRA
        // MTIME (4 bytes) = 0
        // XFL = 0
        footer[9] = 0xff.toByte() // OS = unknown

        // XLEN = 26 (little-endian)
        footer[10] = 26.toByte()
        footer[11] = 0.toByte()

        // Extra field subfield header (4 bytes): SI1='S', SI2='G', LEN=22 LE
        footer[12] = 'S'.code.toByte()
        footer[13] = 'G'.code.toByte()
        footer[14] = 22.toByte()
        footer[15] = 0.toByte()

        // Payload: 16-char hex offset + "STARGZ"
        val hexOffset = "%016x".format(tocOffset)
        val payload = hexOffset + "STARGZ"
        for (i in payload.indices) {
            footer[16 + i] = payload[i].code.toByte()
        }

        // offset 38: flate block header (empty stored block)
        footer[38] = 0x01.toByte()
        footer[39] = 0x00.toByte()
        footer[40] = 0x00.toByte()
        footer[41] = 0xff.toByte()
        footer[42] = 0xff.toByte()

        // CRC32 (4 bytes) = 0
        // ISIZE (4 bytes) = 0

        return footer
    }

    @Test
    fun `parseTocOffset extracts offset from valid footer`() {
        val footer = buildFooter(12345L)
        val offset = parser.parseTocOffset(footer)
        assertNotNull(offset)
        assertEquals(12345L, offset!!)
    }

    @Test
    fun `parseTocOffset handles large offset`() {
        val footer = buildFooter(0x0000ABCDEF123456L)
        val offset = parser.parseTocOffset(footer)
        assertNotNull(offset)
        assertEquals(0x0000ABCDEF123456L, offset!!)
    }

    @Test
    fun `parseTocOffset returns null for non-estargz data`() {
        val randomBytes = ByteArray(51) { (it * 7).toByte() }
        val offset = parser.parseTocOffset(randomBytes)
        assertNull(offset)
    }

    @Test
    fun `parseTocOffset returns null for too-short data`() {
        val tooShort = ByteArray(30)
        val offset = parser.parseTocOffset(tooShort)
        assertNull(offset)
    }

    @Test
    fun `parseTocOffset returns null when FEXTRA not set`() {
        val footer = buildFooter(100L)
        footer[3] = 0x00.toByte() // Clear FEXTRA flag
        val offset = parser.parseTocOffset(footer)
        assertNull(offset)
    }

    @Test
    fun `parseToc decompresses and parses TOC JSON`() {
        val tocJson = """{"version":1,"entries":[{"name":"hello.txt","type":"reg","size":5,"offset":0}]}"""
        val compressed = gzipCompress(tocJson.toByteArray())

        val toc = parser.parseToc(compressed)
        assertNotNull(toc)
        assertEquals(1, toc!!.version)
        assertEquals(1, toc.entries.size)
        assertEquals("hello.txt", toc.entries[0].name)
        assertEquals("reg", toc.entries[0].type)
        assertEquals(5L, toc.entries[0].size)
    }

    @Test
    fun `parseToc returns null for invalid data`() {
        val invalidData = ByteArray(20) { it.toByte() }
        val toc = parser.parseToc(invalidData)
        assertNull(toc)
    }

    @Test
    fun `buildFileIndex creates searchable index`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "etc/", type = "dir", size = 0, offset = 0),
                EstargzTocEntry(name = "etc/passwd", type = "reg", size = 100, offset = 512),
                EstargzTocEntry(name = "etc/hosts", type = "reg", size = 50, offset = 1024),
                EstargzTocEntry(name = "usr/bin/app.wasm", type = "reg", size = 5000, offset = 2048),
            ),
        )

        val index = parser.buildFileIndex(toc)
        assertEquals(4, index.fileCount)

        val passwd = index.getFile("etc/passwd")
        assertNotNull(passwd)
        assertEquals(1, passwd!!.size)
        assertEquals(100L, passwd[0].size)

        val wasm = index.getFile("usr/bin/app.wasm")
        assertNotNull(wasm)

        val missing = index.getFile("nonexistent")
        assertNull(missing)
    }

    @Test
    fun `buildFileIndex calculates compressed sizes from offsets`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "a.txt", type = "reg", size = 10, offset = 0),
                EstargzTocEntry(name = "b.txt", type = "reg", size = 20, offset = 100),
                EstargzTocEntry(name = "c.txt", type = "reg", size = 30, offset = 300),
            ),
        )

        val index = parser.buildFileIndex(toc)

        // Compressed size of entry at offset 0 = 100 - 0 = 100
        val rangeA = index.getCompressedRange(toc.entries[0])
        assertNotNull(rangeA)
        assertEquals(0L, rangeA!!.first)
        assertEquals(99L, rangeA.last)

        // Compressed size of entry at offset 100 = 300 - 100 = 200
        val rangeB = index.getCompressedRange(toc.entries[1])
        assertNotNull(rangeB)
        assertEquals(100L, rangeB!!.first)
        assertEquals(299L, rangeB.last)

        // Last entry has unknown compressed size
        val rangeC = index.getCompressedRange(toc.entries[2])
        assertNull(rangeC)
    }

    @Test
    fun `EstargzFileIndex listFiles returns all file names`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "a.txt", type = "reg", size = 10, offset = 0),
                EstargzTocEntry(name = "b.txt", type = "reg", size = 20, offset = 100),
            ),
        )
        val index = parser.buildFileIndex(toc)
        val files = index.listFiles()
        assertTrue(files.contains("a.txt"))
        assertTrue(files.contains("b.txt"))
    }

    @Test
    fun `EstargzFileIndex listDirectory lists direct children`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "etc/", type = "dir", size = 0, offset = 0),
                EstargzTocEntry(name = "etc/passwd", type = "reg", size = 100, offset = 512),
                EstargzTocEntry(name = "etc/hosts", type = "reg", size = 50, offset = 1024),
                EstargzTocEntry(name = "etc/subdir/file.txt", type = "reg", size = 10, offset = 2048),
            ),
        )
        val index = parser.buildFileIndex(toc)
        val contents = index.listDirectory("etc")
        assertTrue(contents.contains("passwd"))
        assertTrue(contents.contains("hosts"))
        assertTrue(contents.contains("subdir"))
        assertEquals(3, contents.size)
    }

    @Test
    fun `EstargzTocEntry type checks work`() {
        val regEntry = EstargzTocEntry(name = "f.txt", type = "reg")
        assertTrue(regEntry.isRegularFile)
        assertFalse(regEntry.isDirectory)
        assertFalse(regEntry.isSymlink)
        assertFalse(regEntry.isChunk)

        val dirEntry = EstargzTocEntry(name = "dir/", type = "dir")
        assertTrue(dirEntry.isDirectory)
        assertFalse(dirEntry.isRegularFile)

        val symlinkEntry = EstargzTocEntry(name = "link", type = "symlink", linkName = "target")
        assertTrue(symlinkEntry.isSymlink)

        val chunkEntry = EstargzTocEntry(name = "bigfile", type = "chunk", chunkOffset = 1024)
        assertTrue(chunkEntry.isChunk)
    }

    @Test
    fun `parseTocOffset with zero offset`() {
        val footer = buildFooter(0L)
        val offset = parser.parseTocOffset(footer)
        assertNotNull(offset)
        assertEquals(0L, offset!!)
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val gzip = GZIPOutputStream(baos)
        gzip.write(data)
        gzip.close()
        return baos.toByteArray()
    }
}
