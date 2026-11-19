package com.nova5bike.dashboard;

public final class RideReadingsTest {
    public static void main(String[] args) {
        immediateMeasurements();
        invalidMeasurements();
        jumpsAndRecovery();
        mountingAngles();
        stationaryNegativeNoise();
        System.out.println("Ride readings: passed");
    }

    private static void immediateMeasurements() {
        GpsSpeedReading speed = new GpsSpeedReading();
        speed.record(10, .2f, 1000, 1000);
        speed.record(10.5f, .2f, 1260, 1260);
        near(speed.valueAt(1260), 10.5f);
        check(!speed.record(20, .2f, 1260, 1260), "duplicate fix");
        check(!speed.record(20, .2f, 1200, 1300), "out-of-order fix");
        check(!speed.record(20, .2f, 1500, 1400), "future fix");
        near(speed.valueAt(1400), 10.5f);
        near(speed.valueAt(2760), 10.5f);
        check(speed.expiresIn(2760) == 1, "expiry boundary");
        check(Float.isNaN(speed.valueAt(2761)), "stale speed");
        speed.record(10.5f, .2f, 2800, 2800);
        near(speed.valueAt(2800), 10.5f);
    }

    private static void invalidMeasurements() {
        GpsSpeedReading speed = new GpsSpeedReading();
        speed.record(9, .2f, 1000, 1000);
        speed.record(11, 3, 1250, 1250);
        check(Float.isNaN(speed.valueAt(1250)), "uncertain speed must not be shown");
        check("speed_accuracy".equals(speed.reason()), "diagnostic rejection reason");
        speed.record(9.5f, .2f, 1500, 1500);
        near(speed.valueAt(1500), 9.5f);
        speed.record(Float.NaN, .2f, 1750, 1750);
        check(Float.isNaN(speed.valueAt(1750)), "missing speed");
        speed.record(9.5f, Float.POSITIVE_INFINITY, 2000, 2000);
        check(Float.isNaN(speed.valueAt(2000)), "invalid accuracy");
        speed.record(9.5f, Float.NaN, 2250, 2250);
        near(speed.valueAt(2250), 9.5f);
        speed.record(-1, .2f, 2500, 2500);
        check(Float.isNaN(speed.valueAt(2500)), "negative speed");
        speed.record(10, .2f, 3000, 4600);
        check(Float.isNaN(speed.valueAt(4600)), "delayed delivery");
        speed.reset();
        check(Float.isNaN(speed.valueAt(4700)), "reset");
    }

    private static void jumpsAndRecovery() {
        GpsSpeedReading speed = new GpsSpeedReading();
        speed.record(10, .2f, 1000, 1000);
        speed.record(30, .2f, 1250, 1250);
        check(Float.isNaN(speed.valueAt(1250)), "single large spike");
        check("jump".equals(speed.reason()), "spikes must be distinguishable from missing GPS");
        speed.record(10.2f, .2f, 1500, 1500);
        near(speed.valueAt(1500), 10.2f);
        speed.record(0, .2f, 2500, 2500);
        near(speed.valueAt(2500), 0);
        speed.record(20, .2f, 5000, 5000);
        near(speed.valueAt(5000), 20);
    }

    private static void stationaryNegativeNoise() {
        GpsSpeedReading speed = new GpsSpeedReading();
        speed.record(-.061f, .15f, 1000, 1000);
        near(speed.valueAt(1000), 0);
        check("zero_noise".equals(speed.reason()), "logged negative noise remains distinguishable");
        speed.record(-.2f, .15f, 2000, 2000);
        check(Float.isNaN(speed.valueAt(2000)), "larger negative speeds remain invalid");
        speed.record(-.06f, 3, 3000, 3000);
        check(Float.isNaN(speed.valueAt(3000)), "poor GPS quality must not be disguised as parking");
        speed.record(-.06f, .01f, 4000, 4000);
        check(Float.isNaN(speed.valueAt(4000)), "negative values outside the accuracy estimate remain invalid");
    }

    private static void mountingAngles() {
        float[] north = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        near(RideSensors.mountedHeading(north, 0), 0);
        near(RideSensors.mountedHeading(north, 1), 90);
        near(RideSensors.mountedHeading(north, 2), 180);
        near(RideSensors.mountedHeading(north, 3), 270);
        // Physical X tilts upwards while the horizontal bearing remains due east.
        float[] tilted = {.5f, 0, -.8660254f, 0, 1, 0, .8660254f, 0, .5f};
        near(RideSensors.mountedHeading(tilted, 1), 90);
        near(RideSensors.mountedHeading(tilted, 3), 270);
        float[] upright = {0, 0, -1, 0, 1, 0, 1, 0, 0};
        near(RideSensors.mountedHeading(upright, 1), 90);
        float[] wrap = {.9998477f, -.0174524f, 0, .0174524f, .9998477f, 0, 0, 0, 1};
        near(RideSensors.mountedHeading(wrap, 0), 359);
    }

    private static void near(float actual, float expected) {
        check(Float.isFinite(actual) && Math.abs(actual - expected) < .01f,
                "expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
