package com.nova5bike.dashboard;

/** Tracks GNSS horizontal accuracy independently of the navigation acceptance threshold. */
final class LocationAccuracyReading {
    private long sampledAt = -1;
    private float meters = Float.NaN;

    void record(float accuracy, long time, long now) {
        if (time < 0 || time > now || time <= sampledAt) return;
        sampledAt = time;
        meters = Float.isFinite(accuracy) && accuracy >= 0 ? accuracy : Float.NaN;
    }

    String text(long now, long maxAge) {
        return sampledAt >= 0 && now >= sampledAt && now - sampledAt <= maxAge && Float.isFinite(meters)
                ? Math.round(meters) + " m" : "—";
    }

    void reset() { sampledAt = -1; meters = Float.NaN; }
}
