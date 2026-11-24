package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

final class SpeedChart extends View {
    private static final long WINDOW_MS = 60_000;
    private static final long MAX_GAP_MS = 4_000;
    private static final int CAPACITY = (int) (WINDOW_MS / GpsSpeedReading.UPDATE_INTERVAL_MS) + 2;
    private static final long VIBRATION_INTERVAL_MS = 100;
    private static final long VIBRATION_MAX_GAP_MS = 1000;
    private static final int VIBRATION_CAPACITY = (int) (WINDOW_MS / VIBRATION_INTERVAL_MS) + 2;
    private static final float MIN_SCALE_KMH = 25f;
    private static final float MIN_SCALE_G = .25f;
    private final float[] speeds = new float[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private final boolean[] starts = new boolean[CAPACITY];
    private final float[] vibrations = new float[VIBRATION_CAPACITY];
    private final long[] vibrationTimes = new long[VIBRATION_CAPACITY];
    private final float[] x = new float[VIBRATION_CAPACITY + 1];
    private final float[] y = new float[VIBRATION_CAPACITY + 1];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeMask = new Paint();
    private final Path curve = new Path();
    private final Path fill = new Path();
    private final Runnable redraw = this::invalidate;
    private final ChartAnimation reveal = new ChartAnimation(), scale = new ChartAnimation();
    private int next;
    private int count;
    private int vibrationNext, vibrationCount;
    private int accent;
    private int vibrationColor;
    private Shader gradient;
    private boolean edgeFade;
    private boolean newSegment;

    SpeedChart(Context context, int accent) {
        super(context);
        this.accent = accent;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        edgeMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setAccent(int accent) {
        this.accent = accent;
        gradient = null;
        invalidate();
    }

    void setVibrationColor(int color) {
        vibrationColor = (color & 0x00ffffff) | 0x99000000;
        invalidate();
    }

    void setEdgeFade(boolean enabled) {
        if (edgeFade == enabled) return;
        edgeFade = enabled;
        if (enabled) updateEdgeMask(getWidth());
        invalidate();
    }

    private void updateEdgeMask(int width) {
        if (width > 0) edgeMask.setShader(new LinearGradient(0, 0, width, 0,
                new int[]{0, 0xff000000, 0xff000000, 0}, new float[]{0, .21f, .79f, 1},
                Shader.TileMode.CLAMP));
    }

    void record(float kmh, long time) {
        if (!Float.isFinite(kmh) || kmh < 0) return;
        if (count != 0 && time <= timeAt(count - 1)) return;
        long interval = count == 0 ? 0 : time - timeAt(count - 1);
        if (count == 0 || newSegment || interval > MAX_GAP_MS) reveal.snap(time);
        else reveal.moveTo(time, SystemClock.elapsedRealtime(), ChartAnimation.sampleDuration(interval));
        times[next] = time;
        speeds[next] = kmh;
        starts[next] = newSegment;
        newSegment = false;
        next = (next + 1) % CAPACITY;
        count = Math.min(CAPACITY, count + 1);
        invalidate();
    }

    void breakTrace() { newSegment = true; }

    void recordVibration(float g, long time) {
        if (!Float.isFinite(g) || g < 0) return;
        if (vibrationCount != 0
                && time - vibrationTimeAt(vibrationCount - 1) < VIBRATION_INTERVAL_MS) return;
        vibrationTimes[vibrationNext] = time;
        vibrations[vibrationNext] = g;
        vibrationNext = (vibrationNext + 1) % VIBRATION_CAPACITY;
        vibrationCount = Math.min(VIBRATION_CAPACITY, vibrationCount + 1);
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        gradient = null;
        if (edgeFade) updateEdgeMask(width);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(redraw);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        removeCallbacks(redraw);
        if (count == 0 && vibrationCount == 0) return;
        float density = getResources().getDisplayMetrics().density;
        float left = 4 * density;
        float right = getWidth() - left;
        float top = 8 * density;
        float bottom = getHeight() - 5 * density;
        if (bottom <= top || right <= left) return;

        long now = SystemClock.elapsedRealtime();
        // Play the measured segment through a shared time cursor. Its tip stays at the right edge.
        long chartTime = count > 0 && !newSegment && now - timeAt(count - 1) <= GpsSpeedReading.MAX_AGE_MS
                ? Math.min(now, (long) reveal.valueAt(now)) : now;
        int layer = edgeFade ? canvas.saveLayer(0, 0, getWidth(), getHeight(), null) : 0;
        int clip = canvas.save();
        canvas.clipRect(0, 0, right, getHeight());
        boolean speedVisible = drawSpeed(canvas, left, right, top, bottom, chartTime, now);
        boolean vibrationVisible = drawVibration(canvas, left, right, top, bottom, chartTime);
        canvas.restoreToCount(clip);
        if (edgeFade) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), edgeMask);
            canvas.restoreToCount(layer);
        }
        if (speedVisible || vibrationVisible) postOnAnimationDelayed(redraw, 16);
    }

    private boolean drawSpeed(Canvas canvas, float left, float right, float top, float bottom, long chartTime, long now) {
        if (count == 0) return false;
        long latest = timeAt(count - 1);
        long start = chartTime - WINDOW_MS;
        if (latest < start) return false;
        int first = 0;
        while (timeAt(first) < start) first++;
        if (first > 0 && continuesAt(first)) first--;
        long firstTime = Math.max(start, timeAt(first));
        float firstSpeed = interpolateSpeed(first, firstTime);

        int last = count - 1;
        float max = Math.max(MIN_SCALE_KMH, firstSpeed);
        for (int i = first + 1; i <= last; i++) max = Math.max(max, speedAt(i));
        scale.moveTo(max, now, 240);
        max = (float) scale.valueAt(now);

        if (gradient == null) {
            int rgb = accent & 0x00ffffff;
            gradient = new LinearGradient(0, top, 0, bottom, 0x55000000 | rgb, 0x03000000 | rgb, Shader.TileMode.CLAMP);
        }
        paint.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);

        int segmentStart = first;
        int end = last;
        for (int i = first; i <= end; i++) {
            if (i > first && i <= last && !continuesAt(i)) {
                drawSegment(canvas, segmentStart, i - 1, bottom, true);
                segmentStart = i;
            }
            long time = i == first ? firstTime : timeAt(i);
            float speed = i == first ? firstSpeed : speedAt(i);
            x[i] = left + (right - left) * (time - start) / WINDOW_MS;
            y[i] = bottom - (bottom - top) * speed / max;
        }
        if (RideLog.due("chart.speed", 1000)) RideLog.event("chart.speed", "latestTimeMs", latest,
                "sampleAgeMs", now - latest, "chartTimeMs", chartTime, "samples", count,
                "rawLatestX", x[end], "visibleTipX", Math.min(right, x[end]), "rightX", right, "scaleKmh", max, "revealActive", reveal.active(now),
                "newSegment", newSegment, "width", getWidth(), "height", getHeight());
        drawSegment(canvas, segmentStart, end, bottom, true);
        return true;
    }

    private boolean drawVibration(Canvas canvas, float left, float right, float top, float bottom, long now) {
        if (vibrationCount == 0 || vibrationTimeAt(vibrationCount - 1) < now - WINDOW_MS) return false;
        long start = now - WINDOW_MS;
        int first = 0;
        while (vibrationTimeAt(first) < start) first++;
        if (first > 0 && vibrationContinuesAt(first)) first--;
        long firstTime = Math.max(start, vibrationTimeAt(first));
        float firstValue = vibrationAt(first);
        if (firstTime > vibrationTimeAt(first)) {
            float fraction = (float) (firstTime - vibrationTimeAt(first))
                    / (vibrationTimeAt(first + 1) - vibrationTimeAt(first));
            firstValue += (vibrationAt(first + 1) - firstValue) * fraction;
        }
        int last = vibrationCount - 1;
        // G uses its own vertical scale while both traces use the same monotonic time axis.
        float maximum = Math.max(MIN_SCALE_G, firstValue);
        for (int i = first + 1; i <= last; i++) maximum = Math.max(maximum, vibrationAt(i));
        paint.setStrokeWidth(1.3f * getResources().getDisplayMetrics().density);
        int segmentStart = first;
        for (int i = first; i <= last; i++) {
            if (i > first && !vibrationContinuesAt(i)) {
                drawSegment(canvas, segmentStart, i - 1, bottom, false);
                segmentStart = i;
            }
            long time = i == first ? firstTime : vibrationTimeAt(i);
            float value = i == first ? firstValue : vibrationAt(i);
            x[i] = left + (right - left) * (time - start) / WINDOW_MS;
            y[i] = bottom - (bottom - top) * value / maximum;
        }
        drawSegment(canvas, segmentStart, last, bottom, false);
        return true;
    }

    private int vibrationIndexOf(int sample) {
        return (vibrationNext + VIBRATION_CAPACITY - vibrationCount + sample) % VIBRATION_CAPACITY;
    }

    private long vibrationTimeAt(int sample) { return vibrationTimes[vibrationIndexOf(sample)]; }
    private float vibrationAt(int sample) { return vibrations[vibrationIndexOf(sample)]; }
    private boolean vibrationContinuesAt(int sample) {
        return vibrationTimeAt(sample) - vibrationTimeAt(sample - 1) <= VIBRATION_MAX_GAP_MS;
    }

    private int indexOf(int sample) { return (next + CAPACITY - count + sample) % CAPACITY; }
    private long timeAt(int sample) { return times[indexOf(sample)]; }
    private float speedAt(int sample) { return speeds[indexOf(sample)]; }

    private boolean continuesAt(int sample) {
        long gap = timeAt(sample) - timeAt(sample - 1);
        return !starts[indexOf(sample)] && gap <= MAX_GAP_MS;
    }

    private float interpolateSpeed(int index, long time) {
        float speed = speedAt(index);
        long previous = timeAt(index);
        if (time == previous) return speed;
        return speed + (speedAt(index + 1) - speed) * (time - previous) / (timeAt(index + 1) - previous);
    }

    private void drawSegment(Canvas canvas, int first, int last, float bottom, boolean speed) {
        if (last <= first || x[last] <= x[first]) return;
        curve.reset();
        SpeedCurve.append(curve, x, y, first, last);
        if (speed) {
            fill.set(curve);
            fill.lineTo(x[last], bottom);
            fill.lineTo(x[first], bottom);
            fill.close();
            paint.setColor(0xffffffff);
            paint.setShader(gradient);
            canvas.drawPath(fill, paint);
            paint.setShader(null);
        }
        paint.setColor(speed ? accent : vibrationColor);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(curve, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
