package com.example.c2wdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.AssetManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.c2wdemo.data.ImageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VmService : Service() {

    inner class LocalBinder : Binder() {
        val service: VmService get() = this@VmService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var outputCallback: ((String) -> Unit)? = null

    /** Ring buffer of recent output so reconnecting UI can replay missed text. */
    private val outputBuffer = StringBuilder(OUTPUT_BUFFER_CAPACITY)

    /** Whether the VM has been started by this service instance. */
    var vmStarted = false
        private set

    /** The current image ID being run. */
    var currentImageId: String? = null
        private set

    private lateinit var imageManager: ImageManager

    override fun onCreate() {
        super.onCreate()
        imageManager = ImageManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val imageId = intent?.getStringExtra(EXTRA_IMAGE_ID)

        // If a different image is requested, stop the current VM
        if (imageId != null && currentImageId != null && imageId != currentImageId && vmStarted) {
            WamrRuntime.saveCheckpoint()
            WamrRuntime.destroy()
            vmStarted = false
            outputBuffer.clear()
        }

        val targetImageId = imageId ?: currentImageId ?: DEFAULT_IMAGE_ID

        // Set checkpoint path for this image
        val checkpointPath = imageManager.getCheckpointPath(targetImageId)
        WamrRuntime.setCheckpointPath(checkpointPath)

        startForeground(NOTIFICATION_ID, buildNotification(targetImageId))

        if (!vmStarted || (imageId != null && imageId != currentImageId)) {
            currentImageId = targetImageId
            acquireWakeLock()
            serviceScope.launch(Dispatchers.IO) {
                startVm(assets, targetImageId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (WamrRuntime.isRunning) {
            WamrRuntime.saveCheckpoint()
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        WamrRuntime.destroy()
        serviceScope.cancel()
    }

    // --- Output callback management ---

    fun setOutputCallback(cb: (String) -> Unit) {
        outputCallback = cb
    }

    fun clearOutputCallback() {
        outputCallback = null
    }

    /** Returns buffered output for UI replay on reconnect. */
    fun getBufferedOutput(): String {
        synchronized(outputBuffer) {
            return outputBuffer.toString()
        }
    }

    // --- Public methods for checkpoint management ---

    fun saveCheckpoint(): Boolean {
        return if (WamrRuntime.isRunning) {
            WamrRuntime.saveCheckpoint()
            true
        } else {
            false
        }
    }

    // --- VM lifecycle ---

    private suspend fun startVm(assets: AssetManager, imageId: String) {
        try {
            val imageInfo = ImageManager.getImageInfo(imageId)
            val displayName = imageInfo?.displayName ?: imageId

            deliverOutput("Initializing WAMR...\n")

            if (!WamrRuntime.initialize()) {
                deliverOutput("ERROR: Failed to initialize WAMR\n")
                return
            }

            val assetName = imageInfo?.assetName ?: "alpine.aot"
            deliverOutput("Loading $displayName ($assetName)...\n")

            val wasmBytes = imageManager.getImageBytes(imageId, assets)

            deliverOutput("Size: ${wasmBytes.size / 1024 / 1024} MB\n")

            if (!WamrRuntime.loadModule(wasmBytes)) {
                deliverOutput("ERROR: Failed to load module\n")
                return
            }

            val hasCheckpoint = WamrRuntime.hasCheckpoint()

            if (hasCheckpoint) {
                deliverOutput("Restoring $displayName from checkpoint...\n")
            } else {
                deliverOutput("Starting $displayName fresh (Bochs x86 boot)...\n")
            }

            vmStarted = true

            val started = WamrRuntime.startWithRestore { text ->
                deliverOutput(text)
            }

            if (!started) {
                deliverOutput("ERROR: Failed to start VM\n")
                vmStarted = false
            }
        } catch (e: Exception) {
            deliverOutput("ERROR: ${e.message}\n")
            vmStarted = false
        }
    }

    private fun deliverOutput(text: String) {
        synchronized(outputBuffer) {
            outputBuffer.append(text)
            if (outputBuffer.length > OUTPUT_BUFFER_CAPACITY) {
                val excess = outputBuffer.length - OUTPUT_BUFFER_TRIM_TARGET
                outputBuffer.delete(0, excess)
            }
        }
        outputCallback?.invoke(text)
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VM Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the WebAssembly VM running in the background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(imageId: String): Notification {
        val imageInfo = ImageManager.getImageInfo(imageId)
        val displayName = imageInfo?.displayName ?: imageId

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$displayName running")
            .setContentText("Friscy terminal is active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // --- Wake lock ---

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "c2wdemo:vm").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val EXTRA_IMAGE_ID = "image_id"
        private const val DEFAULT_IMAGE_ID = "claude"
        private const val CHANNEL_ID = "vm_service"
        private const val NOTIFICATION_ID = 1
        private const val OUTPUT_BUFFER_CAPACITY = 65536
        private const val OUTPUT_BUFFER_TRIM_TARGET = 49152
    }
}
