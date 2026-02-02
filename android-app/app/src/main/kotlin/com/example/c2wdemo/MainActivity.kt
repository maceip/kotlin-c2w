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
import at.released.weh.bindings.chicory.wasip1.ChicoryWasiPreview1Builder
import at.released.weh.filesystem.stdio.StdioSink
import at.released.weh.filesystem.stdio.StdioSource
import at.released.weh.host.EmbedderHost
import at.released.weh.host.SystemEnvProvider
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Android demo app that boots Alpine Linux inside c2w/Bochs WASM.
 *
 * Key fixes applied:
 * 1. Empty environment variables (c2w has 1024 byte limit)
 * 2. Preopened directory at fd=3
 * 3. Handshake protocol handling (send "=\n" after seeing "==========")
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button
    private lateinit var loadButton: Button

    private var stdinQueue: LinkedBlockingQueue<Int>? = null
    private var vmRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)
        loadButton = findViewById(R.id.loadButton)

        terminalOutput.movementMethod = ScrollingMovementMethod()

        loadButton.setOnClickListener {
            loadAndRunWasm()
        }

        sendButton.setOnClickListener {
            sendCommand()
        }

        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendCommand()
                true
            } else {
                false
            }
        }

        appendOutput("=== Alpine Linux on WASM (c2w + wasi-emscripten-host + Chicory) ===\n")
        appendOutput("Place alpine.wasm in /sdcard/Download/ and tap 'Load WASM'\n\n")
    }

    private fun sendCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.isNotEmpty() && stdinQueue != null) {
            // Send command + newline to VM stdin
            cmd.forEach { stdinQueue?.put(it.code) }
            stdinQueue?.put('\n'.code)
            commandInput.text.clear()
            appendOutput("> $cmd\n")
        }
    }

    private fun loadAndRunWasm() {
        if (vmRunning) {
            appendOutput("VM is already running\n")
            return
        }

        lifecycleScope.launch {
            runVm()
        }
    }

    private suspend fun runVm() = withContext(Dispatchers.IO) {
        // Try to find the WASM file - check internal storage first
        val possiblePaths = listOf(
            "${filesDir.absolutePath}/alpine.wasm",
            "/sdcard/Download/alpine.wasm",
            "/storage/emulated/0/Download/alpine.wasm"
        )

        val wasmFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        if (wasmFile == null) {
            withContext(Dispatchers.Main) {
                appendOutput("ERROR: alpine.wasm not found!\n")
                appendOutput("Tried paths:\n")
                possiblePaths.forEach { appendOutput("  - $it\n") }
                appendOutput("\nGenerate it with: c2w alpine:latest alpine.wasm\n")
            }
            return@withContext
        }

        vmRunning = true
        withContext(Dispatchers.Main) {
            loadButton.isEnabled = false
            appendOutput("Loading WASM from ${wasmFile.absolutePath}...\n")
            appendOutput("File size: ${wasmFile.length() / 1024 / 1024} MB\n")
        }

        try {
            // Parse WASM module
            withContext(Dispatchers.Main) { appendOutput("Parsing WASM module...\n") }
            val wasmModule = Parser.parse(wasmFile)

            // Create stdin/stdout with handshake handling
            stdinQueue = LinkedBlockingQueue()
            val wrappedStdin = C2wStdinStream(stdinQueue!!)
            val wrappedStdout = C2wStdoutStream(
                onOutput = { text ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        appendOutput(text)
                    }
                },
                onHandshake = {
                    // Auto-send handshake signal
                    stdinQueue?.put('='.code)
                    stdinQueue?.put('\n'.code)
                    lifecycleScope.launch(Dispatchers.Main) {
                        appendOutput("[Host] Sent boot signal\n")
                    }
                }
            )

            // Create WASI host with fixes
            withContext(Dispatchers.Main) { appendOutput("Creating WASI host...\n") }
            val host = EmbedderHost {
                fileSystem {
                    addPreopenedDirectory("/", "/")
                }
                // CRITICAL: Empty environment - c2w has 1024 byte limit
                systemEnv = SystemEnvProvider { emptyMap() }
                // Connect terminal I/O
                stdin = StdioSource.Provider { DelegatedStdioSource(wrappedStdin) }
                stdout = StdioSink.Provider { DelegatedStdioSink(wrappedStdout) }
                stderr = StdioSink.Provider { DelegatedStdioSink(wrappedStdout) }
            }

            // Build WASI imports
            withContext(Dispatchers.Main) { appendOutput("Building WASI imports...\n") }
            val wasiImports = ChicoryWasiPreview1Builder { this.host = host }.build()
            val hostImports = ImportValues.builder().withFunctions(wasiImports).build()

            // Instantiate WASM
            withContext(Dispatchers.Main) { appendOutput("Instantiating WASM...\n") }
            val instance = Instance.builder(wasmModule)
                .withImportValues(hostImports)
                .withInitialize(true)
                .withStart(false)
                .build()

            withContext(Dispatchers.Main) {
                appendOutput("Starting VM...\n")
                appendOutput("(Bochs x86 emulator in WASM - VERY slow)\n\n")
            }

            // Run the VM
            try {
                instance.export("_start").apply()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\nVM exited: ${e.message}\n")
                }
            }

            host.close()

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendOutput("ERROR: ${e.message}\n")
                e.printStackTrace()
            }
        } finally {
            vmRunning = false
            stdinQueue = null
            withContext(Dispatchers.Main) {
                loadButton.isEnabled = true
            }
        }
    }

    private fun appendOutput(text: String) {
        terminalOutput.append(text)
        // Auto-scroll to bottom
        val scrollAmount = terminalOutput.layout?.getLineTop(terminalOutput.lineCount) ?: 0
        terminalOutput.scrollTo(0, scrollAmount - terminalOutput.height)
    }
}

/**
 * Custom InputStream for VM stdin with handshake support.
 */
class C2wStdinStream(private val queue: LinkedBlockingQueue<Int>) : InputStream() {
    @Volatile private var closed = false

    override fun read(): Int {
        if (closed) return -1
        while (!closed) {
            val b = queue.poll(100, TimeUnit.MILLISECONDS)
            if (b != null) return b
        }
        return -1
    }

    override fun available(): Int = queue.size

    override fun close() {
        closed = true
    }
}

/**
 * Custom OutputStream for VM stdout with handshake detection.
 */
class C2wStdoutStream(
    private val onOutput: (String) -> Unit,
    private val onHandshake: () -> Unit
) : OutputStream() {
    private var equalCount = 0
    private var handshakeSent = false
    private val buffer = StringBuilder()

    override fun write(b: Int) {
        val c = b.toChar()
        buffer.append(c)

        // Flush on newline
        if (c == '\n') {
            onOutput(buffer.toString())
            buffer.clear()
        }

        // Detect "==========" pattern
        if (!handshakeSent) {
            if (b == '='.code) {
                equalCount++
                if (equalCount >= 10) {
                    handshakeSent = true
                    thread {
                        Thread.sleep(100)
                        onHandshake()
                    }
                }
            } else {
                equalCount = 0
            }
        }
    }

    override fun flush() {
        if (buffer.isNotEmpty()) {
            onOutput(buffer.toString())
            buffer.clear()
        }
    }

    override fun close() {
        flush()
    }
}

/**
 * StdioSource wrapper
 */
class DelegatedStdioSource(
    inputStream: InputStream,
    source: RawSource = inputStream.asSource()
) : StdioSource, RawSource by source

/**
 * StdioSink wrapper
 */
class DelegatedStdioSink(
    outputStream: OutputStream,
    sink: RawSink = outputStream.asSink()
) : StdioSink, RawSink by sink
