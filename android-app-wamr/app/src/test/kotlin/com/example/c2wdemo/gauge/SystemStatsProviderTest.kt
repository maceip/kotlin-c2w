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
