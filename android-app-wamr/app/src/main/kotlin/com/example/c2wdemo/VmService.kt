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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

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

    override fun onCreate() {
        super.onCreate()
        val checkpointFile = File(filesDir, "vm_checkpoint.snap")
        WamrRuntime.setCheckpointPath(checkpointFile.absolutePath)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    // --- VM lifecycle ---

    private suspend fun startVm(assets: AssetManager) {
        try {
            deliverOutput("Initializing WAMR...\n")

            if (!WamrRuntime.initialize()) {
                deliverOutput("ERROR: Failed to initialize WAMR\n")
                return
            }

            val useAot = true
            val assetName = if (useAot) "alpine.aot" else "alpine.wasm"

            deliverOutput("Loading $assetName...\n")

            val wasmBytes = assets.open(assetName).use { it.readBytes() }

            deliverOutput("Size: ${wasmBytes.size / 1024 / 1024} MB\n")

            if (!WamrRuntime.loadModule(wasmBytes)) {
                deliverOutput("ERROR: Failed to load module\n")
                return
            }

            val hasCheckpoint = WamrRuntime.hasCheckpoint()

            if (hasCheckpoint) {
                deliverOutput("Restoring from checkpoint...\n")
            } else {
                deliverOutput("Starting fresh (Bochs x86 boot)...\n")
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

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alpine Linux running")
            .setContentText("WebAssembly VM is active")
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
