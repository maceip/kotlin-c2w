package com.example.c2wdemo

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance c2w Android app using WAMR native runtime.
 *
 * Architecture:
 *   UI (Kotlin) <-> JNI <-> WAMR (C/C++) <-> Bochs WASM <-> Alpine Linux
 *
 * Features:
 *   - WAMR native runtime (10-15x faster than Chicory)
 *   - AOT compilation (pre-compiled ARM64 code)
 *   - Checkpoint/snapshot restore for instant boot
 *
 * Special Commands:
 *   !save     - Save checkpoint (snapshot current VM state)
 *   !restore  - Delete checkpoint and reboot fresh
 *   !info     - Show checkpoint info
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)

        terminalOutput.movementMethod = ScrollingMovementMethod()

        sendButton.setOnClickListener { sendCommand() }

        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendCommand()
                true
            } else false
        }

        appendOutput("=== Alpine Linux on WASM (WAMR Edition) ===\n")
        appendOutput("Runtime: ${WamrRuntime.version}\n")
        appendOutput("Commands: !save !restore !info\n\n")

        // Set checkpoint path
        val checkpointFile = File(filesDir, "vm_checkpoint.snap")
        WamrRuntime.setCheckpointPath(checkpointFile.absolutePath)

        // Show checkpoint status
        if (WamrRuntime.hasCheckpoint()) {
            val info = WamrRuntime.getCheckpointInfo() ?: "Checkpoint found"
            appendOutput("$info\n")
            appendOutput("Will restore from checkpoint (instant boot)\n\n")
        } else {
            appendOutput("No checkpoint - will do full boot\n")
            appendOutput("Use !save after boot to create checkpoint\n\n")
        }

        // Auto-start VM
        lifecycleScope.launch {
            startVm()
        }
    }

    private fun sendCommand() {
        val cmd = commandInput.text.toString().trim()
        if (cmd.isEmpty()) return

        commandInput.text.clear()

        // Handle special commands
        when (cmd.lowercase()) {
            "!save" -> {
                appendOutput("[Host] Saving checkpoint...\n")
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = WamrRuntime.saveCheckpoint()
                    withContext(Dispatchers.Main) {
                        if (success) {
                            val info = WamrRuntime.getCheckpointInfo() ?: "Saved"
                            appendOutput("[Host] $info\n")
                            appendOutput("[Host] Next launch will restore instantly\n")
                        } else {
                            appendOutput("[Host] Failed to save checkpoint\n")
                        }
                    }
                }
                return
            }
            "!restore" -> {
                appendOutput("[Host] Deleting checkpoint...\n")
                WamrRuntime.deleteCheckpoint()
                appendOutput("[Host] Checkpoint deleted\n")
                appendOutput("[Host] Restart app for fresh boot\n")
                return
            }
            "!info" -> {
                val info = WamrRuntime.getCheckpointInfo()
                if (info != null) {
                    appendOutput("[Host] $info\n")
                } else {
                    appendOutput("[Host] No checkpoint exists\n")
                }
                return
            }
        }

        // Regular command - send to VM
        if (WamrRuntime.isRunning) {
            WamrRuntime.sendInput(cmd + "\n")
            appendOutput("> $cmd\n")
        }
    }

    private suspend fun startVm() = withContext(Dispatchers.IO) {
        try {
            // Initialize WAMR
            withContext(Dispatchers.Main) {
                appendOutput("Initializing WAMR...\n")
            }

            if (!WamrRuntime.initialize()) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to initialize WAMR\n")
                }
                return@withContext
            }

            // Load AOT (pre-compiled) or WASM from assets
            // AOT = pre-compiled native code, much faster but requires matching architecture
            // Set to false for x86_64 emulators, true for ARM64 devices
            val useAot = true
            val assetName = if (useAot) "alpine.aot" else "alpine.wasm"

            withContext(Dispatchers.Main) {
                appendOutput("Loading $assetName...\n")
            }

            val wasmBytes = assets.open(assetName).use { it.readBytes() }

            withContext(Dispatchers.Main) {
                appendOutput("Size: ${wasmBytes.size / 1024 / 1024} MB\n")
            }

            if (!WamrRuntime.loadModule(wasmBytes)) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to load module\n")
                }
                return@withContext
            }

            val hasCheckpoint = WamrRuntime.hasCheckpoint()

            withContext(Dispatchers.Main) {
                if (hasCheckpoint) {
                    appendOutput("Restoring from checkpoint...\n")
                } else {
                    appendOutput("Starting fresh (Bochs x86 boot)...\n")
                }
            }

            // Start VM with checkpoint restore support
            val started = WamrRuntime.startWithRestore { text ->
                runOnUiThread {
                    appendOutput(text)
                }
            }

            if (!started) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to start VM\n")
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendOutput("ERROR: ${e.message}\n")
                e.printStackTrace()
            }
        }
    }

    private fun appendOutput(text: String) {
        terminalOutput.append(text)
        val scrollAmount = terminalOutput.layout?.getLineTop(terminalOutput.lineCount) ?: 0
        terminalOutput.scrollTo(0, maxOf(0, scrollAmount - terminalOutput.height))
    }

    override fun onDestroy() {
        super.onDestroy()
        WamrRuntime.destroy()
    }
}
