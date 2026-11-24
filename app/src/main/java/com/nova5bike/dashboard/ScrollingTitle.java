package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextUtils;
import android.widget.TextView;

/** The live card and the moving card draw the same shaped text at the same playback phase. */
final class ScrollingTitle extends TextView {
    private final float density;
    private long titleSince;
    private String titleText;
    private final Runnable scrollFrame = new Runnable() {
        @Override public void run() {
            if (!canScroll()) return;
            invalidate();
            postOnAnimation(this);
        }
    };

    ScrollingTitle(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setSingleLine(true);
        setEllipsize(null);
    }

    @Override protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        // TextView can rebuild its text during styling. Only a different title restarts the pause.
        if (!TextUtils.equals(titleText, text)) {
            titleText = text == null ? "" : text.toString();
            titleSince = SystemClock.uptimeMillis();
        }
        if (scrollFrame != null) post(this::updateScrolling);
    }

    private int contentWidth() { return getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight(); }

    private boolean canScroll() {
        return isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown() && overflows(contentWidth());
    }

    private void updateScrolling() {
        if (scrollFrame == null) return; // TextView invokes visibility hooks during construction.
        removeCallbacks(scrollFrame);
        if (canScroll()) postOnAnimation(scrollFrame);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateScrolling();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(scrollFrame);
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateScrolling();
    }

    @Override protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateScrolling();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateScrolling();
    }

    private float textWidth() {
        Layout layout = getLayout();
        return layout == null ? 0 : layout.getLineWidth(0);
    }

    boolean overflows(float width) {
        return textWidth() > width;
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = getCompoundPaddingLeft();
        float right = getWidth() - getCompoundPaddingRight();
        int saved = canvas.save();
        canvas.clipRect(left, 0, right, getHeight());
        drawTitle(canvas, left, getBaseline(), overflows(right - left) ? 1 : 0);
        if (RideLog.due("media.title_scroll", 5000)) {
            RideLog.event("media.title_scroll", "characters", length(), "viewportPx", right - left,
                    "textPx", textWidth(), "overflow", overflows(right - left),
                    "titleAgeMs", SystemClock.uptimeMillis() - titleSince);
        }
        canvas.restoreToCount(saved);
    }

    /** The caller supplies the clip and interpolates scrollWeight if only one layout overflows. */
    boolean drawTitle(Canvas canvas, float x, float baseline, float scrollWeight) {
        Layout layout = getLayout();
        if (layout == null) return false;
        float period = textWidth() + 32 * density;
        float offset = 0;
        if (scrollWeight > 0) {
            long elapsed = Math.max(0, SystemClock.uptimeMillis() - titleSince - 900);
            offset = (float) ((elapsed * .03 * density) % period) * scrollWeight;
        }
        getPaint().setColor(getCurrentTextColor());
        int saved = canvas.save();
        canvas.translate(x - layout.getLineLeft(0) - offset, baseline - layout.getLineBaseline(0));
        layout.draw(canvas);
        if (offset > 0) {
            canvas.translate(period, 0);
            layout.draw(canvas);
        }
        canvas.restoreToCount(saved);
        return scrollWeight > 0;
    }
}
