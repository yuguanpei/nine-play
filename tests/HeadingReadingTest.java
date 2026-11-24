package com.nova5bike.dashboard;

public final class HeadingReadingTest {
    public static void main(String[] args) {
        initialReferenceAndMagneticIsolation();
        continuousTrackDespiteBumps();
        fastCatchupAndGentleFinish();
        steeringDoesNotBecomePermanentBias();
        parkingAndHandlebar();
        movingTurnWithoutGpsPullback();
        timestampAlignedGps();
        northWrapAndBadFixes();
        stationaryDriftAndRealMotion();
        frameAndResumeRecovery();
        relativeSensorReferenceReset();
        relativeAvailabilityRecovery();
        noRelativeHardware();
        fullThreeDimensionalProjection();
        System.out.println("Heading fusion: seed, nonmagnetic motion, bumps, delayed GPS, parking, drift, frames and recovery passed");
    }

    private static void sample(HeadingReading h, float relative, float magnetic, float gyro, float acceleration, long time) {
        h.mount(4.9f, 0, 8.487f, 1, time);
        h.motion(gyro, acceleration, time);
        h.relative(relative, 2, time);
        h.magnetic(magnetic, true, time);
        h.valueAt(time);
    }

    private static HeadingReading seeded(float relative, float magnetic) {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        for (long t = 0; t <= 2000; t += 20) sample(h, relative, magnetic, 0, .02f, t);
        return h;
    }

