package com.example.c2wdemo.registry

import java.io.File

/**
 * High-level API for pulling OCI container images with lazy pulling support.
 *
 * This orchestrates the full container pull workflow:
 * 1. Resolve image reference (registry or HTTP layout)
 * 2. Fetch manifest and config
 * 3. For eStargz layers: fetch TOC only (lazy)
 * 4. Prefetch priority files
 * 5. Serve file content on-demand via readFile()
 *
 * Usage:
 * ```kotlin
 * val puller = ContainerPuller(cacheDir) { msg -> println(msg) }
 * val result = puller.pull("localhost:5000/ubuntu:22.04")
 *
 * // Read files lazily
 * val passwd = result.readFile("etc/passwd")
 *
 * // Or download all layers for full offline access
 * result.downloadAllLayers()
 * ```
 */
class ContainerPuller(
    private val cacheDir: File,
    private val onProgress: ((String) -> Unit)? = null,
) {
    private val client = RegistryClient(onProgress)

    /**
     * Pull a container image, resolving metadata and preparing for lazy access.
     *
     * @param imageRef Image reference string (e.g. "localhost:5000/ubuntu:22.04")
     * @return PullResult providing lazy access to image contents
     */
    suspend fun pull(imageRef: String): PullResult {
        val ref = ImageReference.parse(imageRef)
        return pull(ref)
    }

    /**
     * Pull a container image from a parsed reference.
     */
    suspend fun pull(ref: ImageReference): PullResult {
        log("Pulling ${ref}...")

        // Step 1: Resolve manifest and config
        val resolved = client.resolve(ref)
        log("Image: ${resolved.config.os}/${resolved.config.architecture}")
        log(resolved.layerSummary())

        // Step 2: Initialize lazy layer reader
        val layerCacheDir = File(cacheDir, "layers/${ref.repository.replace("/", "_")}")
        val lazyReader = LazyLayerReader(client, resolved, layerCacheDir, onProgress)
        lazyReader.initialize()

        // Step 3: Prefetch priority files (for eStargz)
        if (resolved.hasEstargzLayers) {
            log("Prefetching priority files...")
            lazyReader.prefetch()
            log("Prefetch complete")
        }

        return PullResult(
            image = resolved,
            lazyReader = lazyReader,
            cacheDir = layerCacheDir,
            onProgress = onProgress,
        )
    }

    /**
     * Pull a container image and download all layers fully (no lazy pulling).
     *
     * This is useful when you want complete offline access to all image contents.
     */
    suspend fun pullFull(imageRef: String): PullResult {
        val ref = ImageReference.parse(imageRef)
        val resolved = client.resolve(ref)

        log("Image: ${resolved.config.os}/${resolved.config.architecture}")
        log("Downloading ${resolved.layerCount} layers (${resolved.totalLayerSize / 1024 / 1024}MB)...")

        val layerCacheDir = File(cacheDir, "layers/${ref.repository.replace("/", "_")}")
        layerCacheDir.mkdirs()

        // Download each layer fully
        for ((idx, layer) in resolved.manifest.layers.withIndex()) {
            val digest = layer.digest
            val shortDigest = layer.shortDigest
            val cacheFile = File(layerCacheDir, "$shortDigest.layer")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                log("Layer $idx ($shortDigest): cached (${cacheFile.length() / 1024}KB)")
                continue
            }

            log("Layer $idx ($shortDigest): downloading ${layer.size / 1024}KB...")

            val data = when (val source = resolved.source) {
                is ImageSource.Registry -> client.fetchBlob(source.ref, digest)
                is ImageSource.HttpLayout -> client.fetchHttpLayoutBlob(source.baseUrl, digest)
            }

            cacheFile.writeBytes(data)
            log("Layer $idx ($shortDigest): done")
        }

        val lazyReader = LazyLayerReader(client, resolved, layerCacheDir, onProgress)
        lazyReader.initialize()

        return PullResult(
            image = resolved,
            lazyReader = lazyReader,
            cacheDir = layerCacheDir,
            onProgress = onProgress,
        )
    }

    /**
     * Check if an image is available in the local cache.
     */
    fun isCached(imageRef: String): Boolean {
        val ref = ImageReference.parse(imageRef)
        val layerCacheDir = File(cacheDir, "layers/${ref.repository.replace("/", "_")}")
        return layerCacheDir.exists() && (layerCacheDir.listFiles()?.isNotEmpty() == true)
    }

    /**
     * Clear all cached images.
     */
    fun clearCache() {
        val layersDir = File(cacheDir, "layers")
        layersDir.deleteRecursively()
    }

    fun shutdown() {
        client.shutdown()
    }

    private fun log(msg: String) {
        onProgress?.invoke(msg)
    }
}

/**
 * Result of pulling a container image.
 *
 * Provides lazy access to image contents through the LazyLayerReader.
 * Files are fetched on-demand from the registry using HTTP Range requests
 * for eStargz layers, or from the local cache for fully-downloaded layers.
 */
class PullResult(
    val image: ResolvedImage,
    val lazyReader: LazyLayerReader,
    val cacheDir: File,
    private val onProgress: ((String) -> Unit)? = null,
) {
    /** Read a file from the container's filesystem (lazy). */
    fun readFile(path: String): ByteArray? = lazyReader.readFile(path)

    /** List all known files (from eStargz indices). */
    fun listFiles(): Set<String> = lazyReader.listAllFiles()

    /** Get the image configuration. */
    val config: OciImageConfig get() = image.config

    /** Get the container entrypoint. */
    val entrypoint: List<String>?
        get() = image.config.config?.entrypoint

    /** Get the container command. */
    val cmd: List<String>?
        get() = image.config.config?.cmd

    /** Get environment variables. */
    val env: List<String>?
        get() = image.config.config?.env

    /** Get working directory. */
    val workingDir: String?
        get() = image.config.config?.workingDir

    /** Cache statistics. */
    fun cacheStats(): CacheStats = lazyReader.cacheStats()

    /** Clear cached data. */
    fun clearCache() = lazyReader.clearCache()

    /** Summary for display. */
    override fun toString(): String = buildString {
        append("Image: ${image.reference}\n")
        append("OS/Arch: ${image.config.os}/${image.config.architecture}\n")
        append("Layers: ${image.layerSummary()}\n")
        val stats = cacheStats()
        append("Cache: $stats\n")
        image.config.config?.let { cfg ->
            cfg.entrypoint?.let { append("Entrypoint: $it\n") }
            cfg.cmd?.let { append("Cmd: $it\n") }
        }
    }
}
