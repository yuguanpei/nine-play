package com.nova5bike.dashboard;

import android.graphics.Path;

final class SpeedCurve {
    private SpeedCurve() {}

    static void append(Path path, float[] x, float[] y, int first, int last) {
        path.moveTo(x[first], y[first]);
        float slope = 0;
        for (int i = first + 1; i <= last; i++) {
            float nextSlope = 0;
            if (i < last && x[i] > x[i - 1] && x[i + 1] > x[i]) {
                float incoming = (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
                float outgoing = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
                // A shared tangent keeps the join round. Turn at the sampled maximum or minimum.
                if ((incoming > 0 && outgoing > 0) || (incoming < 0 && outgoing < 0)) {
                    nextSlope = 2 * incoming * outgoing / (incoming + outgoing);
                }
            }
            float handle = (x[i] - x[i - 1]) / 3;
            path.cubicTo(x[i - 1] + handle, y[i - 1] + slope * handle,
                    x[i] - handle, y[i] - nextSlope * handle, x[i], y[i]);
            slope = nextSlope;
        }
    }
}
