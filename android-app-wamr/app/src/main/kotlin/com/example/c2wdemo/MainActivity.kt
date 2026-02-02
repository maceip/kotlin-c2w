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

/**
 * High-performance c2w Android app using WAMR native runtime.
 *
 * Architecture:
 *   UI (Kotlin) ←→ JNI ←→ WAMR (C/C++) ←→ Bochs WASM ←→ Alpine Linux
 *
 * This replaces Chicory (Java WASM interpreter) with WAMR (native),
 * providing 10-15x performance improvement.
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
        appendOutput("High-performance native WASM runtime\n")
        appendOutput("Runtime: ${WamrRuntime.version}\n\n")

        // Auto-start VM
        lifecycleScope.launch {
            startVm()
        }
    }

    private fun sendCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.isNotEmpty() && WamrRuntime.isRunning) {
            WamrRuntime.sendInput(cmd + "\n")
            commandInput.text.clear()
            appendOutput("> $cmd\n")
        }
    }

    private suspend fun startVm() = withContext(Dispatchers.IO) {
        try {
            // Initialize WAMR
            withContext(Dispatchers.Main) {
                appendOutput("Initializing WAMR runtime...\n")
            }

            if (!WamrRuntime.initialize()) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to initialize WAMR\n")
                }
                return@withContext
            }

            // Load WASM from assets
            withContext(Dispatchers.Main) {
                appendOutput("Loading alpine.wasm from assets...\n")
            }

            val wasmBytes = assets.open("alpine.wasm").use { it.readBytes() }

            withContext(Dispatchers.Main) {
                appendOutput("Size: ${wasmBytes.size / 1024 / 1024} MB\n")
                appendOutput("Parsing WASM module...\n")
            }

            if (!WamrRuntime.loadModule(wasmBytes)) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to load WASM module\n")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                appendOutput("Starting VM...\n")
                appendOutput("(Bochs x86 emulator - now with WAMR!)\n\n")
            }

            // Start VM with output callback
            val started = WamrRuntime.start { text ->
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
