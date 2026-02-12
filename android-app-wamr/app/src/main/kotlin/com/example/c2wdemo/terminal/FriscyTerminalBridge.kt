package com.example.c2wdemo.terminal

import android.util.Log
import com.example.c2wdemo.FriscyRuntime
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient

/**
 * Bridge between the friscy RISC-V runtime and the Termux TerminalEmulator.
 *
 * Data flow:
 *   Guest stdout → [feedOutput(bytes)] → TerminalEmulator.append() → screen buffer
 *   User keystroke → TerminalEmulator → [write(bytes)] → FriscyRuntime.sendInput()
 *
 * This class extends [TerminalOutput], which the [TerminalEmulator] uses as its
 * "session" (the process that receives input and sends output).
 */
class FriscyTerminalBridge(
    private val listener: BridgeListener,
) : TerminalOutput() {

    companion object {
        private const val TAG = "FriscyBridge"
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_COLS = 80
        private const val TRANSCRIPT_ROWS = 2000
    }

    /** Callbacks for UI-level events. */
    interface BridgeListener {
        fun onScreenUpdated()
        fun onTitleChanged(title: String)
        fun onBell()
        fun onCopyText(text: String)
        fun onPasteRequest()
    }

    /** The TerminalEmulator that parses escape sequences and maintains the screen buffer. */
    var emulator: TerminalEmulator? = null
        private set

    private val client = BridgeSessionClient()

    /**
     * Initialize the emulator with given dimensions.
     * Call this once the view has been measured and we know the rows/cols.
     */
    fun initializeEmulator(cols: Int = DEFAULT_COLS, rows: Int = DEFAULT_ROWS) {
        emulator = TerminalEmulator(this, cols, rows, TRANSCRIPT_ROWS, client)
    }

    /**
     * Resize the emulator when the terminal view dimensions change.
     */
    fun updateSize(cols: Int, rows: Int) {
        emulator?.resize(cols, rows)
    }

    // --- TerminalOutput abstract methods ---

    /**
     * Called by TerminalEmulator when it needs to write data back to the "process".
     * In Termux this goes to a PTY; for us it goes to the friscy runtime's stdin.
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        val text = String(data, offset, count, Charsets.UTF_8)
        FriscyRuntime.sendInput(text)
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        listener.onTitleChanged(newTitle ?: "")
    }

    override fun onCopyTextToClipboard(text: String?) {
        text?.let { listener.onCopyText(it) }
    }

    override fun onPasteTextFromClipboard() {
        listener.onPasteRequest()
    }

    override fun onBell() {
        listener.onBell()
    }

    override fun onColorsChanged() {
        listener.onScreenUpdated()
    }

    // --- Feed output from guest ---

    /**
     * Feed bytes produced by the guest process (stdout/stderr) into the terminal emulator.
     * This parses ANSI escape sequences and updates the screen buffer.
     * Must be called on the main thread.
     */
    fun feedOutput(data: ByteArray, length: Int = data.size) {
        emulator?.append(data, length)
        listener.onScreenUpdated()
    }

    /**
     * Feed a string of output (convenience wrapper).
     */
    fun feedOutput(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        feedOutput(bytes, bytes.size)
    }

    // --- Session client for TerminalEmulator callbacks ---

    /**
     * Minimal TerminalSessionClient. The TerminalEmulator only calls
     * getTerminalCursorStyle() and onTerminalCursorStateChange() directly.
     * The other methods are called via TerminalOutput (our abstract methods above).
     */
    private inner class BridgeSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession?) {
            listener.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession?) {}
        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession?) {}
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession?, text: String?) {}
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
        override fun onBell(session: com.termux.terminal.TerminalSession?) {}
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession?) {}

        override fun onTerminalCursorStateChange(state: Boolean) {
            listener.onScreenUpdated()
        }

        override fun getTerminalCursorStyle(): Int {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message ?: "")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {
            Log.d(tag ?: TAG, message ?: "")
        }

        override fun logVerbose(tag: String?, message: String?) {
            Log.v(tag ?: TAG, message ?: "")
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Exception", e)
        }
    }
}
