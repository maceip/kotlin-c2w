package com.example.c2wdemo

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Describes a container image available for the friscy runtime. */
data class ContainerImage(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val accentColor: Long,
    val bundledAsset: String?,
    val downloadUrl: String?,
    val entryPoint: String,
    val sizeBytes: Long,
)

/** Download progress update. */
sealed class DownloadState {
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    data class Complete(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Manages container image discovery, download, and caching.
 *
 * Images are stored in `filesDir/images/<id>.tar`.
 * Bundled images are served directly from assets.
 */
class ImageManager(private val context: Context) {

    private val imagesDir = File(context.filesDir, "images").also { it.mkdirs() }

    /** Load the image registry from bundled assets. */
    suspend fun loadRegistry(): List<ContainerImage> = withContext(Dispatchers.IO) {
        val json = context.assets.open("image_registry.json").bufferedReader().use { it.readText() }
        parseRegistry(json)
    }

    /** Check whether an image is available locally (bundled or downloaded). */
    fun isAvailable(image: ContainerImage): Boolean {
        if (image.bundledAsset != null) return true
        return cachedFile(image).exists()
    }

    /** Get the tar bytes for an image, loading from asset or cache as appropriate. */
    suspend fun loadImageBytes(image: ContainerImage): ByteArray = withContext(Dispatchers.IO) {
        if (image.bundledAsset != null) {
            context.assets.open(image.bundledAsset).use { it.readBytes() }
        } else {
            val file = cachedFile(image)
            if (!file.exists()) error("Image ${image.id} not downloaded")
            file.readBytes()
        }
    }

    /** Download an image with progress updates. */
    fun download(image: ContainerImage): Flow<DownloadState> = flow {
        val url = image.downloadUrl ?: error("No download URL for ${image.id}")
        val tempFile = File(imagesDir, "${image.id}.tar.tmp")
        val destFile = cachedFile(image)

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Error("HTTP $responseCode"))
                return@flow
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        emit(DownloadState.Progress(downloaded, totalBytes))
                    }
                }
            }

            tempFile.renameTo(destFile)
            emit(DownloadState.Complete(destFile))
        } catch (e: Exception) {
            tempFile.delete()
            emit(DownloadState.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    /** Delete a cached image. */
    fun deleteImage(image: ContainerImage): Boolean {
        return cachedFile(image).delete()
    }

    /** List IDs of images that have been downloaded. */
    fun listCached(): Set<String> {
        return imagesDir.listFiles()
            ?.filter { it.extension == "tar" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    private fun cachedFile(image: ContainerImage): File = File(imagesDir, "${image.id}.tar")

    companion object {
        fun parseRegistry(json: String): List<ContainerImage> {
            val root = JSONObject(json)
            val arr = root.getJSONArray("images")
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ContainerImage(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    icon = obj.optString("icon", "terminal"),
                    accentColor = parseColor(obj.optString("accentColor", "#00FF88")),
                    bundledAsset = obj.optString("bundledAsset").ifEmpty { null },
                    downloadUrl = obj.optString("downloadUrl").ifEmpty { null },
                    entryPoint = obj.getString("entryPoint"),
                    sizeBytes = obj.optLong("sizeBytes", 0),
                )
            }
        }

        private fun parseColor(hex: String): Long {
            val clean = hex.removePrefix("#")
            return when (clean.length) {
                6 -> (0xFF000000 or clean.toLong(16))
                8 -> clean.toLong(16)
                else -> 0xFF00FF88
            }
        }
    }
}
