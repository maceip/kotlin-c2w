package com.example.c2wdemo

/**
 * JNI wrapper for friscy — libriscv RISC-V 64 emulator.
 *
 * Phase 2: Interactive Alpine shell with VFS + rootfs.tar.
 */
object FriscyRuntime {

    init {
        System.loadLibrary("friscy_android")
    }

    interface OutputCallback {
        fun onOutput(text: String)
    }

    // --- Native methods ---

    external fun nativeInit(): Boolean
    external fun nativeLoadRootfs(tarBytes: ByteArray, entryPath: String, callback: OutputCallback): Boolean
    external fun nativeStart(): Boolean
    external fun nativeSendInput(text: String)
    external fun nativeStop()
    external fun nativeDestroy()
    external fun nativeIsRunning(): Boolean
    external fun nativeGetVersion(): String
    external fun nativeSetTerminalSize(cols: Int, rows: Int)
    external fun nativeSaveSnapshot(path: String): Boolean
    external fun nativeRestoreSnapshot(path: String): Boolean

    // --- Kotlin API ---

    fun initialize(): Boolean = nativeInit()

    fun loadRootfs(tarBytes: ByteArray, entryPath: String = "/bin/sh", onOutput: (String) -> Unit): Boolean {
        return nativeLoadRootfs(tarBytes, entryPath, object : OutputCallback {
            override fun onOutput(text: String) {
                onOutput(text)
            }
        })
    }

    fun start(): Boolean = nativeStart()

    fun sendInput(input: String) {
        if (isRunning) {
            nativeSendInput(input)
        }
    }

    fun stop() = nativeStop()

    fun destroy() = nativeDestroy()

    val isRunning: Boolean get() = nativeIsRunning()

    val version: String get() = nativeGetVersion()
}
