package com.example.c2wdemo.registry

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WasmArtifactProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var provider: WasmArtifactProvider
    private val messages = mutableListOf<String>()

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        provider = WasmArtifactProvider(cacheDir) { msg -> messages.add(msg) }
    }

    @After
    fun tearDown() {
        provider.shutdown()
    }

    @Test
    fun `isCached returns false for unknown image`() {
        assertFalse(provider.isCached("ghcr.io/example/app:v1"))
    }

    @Test
    fun `isCached returns true after manual cache population`() {
        // Manually populate the cache to simulate a previous ensure() call
        val imageRef = "ghcr.io/example/app:v1"

        // Create the artifact directory structure
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(imageRef.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safeName = imageRef.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        val artifactDir = File(cacheDir, "artifacts/${safeName}_$hash")
        artifactDir.mkdirs()

        // Create a wasm file and marker
        File(artifactDir, "app.wasm").writeBytes(ByteArray(100) { 0x00 })
        File(artifactDir, ".complete").writeText(imageRef)

        assertTrue(provider.isCached(imageRef))
    }

    @Test
    fun `isCached returns false if wasm file missing despite marker`() {
        val imageRef = "ghcr.io/example/app:v1"

        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(imageRef.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safeName = imageRef.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        val artifactDir = File(cacheDir, "artifacts/${safeName}_$hash")
        artifactDir.mkdirs()

        // Only marker, no wasm file
        File(artifactDir, ".complete").writeText(imageRef)

        assertFalse(provider.isCached(imageRef))
    }

    @Test
    fun `evict removes cached artifact`() {
        val imageRef = "ghcr.io/example/app:v1"

        // Populate cache
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(imageRef.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safeName = imageRef.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        val artifactDir = File(cacheDir, "artifacts/${safeName}_$hash")
        artifactDir.mkdirs()
        File(artifactDir, "app.wasm").writeBytes(ByteArray(100))
        File(artifactDir, ".complete").writeText(imageRef)

        assertTrue(provider.isCached(imageRef))

        provider.evict(imageRef)

        assertFalse(provider.isCached(imageRef))
        assertFalse(artifactDir.exists())
    }

    @Test
    fun `evictAll removes all cached artifacts`() {
        // Populate two caches
        for (tag in listOf("v1", "v2")) {
            val imageRef = "ghcr.io/example/app:$tag"
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(imageRef.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val safeName = imageRef.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
            val artifactDir = File(cacheDir, "artifacts/${safeName}_$hash")
            artifactDir.mkdirs()
            File(artifactDir, "app.wasm").writeBytes(ByteArray(100))
            File(artifactDir, ".complete").writeText(imageRef)
        }

        assertTrue(provider.isCached("ghcr.io/example/app:v1"))
        assertTrue(provider.isCached("ghcr.io/example/app:v2"))

        provider.evictAll()

        assertFalse(provider.isCached("ghcr.io/example/app:v1"))
        assertFalse(provider.isCached("ghcr.io/example/app:v2"))
    }

    @Test
    fun `evict is safe on non-existent image`() {
        // Should not throw
        provider.evict("ghcr.io/nonexistent/image:v1")
    }

    @Test
    fun `evictAll is safe on empty cache`() {
        // Should not throw
        provider.evictAll()
    }

    @Test
    fun `different image refs produce different cache dirs`() {
        val ref1 = "ghcr.io/example/app:v1"
        val ref2 = "ghcr.io/example/app:v2"

        fun artifactDir(imageRef: String): File {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(imageRef.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val safeName = imageRef.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
            return File(cacheDir, "artifacts/${safeName}_$hash")
        }

        assertNotEquals(artifactDir(ref1).path, artifactDir(ref2).path)
    }

    @Test
    fun `shutdown is idempotent`() {
        provider.shutdown()
        provider.shutdown() // should not throw
    }
}
