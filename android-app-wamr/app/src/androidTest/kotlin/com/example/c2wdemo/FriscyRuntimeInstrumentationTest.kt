package com.example.c2wdemo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for the FriscyRuntime JNI lifecycle.
 *
 * Tests are ordered by name because FriscyRuntime is a singleton with
 * global native state — each test builds on the previous one.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FriscyRuntimeInstrumentationTest {

    companion object {
        private val outputBuffer = StringBuilder()
        private val lock = Any()
    }

    @Test
    fun test_01_nativeLibLoads() {
        System.loadLibrary("friscy_android")
        val version = FriscyRuntime.version
        assertNotNull("version should not be null after loading native lib", version)
    }

    @Test
    fun test_02_nativeInitReturnsTrue() {
        val result = FriscyRuntime.initialize()
        assertTrue("initialize() should return true", result)
    }

    @Test
    fun test_03_nativeGetVersionNonEmpty() {
        val version = FriscyRuntime.version
        assertTrue(
            "version should contain 'friscy' or 'libriscv', got: $version",
            version.contains("friscy", ignoreCase = true) ||
                version.contains("libriscv", ignoreCase = true)
        )
    }

    @Test
    fun test_04_loadRootfsSucceeds() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tarBytes = ctx.assets.open("rootfs.tar").use { it.readBytes() }
        assertTrue("rootfs.tar should not be empty", tarBytes.isNotEmpty())

        val result = FriscyRuntime.loadRootfs(tarBytes, "/bin/sh") { text ->
            synchronized(lock) {
                outputBuffer.append(text)
            }
        }
        assertTrue("loadRootfs() should return true", result)
    }

    @Test
    fun test_05_startSucceeds() {
        val result = FriscyRuntime.start()
        assertTrue("start() should return true", result)
        assertTrue("isRunning should be true after start", FriscyRuntime.isRunning)
    }

    @Test
    fun test_06_echoRoundTrip() {
        assertTrue("VM must be running for echo test", FriscyRuntime.isRunning)

        val latch = CountDownLatch(1)

        // Poll output buffer for "hello" on a background thread
        val poller = Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                synchronized(lock) {
                    if (outputBuffer.contains("hello")) {
                        latch.countDown()
                        return@Thread
                    }
                }
                Thread.sleep(200)
            }
        }
        poller.start()

        // Give the shell a moment to be ready, then send echo command
        Thread.sleep(1_000)
        FriscyRuntime.sendInput("echo hello\n")

        val found = latch.await(16, TimeUnit.SECONDS)
        assertTrue(
            "Expected 'hello' in output within 15s, buffer: ${synchronized(lock) { outputBuffer.toString().takeLast(200) }}",
            found
        )
    }

    @Test
    fun test_07_stopDoesNotCrash() {
        FriscyRuntime.stop()
        assertFalse("isRunning should be false after stop", FriscyRuntime.isRunning)
    }

    @Test
    fun test_08_destroyDoesNotCrash() {
        FriscyRuntime.destroy()
        // If we get here without a crash, the test passes
        assertFalse("isRunning should be false after destroy", FriscyRuntime.isRunning)
    }
}
