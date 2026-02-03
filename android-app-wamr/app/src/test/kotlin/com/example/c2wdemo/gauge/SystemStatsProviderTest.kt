package com.example.c2wdemo.gauge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemStatsProviderTest {

    private lateinit var provider: SystemStatsProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        provider = SystemStatsProvider(context)
    }

    @Test
    fun `output events are counted for FPS`() = runTest {
        var lastData = GaugeData()
        provider.start(this) { lastData = it }

        // Simulate 30 output events during the 1-second window
        repeat(30) { provider.onOutputEvent() }

        // Advance past the 1-second tick
        advanceTimeBy(1001)

        assertEquals("FPS should reflect output event count", 30, lastData.fps)

        provider.stop()
    }

    @Test
    fun `FPS resets each second`() = runTest {
        var lastData = GaugeData()
        provider.start(this) { lastData = it }

        repeat(10) { provider.onOutputEvent() }
        advanceTimeBy(1001)
        assertEquals(10, lastData.fps)

        // No events in next second
        advanceTimeBy(1000)
        assertEquals("FPS should reset to 0", 0, lastData.fps)

        provider.stop()
    }

    @Test
    fun `latency is measured from input to output`() {
        // Call onInputEvent, then onOutputEvent
        provider.onInputEvent()
        // Small busy-wait to ensure measurable time
        val start = System.nanoTime()
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() - start < 1_000_000) {} // ~1ms
        provider.onOutputEvent()

        assertTrue("Latency should be >= 1ms", provider.lastLatencyMs >= 1)
    }

    @Test
    fun `latency resets after measurement`() {
        provider.onInputEvent()
        Thread.sleep(2)
        provider.onOutputEvent()
        val first = provider.lastLatencyMs
        assertTrue(first > 0)

        // Second output without input should not update latency
        provider.onOutputEvent()
        assertEquals("Latency unchanged without new input", first, provider.lastLatencyMs)
    }

    @Test
    fun `RAM usage is between 0 and 1`() = runTest {
        var lastData = GaugeData()
        provider.start(this) { lastData = it }

        advanceTimeBy(1100)

        assertTrue("RAM should be >= 0", lastData.ramPercent >= 0f)
        assertTrue("RAM should be <= 1", lastData.ramPercent <= 1f)

        provider.stop()
    }

    @Test
    fun `GaugeData defaults are zero`() {
        val data = GaugeData()
        assertEquals(0f, data.ramPercent, 0.001f)
        assertEquals(0, data.fps)
        assertEquals(0, data.latencyMs)
        assertEquals(0f, data.thermalFraction, 0.001f)
    }

    @Test
    fun `thermal fraction is between 0 and 1`() = runTest {
        var lastData = GaugeData()
        provider.start(this) { lastData = it }

        advanceTimeBy(1100)

        assertTrue("thermalFraction should be >= 0", lastData.thermalFraction >= 0f)
        assertTrue("thermalFraction should be <= 1", lastData.thermalFraction <= 1f)

        provider.stop()
    }

    @Test
    fun `thermalColor returns green at zero`() {
        val color = thermalColor(0f)
        // Should be exactly the first stop: #00FF88
        assertEquals(0f, color.red, 0.01f)
        assertEquals(1f, color.green, 0.01f)
        assertEquals(0.533f, color.blue, 0.02f)
    }

    @Test
    fun `thermalColor returns red at high fraction`() {
        val color = thermalColor(1.0f)
        // Should be the last stop: #FF1A1A
        assertEquals(1f, color.red, 0.01f)
        assertTrue("Green channel should be low at high thermal", color.green < 0.15f)
        assertTrue("Blue channel should be low at high thermal", color.blue < 0.15f)
    }

    @Test
    fun `thermalColor clamps below zero`() {
        val color = thermalColor(-0.5f)
        val colorAtZero = thermalColor(0f)
        assertEquals(colorAtZero, color)
    }

    @Test
    fun `thermalColor clamps above one`() {
        val color = thermalColor(1.5f)
        val colorAtOne = thermalColor(1.0f)
        assertEquals(colorAtOne, color)
    }

    @Test
    fun `thermalColor interpolates mid values`() {
        val cool = thermalColor(0f)
        val warm = thermalColor(0.35f)
        val hot = thermalColor(0.6f)
        val critical = thermalColor(0.85f)
        // Red channel should increase monotonically across stops
        assertTrue("Red should increase from cool to warm", warm.red >= cool.red)
        assertTrue("Red should increase from warm to hot", hot.red >= warm.red)
        assertTrue("Red should increase from hot to critical", critical.red >= hot.red)
        // Green channel should decrease from warm onward
        assertTrue("Green should decrease from warm to critical", critical.green < warm.green)
    }

    @Test
    fun `stop prevents further updates`() = runTest {
        var updateCount = 0
        provider.start(this) { updateCount++ }

        advanceTimeBy(1100)
        provider.stop()
        val countAfterStop = updateCount

        advanceTimeBy(3000)
        assertEquals("No updates after stop", countAfterStop, updateCount)
    }
}
