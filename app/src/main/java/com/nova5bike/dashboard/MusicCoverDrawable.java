package com.nova5bike.dashboard;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Draws the same center-cropped, rounded artwork in the card and its transition snapshots. */
final class MusicCoverDrawable extends Drawable {
    private final Bitmap bitmap;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix crop = new Matrix();
    private final BitmapShader shader;
    private BitmapShader softwareShader;

    MusicCoverDrawable(Bitmap bitmap, float radius) {
        this.bitmap = bitmap;
        this.radius = radius;
        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    MusicCoverDrawable copyForTransition() {
        return new MusicCoverDrawable(bitmap, radius);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        float scale = Math.max((float) bounds.width() / bitmap.getWidth(),
                (float) bounds.height() / bitmap.getHeight());
        crop.setScale(scale, scale);
        crop.postTranslate(bounds.exactCenterX() - bitmap.getWidth() * scale / 2,
                bounds.exactCenterY() - bitmap.getHeight() * scale / 2);
        shader.setLocalMatrix(crop);
        if (softwareShader != null) softwareShader.setLocalMatrix(crop);
    }

    @Override public void draw(Canvas canvas) {
        BitmapShader active = shader;
        if (!canvas.isHardwareAccelerated() && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            if (softwareShader == null) {
                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) return;
                softwareShader = new BitmapShader(copy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                softwareShader.setLocalMatrix(crop);
            }
            active = softwareShader;
        }
        paint.setShader(active);
        Rect b = getBounds();
        canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
