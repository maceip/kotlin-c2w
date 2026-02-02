package com.example.c2wdemo

/**
 * Native WAMR (WebAssembly Micro Runtime) interface.
 *
 * Provides high-performance WASM execution using native code
 * instead of Chicory's Java interpreter.
 *
 * Performance comparison:
 *   - Chicory (Java interpreter): ~5% native speed
 *   - WAMR Fast Interp: ~75% native speed
 *   - Expected speedup: 10-15x for Bochs x86 emulation
 *
 * Features:
 *   - AOT (Ahead-of-Time) compilation support
 *   - Checkpoint/Snapshot restore for instant boot
 */
object WamrRuntime {

    init {
        System.loadLibrary("c2w_wamr")
    }

    interface OutputCallback {
        fun onOutput(text: String)
    }

    // Native methods - Core
    external fun nativeInit(): Boolean
    external fun nativeLoadModule(wasmBytes: ByteArray): Boolean
    external fun nativeStart(callback: OutputCallback): Boolean
    external fun nativeSendInput(input: String)
    external fun nativeStop()
    external fun nativeDestroy()
    external fun nativeIsRunning(): Boolean
    external fun nativeGetVersion(): String

    // Native methods - Checkpoint
    external fun nativeSetCheckpointPath(path: String)
    external fun nativeSaveCheckpoint(): Boolean
    external fun nativeHasCheckpoint(): Boolean
    external fun nativeDeleteCheckpoint()
    external fun nativeGetCheckpointInfo(): String?
    external fun nativeStartWithRestore(callback: OutputCallback): Boolean

    // High-level API
    fun initialize(): Boolean = nativeInit()

    fun loadModule(wasmBytes: ByteArray): Boolean = nativeLoadModule(wasmBytes)

    fun start(onOutput: (String) -> Unit): Boolean {
        return nativeStart(object : OutputCallback {
            override fun onOutput(text: String) {
                onOutput(text)
            }
        })
    }

    /**
     * Start VM, restoring from checkpoint if available.
     * This provides instant boot by skipping the Bochs/Linux initialization.
     */
    fun startWithRestore(onOutput: (String) -> Unit): Boolean {
        return nativeStartWithRestore(object : OutputCallback {
            override fun onOutput(text: String) {
                onOutput(text)
            }
        })
    }

    fun sendInput(input: String) = nativeSendInput(input)

    fun stop() = nativeStop()

    fun destroy() = nativeDestroy()

    val isRunning: Boolean get() = nativeIsRunning()

    val version: String get() = nativeGetVersion()

    // Checkpoint API

    /**
     * Set the path where checkpoints will be saved/loaded.
     * Should be in app's private storage directory.
     */
    fun setCheckpointPath(path: String) = nativeSetCheckpointPath(path)

    /**
     * Save current VM state to checkpoint file.
     * Best called after boot completes (e.g., when shell prompt appears).
     */
    fun saveCheckpoint(): Boolean = nativeSaveCheckpoint()

    /**
     * Check if a checkpoint file exists.
     */
    fun hasCheckpoint(): Boolean = nativeHasCheckpoint()

    /**
     * Delete the checkpoint file.
     */
    fun deleteCheckpoint() = nativeDeleteCheckpoint()

    /**
     * Get checkpoint info (size, etc.) for display.
     */
    fun getCheckpointInfo(): String? = nativeGetCheckpointInfo()
}
