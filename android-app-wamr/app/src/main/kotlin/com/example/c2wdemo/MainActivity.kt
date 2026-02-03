package com.example.c2wdemo

import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.example.c2wdemo.gauge.GaugeData
import com.example.c2wdemo.gauge.SystemStatsProvider
import com.example.c2wdemo.gauge.ZimStatusGauge
import com.example.c2wdemo.ime.ControlFocusInsetsAnimationCallback
import com.example.c2wdemo.ime.RootViewDeferringInsetsCallback
import com.example.c2wdemo.ime.TranslateDeferringInsetsAnimationCallback
import com.example.c2wdemo.terminal.AnsiTerminalParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: EditText
    private lateinit var terminalScroll: NestedScrollView
    private lateinit var contentRoot: View
    private lateinit var zimHelperBar: LinearLayout
    private lateinit var btnCtrl: TextView
    private lateinit var btnPrevWord: TextView
    private lateinit var btnNextWord: TextView
    private lateinit var btnUp: TextView
    private val ansiParser = AnsiTerminalParser()
    private lateinit var statsProvider: SystemStatsProvider
    private val gaugeState = mutableStateOf(GaugeData())

    /** Character index where user input begins (everything before is read-only output). */
    private var inputStartPos = 0

    /** Set to true during internal edits to bypass the output protection filter. */
    private var internalEdit = false

    /** Sticky Ctrl mode: next character typed is sent as a control character. */
    private var ctrlSticky = false

    /** Tracks accumulated output size for auto-close keyboard. */
    private var pendingOutputSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: let the app handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        terminalScroll = findViewById(R.id.terminalScroll)
        contentRoot = findViewById(R.id.contentRoot)
        zimHelperBar = findViewById(R.id.zimHelperBar)
        btnCtrl = findViewById(R.id.btnCtrl)
        btnPrevWord = findViewById(R.id.btnPrevWord)
        btnNextWord = findViewById(R.id.btnNextWord)
        btnUp = findViewById(R.id.btnUp)

        terminalOutput.setText("", TextView.BufferType.SPANNABLE)

        // Prevent user from editing/deleting output text before inputStartPos
        terminalOutput.filters = arrayOf(ProtectOutputFilter())

        setupImeAnimation()
        setupZimHelperBar()
        setupInputHandling()
        setupStatusGauge()

        appendOutput("=== Kiosk Runtime (WAMR Edition) ===\n")
        appendOutput("Runtime: ${WamrRuntime.version}\n")
        appendOutput("Commands: !save !restore !info !clear\n\n")

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

    /**
     * Set up the 3-layer WindowInsetsAnimation callback system:
     *   1. Root: defer IME insets during animation, apply system bars as padding
     *   2. Content views: translate during IME animation
     *   3. EditText: manage focus with IME visibility
     *
     * Also tracks IME visibility to show/hide the Zim helper bar.
     */
    private fun setupImeAnimation() {
        // Layer 1: Root view defers IME insets during animation
        val deferringInsetsListener = RootViewDeferringInsetsCallback(
            persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
            deferredInsetTypes = WindowInsetsCompat.Type.ime(),
        )
        ViewCompat.setWindowInsetsAnimationCallback(contentRoot, deferringInsetsListener)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, deferringInsetsListener)

        // Layer 2: Translate the terminal scroll area and helper bar during IME animation
        ViewCompat.setWindowInsetsAnimationCallback(
            terminalScroll,
            TranslateDeferringInsetsAnimationCallback(
                view = terminalScroll,
                persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
                deferredInsetTypes = WindowInsetsCompat.Type.ime(),
            ),
        )
        ViewCompat.setWindowInsetsAnimationCallback(
            zimHelperBar,
            object : TranslateDeferringInsetsAnimationCallback(
                view = zimHelperBar,
                persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
                deferredInsetTypes = WindowInsetsCompat.Type.ime(),
                dispatchMode = WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        updateHelperBarVisibility()
                    }
                }
            },
        )

        // Layer 2.5: Focus control on the EditText
        ViewCompat.setWindowInsetsAnimationCallback(
            terminalOutput,
            ControlFocusInsetsAnimationCallback(terminalOutput),
        )

        // Track IME visibility changes to show/hide helper bar
        ViewCompat.setOnApplyWindowInsetsListener(terminalScroll) { _, insets ->
            updateHelperBarVisibility()
            insets
        }
    }

    private fun updateHelperBarVisibility() {
        val imeVisible = ViewCompat.getRootWindowInsets(contentRoot)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        zimHelperBar.visibility = if (imeVisible) View.VISIBLE else View.GONE
    }

    /**
     * Set up the Zim helper bar buttons:
     *   - CTRL: Sticky toggle. Next char typed → control character sent to VM.
     *   - ◄W: Previous word (sends ESC b).
     *   - W►: Next word (sends ESC f).
     *   - ▲: Up arrow (sends ESC[A).
     */
    private fun setupZimHelperBar() {
        btnCtrl.setOnClickListener {
            ctrlSticky = !ctrlSticky
            btnCtrl.isSelected = ctrlSticky
            btnCtrl.setTextColor(if (ctrlSticky) 0xFFFF1493.toInt() else 0xFF00FFFF.toInt())
        }

        btnPrevWord.setOnClickListener {
            if (WamrRuntime.isRunning) {
                statsProvider.onInputEvent()
                WamrRuntime.sendInput("\u001Bb")
            }
        }

        btnNextWord.setOnClickListener {
            if (WamrRuntime.isRunning) {
                statsProvider.onInputEvent()
                WamrRuntime.sendInput("\u001Bf")
            }
        }

        btnUp.setOnClickListener {
            if (WamrRuntime.isRunning) {
                statsProvider.onInputEvent()
                WamrRuntime.sendInput("\u001B[A")
            }
        }
    }

    private fun setupStatusGauge() {
        statsProvider = SystemStatsProvider(this)
        statsProvider.start(lifecycleScope) { data ->
            gaugeState.value = data
        }

        val composeView = findViewById<ComposeView>(R.id.statusGauge)
        composeView.setContent {
            ZimStatusGauge(data = gaugeState)
        }
    }

    private fun setupInputHandling() {
        // Handle Enter key to send command
        terminalOutput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInlineCommand()
                true
            } else false
        }
        terminalOutput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendInlineCommand()
                true
            } else false
        }

        // TextWatcher for sticky Ctrl: intercept character input
        terminalOutput.addTextChangedListener(object : TextWatcher {
            private var beforeLength = 0

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                beforeLength = s.length
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (!ctrlSticky || internalEdit) return

                // Check if the user added exactly one character in the input region
                if (s.length == beforeLength + 1) {
                    val newCharIndex = s.length - 1
                    if (newCharIndex >= inputStartPos) {
                        val ch = s[newCharIndex]
                        if (ch in 'a'..'z' || ch in 'A'..'Z') {
                            // Convert to control character: Ctrl+A = 0x01, Ctrl+C = 0x03, etc.
                            val ctrlChar = (ch.uppercaseChar() - '@').toChar()

                            // Remove the typed character
                            internalEdit = true
                            s.delete(newCharIndex, newCharIndex + 1)
                            internalEdit = false

                            // Send control character to VM
                            if (WamrRuntime.isRunning) {
                                WamrRuntime.sendInput(ctrlChar.toString())
                            }

                            // Deactivate sticky Ctrl
                            ctrlSticky = false
                            btnCtrl.isSelected = false
                            btnCtrl.setTextColor(0xFF00FFFF.toInt())
                        }
                    }
                }
            }
        })
    }

    /**
     * Extract the text the user typed after the output area, process it,
     * and send it to the VM (or handle special commands).
     */
    private fun sendInlineCommand() {
        val editable = terminalOutput.editableText ?: return
        val fullText = editable.toString()
        if (inputStartPos > fullText.length) {
            inputStartPos = fullText.length
        }
        val cmd = fullText.substring(inputStartPos).trim()
        if (cmd.isEmpty()) return

        // Remove the typed text
        internalEdit = true
        editable.delete(inputStartPos, editable.length)
        internalEdit = false

        // Handle special commands
        when (cmd.lowercase()) {
            "!save" -> {
                appendOutput("$cmd\n")
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
                appendOutput("$cmd\n")
                appendOutput("[Host] Deleting checkpoint...\n")
                WamrRuntime.deleteCheckpoint()
                appendOutput("[Host] Checkpoint deleted\n")
                appendOutput("[Host] Restart app for fresh boot\n")
                return
            }
            "!info" -> {
                appendOutput("$cmd\n")
                val info = WamrRuntime.getCheckpointInfo()
                if (info != null) {
                    appendOutput("[Host] $info\n")
                } else {
                    appendOutput("[Host] No checkpoint exists\n")
                }
                return
            }
            "!clear" -> {
                ansiParser.reset()
                internalEdit = true
                terminalOutput.setText("", TextView.BufferType.SPANNABLE)
                internalEdit = false
                inputStartPos = 0
                appendOutput("[Host] Terminal cleared\n")
                return
            }
        }

        // Regular command — send to VM
        if (WamrRuntime.isRunning) {
            statsProvider.onInputEvent()
            WamrRuntime.sendInput(cmd + "\n")
        }
    }

    private suspend fun startVm() = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                appendOutput("Initializing WAMR...\n")
            }

            if (!WamrRuntime.initialize()) {
                withContext(Dispatchers.Main) {
                    appendOutput("ERROR: Failed to initialize WAMR\n")
                }
                return@withContext
            }

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
        if (::statsProvider.isInitialized) {
            statsProvider.onOutputEvent()
        }
        val parsed = ansiParser.parse(text)
        val editable = terminalOutput.editableText

        internalEdit = true
        try {
            // Insert parsed output at inputStartPos (before any user-typed text)
            if (editable != null) {
                editable.insert(inputStartPos, parsed)
                inputStartPos += parsed.length
            } else {
                terminalOutput.append(parsed)
                inputStartPos = terminalOutput.text.length
            }

            // Trim if too long (prevent OOM)
            if (editable != null && editable.length > 50_000) {
                val trimCount = editable.length - 37_500
                editable.delete(0, trimCount)
                inputStartPos = maxOf(0, inputStartPos - trimCount)
            }
        } finally {
            internalEdit = false
        }

        // Auto-scroll NestedScrollView to bottom
        terminalScroll.post {
            terminalScroll.fullScroll(View.FOCUS_DOWN)
        }

        // Auto-close keyboard on large output bursts.
        // If the IME is open and we get a large chunk of output (e.g., command output
        // flooding the screen), dismiss the keyboard so the user can read.
        pendingOutputSize += text.length
        terminalScroll.removeCallbacks(outputSizeResetRunnable)
        terminalScroll.postDelayed(outputSizeResetRunnable, 300)

        if (pendingOutputSize > 500) {
            val imeVisible = ViewCompat.getRootWindowInsets(contentRoot)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                ViewCompat.getWindowInsetsController(contentRoot)
                    ?.hide(WindowInsetsCompat.Type.ime())
                pendingOutputSize = 0
            }
        }
    }

    private val outputSizeResetRunnable = Runnable { pendingOutputSize = 0 }

    /**
     * InputFilter that prevents the user from modifying text before [inputStartPos].
     */
    private inner class ProtectOutputFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            if (internalEdit) return null

            if (dstart < inputStartPos) {
                return dest.subSequence(dstart, dend)
            }
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::statsProvider.isInitialized) {
            statsProvider.stop()
        }
        WamrRuntime.destroy()
    }
}
