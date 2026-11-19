package com.nova5bike.dashboard;

/** Short-window RMS of acceleration after removing slow changes in motion and mounting angle. */
final class VibrationReading {
    private long sampledAt = -1;
    private float slowX, slowY, slowZ, energy;

    void record(float x, float y, float z, long time) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || time <= sampledAt) return;
        long elapsed = time - sampledAt;
        if (sampledAt < 0 || elapsed > 1000) {
            slowX = x;
            slowY = y;
            slowZ = z;
            energy = 0;
        } else {
            float baseline = 1f - (float) Math.exp(-elapsed / 600f);
            slowX += (x - slowX) * baseline;
            slowY += (y - slowY) * baseline;
            slowZ += (z - slowZ) * baseline;
            float dx = x - slowX, dy = y - slowY, dz = z - slowZ;
            float weight = 1f - (float) Math.exp(-elapsed / 350f);
            energy += (dx * dx + dy * dy + dz * dz - energy) * weight;
        }
        sampledAt = time;
    }

    float valueAt(long now) {
        return sampledAt >= 0 && now >= sampledAt && now - sampledAt <= 1000
                ? (float) Math.sqrt(Math.max(0, energy)) : Float.NaN;
    }

    void reset() { sampledAt = -1; }
}
