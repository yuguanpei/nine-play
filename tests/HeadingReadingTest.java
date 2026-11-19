package com.nova5bike.dashboard;

public final class HeadingReadingTest {
    public static void main(String[] args) {
        startupAndUnsupportedJumps();
        outOfOrderTimestamps();
        reliableCourseAndParking();
        northWrapAndGpsOutlier();
        shortBackgroundReturn();
        periodicRecalibration();
        slowRideAcrossRestarts();
        magneticToTrueNorth();
        boundedGpsAssistance();
        unreliableFixesAndSensorFailure();
        stationaryFlipHeldAfterRestart();
        System.out.println("Heading warmup, bounded GPS assistance, parking, tunnel and north wrap: passed");
    }

    private static void startupAndUnsupportedJumps() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t < 800; t += 50) {
            h.sensor(t == 250 ? 231 : 331, .01f, true, t);
            check(Float.isNaN(h.valueAt(t)), "startup readings must settle");
        }
        for (long t = 800; t <= 1000; t += 50) h.sensor(331, .01f, true, t);
        near(h.valueAt(1000), 331);
        h.sensor(231, .01f, true, 1050);
        near(h.valueAt(1050), 331);
        h.sensor(300, .01f, true, 1100);
        near(h.valueAt(1100), 331);
        for (long t = 1150; t <= 1550; t += 50) h.sensor(331, .01f, true, t);
        near(h.valueAt(1550), 331);
    }

    private static void outOfOrderTimestamps() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t <= 1000; t += 50) {
            h.sensor(45, 0, true, t);
            h.sensor(180, 0, true, t);
        }
        near(h.valueAt(1000), 45);
        h.gps(8, .2f, 45, 4, 3, 121, 31, 8000, 1000, 0);
        h.gps(8, .2f, 45, 4, 3, 121, 31, 1100, 1100, 0);
        check(!"duplicate_or_old".equals(h.gpsDecision()), "A future timestamp must not suppress subsequent fixes");
    }

    private static HeadingReading ride(float raw, float course, float bearingAccuracy) {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t <= 8000; t += 50) {
            h.sensor(raw, .02f, true, t);
            if (t >= 1000 && t % 1000 == 0) fix(h, course, bearingAccuracy, t);
            h.valueAt(t + 40);
        }
        return h;
    }

    private static void reliableCourseAndParking() {
        HeadingReading h = ride(88, 90, 5);
        near(h.valueAt(8040), 90);
        check("gps_calibrated_phone".equals(h.sourceAt(8040)), "GPS and phone use one direction");
        h.gps(0, .2f, 90, 5, 3, 121, 31, 9000, 9040, 0);
        for (long t = 8050; t <= 10000; t += 50) {
            float heading = 88 + Math.min(90, (t - 8050) * .06f);
            h.sensor(heading, 1.6f, true, t);
            h.valueAt(t);
        }
        near(h.valueAt(10000), 180);
        // No GNSS in a tunnel must retain the learned alignment and real phone rotation.
        for (long t = 10050; t <= 15000; t += 50) h.sensor(178, .01f, true, t);
        h.gps(8, .2f, 270, 5, 3, 121, 31, 10000, 15000, 0);
        near(h.valueAt(15000), 180);
    }

    private static void northWrapAndGpsOutlier() {
        HeadingReading h = ride(359, 1, 5);
        near(h.valueAt(8040), 1);
        h.sensor(359, 0, true, 9000);
        h.gps(8, .2f, 181, 5, 3, 121, 31, 9000, 9040, 0);
        near(h.valueAt(9040), 1);
        HeadingReading missingAccuracy = ride(3, 5, Float.NaN);
        near(missingAccuracy.valueAt(8040), 5);
    }

    private static void shortBackgroundReturn() {
        HeadingReading h = ride(88, 90, 5);
        h.beginSensors(9000);
        for (long t = 9000; t <= 10100; t += 50) h.sensor(t == 9200 ? 231 : 88, .01f, true, t);
        near(h.valueAt(10100), 90);
    }

    private static void periodicRecalibration() {
        HeadingReading h = ride(88, 90, 5);
        // A periodic restart keeps the last recent corrected reading, but does not call it ready.
        h.beginSensors(8200, true);
        check(!h.ready(8200), "held readings must not finish sensor recovery");
        near(h.valueAt(8200), 90);
        for (long t = 8200; t < 9000; t += 50) {
            h.sensor(t == 8450 ? 231 : 88, .01f, true, t);
            near(h.valueAt(t), 90);
            check(!h.ready(t), "startup transients stay hidden during periodic recovery");
        }
        h.sensor(88, .01f, true, 9000);
        check(h.ready(9000), "new stable sensor readings finish recovery");
        near(h.valueAt(9000), 90);
        h.beginSensors(9200, true);
        check(Float.isNaN(h.valueAt(10600)), "a failed restart cannot retain stale direction forever");
    }

    private static void slowRideAcrossRestarts() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        int accepted = 0;
        for (long t = 0; t <= 60_000; t += 50) {
            if (t >= 5200 && t % 5000 == 200) h.beginSensors(t, true);
            if (t < 5000 || t % 5000 >= 200) h.sensor(85, 0, true, t);
            if (t % 1000 == 0) {
                double lon = 121 + t * .005 / (111195 * Math.cos(Math.toRadians(31)));
                h.gps(5, .2f, 90, 4, 10, lon, 31, t, t, 0);
                if ("calibrated".equals(h.gpsDecision())) accepted++;
            }
            h.valueAt(t);
        }
        check(accepted > 30, "GNSS displacement survives the five-second compass restarts");
        near(h.valueAt(60_000), 90);
        System.out.println("60 s straight ride with compass restarts: " + accepted + " trusted fixes");
    }

    private static void magneticToTrueNorth() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        h.setDeclination(-6);
        for (long t = 0; t <= 8000; t += 50) {
            h.sensor(5, 0, true, t);
            if (t >= 1000 && t % 1000 == 0) fix(h, 359, 4, t);
            h.valueAt(t + 40);
        }
        near(h.valueAt(8040), 359);
        near(h.correction(), 0);
        h.gps(0, .2f, 180, 4, 3, 121, 31, 9000, 9000, 0);
        for (long t = 9000; t <= 10200; t += 50) h.sensor(95, 0, true, t);
        near(h.valueAt(10200), 89);
    }

    private static void boundedGpsAssistance() {
        // A split beyond 90° is implausible for a compass bias and stays ignored.
        HeadingReading huge = ride(10, 130, 5);
        near(huge.valueAt(8040), 10);
        check("large_difference_ignored".equals(huge.gpsDecision()), "an implausible GPS/compass split must not take over");
        // Small errors stay bounded at 5°, and the phone is never yanked faster than half a degree per second.
        for (float raw : new float[]{80, 100}) {
            HeadingReading h = ride(raw, 90, 5);
            float previous = h.valueAt(8040);
            long previousAt = 8040;
            for (long t = 8100; t <= 24000; t += 50) {
                h.sensor(raw, .02f, true, t);
                if (t % 1000 == 0) fix(h, 90, 5, t);
                float value = h.valueAt(t + 40);
                check(Math.abs(HeadingReading.delta(raw, value)) <= 5.001, "GPS offset must stay within five degrees");
                check(Math.abs(HeadingReading.delta(previous, value)) <= (t + 40 - previousAt) * .0005f + .001,
                        "GPS must not turn the phone faster than half a degree per second");
                previous = value;
                previousAt = t + 40;
            }
            near(h.valueAt(24040), raw < 90 ? 85 : 95);
        }
        // A larger compass error (30°) is corrected gradually on sustained straight travel.
        HeadingReading large = ride(60, 90, 5);
        check("calibrated".equals(large.gpsDecision()), "a 30-degree error must calibrate on reliable travel");
        float corrected = large.valueAt(8040);
        check(corrected > 60, "the large offset must have started correcting");
        check(HeadingReading.delta(corrected, 60) <= 5, "large correction stays gradual at first");
    }

    private static void unreliableFixesAndSensorFailure() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t <= 8000; t += 50) {
            h.sensor(60, 0, true, t);
            if (t >= 1000 && t % 1000 == 0) h.gps(8, .2f, 90, 30, 3, 121, 31, t, t, 0);
        }
        near(h.valueAt(8000), 60);
        check("phone".equals(h.sourceAt(8000)), "poor bearing accuracy cannot calibrate");
        HeadingReading gpsOnly = new HeadingReading();
        gpsOnly.beginSensors(0);
        for (long t = 1000; t <= 5000; t += 1000) fix(gpsOnly, 90, 5, t);
        check(Float.isNaN(gpsOnly.valueAt(5040)), "GPS alone must not replace an unavailable phone heading");
        check("phone".equals(gpsOnly.sourceAt(5040)), "phone remains the primary heading source");
        check(Float.isNaN(gpsOnly.valueAt(6600)), "an expired course must not masquerade as current");
    }

    private static void stationaryFlipHeldAfterRestart() {
        // A stationary compass flip right after a five-second restart must not replace the last good heading.
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t <= 1000; t += 50) h.sensor(326, 0, true, t);
        near(h.valueAt(1000), 326);
        h.beginSensors(1200, true); // periodic restart keeps the recent heading
        h.sensor(123, 0, true, 1250); // flipped reference, phone still
        near(h.valueAt(1250), 326);
        for (long t = 1300; t <= 3000; t += 50) {
            h.sensor(123, 0, true, t); // the flipped value persists
            near(h.valueAt(t), 326);
        }
        h.sensor(326, 0, true, 3050); // compass returns near the held value
        for (long t = 3100; t <= 5000; t += 50) h.sensor(326, 0, true, t);
        near(h.valueAt(5000), 326);
    }

    private static void fix(HeadingReading h, float bearing, float accuracy, long time) {
        double meters = time / 1000.0 * 8;
        double lon = 121 + meters * Math.sin(Math.toRadians(bearing)) / (111195 * Math.cos(Math.toRadians(31)));
        double lat = 31 + meters * Math.cos(Math.toRadians(bearing)) / 111195;
        h.gps(8, .2f, bearing, accuracy, 3, lon, lat, time, time + 40, .02f);
    }

    private static void near(float actual, float expected) {
        check(Float.isFinite(actual) && Math.abs(HeadingReading.delta(actual, expected)) < .02,
                "expected " + expected + ", got " + actual);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
