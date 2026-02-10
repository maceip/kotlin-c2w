package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.nio.charset.StandardCharsets;

/**
 * Bridge between WamrRuntime pipe I/O and the Termux TerminalEmulator.
 *
 * Extends TerminalOutput (the abstract class from terminal-emulator) so that
 * TerminalEmulator query responses (e.g. cursor position reports) flow back
 * to the VM's stdin via WamrRuntime.sendInput().
 *
 * This replaces TerminalSession for our use case: we don't have a pty-based
 * subprocess — instead, stdout arrives via WamrRuntime's output callback and
 * stdin is sent via WamrRuntime.sendInput().
 */
public class WasmTerminalBridge extends TerminalOutput {

    private TerminalEmulator mEmulator;

    /** Callback to send input bytes to the VM (WamrRuntime.sendInput). */
    private InputSink mInputSink;

    /** Callback to check if the VM is running. */
    private RunningCheck mRunningCheck;

    /** Listener for bridge events (text changes, bell, etc.) */
    private BridgeListener mListener;

    private String mTitle = "Alpine Linux";

    public interface InputSink {
        void sendInput(String input);
    }

    public interface RunningCheck {
        boolean isRunning();
    }

    /**
     * Listener for bridge events. This replaces the TerminalSessionClient
     * callbacks that would normally be invoked by TerminalSession.
     * TerminalSessionClient.onTextChanged(TerminalSession) can't work here
     * because TerminalSession is final and we're not one.
     */
    public interface BridgeListener {
        void onTextChanged();
        void onTitleChanged(String title);
        void onCopyTextToClipboard(String text);
        void onPasteTextFromClipboard();
        void onBell();
        void onColorsChanged();
    }

    public WasmTerminalBridge() {
        // Default constructor — call init() before use
    }

    /**
     * Initialize the bridge with callbacks.
     */
    public void init(InputSink inputSink, RunningCheck runningCheck) {
        mInputSink = inputSink;
        mRunningCheck = runningCheck;
    }

    /**
     * Set the bridge listener for UI update callbacks.
     */
    public void setBridgeListener(BridgeListener listener) {
        mListener = listener;
    }

    /**
     * Initialize the terminal emulator with the given dimensions.
     * The TerminalSessionClient is needed by the emulator for cursor style queries.
     */
    public void initializeEmulator(int columns, int rows, TerminalSessionClient client) {
        mEmulator = new TerminalEmulator(this, columns, rows, /* transcript rows */ 2000, client);
    }

    /**
     * Get the terminal emulator instance.
     */
    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /**
     * Process output from the VM (stdout data). Converts the String to bytes
     * and feeds them to the emulator's VT100 state machine.
     *
     * @param text Raw terminal output from the VM.
     */
    public void processOutput(String text) {
        if (mEmulator == null || text == null || text.isEmpty()) return;

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytes, bytes.length);

        if (mListener != null) {
            mListener.onTextChanged();
        }
    }

    /**
     * Called by TerminalEmulator when it needs to write data back to the
     * terminal (query responses like cursor position reports, etc.).
     * This is the TerminalOutput abstract method implementation.
     */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mInputSink == null) return;
        String str = new String(data, offset, count, StandardCharsets.UTF_8);
        mInputSink.sendInput(str);
    }

    /**
     * Send user keyboard input to the VM.
     */
    public void sendInput(byte[] data, int offset, int count) {
        if (mInputSink == null) return;
        String str = new String(data, offset, count, StandardCharsets.UTF_8);
        mInputSink.sendInput(str);
    }

    /**
     * Write a string directly to the VM input.
     * Note: TerminalOutput.write(String) is final, so we override via the
     * byte[] path. This method name shadows it for callers with (String).
     * Actually, write(String) on TerminalOutput converts to bytes and calls
     * write(byte[], int, int). That's the emulator response path. For user
     * input we need a separate method.
     */
    public void sendString(String data) {
        if (mInputSink != null && data != null) {
            mInputSink.sendInput(data);
        }
    }

    /**
     * Write a Unicode code point, optionally prepending ESC for Alt key.
     */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (mInputSink == null) return;

        StringBuilder sb = new StringBuilder();
        if (prependEscape) sb.append('\u001B');

        if (codePoint <= 0xFFFF) {
            sb.append((char) codePoint);
        } else {
            sb.appendCodePoint(codePoint);
        }
        mInputSink.sendInput(sb.toString());
    }

    /**
     * Update the terminal size.
     */
    public void updateSize(int columns, int rows) {
        if (mEmulator != null) {
            mEmulator.resize(columns, rows);
        }
    }

    /**
     * Check if the VM is running.
     */
    public boolean isRunning() {
        return mRunningCheck != null && mRunningCheck.isRunning();
    }

    /**
     * Get the terminal title.
     */
    public String getTitle() {
        return mTitle;
    }

    // --- TerminalOutput abstract method implementations ---
    // These are called by the TerminalEmulator through mSession (which is us).

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mTitle = newTitle;
        if (mListener != null) {
            mListener.onTitleChanged(newTitle);
        }
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mListener != null) {
            mListener.onCopyTextToClipboard(text);
        }
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mListener != null) {
            mListener.onPasteTextFromClipboard();
        }
    }

    @Override
    public void onBell() {
        if (mListener != null) {
            mListener.onBell();
        }
    }

    @Override
    public void onColorsChanged() {
        if (mListener != null) {
            mListener.onColorsChanged();
        }
    }

    /**
     * Reset the terminal emulator.
     */
    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
        }
    }
}
