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
 */
object WamrRuntime {

    init {
        System.loadLibrary("c2w_wamr")
    }

    interface OutputCallback {
        fun onOutput(text: String)
    }

    // Native methods
    external fun nativeInit(): Boolean
    external fun nativeLoadModule(wasmBytes: ByteArray): Boolean
    external fun nativeStart(callback: OutputCallback): Boolean
    external fun nativeSendInput(input: String)
    external fun nativeStop()
    external fun nativeDestroy()
    external fun nativeIsRunning(): Boolean
    external fun nativeGetVersion(): String

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

    fun sendInput(input: String) = nativeSendInput(input)

    fun stop() = nativeStop()

    fun destroy() = nativeDestroy()

    val isRunning: Boolean get() = nativeIsRunning()

    val version: String get() = nativeGetVersion()
}
