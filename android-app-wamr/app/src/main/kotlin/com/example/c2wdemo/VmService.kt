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
import java.io.File
import androidx.core.app.NotificationCompat
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

    /** Image source: "asset" or "file". */
    private var imageSource: String = ImagePickerActivity.SOURCE_ASSET
    /** Asset name (when source is "asset"). */
    private var assetName: String = "rootfs.tar"
    /** File path (when source is "file"). */
    private var filePath: String? = null
    /** Entry point binary inside the rootfs. */
    private var entryPoint: String = "/bin/sh"

    /** Ring buffer of recent output so reconnecting UI can replay missed text. */
    private val outputBuffer = StringBuilder(OUTPUT_BUFFER_CAPACITY)

    /** Whether the VM has been started by this service instance. */
    var vmStarted = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read image params from intent
        intent?.let {
            imageSource = it.getStringExtra(ImagePickerActivity.EXTRA_IMAGE_SOURCE)
                ?: ImagePickerActivity.SOURCE_ASSET
            assetName = it.getStringExtra(ImagePickerActivity.EXTRA_ASSET_NAME) ?: "rootfs.tar"
            filePath = it.getStringExtra(ImagePickerActivity.EXTRA_FILE_PATH)
            entryPoint = it.getStringExtra(ImagePickerActivity.EXTRA_ENTRY_POINT) ?: "/bin/sh"
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (!vmStarted) {
            acquireWakeLock()
            serviceScope.launch(Dispatchers.IO) {
                startVm(assets)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        FriscyRuntime.destroy()
        releaseWakeLock()
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

    // --- VM lifecycle ---

    private suspend fun startVm(assets: AssetManager) {
        try {
            deliverOutput("Initializing friscy runtime...\r\n")

            if (!FriscyRuntime.initialize()) {
                deliverOutput("ERROR: Failed to initialize friscy\r\n")
                return
            }

            deliverOutput("Loading rootfs ($entryPoint)...\r\n")

            val tarBytes = when (imageSource) {
                ImagePickerActivity.SOURCE_FILE -> {
                    val file = File(filePath ?: error("No file path"))
                    if (!file.exists()) error("Image file not found: $filePath")
                    deliverOutput("Source: ${file.name}\r\n")
                    file.readBytes()
                }
                else -> {
                    deliverOutput("Source: asset/$assetName\r\n")
                    assets.open(assetName).use { it.readBytes() }
                }
            }
            deliverOutput("rootfs: ${tarBytes.size} bytes\r\n")

            val loaded = FriscyRuntime.loadRootfs(tarBytes, entryPoint) { text ->
                deliverOutput(text)
            }
            if (!loaded) {
                deliverOutput("ERROR: Failed to load rootfs\r\n")
                return
            }

            vmStarted = true

            if (!FriscyRuntime.start()) {
                deliverOutput("ERROR: Failed to start execution\r\n")
                vmStarted = false
                return
            }

            deliverOutput("[friscy] Shell started\r\n")
        } catch (e: Exception) {
            deliverOutput("ERROR: ${e.message}\r\n")
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
                description = "Keeps the friscy runtime running in the background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("friscy runtime")
            .setContentText("RISC-V emulator active")
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
        private const val CHANNEL_ID = "vm_service"
        private const val NOTIFICATION_ID = 1
        private const val OUTPUT_BUFFER_CAPACITY = 8192
        private const val OUTPUT_BUFFER_TRIM_TARGET = 6144
    }
}
