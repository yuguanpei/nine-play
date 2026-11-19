package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.widget.FrameLayout;

/** Applies the inner-edge fade to the whole compact instruments panel. */
final class CompactPanel extends FrameLayout {
    private final Paint mask = new Paint();

    CompactPanel(Context context) {
        super(context);
        setWillNotDraw(false);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    static float fadeStart(int width) { return width * (2f / 3f); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0) {
            mask.setShader(new LinearGradient(fadeStart(width), 0, width, 0,
                    0xff000000, 0, Shader.TileMode.CLAMP));
        }
    }

    @Override public void draw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        int save = canvas.saveLayer(0, 0, width, height, null);
        super.draw(canvas);
        canvas.drawRect(0, 0, width, height, mask);
        canvas.restoreToCount(save);
    }
}
