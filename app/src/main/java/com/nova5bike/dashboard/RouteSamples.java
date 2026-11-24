package com.nova5bike.dashboard;

/** Distance-binned measurements. A later stop never overwrites an already recorded positive speed. */
final class RouteSamples {
    static final int BINS = 512, CAPACITY = BINS * 2 + 1;
    final double[] distances = new double[CAPACITY];
    final float[] speeds = new float[CAPACITY];
    final boolean[] starts = new boolean[CAPACITY];
    int count;

    RouteSamples() { reset(); }

    void reset() {
        count = 1;
        distances[0] = speeds[0] = 0;
        starts[0] = true;
    }

    void append(double meters, float kmh, boolean gap, double totalMeters) {
        if (!Double.isFinite(meters) || !Float.isFinite(kmh) || kmh < 0 || meters < 0 || !Double.isFinite(totalMeters) || totalMeters <= 0) return;
        if (count == CAPACITY) compact(totalMeters);
        append(meters, kmh, gap, totalMeters, false);
    }

    private void append(double meters, float kmh, boolean gap, double totalMeters, boolean compacting) {
        int last = count - 1;
        boolean sameStopState = (kmh == 0) == (speeds[last] == 0);
        if (last > 0 && (!gap || compacting) && (sameStopState || compacting)
                && bin(meters, totalMeters) == bin(distances[last], totalMeters)) {
            distances[last] = meters;
            speeds[last] = Math.max(speeds[last], kmh);
            starts[last] |= gap;
        } else {
            distances[count] = meters;
            speeds[count] = kmh;
            starts[count++] = gap;
        }
    }

    private void compact(double totalMeters) {
        int previousCount = count;
        count = 1;
        for (int i = 1; i < previousCount; i++) {
            append(distances[i], speeds[i], starts[i], totalMeters, true);
        }
    }

    private static int bin(double meters, double totalMeters) {
        return Math.min(BINS - 1, Math.max(0, (int) (meters / totalMeters * BINS)));
    }
}
