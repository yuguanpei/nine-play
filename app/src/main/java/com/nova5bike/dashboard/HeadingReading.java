package com.nova5bike.dashboard;

import java.util.ArrayDeque;

/** Magnetic seed + nonmagnetic relative pose + timestamp-aligned GNSS absolute correction. */
final class HeadingReading {
    private static final long RELATIVE_AGE_MS = 350, FIX_AGE_MS = 1500, WARMUP_MS = 800;
    private static final float MIN_SPEED_MPS = 2.5f;
    static final float MAX_GPS_CORRECTION_DEG = 180, GPS_CORRECTION_DEG_PER_SECOND = 72;
    private static final float GPS_CORRECTION_SETTLE_DEG = 8, GPS_CORRECTION_CATCHUP_GAIN = 3;
    private final long[] times = new long[256];
    private final float[] directions = new float[256];
    private final ArrayDeque<Fix> course = new ArrayDeque<>();
    private int next, count, epoch, frame = -1, seedCount;
    private long startedAt = -1, relativeAt = -1, frameAt = -1, magneticAt = -1, fixAt = -1;
    private long seedAt = -1, seedSampleAt = -1, offsetAt = -1, learningUntil = -1, trustedAt = -1;
    private long quietAt = -1, motionAt = -1, suspendedAt = -1;
    private long absoluteAt = -1;
    private long yawWindowAt = -1;
    private float yawWindowDirection, yawWindowRate;
    private float rawRelative = Float.NaN, relative = Float.NaN, magnetic = Float.NaN, declination;
    private float offset, target, lastHeading = Float.NaN, seedOrigin, seedSin, seedCos;
    private float speed = Float.NaN, gyro = Float.NaN, acceleration = Float.NaN;
    private float magneticBias, magneticReference, cachedBias;
    private boolean anchored, calibrated, relativeAvailable = true, driftHeld, cacheEligible;
    private String decision = "no_fix", holdReason = "initializing";
    private Fix previousPosition;
    private double displacement;
    private float displacementCourse = Float.NaN, relativeAtFix = Float.NaN;
    private float side, forward, normal, referenceSide, referenceForward, referenceNormal;
    private boolean mountKnown, referenceKnown;
    private long mountAt = -1;

    void setRelativeAvailable(boolean available, long now) {
        if (relativeAvailable == available) return;
        float held = valueAt(now);
        relativeAvailable = available;
        if (startedAt < 0) return;
        if (!available) {
            // A relative-frame offset is not an absolute heading. Preserve the last direction on fallback.
            pauseCorrection(now, "relative_unavailable");
            if (Float.isFinite(held)) offset = target = held;
            absoluteAt = -1;
            course.clear();
            next = count = 0;
            epoch++;
        } else {
            // A recovered game sensor may have a different zero; confirm it before using live rotation.
            beginSensors(now, true);
            cacheEligible = false;
            holdReason = "relative_recovery";
        }
    }

    void beginSensors(long now) { beginSensors(now, false); }
    void beginSensors(long now, boolean retainRecentHeading) {
        cacheEligible = calibrated && suspendedAt >= 0 && now - suspendedAt <= 30000 && referenceKnown;
        cachedBias = magneticBias;
        pauseCorrection(now, "initializing");
        startedAt = frameAt = now;
        relativeAt = magneticAt = -1;
        rawRelative = relative = Float.NaN;
        anchored = calibrated = false;
        if (!retainRecentHeading) lastHeading = Float.NaN;
        frame = -1;
        next = count = seedCount = 0;
        seedAt = seedSampleAt = -1;
        course.clear();
        previousPosition = null;
        quietAt = motionAt = -1;
        yawWindowAt = -1;
        epoch++;
    }

    void beginMagnetic(long now) {
        magneticAt = -1;
        seedCount = 0;
        seedAt = seedSampleAt = -1;
    }

