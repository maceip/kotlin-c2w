package com.example.c2wdemo.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.example.c2wdemo.FriscyRuntime
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle

/**
 * Custom View that renders a Termux [TerminalEmulator] screen buffer via Canvas.
 *
 * Renders each cell using a monospace font with proper foreground/background colors,
 * bold/italic/underline attributes, and a block cursor.
 */
class FriscyTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** The bridge that owns the TerminalEmulator. */
    var bridge: FriscyTerminalBridge? = null
        set(value) {
            field = value
            requestLayout()
        }

    // Font metrics
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 28f  // Will be adjusted in onSizeChanged
    }
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var fontAscent = 0f

    // Colors — Invader Zim theme defaults
    private val defaultFg = 0xFF00FF88.toInt()  // green terminal
    private val defaultBg = 0xFF0A0A14.toInt()  // dark background
    private val cursorColor = 0xFF00FFFF.toInt() // cyan cursor

    // Background paint
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }

    // Cursor blink
    private var cursorVisible = true
    private val cursorBlinkHandler = Handler(Looper.getMainLooper())
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            cursorBlinkHandler.postDelayed(this, 530)
        }
    }

    /** Terminal dimensions in cells. */
    var termRows = 24; private set
    var termCols = 80; private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(defaultBg)
        computeFontMetrics()
    }

    private fun computeFontMetrics() {
        val metrics = textPaint.fontMetrics
        fontAscent = -metrics.ascent
        cellHeight = metrics.descent - metrics.ascent + metrics.leading
        cellWidth = textPaint.measureText("W")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // Compute how many cells fit
        val padding = paddingLeft + paddingRight
        val vPadding = paddingTop + paddingBottom
        val usableW = w - padding
        val usableH = h - vPadding

        termCols = maxOf(1, (usableW / cellWidth).toInt())
        termRows = maxOf(1, (usableH / cellHeight).toInt())

        // Initialize or resize the emulator
        val b = bridge ?: return
        if (b.emulator == null) {
            b.initializeEmulator(termCols, termRows)
        } else {
            b.updateSize(termCols, termRows)
        }

        // Update native runtime with terminal dimensions
        FriscyRuntime.nativeSetTerminalSize(termCols, termRows)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cursorBlinkHandler.postDelayed(cursorBlinkRunnable, 530)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cursorBlinkHandler.removeCallbacks(cursorBlinkRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val emulator = bridge?.emulator ?: return
        val screen = emulator.screen ?: return
        val colors = emulator.mColors.mCurrentColors

        val startX = paddingLeft.toFloat()
        val startY = paddingTop.toFloat()

        val cursorRow = emulator.cursorRow
        val cursorCol = emulator.cursorCol
        val cursorEnabled = emulator.shouldCursorBeVisible()

        for (row in 0 until termRows) {
            val y = startY + row * cellHeight
            val termRow = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))

            for (col in 0 until termCols) {
                val x = startX + col * cellWidth
                val style = termRow.getStyle(col)

                // Decode colors
                var fg = resolveColor(TextStyle.decodeForeColor(style), colors, defaultFg)
                var bg = resolveColor(TextStyle.decodeBackColor(style), colors, defaultBg)
                val effect = TextStyle.decodeEffect(style)

                // Handle inverse
                if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) {
                    val tmp = fg; fg = bg; bg = tmp
                }

                // Handle dim
                if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
                    fg = dimColor(fg)
                }

                // Draw background (skip if same as default)
                if (bg != defaultBg) {
                    bgPaint.color = bg
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint)
                }

                // Draw cursor
                if (row == cursorRow && col == cursorCol && cursorEnabled && cursorVisible) {
                    bgPaint.color = cursorColor
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint)
                    fg = defaultBg  // Invert text color on cursor
                }

                // Handle invisible
                if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) continue

                // Get character
                val charIdx = termRow.findStartOfColumn(col)
                if (charIdx >= termRow.getSpaceUsed()) continue
                val c = termRow.mText[charIdx]
                if (c == ' ' || c == '\u0000') continue

                // Set text style
                textPaint.color = fg
                textPaint.isFakeBoldText = (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                textPaint.textSkewX = if ((effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) -0.25f else 0f
                textPaint.isUnderlineText = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                textPaint.isStrikeThruText = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0

                // Draw character
                if (Character.isHighSurrogate(c) && charIdx + 1 < termRow.getSpaceUsed()) {
                    val text = String(charArrayOf(c, termRow.mText[charIdx + 1]))
                    canvas.drawText(text, x, y + fontAscent, textPaint)
                } else {
                    canvas.drawText(c.toString(), x, y + fontAscent, textPaint)
                }
            }
        }
    }

    private fun resolveColor(colorIndex: Int, palette: IntArray, fallback: Int): Int {
        return if (colorIndex and 0xff000000.toInt() == 0xff000000.toInt()) {
            // 24-bit true color
            colorIndex
        } else if (colorIndex in 0 until TextStyle.NUM_INDEXED_COLORS) {
            palette[colorIndex]
        } else {
            fallback
        }
    }

    private fun dimColor(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 2 / 3
        val g = ((color shr 8) and 0xFF) * 2 / 3
        val b = (color and 0xFF) * 2 / 3
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // --- Input handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // Show keyboard on tap
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyEvent(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consume key-up for keys we handled on key-down
        return true
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val emulator = bridge?.emulator ?: return false

        // Build key mode from modifiers
        var keyMod = 0
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isAltPressed) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isCtrlPressed) keyMod = keyMod or KeyHandler.KEYMOD_CTRL

        val code = KeyHandler.getCode(
            keyCode, keyMod,
            emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode,
        )

        if (code != null) {
            bridge?.write(code)
            return true
        }

        // Handle regular character input
        val c = event.unicodeChar
        if (c != 0) {
            if (event.isCtrlPressed) {
                // Ctrl+letter -> control character
                val upper = Character.toUpperCase(c)
                if (upper in 'A'.code..'Z'.code) {
                    bridge?.write(byteArrayOf((upper - '@'.code).toByte()), 0, 1)
                    return true
                }
            }
            val bytes = String(Character.toChars(c)).toByteArray(Charsets.UTF_8)
            bridge?.write(bytes, 0, bytes.size)
            return true
        }

        return false
    }

    /**
     * Send a raw string to the terminal (for helper bar buttons).
     */
    fun sendInput(text: String) {
        bridge?.write(text)
    }

    /**
     * InputConnection for soft keyboard input.
     */
    private class TerminalInputConnection(
        private val termView: FriscyTerminalView,
    ) : BaseInputConnection(termView, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text != null && text.isNotEmpty()) {
                val bytes = text.toString().toByteArray(Charsets.UTF_8)
                termView.bridge?.write(bytes, 0, bytes.size)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Backspace
            if (beforeLength > 0) {
                for (i in 0 until beforeLength) {
                    termView.bridge?.write(byteArrayOf(127), 0, 1) // DEL
                }
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                termView.handleKeyEvent(event.keyCode, event)
            }
            return true
        }
    }
}
