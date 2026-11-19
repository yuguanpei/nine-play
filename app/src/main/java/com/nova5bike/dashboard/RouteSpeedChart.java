package com.nova5bike.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/** Speed samples indexed by distance along the active navigation. */
final class RouteSpeedChart extends View {
    private static final int MAX_SAMPLES = RouteSamples.CAPACITY;
    private final RouteSamples samples = new RouteSamples();
    private final double[] distances = samples.distances;
    private final float[] speeds = samples.speeds;
    private final boolean[] starts = samples.starts;
    private final float[] x = new float[MAX_SAMPLES];
    private final float[] y = new float[MAX_SAMPLES];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path area = new Path();
    private final ChartAnimation reveal = new ChartAnimation(), scale = new ChartAnimation();
    private final Runnable redraw = this::invalidate;
    private int accent;
    private long lastFixAt = -1;
    private boolean newSegment;
    private double progressMeters, totalMeters;

    RouteSpeedChart(Context context, int accent) {
        super(context);
        this.accent = accent;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        reset();
    }

    void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    void reset() {
        removeCallbacks(redraw);
        samples.reset();
        lastFixAt = -1;
        newSegment = false;
        progressMeters = totalMeters = 0;
        reveal.snap(0);
        scale.snap(Double.NaN);
        setContentDescription("路线进度 0%");
        invalidate();
    }

    void breakTrace() {
        newSegment = true;
    }

    void setTotalMeters(double meters) {
        totalMeters = meters;
        setContentDescription("路线进度 " + progressPercent() + "%");
        invalidate();
    }

    void record(double meters, float kmh, long fixAt, long maxGap) {
        if (fixAt <= lastFixAt || totalMeters <= 0) return;
        boolean firstFix = lastFixAt < 0;
        long interval = firstFix ? 0 : fixAt - lastFixAt;
        boolean gap = newSegment || (lastFixAt >= 0 && fixAt - lastFixAt >= maxGap);
        lastFixAt = fixAt;
        int percent = progressPercent();
        progressMeters = Math.min(totalMeters, Math.max(progressMeters, meters));
        if (progressPercent() != percent) {
            setContentDescription("路线进度 " + progressPercent() + "%");
        }
        int last = samples.count - 1;
        float sample = kmh;
        if (!Float.isFinite(sample) || kmh < 0) {
            breakTrace();
        } else if (firstFix && samples.count == 1 && progressMeters == 0) {
            speeds[0] = sample;
            newSegment = false;
        } else if (progressMeters > distances[last]) {
            samples.append(progressMeters, sample, gap, totalMeters);
            newSegment = false;
        } else if (gap) {
            // A reconnect can land on the same distance. Keep the gap until we advance.
            newSegment = true;
        } else if (sample == 0 && speeds[last] > 0) {
            samples.append(progressMeters, 0, false, totalMeters);
        }

        if (gap || interval == 0) reveal.snap(progressMeters);
        else reveal.moveTo(progressMeters, SystemClock.elapsedRealtime(), ChartAnimation.sampleDuration(interval));
        invalidate();
    }

    private int progressPercent() {
        return totalMeters > 0 ? (int) Math.round(progressMeters / totalMeters * 100) : 0;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(redraw);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        removeCallbacks(redraw);
        float density = getResources().getDisplayMetrics().density;
        float top = density;
        float bottom = getHeight() - .5f * density;
        float width = getWidth();
        if (width <= 0 || bottom <= top || totalMeters <= 0) return;

        float peak = 0;
        for (int i = 0; i < samples.count; i++) peak = Math.max(peak, speeds[i]);
        if (samples.count < 2 || peak <= 0) return;
        long now = SystemClock.elapsedRealtime();
        scale.moveTo(peak, now, 240);
        peak = (float) scale.valueAt(now);

        // Round the joins, never the measured heights: a later stop must not rewrite earlier peaks.
        for (int i = 0; i < samples.count; i++) {
            x[i] = (float) (distances[i] / totalMeters * width);
            y[i] = bottom - (bottom - top) * speeds[i] / peak;
        }

        curve.reset();
        area.reset();
        int first = 0;
        for (int i = 1; i <= samples.count; i++) {
            if (i < samples.count && !starts[i]) continue;
            if (i - first >= 2) {
                SpeedCurve.append(curve, x, y, first, i - 1);
                SpeedCurve.append(area, x, y, first, i - 1);
                area.lineTo(x[i - 1], bottom);
                area.lineTo(x[first], bottom);
                area.close();
            }
            first = i;
        }
        if (RideLog.due("chart.route", 1000)) RideLog.event("chart.route", "totalM", totalMeters,
                "progressM", progressMeters, "revealM", reveal.valueAt(now), "sampleTimeMs", lastFixAt,
                "sampleCount", samples.count, "newSegment", newSegment, "width", getWidth(), "height", getHeight(),
                "peakKmh", peak, "stopAttenuation", false, "lastSpeedKmh", speeds[samples.count - 1]);
        int save = canvas.save();
        if (reveal.active(now)) {
            canvas.clipRect(0, 0, (float) (reveal.valueAt(now) / totalMeters * width), getHeight());
        }
        paint.setColor((accent & 0x00ffffff) | 0x45000000);
        canvas.drawPath(area, paint);
        paint.setColor((accent & 0x00ffffff) | 0xa0000000);
        paint.setStrokeWidth(1.1f * density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(curve, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(save);
        if (reveal.active(now) || scale.active(now)) postOnAnimationDelayed(redraw, 16);
    }
}
