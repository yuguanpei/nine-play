package com.nova5bike.dashboard;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Draws a short-lived copy of the unit glyphs after the unit leaves the layout. */
final class SpeedUnitDust {
    private static final long DURATION_MS = 1000;
    private final View host;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Fragment> fragments = new ArrayList<>();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private float progress, glyphWidth, glyphHeight, topFromBaseline;

    SpeedUnitDust(View host) {
        this.host = host;
        density = host.getResources().getDisplayMetrics().density;
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            if (progress >= 1f) fragments.clear();
            host.invalidate();
        });
    }

    void start(TextView unit) {
        clear();
        int width = unit.getWidth();
        int height = unit.getHeight();
        if (width <= 0 || height <= 0 || unit.getLayout() == null || unit.getAlpha() <= 0f) return;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        unit.draw(new Canvas(bitmap));
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();

        Rect ink = new Rect(width, height, 0, 0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(pixels[y * width + x]) < 30) continue;
                ink.left = Math.min(ink.left, x);
                ink.top = Math.min(ink.top, y);
                ink.right = Math.max(ink.right, x + 1);
                ink.bottom = Math.max(ink.bottom, y + 1);
            }
        }
        if (ink.isEmpty()) return;

        glyphWidth = ink.width();
        glyphHeight = ink.height();
        topFromBaseline = ink.top - unit.getBaseline();
        int step = Math.max(1, Math.round(density * 2f / 3f));
        for (int y = ink.top + step / 2; y < ink.bottom; y += step) {
            for (int x = ink.left + step / 2; x < ink.right; x += step) {
                int alpha = Color.alpha(pixels[y * width + x]);
                if (alpha < 30) continue;
                float across = (x - ink.left) / glyphWidth;
                float down = (y - ink.top) / glyphHeight;
                float jitter = ((x * 211 + y * 89 + x * y * 29) % 113) / 112f;
                float drift = ((x * 131 + y * 173 + x * y * 11) % 149) / 148f;
                float release = Math.max(0f, .4f * (1f - across) + .19f * jitter - .05f * down);
                fragments.add(new Fragment(across, down, release, jitter, drift, alpha * unit.getAlpha()));
            }
        }
        if (fragments.isEmpty()) return;
        progress = 0f;
        animator.start();
        host.invalidate();
    }

    void clear() {
        animator.cancel();
        if (fragments.isEmpty()) return;
        fragments.clear();
        host.invalidate();
    }

    boolean active() { return !fragments.isEmpty(); }

    void draw(Canvas canvas, float left, float right, float baseline, boolean compact, int color) {
        float width = Math.min(right - left, density * (compact ? 51f : 28f));
        if (width <= 0f) return;
        float word = Math.min(glyphWidth, width * .76f);
        float top = baseline + topFromBaseline - density;
        float lift = density * (compact ? 27f : 16f);
        int clip = canvas.save();
        canvas.clipRect(left, 0f, left + width, host.getHeight());
        paint.setColor(color);
        for (Fragment fragment : fragments) {
            float age = Math.max(0f, (progress - fragment.release) / (1f - fragment.release));
            float travel = ease(age);
            float flutter = (float) (Math.sin(fragment.drift * 11f + age * 7f) * Math.sin(age * Math.PI));
            float spread = .16f + .87f * fragment.jitter;
            float up = -.16f + 1.12f * fragment.drift;
            float x = left + fragment.x * word + width * (.63f * spread * travel + .035f * flutter);
            float y = top + fragment.y * glyphHeight - lift * (up * travel + .08f * flutter);
            float grain = ease(Math.min(1f, age * 5f));
            float half = density / 6f * (2.18f + (.46f + .66f * fragment.jitter - 2.18f) * grain);
            float corner = half * grain;
            paint.setAlpha(Math.round(fragment.alpha * (float) Math.pow(1f - age, 1.48)));
            canvas.drawRoundRect(x - half, y - half, x + half, y + half, corner, corner, paint);
        }
        canvas.restoreToCount(clip);
    }

    private static float ease(float value) { return value * value * (3f - 2f * value); }

    private static final class Fragment {
        final float x, y, release, jitter, drift, alpha;

        Fragment(float x, float y, float release, float jitter, float drift, float alpha) {
            this.x = x;
            this.y = y;
            this.release = release;
            this.jitter = jitter;
            this.drift = drift;
            this.alpha = alpha;
        }
    }
}
