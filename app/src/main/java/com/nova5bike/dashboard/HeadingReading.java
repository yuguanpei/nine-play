package com.nova5bike.dashboard;

/** Continuous phone direction, with a correction learned only from independently reliable travel. */
final class HeadingReading {
    private static final long STARTUP_MS = 800, SETTLE_MS = 350, MAX_AGE_MS = 1500;
    private static final float MIN_COURSE_SPEED_MPS = 3.6f;
    static final float MAX_GPS_CORRECTION_DEG = 5, MAX_GPS_DIFFERENCE_DEG = 15, GPS_CORRECTION_DEG_PER_SECOND = .5f;
    // Sustained straight travel with a reliable GPS course may correct a larger compass error gradually.
    static final float MAX_LARGE_GPS_CORRECTION_DEG = 90, MAX_LARGE_GPS_DIFFERENCE_DEG = 90;
    // A compass restart can re-establish a rotated reference. When the phone has not physically turned
    // (gyro quiet), a jump larger than this right after a restart is treated as a spurious flip and held.
    private static final float HOLD_JUMP_DEG = 30, QUIET_GYRO_RAD = .1f, RECOVER_DEG = 12;
    private final long[] times = new long[128];
    private final float[] angles = new float[128];
    private int next, count, courseSamples;
    private long startedAt = -1, stableAt = -1, rawAt = -1, sensorAt = -1, offsetAt = -1;
    private long fixAt = -1, courseStartedAt = -1, trustedCourseAt = -1;
    private float raw = Float.NaN, sensor = Float.NaN, offset, targetOffset;
    private float declination;
    private float courseOrigin, lastCourse, worstPositionAccuracy;
    private double courseLon, courseLat;
    private boolean calibrated, holding;
    private String gpsDecision = "no_fix";

    void reset() {
        startedAt = stableAt = rawAt = sensorAt = offsetAt = fixAt = courseStartedAt = trustedCourseAt = -1;
        next = count = courseSamples = 0;
        raw = sensor = Float.NaN;
        offset = targetOffset = 0;
        calibrated = false;
        holding = false;
        gpsDecision = "no_fix";
    }

    void setDeclination(float degrees) {
        if (Float.isFinite(degrees)) declination = degrees;
    }

    void beginSensors(long now) {
        beginSensors(now, false);
    }

    void beginSensors(long now, boolean retainRecentHeading) {
        startedAt = now;
        stableAt = rawAt = -1;
        raw = Float.NaN;
        if (!retainRecentHeading) {
            sensorAt = -1;
            sensor = Float.NaN;
            courseSamples = 0;
            courseStartedAt = -1;
            holding = false;
        }
        // A compass restart invalidates phone samples. GNSS has its own continuous subscription.
        next = count = 0;
    }

    boolean sensor(float heading, float gyroRadiansPerSecond, boolean accurate, long time) {
        if (time < startedAt || time <= rawAt) return false;
        if (!accurate || !Float.isFinite(heading)) {
            stableAt = -1;
            return false;
        }
        heading = normalize(heading);
        if (startedAt < 0) startedAt = time;
        long dt = rawAt < 0 ? 0 : time - rawAt;
        boolean gyroQuiet = !Float.isFinite(gyroRadiansPerSecond) || gyroRadiansPerSecond < QUIET_GYRO_RAD;
        if (holding) {
            boolean recovered = !Float.isFinite(sensor) || Math.abs(delta(sensor, heading)) <= RECOVER_DEG || !gyroQuiet;
            if (!recovered) {
                // The compass stays rotated while the phone has not physically turned: keep the last good heading.
                raw = heading;
                rawAt = time;
                stableAt = time;
                times[next] = time;
                angles[next] = sensor;
                next = (next + 1) % times.length;
                count = Math.min(count + 1, times.length);
                return false;
            }
            holding = false;
        }
        // A fusion restart can re-establish a rotated reference: a large unsupported jump right after it is a flip.
        if (rawAt < 0 && Float.isFinite(sensor) && gyroQuiet && Math.abs(delta(sensor, heading)) > HOLD_JUMP_DEG) {
            holding = true;
            raw = heading;
            rawAt = time;
            stableAt = time;
            return false;
        }
        // A jump unsupported by measured physical rotation must settle before being displayed.
        float limit = Float.isFinite(gyroRadiansPerSecond)
                ? Math.max(8, (float) Math.toDegrees(Math.max(0, gyroRadiansPerSecond)) * dt / 1000f * 3 + 3)
                : Math.max(8, 360 * dt / 1000f);
        if (rawAt < 0 || dt > 350 || Math.abs(delta(raw, heading)) > limit) stableAt = time;
        if (stableAt < 0) stableAt = time;
        raw = heading;
        rawAt = time;
        if (time - startedAt < STARTUP_MS || time - stableAt < SETTLE_MS) return false;
        sensor = heading;
        sensorAt = time;
        times[next] = time;
        angles[next] = heading;
        next = (next + 1) % times.length;
        count = Math.min(count + 1, times.length);
        return true;
    }

