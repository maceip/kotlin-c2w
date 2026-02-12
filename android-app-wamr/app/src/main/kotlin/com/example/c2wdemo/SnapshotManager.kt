package com.example.c2wdemo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Describes a saved snapshot.
 */
data class SnapshotInfo(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val createdAt: Long,
)

/**
 * Manages local snapshot files in `filesDir/snapshots/`.
 *
 * Snapshots are binary files containing CPU registers + flat arena memory,
 * allowing instant restore of a running container's state.
 */
class SnapshotManager(context: Context) {

    private val snapshotsDir = File(context.filesDir, "snapshots").also { it.mkdirs() }

    /** List all saved snapshots, sorted by creation time (newest first). */
    fun list(): List<SnapshotInfo> {
        return snapshotsDir.listFiles()
            ?.filter { it.extension == "snap" }
            ?.map { file ->
                SnapshotInfo(
                    name = file.nameWithoutExtension,
                    file = file,
                    sizeBytes = file.length(),
                    createdAt = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Save the current machine state to a named snapshot. */
    suspend fun save(name: String = generateName()): Boolean = withContext(Dispatchers.IO) {
        val file = File(snapshotsDir, "$name.snap")
        FriscyRuntime.nativeSaveSnapshot(file.absolutePath)
    }

    /** Restore machine state from a named snapshot. Machine must be loaded first. */
    suspend fun restore(name: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(snapshotsDir, "$name.snap")
        if (!file.exists()) return@withContext false
        FriscyRuntime.nativeRestoreSnapshot(file.absolutePath)
    }

    /** Delete a snapshot by name. */
    fun delete(name: String): Boolean {
        return File(snapshotsDir, "$name.snap").delete()
    }

    /** Check if a snapshot exists. */
    fun exists(name: String): Boolean {
        return File(snapshotsDir, "$name.snap").exists()
    }

    /** Get the file path for a snapshot. */
    fun getPath(name: String): String {
        return File(snapshotsDir, "$name.snap").absolutePath
    }

    private fun generateName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return fmt.format(Date())
    }
}
