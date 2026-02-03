package com.example.c2wdemo.gauge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
                val thermal = queryThermalFraction()
                lastFps = outputEventCount
                outputEventCount = 0

                val data = GaugeData(
                    ramPercent = ram,
                    fps = lastFps,
                    latencyMs = lastLatencyMs,
                    thermalFraction = thermal,
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

    /**
     * Returns a thermal fraction (0.0 = cool, 1.0 = emergency) using the best
     * API available on the current device.
     *
     * - API 30+: PowerManager.getThermalHeadroom(0) — smooth 0.0–1.0 float
     * - API 29:  PowerManager.getCurrentThermalStatus() — discrete 0–6 levels
     * - API 26–28: Battery temperature via ACTION_BATTERY_CHANGED sticky broadcast
     */
    private fun queryThermalFraction(): Float {
        return if (Build.VERSION.SDK_INT >= 30) {
            queryThermalHeadroom()
        } else if (Build.VERSION.SDK_INT >= 29) {
            queryThermalStatus()
        } else {
            queryBatteryTemperature()
        }
    }

    private fun queryThermalHeadroom(): Float {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return 0f
        val headroom = pm.getThermalHeadroom(0)
        // getThermalHeadroom returns NaN on some devices when unavailable
        if (headroom.isNaN()) return 0f
        return headroom.coerceIn(0f, 1f)
    }

    private fun queryThermalStatus(): Float {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return 0f
        val status = if (Build.VERSION.SDK_INT >= 29) {
            pm.currentThermalStatus
        } else {
            return 0f
        }
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> 0.0f
            PowerManager.THERMAL_STATUS_LIGHT -> 0.17f
            PowerManager.THERMAL_STATUS_MODERATE -> 0.33f
            PowerManager.THERMAL_STATUS_SEVERE -> 0.5f
            PowerManager.THERMAL_STATUS_CRITICAL -> 0.7f
            PowerManager.THERMAL_STATUS_EMERGENCY -> 0.9f
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 1.0f
            else -> 0f
        }
    }

    private fun queryBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0f
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
        val tempC = tempTenths / 10f
        // Map 25°C → 0.0, 55°C → 1.0
        return ((tempC - 25f) / 30f).coerceIn(0f, 1f)
    }
}
