package com.example.c2wdemo.gauge

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls system stats and provides GaugeData updates.
 *
 * - RAM: device memory usage via ActivityManager
 * - FPS: tracks output callback frequency (set externally)
 * - Latency: tracks command round-trip time (set externally)
 */
class SystemStatsProvider(private val context: Context) {

    private var job: Job? = null
    private var onUpdate: ((GaugeData) -> Unit)? = null

    // FPS tracking: count output events per second
    private var outputEventCount = 0
    private var lastFps = 0

    // Latency tracking: time from last input to next output
    private var lastInputTimeNs = 0L
    @Volatile
    var lastLatencyMs = 0
        private set

    fun start(scope: CoroutineScope, onUpdate: (GaugeData) -> Unit) {
        this.onUpdate = onUpdate
        job = scope.launch {
            while (isActive) {
                delay(1000)
                val ram = queryRamUsage()
                lastFps = outputEventCount
                outputEventCount = 0

                val data = GaugeData(
                    ramPercent = ram,
                    fps = lastFps,
                    latencyMs = lastLatencyMs,
                )
                onUpdate(data)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Call this each time the VM produces output. */
    fun onOutputEvent() {
        outputEventCount++
        if (lastInputTimeNs > 0) {
            val elapsed = (System.nanoTime() - lastInputTimeNs) / 1_000_000
            lastLatencyMs = elapsed.toInt().coerceAtMost(999)
            lastInputTimeNs = 0
        }
    }

    /** Call this each time input is sent to the VM. */
    fun onInputEvent() {
        lastInputTimeNs = System.nanoTime()
    }

    private fun queryRamUsage(): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val total = memInfo.totalMem.toFloat()
        val avail = memInfo.availMem.toFloat()
        return if (total > 0) (1f - avail / total).coerceIn(0f, 1f) else 0f
    }
}
