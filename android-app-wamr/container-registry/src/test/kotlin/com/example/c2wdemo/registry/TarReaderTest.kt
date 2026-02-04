package com.example.c2wdemo.registry

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class TarReaderTest {

    /**
     * Build a minimal tar archive in memory with the given entries.
     * Each entry is a pair of (filename, content).
     */
    private fun buildTar(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        for ((name, content) in entries) {
            val header = ByteArray(512)

            // Name field (offset 0, 100 bytes)
            val nameBytes = name.toByteArray(Charset.forName("UTF-8"))
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))

            // Mode (offset 100, 8 bytes) - "0000644\0"
            val mode = "0000644\u0000".toByteArray()
            System.arraycopy(mode, 0, header, 100, mode.size)

            // UID (offset 108, 8 bytes) - "0000000\0"
            val uid = "0000000\u0000".toByteArray()
            System.arraycopy(uid, 0, header, 108, uid.size)

            // GID (offset 116, 8 bytes) - "0000000\0"
            System.arraycopy(uid, 0, header, 116, uid.size)

            // Size (offset 124, 12 bytes) - octal
            val sizeStr = "%011o\u0000".format(content.size)
            System.arraycopy(sizeStr.toByteArray(), 0, header, 124, 12)

            // Mtime (offset 136, 12 bytes)
            val mtime = "00000000000\u0000".toByteArray()
            System.arraycopy(mtime, 0, header, 136, 12)

            // Type flag (offset 156) - '0' for regular file
            header[156] = '0'.code.toByte()

            // Checksum (offset 148, 8 bytes) - calculate
            // First fill checksum field with spaces
            for (i in 148..155) header[i] = ' '.code.toByte()
            var checksum = 0
            for (b in header) checksum += (b.toInt() and 0xFF)
            val chkStr = "%06o\u0000 ".format(checksum)
            System.arraycopy(chkStr.toByteArray(), 0, header, 148, 8)

            out.write(header)
            out.write(content)

            // Pad to 512-byte boundary
            val remainder = content.size % 512
            if (remainder > 0) {
                out.write(ByteArray(512 - remainder))
            }
        }

        // End-of-archive: two 512-byte blocks of zeros
        out.write(ByteArray(1024))

        return out.toByteArray()
    }

    @Test
    fun `readFile extracts file from tar`() {
        val content = "Hello, World!".toByteArray()
        val tar = buildTar("hello.txt" to content)
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "hello.txt")
        assertNotNull(result)
        assertEquals("Hello, World!", String(result!!))
    }

    @Test
    fun `readFile returns null for missing file`() {
        val tar = buildTar("hello.txt" to "content".toByteArray())
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "missing.txt")
        assertNull(result)
    }

    @Test
    fun `readFile strips leading dot-slash from target`() {
        val content = "data".toByteArray()
        val tar = buildTar("myfile.txt" to content)
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "./myfile.txt")
        assertNotNull(result)
        assertEquals("data", String(result!!))
    }

    @Test
    fun `readFile strips leading slash from target`() {
        val content = "data".toByteArray()
        val tar = buildTar("etc/passwd" to content)
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "/etc/passwd")
        assertNotNull(result)
    }

    @Test
    fun `listEntries returns all entries`() {
        val tar = buildTar(
            "file1.txt" to "aaa".toByteArray(),
            "file2.txt" to "bbb".toByteArray(),
            "file3.wasm" to "ccc".toByteArray(),
        )
        val input = ByteArrayInputStream(tar)

        val entries = TarReader.listEntries(input)
        assertEquals(3, entries.size)
        assertEquals("file1.txt", entries[0].name)
        assertEquals("file2.txt", entries[1].name)
        assertEquals("file3.wasm", entries[2].name)
        assertEquals(TarEntryType.FILE, entries[0].type)
    }

    @Test
    fun `listEntries reports correct sizes`() {
        val tar = buildTar(
            "small.txt" to "hi".toByteArray(),
            "bigger.txt" to "a".repeat(1000).toByteArray(),
        )
        val input = ByteArrayInputStream(tar)

        val entries = TarReader.listEntries(input)
        assertEquals(2, entries.size)
        assertEquals(2L, entries[0].size)
        assertEquals(1000L, entries[1].size)
    }

    @Test
    fun `extractAll returns all file contents`() {
        val tar = buildTar(
            "a.txt" to "alpha".toByteArray(),
            "b.txt" to "beta".toByteArray(),
        )
        val input = ByteArrayInputStream(tar)

        val files = TarReader.extractAll(input)
        assertEquals(2, files.size)
        assertEquals("alpha", String(files["a.txt"]!!))
        assertEquals("beta", String(files["b.txt"]!!))
    }

    @Test
    fun `readFile works with multiple files`() {
        val tar = buildTar(
            "first.txt" to "1111".toByteArray(),
            "second.txt" to "2222".toByteArray(),
            "third.txt" to "3333".toByteArray(),
        )
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "third.txt")
        assertNotNull(result)
        assertEquals("3333", String(result!!))
    }

    @Test
    fun `readFile handles binary content`() {
        val binaryContent = ByteArray(256) { it.toByte() }
        val tar = buildTar("binary.bin" to binaryContent)
        val input = ByteArrayInputStream(tar)

        val result = TarReader.readFile(input, "binary.bin")
        assertNotNull(result)
        assertArrayEquals(binaryContent, result!!)
    }

    @Test
    fun `extractAll handles empty tar`() {
        // Just the end-of-archive marker
        val tar = ByteArray(1024)
        val input = ByteArrayInputStream(tar)

        val files = TarReader.extractAll(input)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `listEntries handles empty tar`() {
        val tar = ByteArray(1024)
        val input = ByteArrayInputStream(tar)

        val entries = TarReader.listEntries(input)
        assertTrue(entries.isEmpty())
    }
}
