package com.example.c2wdemo.terminal

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * State-machine parser for ANSI/VT100 terminal escape sequences.
 *
 * Processes raw terminal output in chunks, stripping control sequences
 * (cursor queries, screen clears, private modes) and converting SGR
 * color codes into ForegroundColorSpan using Invader Zim theme colors.
 *
 * The parser is stateful: color state persists across [parse] calls
 * because terminal output arrives in arbitrary chunks from the pipe.
 */
class AnsiTerminalParser {

    private enum class State { NORMAL, ESCAPE, CSI }

    // Zim palette ANSI color map
    companion object {
        private val DEFAULT_COLOR = Color.parseColor("#00FF88")  // zim green
        private val COLOR_MAP = mapOf(
            30 to Color.parseColor("#1A1A2E"),   // black
            31 to Color.parseColor("#FF1493"),   // red -> zim pink bright
            32 to Color.parseColor("#00FF88"),   // green -> zim green terminal
            33 to Color.parseColor("#C77DFF"),   // yellow -> zim purple highlight
            34 to Color.parseColor("#00FFFF"),   // blue -> zim cyan glow
            35 to Color.parseColor("#9B4DCA"),   // magenta -> zim purple light
            36 to Color.parseColor("#00CED1"),   // cyan
            37 to Color.parseColor("#CCCCCC"),   // white
            // Bright variants (90-97)
            90 to Color.parseColor("#333355"),
            91 to Color.parseColor("#FF69B4"),
            92 to Color.parseColor("#33FF99"),
            93 to Color.parseColor("#D9A0FF"),
            94 to Color.parseColor("#66FFFF"),
            95 to Color.parseColor("#BB77DD"),
            96 to Color.parseColor("#33E0E0"),
            97 to Color.parseColor("#FFFFFF"),
        )
    }

    private var state = State.NORMAL
    private var csiParams = StringBuilder()
    private var currentColor: Int? = null  // null = default green
    private var bold = false

    /**
     * Parse a chunk of raw terminal output.
     * Returns a [SpannableStringBuilder] with escape sequences stripped
     * and SGR colors applied as [ForegroundColorSpan].
     */
    fun parse(text: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        var runStart = result.length
        var runColor = currentColor

        fun flushRun() {
            if (result.length > runStart && runColor != null) {
                result.setSpan(
                    ForegroundColorSpan(runColor!!),
                    runStart,
                    result.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            runStart = result.length
            runColor = currentColor
        }

        for (ch in text) {
            when (state) {
                State.NORMAL -> {
                    when (ch) {
                        '\u001B' -> {
                            state = State.ESCAPE
                        }
                        '\r' -> {
                            // Strip carriage returns in append-only model
                        }
                        else -> {
                            // If color changed since run started, flush
                            if (currentColor != runColor) {
                                flushRun()
                            }
                            result.append(ch)
                        }
                    }
                }

                State.ESCAPE -> {
                    when (ch) {
                        '[' -> {
                            csiParams.clear()
                            state = State.CSI
                        }
                        else -> {
                            // Unknown escape (e.g. ESC followed by non-[)
                            // Discard and return to normal
                            state = State.NORMAL
                        }
                    }
                }

                State.CSI -> {
                    when {
                        // Parameter bytes: digits, semicolons, question mark, etc.
                        ch in '0'..'9' || ch == ';' || ch == '?' -> {
                            csiParams.append(ch)
                        }
                        // Final byte: letter or @-~ range dispatches the command
                        ch in '@'..'~' -> {
                            dispatchCsi(ch)
                            // After dispatching, color may have changed
                            if (currentColor != runColor) {
                                flushRun()
                            }
                            state = State.NORMAL
                        }
                        else -> {
                            // Intermediate bytes (space, !, ", #, etc.)
                            // Accumulate but we'll discard the whole sequence
                            csiParams.append(ch)
                        }
                    }
                }
            }
        }

        // Flush any remaining text run
        flushRun()

        return result
    }

    /** Reset parser state and color. */
    fun reset() {
        state = State.NORMAL
        csiParams.clear()
        currentColor = null
        bold = false
    }

    private fun dispatchCsi(finalByte: Char) {
        val params = csiParams.toString()

        when (finalByte) {
            'm' -> handleSgr(params)
            // All other CSI sequences are stripped (not appended to output)
            // Includes: cursor movement (H, f, A-D), erase (J, K),
            // cursor position query (n), private modes (h, l), etc.
        }
    }

    private fun handleSgr(params: String) {
        // Empty params means reset (ESC[m is equivalent to ESC[0m)
        if (params.isEmpty()) {
            currentColor = null
            bold = false
            return
        }

        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) {
            currentColor = null
            bold = false
            return
        }

        for (code in codes) {
            when (code) {
                0 -> {
                    currentColor = null
                    bold = false
                }
                1 -> {
                    bold = true
                    // Brighten current color if it's a standard color
                    currentColor?.let { c ->
                        currentColor = brighten(c)
                    }
                }
                in 30..37 -> {
                    currentColor = COLOR_MAP[code]
                    if (bold) currentColor = brighten(currentColor ?: DEFAULT_COLOR)
                }
                39 -> {
                    // Default foreground
                    currentColor = null
                }
                in 90..97 -> {
                    currentColor = COLOR_MAP[code]
                }
            }
        }
    }

    private fun brighten(color: Int): Int {
        val r = minOf(255, Color.red(color) + 40)
        val g = minOf(255, Color.green(color) + 40)
        val b = minOf(255, Color.blue(color) + 40)
        return Color.rgb(r, g, b)
    }
}
