package com.example.c2wdemo.registry

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Lazy layer reader that fetches file content on-demand from eStargz layers.
 *
 * Instead of downloading entire layers upfront, this reader:
 * 1. Downloads only the eStargz footer + TOC (small metadata)
 * 2. Builds a file index from the TOC
 * 3. Fetches individual files/chunks via HTTP Range requests when accessed
 * 4. Caches fetched data to local disk to avoid re-downloading
 * 5. Supports prefetching priority files for faster initial access
 *
 * For non-eStargz layers, falls back to full download.
 */
class LazyLayerReader(
    private val client: RegistryClient,
    private val resolvedImage: ResolvedImage,
    private val cacheDir: File,
    private val onProgress: ((String) -> Unit)? = null,
) {
    private val parser = EstargzParser()

    /** Per-layer eStargz file indices, keyed by layer digest. */
    private val layerIndices = ConcurrentHashMap<String, EstargzFileIndex>()

    /** Per-layer cache files for compressed blob ranges. */
    private val layerCaches = ConcurrentHashMap<String, File>()

    /** Tracks which byte ranges have been cached for each layer. */
    private val cachedRanges = ConcurrentHashMap<String, MutableSet<LongRange>>()

    /** Layers that have been fully downloaded (non-eStargz). */
    private val fullyDownloaded = ConcurrentHashMap<String, File>()

    init {
        cacheDir.mkdirs()
    }

    /**
     * Initialize lazy reading for all layers.
     *
     * For eStargz layers: fetches footer + TOC and builds file index.
     * For regular layers: marks them for full download on first access.
     */
    suspend fun initialize() {
        val layers = resolvedImage.manifest.layers

        for ((idx, layer) in layers.withIndex()) {
            val digest = layer.digest
            val shortDigest = layer.shortDigest

            if (layer.hasEstargzToc) {
                log("Layer $idx ($shortDigest): eStargz - fetching TOC...")
                initEstargzLayer(layer)
            } else {
                log("Layer $idx ($shortDigest): standard ${layer.mediaType} (${layer.size / 1024}KB)")
            }
        }

        // Report summary
        val esgzCount = layerIndices.size
        val totalFiles = layerIndices.values.sumOf { it.fileCount }
        if (esgzCount > 0) {
            log("Indexed $totalFiles files across $esgzCount eStargz layers")
            val prefetchTotal = layerIndices.values.sumOf { it.prefetchSize }
            if (prefetchTotal > 0) {
                log("Prefetch data: ${prefetchTotal / 1024}KB")
            }
        }
    }

    /**
     * Initialize an eStargz layer by fetching its footer and TOC.
     */
    private fun initEstargzLayer(layer: OciDescriptor) {
        val digest = layer.digest

        // Step 1: Fetch the footer (last 51 bytes)
        val footerBytes = fetchRange(digest, layer.size - EstargzParser.FOOTER_SIZE, EstargzParser.FOOTER_SIZE.toLong())

        // Step 2: Parse TOC offset from footer
        val tocOffset = parser.parseTocOffset(footerBytes)
            ?: throw RegistryException("Failed to parse eStargz footer for ${layer.shortDigest}")

        log("  TOC offset: $tocOffset (${(layer.size - tocOffset) / 1024}KB of metadata)")

        // Step 3: Fetch the TOC (from tocOffset to end of blob, minus footer)
        val tocSize = layer.size - tocOffset
        val tocData = fetchRange(digest, tocOffset, tocSize)

        // Step 4: Parse the TOC
        val toc = parser.parseToc(tocData)
            ?: throw RegistryException("Failed to parse eStargz TOC for ${layer.shortDigest}")

        log("  TOC: ${toc.entries.size} entries, version ${toc.version}")

        // Step 5: Build file index
        val fileIndex = parser.buildFileIndex(toc)
        layerIndices[digest] = fileIndex

        // Create cache file for this layer's blob data
        val cacheFile = File(cacheDir, "${layer.shortDigest}.cache")
        layerCaches[digest] = cacheFile
        cachedRanges[digest] = ConcurrentHashMap.newKeySet()
    }

    /**
     * Prefetch priority files for all eStargz layers.
     *
     * eStargz places frequently-accessed files before a "prefetch landmark"
     * in the blob. This method downloads that entire prefix in one Range request.
     */
    suspend fun prefetch() {
        for ((digest, index) in layerIndices) {
            val prefetchSize = index.prefetchSize
            if (prefetchSize <= 0) continue

            val shortDigest = digest.substringAfter("sha256:").take(12)
            log("Prefetching ${prefetchSize / 1024}KB for layer $shortDigest...")

            val minOffset = index.prefetchEntries.minOf { it.offset }
            val data = fetchRange(digest, minOffset, prefetchSize)

            // Write to cache
            val cacheFile = layerCaches[digest] ?: continue
            writeToCache(cacheFile, minOffset, data)
            cachedRanges[digest]?.add(minOffset until (minOffset + prefetchSize))
        }
    }

    /**
     * Read a file from the container image layers.
     *
     * Searches layers in reverse order (top layer wins, overlay semantics).
     * For eStargz layers, fetches only the needed bytes via Range request.
     *
     * @param path File path within the container rootfs
     * @return Decompressed file content, or null if not found
     */
    fun readFile(path: String): ByteArray? {
        val layers = resolvedImage.manifest.layers

        // Search layers in reverse (top layer = highest priority)
        for (i in layers.indices.reversed()) {
            val layer = layers[i]
            val digest = layer.digest

            // Try eStargz index first
            val index = layerIndices[digest]
            if (index != null) {
                val content = readFromEstargz(digest, index, path)
                if (content != null) return content
                continue
            }

            // For non-eStargz layers, ensure fully downloaded
            val fullFile = ensureFullyDownloaded(layer)
            val content = readFromTarGzip(fullFile, path)
            if (content != null) return content
        }

        return null
    }

    /**
     * Read a file from an eStargz layer using the file index.
     */
    private fun readFromEstargz(digest: String, index: EstargzFileIndex, path: String): ByteArray? {
        val entries = index.getFile(path) ?: return null

        // Handle whiteouts (file deletions in overlay)
        val regEntries = entries.filter { it.isRegularFile || it.isChunk }
        if (regEntries.isEmpty()) {
            // Could be a directory or symlink
            val firstEntry = entries.first()
            return when {
                firstEntry.isSymlink -> firstEntry.linkName?.toByteArray()
                firstEntry.isDirectory -> ByteArray(0)
                else -> null
            }
        }

        // Reconstruct file from chunks
        val output = ByteArrayOutputStream()

        for (entry in regEntries.sortedBy { it.chunkOffset }) {
            val range = index.getCompressedRange(entry)
            if (range == null) {
                // Cannot determine compressed size; fetch a generous range
                val compressedData = fetchRangeWithCache(digest, entry.offset, entry.size + 1024)
                val decompressed = decompressGzip(compressedData)
                output.write(decompressed)
            } else {
                val compressedData = fetchRangeWithCache(
                    digest,
                    range.first,
                    range.last - range.first + 1
                )
                val decompressed = decompressGzip(compressedData)

                // If this is a chunk, extract the right portion
                if (entry.isChunk && entry.chunkSize > 0) {
                    val chunkStart = entry.chunkOffset.toInt()
                    val chunkEnd = (chunkStart + entry.chunkSize).toInt()
                        .coerceAtMost(decompressed.size)
                    output.write(decompressed, chunkStart, chunkEnd - chunkStart)
                } else {
                    output.write(decompressed)
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * Fetch a byte range, using local cache when available.
     */
    private fun fetchRangeWithCache(digest: String, offset: Long, length: Long): ByteArray {
        val ranges = cachedRanges[digest]
        val cacheFile = layerCaches[digest]

        // Check if this range is already cached
        if (ranges != null && cacheFile != null && cacheFile.exists()) {
            val cached = ranges.find { range ->
                offset >= range.first && (offset + length - 1) <= range.last
            }
            if (cached != null) {
                return readFromCache(cacheFile, offset, length)
            }
        }

        // Fetch from remote
        val data = fetchRange(digest, offset, length)

        // Write to cache
        if (cacheFile != null) {
            writeToCache(cacheFile, offset, data)
            ranges?.add(offset until (offset + data.size))
        }

        return data
    }

    /**
     * Fetch a byte range from the appropriate source.
     */
    private fun fetchRange(digest: String, offset: Long, length: Long): ByteArray {
        return when (val source = resolvedImage.source) {
            is ImageSource.Registry -> {
                client.fetchBlobRange(source.ref, digest, offset, length)
            }
            is ImageSource.HttpLayout -> {
                client.fetchHttpLayoutBlobRange(source.baseUrl, digest, offset, length)
            }
        }
    }

    /**
     * Ensure a non-eStargz layer is fully downloaded.
     */
    private fun ensureFullyDownloaded(layer: OciDescriptor): File {
        val digest = layer.digest
        fullyDownloaded[digest]?.let { if (it.exists()) return it }

        val cacheFile = File(cacheDir, "${layer.shortDigest}.layer")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            fullyDownloaded[digest] = cacheFile
            return cacheFile
        }

        log("Downloading full layer ${layer.shortDigest} (${layer.size / 1024}KB)...")

        val data = when (val source = resolvedImage.source) {
            is ImageSource.Registry -> client.fetchBlob(source.ref, digest)
            is ImageSource.HttpLayout -> client.fetchHttpLayoutBlob(source.baseUrl, digest)
        }

        cacheFile.writeBytes(data)
        fullyDownloaded[digest] = cacheFile
        return cacheFile
    }

    /**
     * Read a file from a fully-downloaded tar.gz layer.
     */
    private fun readFromTarGzip(file: File, path: String): ByteArray? {
        return try {
            val gis = GZIPInputStream(file.inputStream())
            val result = TarReader.readFile(gis, path)
            gis.close()
            result
        } catch (e: Exception) {
            null
        }
    }

    // --- Cache I/O ---

    private fun writeToCache(file: File, offset: Long, data: ByteArray) {
        try {
            val raf = RandomAccessFile(file, "rw")
            raf.seek(offset)
            raf.write(data)
            raf.close()
        } catch (e: Exception) {
            // Cache write failure is non-fatal
        }
    }

    private fun readFromCache(file: File, offset: Long, length: Long): ByteArray {
        val raf = RandomAccessFile(file, "r")
        raf.seek(offset)
        val buf = ByteArray(length.toInt())
        val read = raf.read(buf)
        raf.close()
        return if (read == buf.size) buf else buf.copyOf(read)
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
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

    /**
     * Get the eStargz file index for a layer, if available.
     */
    fun getFileIndex(layerDigest: String): EstargzFileIndex? = layerIndices[layerDigest]

    /**
     * List all files across all layers (merged view).
     */
    fun listAllFiles(): Set<String> {
        val allFiles = mutableSetOf<String>()
        for (index in layerIndices.values) {
            allFiles.addAll(index.listFiles())
        }
        return allFiles
    }

    /**
     * Get cache statistics.
     */
    fun cacheStats(): CacheStats {
        var totalCacheSize = 0L
        for (file in cacheDir.listFiles() ?: emptyArray()) {
            totalCacheSize += file.length()
        }
        return CacheStats(
            layerCount = resolvedImage.manifest.layers.size,
            estargzLayerCount = layerIndices.size,
            indexedFileCount = layerIndices.values.sumOf { it.fileCount },
            cacheSizeBytes = totalCacheSize,
            cachedRangeCount = cachedRanges.values.sumOf { it.size },
        )
    }

    /**
     * Clear all cached data.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedRanges.clear()
        fullyDownloaded.clear()
    }

    private fun log(msg: String) {
        onProgress?.invoke(msg)
    }
}

data class CacheStats(
    val layerCount: Int,
    val estargzLayerCount: Int,
    val indexedFileCount: Int,
    val cacheSizeBytes: Long,
    val cachedRangeCount: Int,
) {
    override fun toString(): String = buildString {
        append("Layers: $layerCount ($estargzLayerCount eStargz)\n")
        append("Indexed files: $indexedFileCount\n")
        append("Cache: ${cacheSizeBytes / 1024}KB in $cachedRangeCount ranges")
    }
}
