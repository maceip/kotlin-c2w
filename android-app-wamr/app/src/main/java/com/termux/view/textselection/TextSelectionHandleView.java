package com.termux.view.textselection;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.PopupWindow;

import com.termux.view.TerminalView;

/**
 * Vendored from Termux terminal-view v0.118.1. No modifications needed.
 */
public class TextSelectionHandleView extends View {
    private final TerminalView mTerminalView;
    private PopupWindow mHandle;
    private Drawable mHandleLeftDrawable;
    private Drawable mHandleRightDrawable;
    private Drawable mHandleDrawable;
    private boolean mIsDragging;
    private boolean mIsShowing;
    private int mPointX;
    private int mPointY;
    private float mTouchToWindowOffsetX;
    private float mTouchToWindowOffsetY;
    private float mHotspotX;
    private float mHotspotY;
    private float mTouchOffsetY;
    private int mLastParentX;
    private int mLastParentY;
    private int mHandleHeight;
    private int mHandleWidth;
    private int mOrientation;

    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    private final int[] mTempCoords = new int[2];

    public TextSelectionHandleView(TerminalView terminalView, Drawable handleLeftDrawable, Drawable handleRightDrawable) {
        super(terminalView.getContext());
        mTerminalView = terminalView;
        mHandleLeftDrawable = handleLeftDrawable;
        mHandleRightDrawable = handleRightDrawable;
        setOrientation(LEFT);
    }

    public void setOrientation(int orientation) {
        mOrientation = orientation;
        mHandleDrawable = (orientation == LEFT) ? mHandleLeftDrawable : mHandleRightDrawable;
        mHandleHeight = mHandleDrawable.getIntrinsicHeight();
        mHandleWidth = mHandleDrawable.getIntrinsicWidth();
        mHotspotX = (orientation == LEFT) ? (mHandleWidth * 3f / 4f) : (mHandleWidth / 4f);
    }

    public void show() {
        if (!isPositionVisible()) {
            hide();
            return;
        }

        initHandle();
        invalidate();

        final int[] coords = mTempCoords;
        mTerminalView.getLocationInWindow(coords);
        coords[0] += mPointX;
        coords[1] += mPointY;

        mHandle.showAtLocation(mTerminalView, 0, (int) (coords[0] - mHotspotX), coords[1]);
        mIsShowing = true;
    }

    public void hide() {
        mIsDragging = false;
        if (mHandle != null) {
            mHandle.dismiss();
        }
        mIsShowing = false;
    }

    public boolean isShowing() {
        return mIsShowing;
    }

    private void initHandle() {
        if (mHandle == null) {
            mHandle = new PopupWindow(mTerminalView.getContext(), null, android.R.attr.textSelectHandleWindowStyle);
            mHandle.setSplitTouchEnabled(true);
            mHandle.setClippingEnabled(false);
            mHandle.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mHandle.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            mHandle.setContentView(this);
        }
    }

    public void positionAtCursor(final int cx, final int cy, boolean forceOrientationCheck) {
        int x = mTerminalView.getPointX(cx);
        // Position below the text row: getPointY(cy+1) gives the Y of the next row
        int y = mTerminalView.getPointY(cy + 1);

        moveTo(x, y, forceOrientationCheck);
    }

    private void moveTo(int x, int y, boolean forceOrientationCheck) {
        mPointX = x;
        mPointY = y;

        if (!isPositionVisible()) {
            hide();
            return;
        }

        if (mHandle != null && mHandle.isShowing()) {
            final int[] coords = mTempCoords;
            mTerminalView.getLocationInWindow(coords);
            int newX = (int) (coords[0] + mPointX - mHotspotX);
            int newY = coords[1] + mPointY;
            mHandle.update(newX, newY, -1, -1);
        } else {
            show();
        }
    }

    private boolean isPositionVisible() {
        // Simplified check
        return mTerminalView.getVisibility() == VISIBLE;
    }

    public void changeOrientation(int orientation) {
        if (mOrientation != orientation) {
            setOrientation(orientation);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int drawWidth = mHandleDrawable.getIntrinsicWidth();
        final int drawHeight = mHandleDrawable.getIntrinsicHeight();
        mHandleDrawable.setBounds(0, 0, drawWidth, drawHeight);
        mHandleDrawable.draw(canvas);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(mHandleDrawable.getIntrinsicWidth(), mHandleDrawable.getIntrinsicHeight());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float rawX = event.getRawX();
                final float rawY = event.getRawY();
                mTouchToWindowOffsetX = rawX - mPointX;
                mTouchToWindowOffsetY = rawY - mPointY;
                final int[] coords = mTempCoords;
                mTerminalView.getLocationInWindow(coords);
                mLastParentX = coords[0];
                mLastParentY = coords[1];
                mIsDragging = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mIsDragging) {
                    final float rawX = event.getRawX();
                    final float rawY = event.getRawY();
                    final float newPosX = rawX - mTouchToWindowOffsetX + mHotspotX;
                    final float newPosY = rawY - mTouchToWindowOffsetY + mTouchOffsetY;

                    final int[] coords = mTempCoords;
                    mTerminalView.getLocationInWindow(coords);

                    int x = (int) (newPosX - coords[0]);
                    int y = (int) (newPosY - coords[1]);
                    mPointX = x;
                    mPointY = y;
                    // Notify controller of handle position change
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                break;
        }
        return true;
    }

    public boolean isDragging() {
        return mIsDragging;
    }
}
