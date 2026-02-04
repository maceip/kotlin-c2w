package com.example.c2wdemo.registry

import java.io.File
import java.security.MessageDigest

/**
 * High-level API for ensuring WASM artifacts are available locally.
 *
 * Wraps the OCI container registry client to provide a simple interface
 * for pulling container images and extracting WASM binaries from them.
 *
 * Usage:
 * ```kotlin
 * val provider = WasmArtifactProvider(cacheDir) { msg -> println(msg) }
 * val wasmFile = provider.ensure("ghcr.io/example/my-wasm-app:latest")
 * // wasmFile is now a local File pointing to the extracted .wasm binary
 * ```
 */
class WasmArtifactProvider(
    private val cacheDir: File,
    private val onProgress: ((String) -> Unit)? = null,
) {
    private val puller = ContainerPuller(cacheDir, onProgress)

    /**
     * Ensure a WASM artifact is available locally, pulling if necessary.
     *
     * Pulls the container image (lazily if eStargz, fully otherwise),
     * extracts the first `.wasm` file found, and caches it on disk.
     *
     * @param imageRef Image reference string (e.g. "ghcr.io/example/app:latest")
     * @return A local [File] containing the WASM binary
     * @throws RegistryException if the image cannot be pulled or no .wasm file is found
     */
    suspend fun ensure(imageRef: String): File {
        val artifactDir = artifactDir(imageRef)
        val marker = File(artifactDir, ".complete")

        // Check if already cached and complete
        if (marker.exists()) {
            val wasmFile = findWasmFile(artifactDir)
            if (wasmFile != null) {
                log("Using cached WASM artifact: ${wasmFile.name}")
                return wasmFile
            }
            // Cache is corrupt, re-pull
            artifactDir.deleteRecursively()
        }

        artifactDir.mkdirs()

        log("Pulling image $imageRef...")
        val result = puller.pull(imageRef)

        // Extract WASM file from the image layers
        val wasmFile = extractWasmArtifact(result, artifactDir)
            ?: throw RegistryException("No .wasm file found in image $imageRef")

        // Mark cache as complete
        marker.writeText(imageRef)

        log("WASM artifact ready: ${wasmFile.name} (${wasmFile.length()} bytes)")
        return wasmFile
    }

    /**
     * Check if a WASM artifact is cached locally.
     *
     * @param imageRef Image reference string
     * @return true if the artifact is cached and ready to use
     */
    fun isCached(imageRef: String): Boolean {
        val artifactDir = artifactDir(imageRef)
        val marker = File(artifactDir, ".complete")
        return marker.exists() && findWasmFile(artifactDir) != null
    }

    /**
     * Evict a cached WASM artifact.
     *
     * @param imageRef Image reference string to evict
     */
    fun evict(imageRef: String) {
        val artifactDir = artifactDir(imageRef)
        artifactDir.deleteRecursively()

        // Also evict the puller layer cache
        val ref = ImageReference.parse(imageRef)
        val layerCacheDir = File(cacheDir, "layers/${ref.repository.replace("/", "_")}")
        layerCacheDir.deleteRecursively()
    }

    /**
     * Evict all cached WASM artifacts and layer data.
     */
    fun evictAll() {
        val artifactsDir = File(cacheDir, "artifacts")
        artifactsDir.deleteRecursively()

        val layersDir = File(cacheDir, "layers")
        layersDir.deleteRecursively()
    }

    /**
     * Shut down the underlying HTTP client.
     */
    fun shutdown() {
        puller.shutdown()
    }

    /**
     * Extract a WASM file from a pulled container image.
     *
     * Searches through the image's file list (eStargz) or layer contents
     * for files with a `.wasm` extension.
     */
    private fun extractWasmArtifact(result: PullResult, outputDir: File): File? {
        // First try: search eStargz file index for .wasm files
        val knownFiles = result.listFiles()
        if (knownFiles.isNotEmpty()) {
            val wasmPath = knownFiles.firstOrNull { it.endsWith(".wasm") }
            if (wasmPath != null) {
                val content = result.readFile(wasmPath)
                if (content != null) {
                    val fileName = wasmPath.substringAfterLast("/")
                    val outputFile = File(outputDir, fileName)
                    outputFile.writeBytes(content)
                    return outputFile
                }
            }
        }

        // Second try: search common paths
        val commonPaths = listOf(
            "app.wasm",
            "main.wasm",
            "module.wasm",
            "usr/local/bin/app.wasm",
            "opt/wasm/app.wasm",
        )

        for (path in commonPaths) {
            val content = result.readFile(path)
            if (content != null) {
                val fileName = path.substringAfterLast("/")
                val outputFile = File(outputDir, fileName)
                outputFile.writeBytes(content)
                return outputFile
            }
        }

        // Third try: for non-eStargz layers, scan tar entries for .wasm files
        val image = result.image
        for (layer in image.manifest.layers) {
            if (layer.hasEstargzToc) continue

            try {
                val digest = layer.digest
                val data = when (val source = image.source) {
                    is ImageSource.Registry -> {
                        val client = RegistryClient(onProgress)
                        try {
                            client.fetchBlob(source.ref, digest)
                        } finally {
                            client.shutdown()
                        }
                    }
                    is ImageSource.HttpLayout -> {
                        val client = RegistryClient(onProgress)
                        try {
                            client.fetchHttpLayoutBlob(source.baseUrl, digest)
                        } finally {
                            client.shutdown()
                        }
                    }
                }

                val gis = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(data))
                val files = TarReader.extractAll(gis)
                gis.close()

                val wasmEntry = files.entries.firstOrNull { it.key.endsWith(".wasm") }
                if (wasmEntry != null) {
                    val fileName = wasmEntry.key.substringAfterLast("/")
                    val outputFile = File(outputDir, fileName)
                    outputFile.writeBytes(wasmEntry.value)
                    return outputFile
                }
            } catch (e: Exception) {
                log("Failed to scan layer ${layer.shortDigest}: ${e.message}")
            }
        }

        return null
    }

    /**
     * Find a .wasm file in the artifact directory.
     */
    private fun findWasmFile(dir: File): File? {
        return dir.listFiles()?.firstOrNull { it.extension == "wasm" && it.length() > 0 }
    }

    /**
     * Get the artifact cache directory for an image reference.
     * Uses a hash of the reference to avoid filesystem issues with special chars.
     */
    private fun artifactDir(imageRef: String): File {
        val hash = sha256Hex(imageRef).take(16)
        val safeName = imageRef
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(64)
        return File(cacheDir, "artifacts/${safeName}_$hash")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun log(msg: String) {
        onProgress?.invoke(msg)
    }
}
