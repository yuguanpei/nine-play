package com.nova5bike.dashboard;

/** Projects the screen's mounted forward edge from a full device-to-world rotation matrix. */
final class HeadingFrame {
    static int key(float[] matrix, int rotation) {
        int column = rotation == 1 || rotation == 3 ? 0 : 1;
        if (Math.hypot(matrix[column], matrix[column + 3]) >= .35) return rotation * 2;
        column = column == 0 ? 1 : 0;
        return Math.hypot(matrix[column], matrix[column + 3]) >= .35 ? rotation * 2 + 1 : -1;
    }

    static float direction(float[] matrix, int rotation) {
        int key = key(matrix, rotation);
        if (key < 0) return Float.NaN;
        boolean landscape = rotation == 1 || rotation == 3;
        int column = landscape ? 0 : 1;
        float extra = 0;
        if ((key & 1) != 0) { column = landscape ? 1 : 0; extra = landscape ? 90 : -90; }
        if (rotation == 2 || rotation == 3) extra += 180;
        return HeadingReading.normalize((float) Math.toDegrees(Math.atan2(matrix[column], matrix[column + 3])) + extra);
    }
}
