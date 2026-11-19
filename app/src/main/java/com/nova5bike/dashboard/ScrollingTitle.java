package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.widget.TextView;

/** The live card and the moving card draw the same shaped text at the same playback phase. */
final class ScrollingTitle extends TextView {
    private final float density;
    private long titleSince;

    ScrollingTitle(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setSingleLine(true);
        setEllipsize(null);
    }

    @Override protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        titleSince = SystemClock.uptimeMillis();
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
        if (drawTitle(canvas, left, getBaseline(), overflows(right - left) ? 1 : 0)) {
            postInvalidateOnAnimation();
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
