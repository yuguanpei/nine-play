package com.nova5bike.dashboard;

/** Converts physical orientation into a stable landscape display rotation. */
final class LandscapeRotation {
    static final long STABLE_MS = 250;
    private int candidate = -1;
    private long candidateAt;

    int update(int degrees, boolean naturalLandscape, long now) {
        int rotation = -1;
        if (degrees >= 0 && degrees < 360) {
            int quadrant = ((degrees + 45) / 90) % 4;
            int difference = Math.abs(degrees - quadrant * 90);
            difference = Math.min(difference, 360 - difference);
            if (difference <= 30 && (quadrant % 2 == (naturalLandscape ? 0 : 1))) {
                rotation = (4 - quadrant) % 4;
            }
        }
        if (rotation < 0 || rotation != candidate) {
            candidate = rotation;
            candidateAt = now;
            return -1;
        }
        return now - candidateAt >= STABLE_MS ? rotation : -1;
    }

    void reset() { candidate = -1; }
}
