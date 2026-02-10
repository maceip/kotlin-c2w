package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 *
 * Vendored from Termux terminal-view v0.118.1. No modifications needed.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;

    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08fontmetrics/08fontmetrics.htm */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. The font metrics' ascent is the distance above (negative) the baseline. */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] mAsciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < mAsciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            mAsciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with or without text selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionX1, int selectionY2, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;

        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        else
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_BACKGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;

        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
                if (row > selectionY1) selx1 = 0;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();
            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final int columnsForThisCodePoint = Math.max(1, codePointWcWidth);

                final boolean insideCursor = (cursorX == column || (columnsForThisCodePoint == 2 && cursorX == column + 1));
                final boolean insideSelection = (column >= selx1 && column <= selx2);
                final long style = cycleStyleIfNeeded(lineObject.getStyle(column));

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || lastRunStartColumn == -1) {
                    if (lastRunStartColumn != -1) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            lastRunInsideCursor, lastRunInsideSelection, lastRunStyle, reverseVideo, cursorShape);
                    }
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    measuredWidthForRun = 0;
                }

                measuredWidthForRun += measureChar(charAtIndex, charsForCodePoint == 2 ? line[currentCharIndex + 1] : 0);

                column += columnsForThisCodePoint;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            if (lastRunStartColumn != -1) {
                final int columnWidthSinceLastRun = columns - lastRunStartColumn;
                final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                    lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                    lastRunInsideCursor, lastRunInsideSelection, lastRunStyle, reverseVideo, cursorShape);
            }
        }
    }

    private long cycleStyleIfNeeded(long style) {
        // No cycling needed in our use case
        return style;
    }

    private float measureChar(char c1, char c2) {
        if (c2 == 0 && c1 < 127) return mAsciiMeasures[c1];
        char[] chars = (c2 == 0) ? new char[]{c1} : new char[]{c1, c2};
        return mTextPaint.measureText(chars, 0, chars.length);
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y,
                             int startColumn, int runWidthColumns,
                             int startCharIndex, int runCharCount,
                             float measuredWidth,
                             boolean cursor, boolean insideSelection,
                             long textStyle, boolean reverseVideo, int cursorShape) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);

        // Bold
        final boolean bold = (effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0;
        // Italic
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        // Underline
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        // Strikethrough
        final boolean strikethrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        // Dim
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;
        // Invisible
        final boolean invisible = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0;

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        if (reverseVideo) {
            // Swap if terminal is in reverse video mode
            if (foreColor == TextStyle.COLOR_INDEX_FOREGROUND) foreColor = TextStyle.COLOR_INDEX_BACKGROUND;
            else if (foreColor == TextStyle.COLOR_INDEX_BACKGROUND) foreColor = TextStyle.COLOR_INDEX_FOREGROUND;
            if (backColor == TextStyle.COLOR_INDEX_FOREGROUND) backColor = TextStyle.COLOR_INDEX_BACKGROUND;
            else if (backColor == TextStyle.COLOR_INDEX_BACKGROUND) backColor = TextStyle.COLOR_INDEX_FOREGROUND;
        }

        // Resolve palette colors
        int foreColorFinal = palette[foreColor];
        int backColorFinal = palette[backColor];

        // Bold can brighten foreground color
        if (bold && foreColor < 8) foreColorFinal = palette[foreColor + 8];

        if (dim) {
            foreColorFinal = (0xFF000000) | // Keep alpha
                ((((foreColorFinal >> 16) & 0xFF) / 2) << 16) |
                ((((foreColorFinal >> 8) & 0xFF) / 2) << 8) |
                (((foreColorFinal) & 0xFF) / 2);
        }

        if (insideSelection) {
            // Selection highlight
            foreColorFinal = 0xFF000000;
            backColorFinal = 0xFFCCCCCC;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        // Draw background
        mTextPaint.setColor(backColorFinal);
        canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);

        // Draw cursor
        if (cursor) {
            mTextPaint.setColor(palette[TextStyle.COLOR_INDEX_CURSOR]);
            float cursorHeight = mFontLineSpacing;
            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cursorHeight = mFontLineSpacing / 4f;
                canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, left + mFontWidth / 4f, y, mTextPaint);
            } else {
                // Block cursor
                canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
                // Draw text in background color over block cursor
                foreColorFinal = backColorFinal;
            }
        }

        if (!invisible && runCharCount > 0) {
            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0f);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setStrikeThruText(strikethrough);
            mTextPaint.setColor(foreColorFinal);
            canvas.drawText(text, startCharIndex, runCharCount, left, y - mFontLineSpacing + mFontLineSpacingAndAscent, mTextPaint);
        }
    }
}
