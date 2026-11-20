package com.nova5bike.dashboard;

/** Validated GNSS measurements. No interpolation or inertial speed prediction. */
final class GpsSpeedReading {
    static final long UPDATE_INTERVAL_MS = 250;
    static final long MAX_AGE_MS = 1500;
    private static final float MAX_ACCURACY_MPS = 1.5f;
    private static final float MAX_ACCELERATION_MPS2 = 6f;
    private static final float MAX_BRAKING_MPS2 = 12f;
    private static final float JUMP_TOLERANCE_MPS = .8f;

    private String reason = "reset";
    String reason() { return reason; }

    private long sampleAt = -1, acceptedAt = -1;
    private float value = Float.NaN, previous = Float.NaN;

    boolean record(float speed, float accuracy, long time, long now) {
        if (time <= sampleAt || time > now) {
            reason = time > now ? "future" : "duplicate_or_out_of_order";
            return false;
        }
        sampleAt = time;
        value = Float.NaN;
        if (now - time > MAX_AGE_MS || !Float.isFinite(speed)) {
            reason = now - time > MAX_AGE_MS ? "stale" : "missing_or_invalid_speed";
            return true;
        }
        // NaN denotes an absent accuracy field; a supplied invalid field is rejected by the caller.
        if (!Float.isNaN(accuracy)
                && (!Float.isFinite(accuracy) || accuracy < 0 || accuracy > MAX_ACCURACY_MPS)) {
            reason = "speed_accuracy";
            return true;
        }
        boolean zeroNoise = speed < 0 && speed >= -.1f
                && (Float.isNaN(accuracy) || -speed <= accuracy);
        if (speed < 0 && !zeroNoise) { reason = "missing_or_invalid_speed"; return true; }
        if (zeroNoise) speed = 0;
        if (acceptedAt >= 0 && time - acceptedAt <= MAX_AGE_MS) {
            float rate = speed < previous ? MAX_BRAKING_MPS2 : MAX_ACCELERATION_MPS2;
            float allowed = JUMP_TOLERANCE_MPS + rate * (time - acceptedAt) / 1000f;
            if (Math.abs(speed - previous) > allowed) { reason = "jump"; return true; }
        }
        reason = zeroNoise ? "zero_noise" : "accepted";
        acceptedAt = time;
        previous = value = speed;
        return true;
    }

    float valueAt(long now) {
        return sampleAt >= 0 && now >= sampleAt && now - sampleAt <= MAX_AGE_MS ? value : Float.NaN;
    }

    long expiresIn(long now) {
        return Float.isFinite(valueAt(now)) ? MAX_AGE_MS - (now - sampleAt) + 1 : 0;
    }

    void reset() {
        reason = "reset";
        sampleAt = acceptedAt = -1;
        value = previous = Float.NaN;
    }
}
