package com.nova5bike.dashboard;

/** A display-only transition; recorded measurements are never changed. */
final class ChartAnimation {
    private double from = Double.NaN, target = Double.NaN;
    private long startedAt, duration;

    void moveTo(double next, long now, long milliseconds) {
        if (!Double.isFinite(next) || next == target) return;
        double shown = valueAt(now);
        from = Double.isFinite(shown) ? shown : next;
        target = next;
        startedAt = now;
        duration = Double.isFinite(shown) ? Math.max(0, milliseconds) : 0;
    }

    void snap(double next) {
        from = target = next;
        duration = 0;
    }

    double valueAt(long now) {
        if (duration <= 0) return target;
        double fraction = Math.max(0, Math.min(1, (now - startedAt) / (double) duration));
        return from + (target - from) * fraction;
    }

    boolean active(long now) { return duration > 0 && now < startedAt + duration; }

    static long sampleDuration(long interval) { return Math.max(120, Math.min(1200, interval)); }
}
