package com.example.c2wdemo.registry

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OciModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `OciDescriptor shortDigest extracts first 12 chars`() {
        val desc = OciDescriptor(
            digest = "sha256:abcdef1234567890abcdef",
            size = 100,
        )
        assertEquals("abcdef123456", desc.shortDigest)
    }

    @Test
    fun `OciDescriptor hasEstargzToc detects annotation`() {
        val withToc = OciDescriptor(
            annotations = mapOf(
                "containerd.io/snapshot/stargz/toc.digest" to "sha256:abc"
            ),
        )
        assertTrue(withToc.hasEstargzToc)

        val withoutToc = OciDescriptor(annotations = null)
        assertFalse(withoutToc.hasEstargzToc)

        val emptyAnnotations = OciDescriptor(annotations = emptyMap())
        assertFalse(emptyAnnotations.hasEstargzToc)
    }

    @Test
    fun `OciDescriptor estargzTocDigest returns annotation value`() {
        val desc = OciDescriptor(
            annotations = mapOf(
                "containerd.io/snapshot/stargz/toc.digest" to "sha256:tocdigest"
            ),
        )
        assertEquals("sha256:tocdigest", desc.estargzTocDigest)
    }

    @Test
    fun `OciImageManifest serialization round-trip`() {
        val manifest = OciImageManifest(
            schemaVersion = 2,
            config = OciDescriptor(
                mediaType = OciMediaTypes.IMAGE_CONFIG,
                digest = "sha256:config123",
                size = 1024,
            ),
            layers = listOf(
                OciDescriptor(
                    mediaType = OciMediaTypes.LAYER_TAR_GZIP,
                    digest = "sha256:layer1",
                    size = 50000,
                ),
                OciDescriptor(
                    mediaType = OciMediaTypes.LAYER_TAR_GZIP,
                    digest = "sha256:layer2",
                    size = 30000,
                ),
            ),
        )

        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<OciImageManifest>(encoded)

        assertEquals(2, decoded.schemaVersion)
        assertEquals("sha256:config123", decoded.config.digest)
        assertEquals(2, decoded.layers.size)
        assertEquals("sha256:layer1", decoded.layers[0].digest)
        assertEquals(50000L, decoded.layers[0].size)
    }

    @Test
    fun `OciImageConfig deserialization`() {
        val configJson = """
        {
            "architecture": "amd64",
            "os": "linux",
            "config": {
                "Env": ["PATH=/usr/local/bin:/usr/bin"],
                "Cmd": ["/bin/sh"],
                "Entrypoint": ["/app"],
                "WorkingDir": "/workspace"
            },
            "rootfs": {
                "type": "layers",
                "diff_ids": ["sha256:aaa", "sha256:bbb"]
            }
        }
        """.trimIndent()

        val config = json.decodeFromString<OciImageConfig>(configJson)
        assertEquals("amd64", config.architecture)
        assertEquals("linux", config.os)
        assertEquals(listOf("PATH=/usr/local/bin:/usr/bin"), config.config?.env)
        assertEquals(listOf("/bin/sh"), config.config?.cmd)
        assertEquals(listOf("/app"), config.config?.entrypoint)
        assertEquals("/workspace", config.config?.workingDir)
        assertEquals("layers", config.rootfs?.type)
        assertEquals(2, config.rootfs?.diffIds?.size)
    }

    @Test
    fun `OciImageIndex deserialization with platforms`() {
        val indexJson = """
        {
            "schemaVersion": 2,
            "manifests": [
                {
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "digest": "sha256:amd64digest",
                    "size": 500,
                    "platform": {
                        "architecture": "amd64",
                        "os": "linux"
                    }
                },
                {
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "digest": "sha256:arm64digest",
                    "size": 500,
                    "platform": {
                        "architecture": "arm64",
                        "os": "linux"
                    }
                }
            ]
        }
        """.trimIndent()

        val index = json.decodeFromString<OciImageIndex>(indexJson)
        assertEquals(2, index.schemaVersion)
        assertEquals(2, index.manifests.size)
        assertEquals("amd64", index.manifests[0].platform?.architecture)
        assertEquals("linux", index.manifests[0].platform?.os)
        assertEquals("arm64", index.manifests[1].platform?.architecture)
    }

    @Test
    fun `OciMediaTypes isManifestList identifies list types`() {
        assertTrue(OciMediaTypes.isManifestList(OciMediaTypes.IMAGE_INDEX))
        assertTrue(OciMediaTypes.isManifestList(OciMediaTypes.DOCKER_MANIFEST_LIST))
        assertFalse(OciMediaTypes.isManifestList(OciMediaTypes.IMAGE_MANIFEST))
        assertFalse(OciMediaTypes.isManifestList(OciMediaTypes.DOCKER_MANIFEST_V2))
    }

    @Test
    fun `OciMediaTypes isGzipLayer identifies gzip layers`() {
        assertTrue(OciMediaTypes.isGzipLayer(OciMediaTypes.LAYER_TAR_GZIP))
        assertTrue(OciMediaTypes.isGzipLayer(OciMediaTypes.DOCKER_LAYER_GZIP))
        assertFalse(OciMediaTypes.isGzipLayer(OciMediaTypes.LAYER_TAR))
        assertFalse(OciMediaTypes.isGzipLayer(OciMediaTypes.LAYER_TAR_ZSTD))
    }

    @Test
    fun `EstargzToc files returns only regular files`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "dir/", type = "dir"),
                EstargzTocEntry(name = "file.txt", type = "reg", size = 100),
                EstargzTocEntry(name = "link", type = "symlink"),
                EstargzTocEntry(name = "file2.txt", type = "reg", size = 200),
            ),
        )
        val files = toc.files()
        assertEquals(2, files.size)
        assertTrue(files.all { it.isRegularFile })
    }

    @Test
    fun `EstargzToc prefetchLandmark finds landmark entry`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "priority.txt", type = "reg", size = 10, offset = 0),
                EstargzTocEntry(name = ".prefetch.landmark", type = "reg", size = 0, offset = 100),
                EstargzTocEntry(name = "other.txt", type = "reg", size = 20, offset = 200),
            ),
        )
        val landmark = toc.prefetchLandmark()
        assertNotNull(landmark)
        assertEquals(".prefetch.landmark", landmark!!.name)
    }

    @Test
    fun `EstargzToc prefetchEntries returns entries before landmark`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "priority1.txt", type = "reg", size = 10, offset = 0),
                EstargzTocEntry(name = "priority2.txt", type = "reg", size = 20, offset = 100),
                EstargzTocEntry(name = ".prefetch.landmark", type = "reg", size = 0, offset = 200),
                EstargzTocEntry(name = "lazy.txt", type = "reg", size = 30, offset = 300),
            ),
        )
        val prefetch = toc.prefetchEntries()
        assertEquals(2, prefetch.size)
        assertEquals("priority1.txt", prefetch[0].name)
        assertEquals("priority2.txt", prefetch[1].name)
    }

    @Test
    fun `EstargzToc prefetchEntries returns empty if no landmark`() {
        val toc = EstargzToc(
            version = 1,
            entries = listOf(
                EstargzTocEntry(name = "file.txt", type = "reg", size = 10, offset = 0),
            ),
        )
        val prefetch = toc.prefetchEntries()
        assertTrue(prefetch.isEmpty())
    }

    @Test
    fun `OciImageConfig with unknown fields deserializes`() {
        val configJson = """
        {
            "architecture": "wasm",
            "os": "wasi",
            "unknownField": "should be ignored",
            "config": {
                "Entrypoint": ["/module.wasm"],
                "UnknownKey": "ignored"
            }
        }
        """.trimIndent()

        val config = json.decodeFromString<OciImageConfig>(configJson)
        assertEquals("wasm", config.architecture)
        assertEquals("wasi", config.os)
        assertEquals(listOf("/module.wasm"), config.config?.entrypoint)
    }

    @Test
    fun `HistoryEntry deserialization`() {
        val historyJson = """
        {
            "created": "2024-01-01T00:00:00Z",
            "created_by": "ADD file:abc in /",
            "empty_layer": false
        }
        """.trimIndent()

        val entry = json.decodeFromString<HistoryEntry>(historyJson)
        assertEquals("2024-01-01T00:00:00Z", entry.created)
        assertEquals("ADD file:abc in /", entry.createdBy)
        assertEquals(false, entry.emptyLayer)
    }
}
