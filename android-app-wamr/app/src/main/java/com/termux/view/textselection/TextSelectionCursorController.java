package com.termux.view.textselection;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalView;

/**
 * Vendored from Termux terminal-view v0.118.1. Minimal implementation for text selection.
 */
public class TextSelectionCursorController implements ViewTreeObserver.OnTouchModeChangeListener {
    private final TerminalView mTerminalView;
    private TextSelectionHandleView mStartHandle;
    private TextSelectionHandleView mEndHandle;
    private boolean mIsActive = false;
    private ActionMode mActionMode;

    private int mSelX1 = -1, mSelY1 = -1, mSelX2 = -1, mSelY2 = -1;

    private final int[] mSelectors = new int[]{-1, -1, -1, -1};

    private static final int MENU_ITEM_COPY = 1;
    private static final int MENU_ITEM_PASTE = 2;

    public TextSelectionCursorController(TerminalView terminalView) {
        mTerminalView = terminalView;
    }

    public void show(MotionEvent event) {
        setInitialTextSelectionPosition(event);
        mIsActive = true;

        Drawable handleLeftDrawable = androidx.core.content.ContextCompat.getDrawable(
            mTerminalView.getContext(), android.R.drawable.btn_default);
        Drawable handleRightDrawable = androidx.core.content.ContextCompat.getDrawable(
            mTerminalView.getContext(), android.R.drawable.btn_default);

        if (mStartHandle == null) {
            mStartHandle = new TextSelectionHandleView(mTerminalView, handleLeftDrawable, handleRightDrawable);
            mEndHandle = new TextSelectionHandleView(mTerminalView, handleLeftDrawable, handleRightDrawable);
        }
        mStartHandle.setOrientation(TextSelectionHandleView.LEFT);
        mEndHandle.setOrientation(TextSelectionHandleView.RIGHT);

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        mStartHandle.show();
        mEndHandle.show();

        startActionMode();
    }

    public boolean hide() {
        if (!mIsActive) return false;

        if (mStartHandle != null) mStartHandle.hide();
        if (mEndHandle != null) mEndHandle.hide();

        if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsActive = false;
        return true;
    }

    public boolean isActive() {
        return mIsActive;
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    public void getSelectors(int[] selectors) {
        if (mIsActive) {
            selectors[0] = mSelY1;
            selectors[1] = mSelX1;
            selectors[2] = mSelY2;
            selectors[3] = mSelX2;
        } else {
            selectors[0] = selectors[1] = selectors[2] = selectors[3] = -1;
        }
    }

    public void decrementYTextSelectionCursors(int decrement) {
        mSelY1 -= decrement;
        mSelY2 -= decrement;
    }

    private void setInitialTextSelectionPosition(MotionEvent event) {
        int[] columnAndRow = mTerminalView.getColumnAndRow(event, true);
        mSelX1 = mSelX2 = columnAndRow[0];
        mSelY1 = mSelY2 = columnAndRow[1];

        TerminalEmulator emulator = mTerminalView.mEmulator;
        if (emulator == null) return;

        // Expand selection to word boundaries
        TerminalBuffer screen = emulator.getScreen();
        String line = screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1);
        if (line != null && !line.isEmpty()) {
            // Simple word expansion - select current word
            int row = mSelY1;
            String fullLine = screen.getSelectedText(0, row, emulator.mColumns - 1, row);
            if (fullLine != null) {
                int start = mSelX1;
                int end = mSelX1;

                while (start > 0 && !Character.isWhitespace(fullLine.charAt(Math.min(start - 1, fullLine.length() - 1)))) {
                    start--;
                }
                while (end < emulator.mColumns - 1 && end < fullLine.length() - 1 && !Character.isWhitespace(fullLine.charAt(end + 1))) {
                    end++;
                }

                mSelX1 = start;
                mSelX2 = end;
            }
        }
    }

    private void startActionMode() {
        mActionMode = mTerminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, MENU_ITEM_COPY, Menu.NONE, android.R.string.copy);
                menu.add(Menu.NONE, MENU_ITEM_PASTE, Menu.NONE, android.R.string.paste);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_ITEM_COPY: {
                        String selectedText = getSelectedText();
                        if (!TextUtils.isEmpty(selectedText)) {
                            ClipboardManager cm = (ClipboardManager) mTerminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("Terminal", selectedText));
                        }
                        hide();
                        return true;
                    }
                    case MENU_ITEM_PASTE: {
                        ClipboardManager cm = (ClipboardManager) mTerminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clipData = cm.getPrimaryClip();
                        if (clipData != null) {
                            CharSequence paste = clipData.getItemAt(0).coerceToText(mTerminalView.getContext());
                            if (!TextUtils.isEmpty(paste)) {
                                mTerminalView.mBridge.sendInput(paste.toString().getBytes(), 0, paste.toString().getBytes().length);
                            }
                        }
                        hide();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // clean up
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                int x1 = mTerminalView.getPointX(mSelX1);
                int y1 = mTerminalView.getPointY(mSelY1);
                int x2 = mTerminalView.getPointX(mSelX2 + 1);
                int y2 = mTerminalView.getPointY(mSelY2 + 1);
                outRect.set(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    private String getSelectedText() {
        TerminalEmulator emulator = mTerminalView.mEmulator;
        if (emulator == null) return "";
        return emulator.getScreen().getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    public void render() {
        if (!mIsActive || mStartHandle == null) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) {
            hide();
        }
    }

    public void onDetached() {
        hide();
    }
}