    void suspend(long now) {
        if (calibrated && magneticAt >= 0 && now - magneticAt <= 250) {
            float atMagnetic = relativeAt(magneticAt);
            if (Float.isFinite(atMagnetic)) magneticBias = delta(magnetic + declination, normalize(atMagnetic + offset));
        }
        pauseCorrection(now, "suspended");
        suspendedAt = now;
    }

    void setDeclination(float degrees) {
        if (!Float.isFinite(degrees)) return;
        if (!anchored) { seedCount = 0; seedAt = seedSampleAt = -1; }
        if (anchored && !calibrated) {
            offset = normalize(offset + degrees - declination);
            target = offset;
        }
        declination = degrees;
    }

    void mount(float x, float y, float z, int rotation, long time) {
        if (time <= mountAt || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
        double norm = Math.sqrt(x * x + y * y + z * z);
        if (norm < 5 || norm > 15) return;
        side = (float) ((rotation == 1 ? -y : rotation == 2 ? -x : rotation == 3 ? y : x) / norm);
        forward = (float) ((rotation == 1 ? x : rotation == 2 ? -y : rotation == 3 ? -x : y) / norm);
        normal = (float) (z / norm);
        mountAt = time;
        mountKnown = true;
    }

    /** Physical quiescence, not vehicle speed alone, controls the small stationary drift deadband. */
    void motion(float gyroNorm, float linearAccelerationNorm, long time) {
        if (time <= motionAt) return;
        boolean continuous = motionAt >= 0 && time - motionAt <= 250;
        gyro = gyroNorm;
        acceleration = linearAccelerationNorm;
        motionAt = time;
        if (!continuous || !Float.isFinite(gyro) || gyro > .035f || !Float.isFinite(acceleration) || acceleration > .25f) {
            quietAt = -1;
        } else if (quietAt < 0) quietAt = time;
    }

    boolean relative(float heading, int frameKey, long time) {
        if (!Float.isFinite(heading) || frameKey < 0 || time < startedAt || time <= relativeAt) return false;
        heading = normalize(heading);
        long dt = relativeAt < 0 ? 0 : time - relativeAt;
        boolean frameChange = frame >= 0 && frame != frameKey;
        boolean gap = relativeAt >= 0 && dt > 500;
        float change = Float.isFinite(rawRelative) ? delta(rawRelative, heading) : 0;
        if (yawWindowAt < 0) {
            yawWindowAt = time;
            yawWindowDirection = heading;
            yawWindowRate = 0;
        } else if (time - yawWindowAt >= 250) {
            yawWindowRate = Math.abs(delta(yawWindowDirection, heading)) * 1000f / (time - yawWindowAt);
            yawWindowDirection = heading;
            yawWindowAt = time;
        }
        float physicalLimit = Float.isFinite(gyro) ? Math.max(12, (float) Math.toDegrees(gyro) * dt / 1000f * 3 + 5) : 30;
        boolean resetJump = dt > 0 && dt <= 500 && !frameChange && Math.abs(change) > physicalLimit;
        if (frameChange || gap || resetJump) {
            float held = valueAt(relativeAt);
            epoch++;
            next = count = seedCount = 0;
            seedAt = seedSampleAt = -1;
            frameAt = time;
            pauseCorrection(time, frameChange ? "frame_changed" : "relative_recovery");
            if (resetJump && Float.isFinite(held)) {
                // An unsupported game-reference reset preserves continuity; GNSS will revalidate it.
                relative = heading;
                offset = target = normalize(held - relative);
            } else {
                cacheEligible = frameChange && calibrated && referenceKnown;
                cachedBias = magneticBias;
                anchored = calibrated = false;
                relative = heading;
            }
        } else if (!Float.isFinite(relative)) {
            relative = heading;
            frameAt = time;
        } else {
            driftHeld = anchored && quietAt >= 0 && time - quietAt >= 3000
                    && time - motionAt <= 250 && yawWindowRate < .3f;
            if (!driftHeld) {
                relative = normalize(relative + change);
                if (yawWindowRate >= .3f) quietAt = -1;
            }
        }
        rawRelative = heading;
        relativeAt = time;
        frame = frameKey;
        times[next] = time;
        directions[next] = relative;
        next = (next + 1) % times.length;
        count = Math.min(count + 1, times.length);
        trySeed(time);
        return true;
    }

    void magnetic(float heading, boolean accurate, long time) {
        if (!accurate || !Float.isFinite(heading) || time < startedAt || time <= magneticAt) return;
        magnetic = normalize(heading);
        magneticAt = time;
        trySeed(time);
    }

    private void trySeed(long now) {
        if (anchored || magneticAt < startedAt || now - magneticAt > 250 || now - startedAt < WARMUP_MS) return;
        if (relativeAvailable && (relativeAt < 0 || now - relativeAt > 250 || now - frameAt < WARMUP_MS)) return;
        float pose = relativeAvailable ? relativeAt(magneticAt) : 0;
        if (!Float.isFinite(pose)) return;
        float candidate = delta(pose, magnetic + declination);
        if (seedCount == 0 || Math.abs(delta(seedOrigin, candidate)) > 8) {
            seedCount = 0;
            seedAt = now;
            seedOrigin = candidate;
            seedSin = seedCos = 0;
        }
        if (seedSampleAt >= 0 && now - seedSampleAt < 100) return;
        seedSampleAt = now;
        seedSin += Math.sin(Math.toRadians(candidate));
        seedCos += Math.cos(Math.toRadians(candidate));
        seedCount++;
        if (seedCount < 5 || now - seedAt < 500) return;
        boolean reuseAllowed = cacheEligible && mountKnown
                && side * referenceSide + forward * referenceForward + normal * referenceNormal > .94f;
        float reuse = reuseAllowed ? cachedBias : 0;
        offset = target = normalize((float) Math.toDegrees(Math.atan2(seedSin, seedCos)) + reuse);
        magneticBias = reuse;
        anchored = true;
        calibrated = reuseAllowed;
        offsetAt = now;
        absoluteAt = now;
        holdReason = calibrated ? "cached_alignment_rechecked" : "magnetic_seed";
        if (!relativeAvailable) lastHeading = normalize(offset);
    }

    void gps(float measuredSpeed, float speedAccuracy, float bearing, float bearingAccuracy,
            float accuracy, double lon, double lat, long time, long now) {
        if (time > now) { decision = "future"; return; }
        if (time <= fixAt) { decision = "duplicate_or_old"; return; }
        long interval = fixAt < 0 ? 0 : time - fixAt;
        fixAt = time;
        if (now - time > FIX_AGE_MS || !Float.isFinite(measuredSpeed) || measuredSpeed < 0
                || !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 20
                || (!Float.isNaN(speedAccuracy) && (!Float.isFinite(speedAccuracy) || speedAccuracy < 0 || speedAccuracy > 1.5f))
                || (!Float.isNaN(bearingAccuracy) && (!Float.isFinite(bearingAccuracy) || bearingAccuracy < 0 || bearingAccuracy > 12))
                || !Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) {
            reject(now, "fix_quality");
            return;
        }
        speed = measuredSpeed;
        if (speed < MIN_SPEED_MPS) { reject(now, "stopped_or_slow"); course.clear(); return; }
        relativeAtFix = relativeAvailable ? relativeAt(time) : 0;
        Fix point = new Fix(lon, lat, accuracy, speed, bearing, relativeAtFix, epoch, time);
        if (previousPosition != null && time - previousPosition.time <= 5000) {
            double allowed = Math.max(20, (Math.max(speed, previousPosition.speed) + 6)
                    * (time - previousPosition.time) / 1000. + accuracy + previousPosition.accuracy);
            if (distance(previousPosition, point) > allowed) { reject(now, "position_jump"); return; }
        }
        previousPosition = point;
        Fix first = course.peekFirst(), last = course.peekLast();
        if (interval > 3000 || (last != null && Float.isFinite(bearing) && Float.isFinite(last.bearing)
                && Math.abs(delta(last.bearing, bearing)) > 15)
                || (first != null && Float.isFinite(bearing) && Float.isFinite(first.bearing)
                && Math.abs(delta(first.bearing, bearing)) > 15)) course.clear();
        course.addLast(point);
        while (course.size() > 8 || time - course.peekFirst().time > 8000) course.removeFirst();
        first = course.peekFirst();
        boolean missingBearing = !Float.isFinite(bearing) || Float.isNaN(bearingAccuracy);
        if (course.size() < (missingBearing ? 4 : 3) || time - first.time < (missingBearing ? 2500 : 1500)) {
            reject(now, "confirming_track"); return;
        }
        double path = 0;
        float worstAccuracy = accuracy;
        Fix previous = null;
        for (Fix fix : course) {
            worstAccuracy = Math.max(worstAccuracy, fix.accuracy);
            if (previous != null) path += distance(previous, fix);
            previous = fix;
        }
        displacement = distance(first, point);
        displacementCourse = direction(first, point);
        if (displacement < Math.max(missingBearing ? 20 : 15, worstAccuracy * 3)) {
            reject(now, "confirming_displacement"); return;
        }
        if (path > displacement * 1.15 || (Float.isFinite(bearing) && Math.abs(delta(displacementCourse, bearing)) > 15)) {
            reject(now, "track_direction_mismatch"); return;
        }
        double sin = 0, cos = 0;
        int skip = Math.max(0, course.size() - 3);
        for (Fix fix : course) {
            if (skip-- > 0) continue;
            if (fix.epoch != epoch || !Float.isFinite(fix.relative)) { reject(now, "relative_unavailable"); return; }
            float reference = !missingBearing && Float.isFinite(fix.bearing) ? fix.bearing : displacementCourse;
            float alignment = delta(fix.relative, reference);
            sin += Math.sin(Math.toRadians(alignment)); cos += Math.cos(Math.toRadians(alignment));
        }
        float candidate = (float) Math.toDegrees(Math.atan2(sin, cos));
        skip = Math.max(0, course.size() - 3);
        for (Fix fix : course) {
            if (skip-- > 0) continue;
            float reference = !missingBearing && Float.isFinite(fix.bearing) ? fix.bearing : displacementCourse;
            if (Math.abs(delta(candidate, delta(fix.relative, reference))) > 8) {
                reject(now, "steering_inconsistent"); return;
            }
        }
        stepOffset(now);
        if (!anchored) offset = candidate;
        target = candidate;
        anchored = calibrated = true;
        learningUntil = now + FIX_AGE_MS;
        trustedAt = time;
        absoluteAt = time;
        offsetAt = now;
        magneticReference = magneticAt >= 0 && now - magneticAt < 250 ? magnetic + declination : Float.NaN;
        if (Float.isFinite(magneticReference) && relativeAvailable && Float.isFinite(relativeAtFix)) {
            float atMagnetic = relativeAt(magneticAt);
            if (Float.isFinite(atMagnetic)) magneticBias = delta(magneticReference, normalize(atMagnetic + candidate));
        }
        if (mountKnown && now - mountAt < 500) {
            referenceSide = side; referenceForward = forward; referenceNormal = normal; referenceKnown = true;
        }
        if (!relativeAvailable) { offset = target; lastHeading = normalize(offset); }
        decision = "calibrated";
        holdReason = "gps_alignment";
    }

    private void reject(long now, String reason) { decision = reason; pauseCorrection(now, reason); }
    private void pauseCorrection(long now, String reason) {
        target = offset;
        learningUntil = -1;
        offsetAt = Math.max(offsetAt, now);
        holdReason = reason;
    }
    private void stepOffset(long now) {
        if (offsetAt < 0) { offsetAt = now; return; }
        if (!anchored || learningUntil < 0) { offsetAt = Math.max(offsetAt, now); return; }
        if (now > learningUntil || !ready(now)) { pauseCorrection(now, "alignment_hold"); return; }
        long dt = Math.max(0, Math.min(100, now - offsetAt));
        offsetAt = Math.max(offsetAt, now);
        float difference = delta(offset, target);
        float error = Math.abs(difference);
        // Catch up quickly after GNSS confirmation, retaining the existing gentle finish within 8 degrees.
        float catchup = Math.max(0, error - GPS_CORRECTION_SETTLE_DEG) * GPS_CORRECTION_CATCHUP_GAIN;
        float rate = Math.min(GPS_CORRECTION_DEG_PER_SECOND, Math.max(2, error * 1.5f) + catchup);
        float limit = rate * dt / 1000f;
        offset = normalize(offset + Math.max(-limit, Math.min(limit, difference)));
    }
    float valueAt(long now) {
        stepOffset(now);
        if (ready(now)) lastHeading = normalize((relativeAvailable ? relative : 0) + offset);
        return lastHeading;
    }
    boolean ready(long now) {
        return anchored && (!relativeAvailable ? absoluteAt >= 0 && now >= absoluteAt && now - absoluteAt <= FIX_AGE_MS
                : relativeAt >= 0 && now >= relativeAt
                && now - relativeAt <= RELATIVE_AGE_MS && now - frameAt >= WARMUP_MS);
    }
    private float relativeAt(long time) {
        if (time < frameAt) return Float.NaN;
        long before = -1, after = Long.MAX_VALUE;
        float a = Float.NaN, b = Float.NaN;
        for (int i = 0; i < count; i++) {
            if (times[i] <= time && times[i] > before) { before = times[i]; a = directions[i]; }
            if (times[i] >= time && times[i] < after) { after = times[i]; b = directions[i]; }
        }
        if (before >= 0 && after < Long.MAX_VALUE && after - before <= 250) {
            return before == after ? a : normalize(a + delta(a, b) * (time - before) / (after - before));
        }
        if (before >= 0 && time - before <= 250) return a;
        if (after < Long.MAX_VALUE && after - time <= 250) return b;
        return Float.NaN;
    }
    String sourceAt(long now) {
        if (!anchored && !Float.isFinite(lastHeading)) return "unknown";
        return (relativeAvailable ? calibrated ? "gps_relative" : "magnetic_seed_relative" : "absolute_only")
                + (ready(now) ? "" : "_hold");
    }
    String gpsDecision() { return decision; }
    String holdReason() { return holdReason; }
    boolean calibrated() { return calibrated; }
    float correction() { return delta(0, offset); }
    float targetCorrection() { return delta(0, target); }
    long gpsAge(long now) { return trustedAt < 0 ? -1 : now - trustedAt; }
    boolean learning(long now) { return learningUntil >= now && ready(now); }
    float relativeAtFix() { return relativeAtFix; }
    float relativeDirection() { return relative; }
    boolean driftHeld() { return driftHeld; }
    double displacement() { return displacement; }
    float displacementBearing() { return displacementCourse; }
    static float normalize(float value) { return (value % 360 + 360) % 360; }
    static float delta(float from, float to) { return normalize(to - from + 180) - 180; }
    private static double east(Fix a, Fix b) {
        return (((b.lon - a.lon + 540) % 360) - 180) * 111195 * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
    }
    private static double north(Fix a, Fix b) { return (b.lat - a.lat) * 111195; }
    private static double distance(Fix a, Fix b) { return Math.hypot(east(a, b), north(a, b)); }
    private static float direction(Fix a, Fix b) { return normalize((float) Math.toDegrees(Math.atan2(east(a, b), north(a, b)))); }
    private static final class Fix {
        final double lon, lat;
        final float accuracy, speed, bearing, relative;
        final int epoch;
        final long time;
        Fix(double lon, double lat, float accuracy, float speed, float bearing, float relative, int epoch, long time) {
            this.lon = lon; this.lat = lat; this.accuracy = accuracy; this.speed = speed;
            this.bearing = bearing; this.relative = relative; this.epoch = epoch; this.time = time;
        }
    }
}