    void gps(float speed, float speedAccuracy, float bearing, float bearingAccuracy,
            float positionAccuracy, double lon, double lat, long time, long now, float gyroRate) {
        if (time > now) { gpsDecision = "future"; return; }
        if (time <= fixAt) { gpsDecision = "duplicate_or_old"; return; }
        long interval = fixAt < 0 ? 0 : time - fixAt;
        fixAt = time;
        if (now - time > MAX_AGE_MS || !Float.isFinite(speed) || speed < MIN_COURSE_SPEED_MPS
                || !Float.isFinite(bearing) || !Float.isFinite(positionAccuracy) || positionAccuracy < 0
                || positionAccuracy > 20 || (!Float.isNaN(speedAccuracy)
                && (!Float.isFinite(speedAccuracy) || speedAccuracy < 0 || speedAccuracy > 1.5f))
                || (!Float.isNaN(bearingAccuracy)
                && (!Float.isFinite(bearingAccuracy) || bearingAccuracy < 0 || bearingAccuracy > 12))
                || !Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90
                || (Float.isFinite(gyroRate) && gyroRate > 1.5f)) {
            courseSamples = 0;
            gpsDecision = "quality_or_motion";
            return;
        }
        bearing = normalize(bearing);
        if (courseSamples == 0 || interval > 2200 || Math.abs(delta(lastCourse, bearing)) > 12
                || Math.abs(delta(courseOrigin, bearing)) > 12) {
            courseStartedAt = time;
            courseOrigin = bearing;
            courseLon = lon;
            courseLat = lat;
            worstPositionAccuracy = positionAccuracy;
            courseSamples = 0;
        }
        lastCourse = bearing;
        courseSamples++;
        worstPositionAccuracy = Math.max(worstPositionAccuracy, positionAccuracy);
        boolean missingAccuracy = Float.isNaN(bearingAccuracy);
        if (courseSamples < (missingAccuracy ? 4 : 3) || time - courseStartedAt < (missingAccuracy ? 2500 : 1500)) {
            gpsDecision = "confirming_course";
            return;
        }
        double east = longitudeDelta(courseLon, lon) * 111195 * Math.cos(Math.toRadians((courseLat + lat) / 2));
        double north = (lat - courseLat) * 111195;
        double distance = Math.hypot(east, north);
        float displacementBearing = normalize((float) Math.toDegrees(Math.atan2(east, north)));
        if (distance < Math.max(missingAccuracy ? 20 : 12, worstPositionAccuracy * 3)
                || Math.abs(delta(displacementBearing, bearing)) > 15) {
            gpsDecision = "confirming_displacement";
            return;
        }
        float phone = phoneAt(time);
        if (!Float.isFinite(phone)) { gpsDecision = "sensor_unavailable"; return; }
        float difference = delta(phone + declination, bearing);
        float correctionCap;
        if (Math.abs(difference) <= MAX_GPS_DIFFERENCE_DEG) {
            correctionCap = MAX_GPS_CORRECTION_DEG;
        } else if (Math.abs(difference) <= MAX_LARGE_GPS_DIFFERENCE_DEG) {
            correctionCap = MAX_LARGE_GPS_CORRECTION_DEG;
        } else {
            gpsDecision = "large_difference_ignored";
            return;
        }
        trustedCourseAt = time;
        stepOffset(now);
        targetOffset = Math.max(-correctionCap, Math.min(correctionCap, difference));
        calibrated = true;
        if (offsetAt < 0) offsetAt = now;
        gpsDecision = "calibrated";
    }

    float valueAt(long now) {
        stepOffset(now);
        if (holding && Float.isFinite(sensor)) return normalize(sensor + declination + offset);
        if (sensorAt >= 0 && now >= sensorAt && now - sensorAt <= MAX_AGE_MS) return normalize(sensor + declination + offset);
        return Float.NaN;
    }

    private void stepOffset(long now) {
        if (offsetAt < 0) { offsetAt = now; return; }
        long elapsed = Math.max(0, Math.min(100, now - offsetAt));
        offsetAt = Math.max(offsetAt, now);
        float difference = delta(offset, targetOffset);
        float limit = GPS_CORRECTION_DEG_PER_SECOND * elapsed / 1000f;
        offset = normalize(offset + Math.max(-limit, Math.min(limit, difference)));
    }

    private float phoneAt(long time) {
        long before = -1, after = Long.MAX_VALUE;
        float first = Float.NaN, second = Float.NaN;
        for (int i = 0; i < count; i++) {
            if (times[i] <= time && times[i] > before) { before = times[i]; first = angles[i]; }
            if (times[i] >= time && times[i] < after) { after = times[i]; second = angles[i]; }
        }
        if (before >= 0 && after < Long.MAX_VALUE && after - before <= 350) {
            return before == after ? first : normalize(first + delta(first, second) * (time - before) / (after - before));
        }
        if (before >= 0 && time - before <= 250) return first;
        if (after < Long.MAX_VALUE && after - time <= 250) return second;
        return Float.NaN;
    }

    String sourceAt(long now) {
        return calibrated ? "gps_calibrated_phone" : "phone";
    }
    boolean holding() { return holding; }
    String gpsDecision() { return gpsDecision; }
    float correction() { return delta(0, offset); }
    float targetCorrection() { return targetOffset; }
    long gpsAge(long now) { return trustedCourseAt < 0 ? -1 : now - trustedCourseAt; }
    boolean ready(long now) { return sensorAt >= startedAt && sensorAt >= 0 && now >= sensorAt && now - sensorAt <= MAX_AGE_MS; }
    static float normalize(float value) { return (value % 360 + 360) % 360; }
    static float delta(float from, float to) { return normalize(to - from + 180) - 180; }
    private static double longitudeDelta(double from, double to) { return ((to - from + 540) % 360) - 180; }
}