    private static void initialReferenceAndMagneticIsolation() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        h.setDeclination(-6);
        for (long t = 0; t < 800; t += 20) {
            sample(h, 10, t == 200 ? 200 : 100, .01f, .02f, t);
            check(Float.isNaN(h.valueAt(t)), "Unsettled initial magnetic readings stay hidden");
        }
        for (long t = 800; t <= 2000; t += 20) sample(h, 10, 100, .01f, .02f, t);
        near(h.valueAt(2000), 94, .02f);
        for (long t = 2020; t <= 5000; t += 20) sample(h, 10, 240, .01f, .02f, t);
        near(h.valueAt(5000), 94, .02f);
        check(!h.calibrated(), "Magnetic changes after seeding cannot silently become GNSS alignment");
    }

    private static void continuousTrackDespiteBumps() {
        HeadingReading h = seeded(10, 140); // True travel is east, initial compass is wrong by 50 degrees.
        int accepted = 0;
        for (long t = 2020; t <= 14000; t += 20) {
            // Roll/pitch angular activity and acceleration do not change the projected relative yaw.
            sample(h, 10, 140 + (t > 6000 ? 40 : 0), t % 200 < 100 ? .8f : .1f, 3, t);
            if (t % 1000 == 0) {
                fix(h, 8, 90, 4, t * .008, 0, t, t);
                if ("calibrated".equals(h.gpsDecision())) accepted++;
            }
            h.valueAt(t);
        }
        check(accepted >= 8, "Road-like angular activity must not keep erasing a straight GPS window");
        near(h.valueAt(14000), 90, .1f);
        check(h.sourceAt(14000).equals("gps_relative"), "GPS must actually replace the initial absolute reference");
    }

    private static HeadingReading aligned() {
        HeadingReading h = seeded(10, 120);
        for (long t = 2020; t <= 12000; t += 20) {
            sample(h, 10, 120, .01f, .02f, t);
            if (t % 1000 == 0) fix(h, 8, 90, 4, t * .008, 0, t, t);
            h.valueAt(t);
        }
        near(h.valueAt(12000), 90, .1f);
        return h;
    }

    private static void fastCatchupAndGentleFinish() {
        for (float initialError : new float[]{90, 110, -110, 180, 5}) {
            HeadingReading h = seeded(10, 90 + initialError);
            long firstCalibration = -1, settledAfter = -1;
            float previous = h.valueAt(2000);
            boolean sawFastCatchup = false, sawGentleFinish = false;
            for (long t = 2020; t <= 14000; t += 20) {
                sample(h, 10, 90 + initialError, .01f, .3f, t);
                if (t % 1000 == 0) {
                    fix(h, 8, 90, 4, t * .008, 0, t, t);
                    if (firstCalibration < 0 && "calibrated".equals(h.gpsDecision())) firstCalibration = t;
                }
                float current = h.valueAt(t);
                float before = Math.abs(HeadingReading.delta(previous, 90));
                float remaining = Math.abs(HeadingReading.delta(current, 90));
                float step = Math.abs(HeadingReading.delta(previous, current));
                if (firstCalibration < 0) {
                    near(current, 90 + initialError, .02f);
                } else {
                    check(step <= 1.45f, "Faster GNSS correction remains rate-limited without snapping");
                    check(remaining <= before + .01f, "Correction approaches the target without overshoot");
                    if (before > 60 && step > 1) sawFastCatchup = true;
                    if (before <= 8) {
                        check(step <= .25f, "Final eight degrees retain a gentle approach");
                        if (before > 2 && step > 0) sawGentleFinish = true;
                    }
                    if (settledAfter < 0 && remaining <= 1) settledAfter = t - firstCalibration;
                }
                previous = current;
            }
            check(firstCalibration >= 0 && settledAfter >= 0, "Trusted continuous fixes converge");
            check(settledAfter <= (Math.abs(initialError) == 180 ? 4200 : 3300),
                    "Confirmed heading recovers promptly even from a large offset");
            check(sawGentleFinish, "Large and small corrections both finish gently");
            if (Math.abs(initialError) >= 90) check(sawFastCatchup, "Large offsets use faster catchup");
            near(h.valueAt(14000), 90, .02f);
            System.out.println("Confirmed GPS correction " + initialError + " degrees: within 1 degree in " + settledAfter + "ms");
        }
    }

    private static void steeringDoesNotBecomePermanentBias() {
        HeadingReading h = seeded(10, 120);
        for (long t = 2020; t <= 7000; t += 20) {
            float angle = t / 1000 % 2 == 0 ? 0 : 20;
            sample(h, angle, 120, 8, .3f, t);
            if (t % 1000 == 0) fix(h, 8, 90, 4, t * .008, 0, t, t);
        }
        check(!h.calibrated(), "Alternating handlebar offsets cannot become a permanent calibration");
        for (long t = 7020; t <= 16000; t += 20) {
            sample(h, 10, 120, .4f, 1, t);
            if (t % 1000 == 0) fix(h, 8, 90, 4, t * .008, 0, t, t);
            h.valueAt(t);
        }
        near(h.valueAt(16000), 90, .1f);
    }

    private static void parkingAndHandlebar() {
        HeadingReading h = aligned();
        fix(h, 0, 270, 4, 96, 0, 12020, 12020);
        float alignment = h.correction();
        for (long t = 12020; t <= 15000; t += 20) {
            float turn = Math.min(90, (t - 12020) * .045f);
            sample(h, 10 + turn, 280, .8f, .1f, t);
            near(h.correction(), alignment, .02f);
        }
        near(h.valueAt(15000), 180, .1f);
        check(!h.learning(15000), "Stopped GNSS bearings cannot rotate the calibrated base");
        for (long t = 15020; t <= 18000; t += 20) sample(h, 100, 50, 0, .02f, t);
        near(h.valueAt(18000), 180, .1f);
    }

    private static void timestampAlignedGps() {
        HeadingReading h = new HeadingReading();
        h.beginSensors(0);
        double east = 0, north = 0;
        double[] xs = new double[21], ys = new double[21];
        for (long t = 0; t <= 20000; t += 20) {
            float body = 90 + t * .0005f;
            east += 8 * .02 * Math.sin(Math.toRadians(body));
            north += 8 * .02 * Math.cos(Math.toRadians(body));
            if (t % 1000 == 0) { xs[(int) (t / 1000)] = east; ys[(int) (t / 1000)] = north; }
            sample(h, 10 + t * .0005f, body + 30, .01f, .3f, t);
            if (t >= 1600 && t % 1000 == 600) {
                long measured = t - 600;
                int index = (int) (measured / 1000);
                fix(h, 8, 90 + measured * .0005f, 4, xs[index], ys[index], measured, t);
            }
            h.valueAt(t);
        }
        // The current direction includes the relative rotation AFTER the 600ms-old GPS measurement.
        near(h.valueAt(20000), 100, .05f);
        near(h.relativeAtFix(), 19.5f, .03f);
    }

    private static void movingTurnWithoutGpsPullback() {
        HeadingReading h = aligned();
        for (long t = 12020; t <= 14000; t += 20) {
            sample(h, 10 + (t - 12000) * .045f, 250, .8f, .5f, t);
        }
        near(h.valueAt(14000), 180, .1f);
        for (long t = 14020; t <= 18000; t += 20) {
            sample(h, 100, 250, .02f, .1f, t);
            if (t % 1000 == 0) fix(h, 8, 180, 4, 96, -(t - 12000) * .008, t, t);
            h.valueAt(t);
        }
        near(h.valueAt(18000), 180, .1f);
    }

    private static void northWrapAndBadFixes() {
        HeadingReading h = seeded(359, 359);
        for (long t = 2020; t <= 3020; t += 20) sample(h, 359 + (t - 2020) * .002f, 50, .02f, .1f, t);
        near(h.valueAt(3020), 1, .1f);
        h.gps(8, .2f, 180, 4, 3, 121, 31, 90000, 4000);
        check("future".equals(h.gpsDecision()), "Future GPS cannot poison timestamps");
        fix(h, 8, 1, 4, 0, 32, 4000, 4000);
        check(!"duplicate_or_old".equals(h.gpsDecision()), "Later real fixes still work");
        HeadingReading aligned = aligned();
        float before = aligned.correction();
        aligned.gps(8, .2f, 270, 4, 3, 123, 31, 13000, 13000);
        check("position_jump".equals(aligned.gpsDecision()), "GPS teleport rejected");
        near(aligned.correction(), before, .01f);
        fix(aligned, 8, 270, 50, 104, 0, 14000, 14000);
        check("fix_quality".equals(aligned.gpsDecision()), "Bad bearing uncertainty cannot reset the base");
    }

    private static void stationaryDriftAndRealMotion() {
        HeadingReading h = seeded(10, 100);
        float atQuiet = Float.NaN;
        for (long t = 2020; t <= 10000; t += 20) {
            sample(h, 10 + (t - 2000) * .0001f, 180, .003f, .02f, t);
            if (t == 5000) atQuiet = h.valueAt(t);
        }
        check(h.driftHeld(), "Physical quietness suppresses small stationary game-vector yaw drift");
        near(h.valueAt(10000), atQuiet, .02f);
        float before = h.valueAt(10000);
        for (long t = 10020; t <= 11000; t += 20) sample(h, 10.8f + (t - 10000) * .03f, 180, .53f, .03f, t);
        near(h.valueAt(11000), before + 30, .1f);
        check(!h.driftHeld(), "Turning the handlebar releases quiet hold even with the vehicle parked");
    }

    private static void frameAndResumeRecovery() {
        HeadingReading h = aligned();
        h.suspend(12020);
        h.beginSensors(20000);
        for (long t = 20000; t <= 22000; t += 20) sample(h, 200, 150, .01f, .02f, t);
        near(h.valueAt(22000), 120, .15f); // Fresh compass reference plus rechecked -30 degree bias.
        check(h.calibrated(), "Short resume checks mounting and retains the learned correction");
        for (long t = 22020; t <= 24500; t += 20) {
            h.mount(-4.9f, 0, 8.487f, 3, t);
            h.motion(.01f, .02f, t);
            h.relative(200, 6, t);
            h.magnetic(150, true, t);
            h.valueAt(t);
        }
        near(h.valueAt(24500), 120, .15f);
        h.suspend(24600);
        h.beginSensors(90000);
        for (long t = 90000; t <= 92000; t += 20) sample(h, 80, 72, .01f, .02f, t);
        near(h.valueAt(92000), 72, .1f);
        check(!h.calibrated(), "Long sensor-off gaps need fresh absolute confirmation");
    }

    private static void relativeSensorReferenceReset() {
        HeadingReading h = seeded(10, 100);
        for (long t = 2020; t <= 4000; t += 20) sample(h, 210, 100, 0, .02f, t);
        near(h.valueAt(4000), 100, .1f);
        check(h.ready(4000), "Reference reset continuity recovers without turning the arrow 160 degrees");
    }

    private static void noRelativeHardware() {
        HeadingReading h = new HeadingReading();
        h.setRelativeAvailable(false, 0);
        h.beginSensors(0);
        for (long t = 0; t <= 1600; t += 100) h.magnetic(45, true, t);
        near(h.valueAt(1600), 45, .1f);
        h.magnetic(200, true, 2000);
        near(h.valueAt(2000), 45, .1f);
    }

    private static void relativeAvailabilityRecovery() {
        HeadingReading h = aligned();
        h.setRelativeAvailable(false, 12020);
        near(h.valueAt(12020), 90, .1f);
        check(!h.ready(12020), "A missing relative channel holds the last direction rather than reinterpreting its offset");
        h.magnetic(210, true, 12500);
        near(h.valueAt(12500), 90, .1f);
        h.setRelativeAvailable(true, 13000);
        near(h.valueAt(13000), 90, .1f);
        for (long t = 13000; t <= 15000; t += 20) sample(h, 200, 120, .01f, .02f, t);
        near(h.valueAt(15000), 120, .1f);
        check(h.ready(15000), "Recovered relative zero is confirmed against a fresh absolute reference");
    }

    private static void fullThreeDimensionalProjection() {
        float[] tilted = {.5f, 0, -.8660254f, 0, 1, 0, .8660254f, 0, .5f};
        near(HeadingFrame.direction(tilted, 1), 90, .01f);
        float[] flipped = {-.5f, 0, -.8660254f, 0, -1, 0, -.8660254f, 0, .5f};
        near(HeadingFrame.direction(flipped, 3), 90, .01f);
        check(HeadingFrame.key(tilted, 1) != HeadingFrame.key(flipped, 3), "Frame changes are explicit");
        for (double pitch = -.9; pitch <= 1.1; pitch += .2) {
            for (double roll = -2; roll <= 2; roll += .4) {
                double yaw = -.4, c = Math.cos(yaw), s = Math.sin(yaw);
                double cp = Math.cos(pitch), sp = Math.sin(pitch), cr = Math.cos(roll), sr = Math.sin(roll);
                float[] matrix = {(float) (c * cp), (float) (c * sp * sr - s * cr), (float) (c * sp * cr + s * sr),
                        (float) (s * cp), (float) (s * sp * sr + c * cr), (float) (s * sp * cr - c * sr),
                        (float) -sp, (float) (cp * sr), (float) (cp * cr)};
                near(HeadingFrame.direction(matrix, 1), 90 - (float) Math.toDegrees(yaw), .01f);
            }
        }
    }

    private static void fix(HeadingReading h, float speed, float bearing, float bearingAccuracy,
            double east, double north, long measured, long now) {
        h.gps(speed, .2f, bearing, bearingAccuracy, 3, 121 + east / (111195 * Math.cos(Math.toRadians(31))),
                31 + north / 111195, measured, now);
    }
    private static void near(float actual, float expected, float tolerance) {
        check(Float.isFinite(actual) && Math.abs(HeadingReading.delta(actual, expected)) <= tolerance,
                "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
