package com.example.c2wdemo.data

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

data class ImageInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val assetName: String
)

class ImageManager(private val context: Context) {

    companion object {
        val AVAILABLE_IMAGES = listOf(
            ImageInfo(
                id = "claude",
                displayName = "Claude",
                description = "Anthropic's Claude AI assistant for code, analysis, and creative tasks",
                assetName = "alpine.aot"
            ),
            ImageInfo(
                id = "codex",
                displayName = "Codex",
                description = "OpenAI Codex - specialized for code generation and completion",
                assetName = "alpine.aot"
            ),
            ImageInfo(
                id = "gemini",
                displayName = "Gemini",
                description = "Google's Gemini multimodal AI for reasoning and problem-solving",
                assetName = "alpine.aot"
            )
        )

        fun getImageInfo(imageId: String): ImageInfo? {
            return AVAILABLE_IMAGES.find { it.id == imageId }
        }
    }

    private val imagesDir: File
        get() = File(context.filesDir, "images")

    private fun getImageDir(imageId: String): File {
        return File(imagesDir, imageId)
    }

    fun getCheckpointPath(imageId: String): String {
        val imageDir = getImageDir(imageId)
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        return File(imageDir, "checkpoint.snap").absolutePath
    }

    fun hasCheckpoint(imageId: String): Boolean {
        return File(getCheckpointPath(imageId)).exists()
    }

    fun deleteCheckpoint(imageId: String): Boolean {
        val checkpointFile = File(getCheckpointPath(imageId))
        return if (checkpointFile.exists()) {
            checkpointFile.delete()
        } else {
            true
        }
    }

    fun getImageBytes(imageId: String, assets: AssetManager): ByteArray {
        val imageInfo = getImageInfo(imageId)
            ?: throw IllegalArgumentException("Unknown image: $imageId")
        return assets.open(imageInfo.assetName).use { it.readBytes() }
    }

    fun exportCheckpoint(imageId: String, outputStream: OutputStream): Boolean {
        val checkpointPath = getCheckpointPath(imageId)
        val checkpointFile = File(checkpointPath)
        if (!checkpointFile.exists()) {
            return false
        }
        return try {
            FileInputStream(checkpointFile).use { input ->
                input.copyTo(outputStream)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun importCheckpoint(imageId: String, inputStream: InputStream): Boolean {
        val checkpointPath = getCheckpointPath(imageId)
        val checkpointFile = File(checkpointPath)
        return try {
            // Ensure parent directory exists
            checkpointFile.parentFile?.mkdirs()
            FileOutputStream(checkpointFile).use { output ->
                inputStream.copyTo(output)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getCheckpointSize(imageId: String): Long {
        val checkpointFile = File(getCheckpointPath(imageId))
        return if (checkpointFile.exists()) checkpointFile.length() else 0
    }
}
