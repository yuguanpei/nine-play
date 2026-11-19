package com.nova5bike.dashboard;

import android.location.Location;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import java.util.function.IntSupplier;

final class RideSensors implements SensorEventListener {
    interface Listener {
        void phoneHeading(float degrees);
        void phoneVibration(float metersPerSecondSquared, long time);
    }

    private final SensorManager manager;
    private final IntSupplier displayRotation;
    private final Handler handler;
    private final Listener listener;
    private final float[] compassMatrix = new float[9];
    private final float[] magneticMatrix = new float[9], gravity = new float[3], magnetic = new float[3];
    private final Runnable compassCheck = this::checkCompass;
    private final Runnable compassRestart = this::restartCompass;
    private final VibrationReading vibration = new VibrationReading();
    private final HeadingReading headingReading = new HeadingReading();
    private static final long COMPASS_REFRESH_MS = 5_000;
    private boolean running, recoveringHeading;
    private long refreshAt, registeredAt, refreshStartedAt, stoppedAt = -1;
    private float gyroRate, peakGyroRate, headingBeforeRefresh;
    private final float[] rotationVector = new float[5];
    private final float[] gyroValues = new float[3];
    private Sensor compass;
    private Sensor gravitySensor, magneticSensor, gyroscope, vibrationSensor;
    private int compassType, loggedRotation = -1;
    private int compassAccuracy, magneticAccuracy;
    private long gravityAt = -1, magneticAt = -1, gyroAt = -1, quietSince = -1;
    private long speedAt = -1, headingAt = -1;
    private float measuredSpeed = Float.NaN, fusedHeading = Float.NaN;
    private Location declinationFix;
    private long compassAt;
    private float reportedHeading = Float.NaN;

    RideSensors(SensorManager manager, IntSupplier displayRotation, Handler handler, Listener listener) {
        this.manager = manager;
        this.displayRotation = displayRotation;
        this.handler = handler;
        this.listener = listener;
    }

    void start() {
        if (running || manager == null) return;
        running = true;
        refreshAt = SystemClock.elapsedRealtime();
        boolean retainedAlignment = stoppedAt >= 0 && refreshAt - stoppedAt <= 30_000;
        if (!retainedAlignment) headingReading.reset();
        RideLog.event("sensors.start", "refreshMs", COMPASS_REFRESH_MS, "periodicRestart", true,
                "retainedAlignment", retainedAlignment, "primary", "phone", "gpsRole", "auxiliary",
                "maxGpsCorrectionDeg", HeadingReading.MAX_GPS_CORRECTION_DEG,
                "maxGpsDifferenceDeg", HeadingReading.MAX_GPS_DIFFERENCE_DEG,
                "maxLargeGpsCorrectionDeg", HeadingReading.MAX_LARGE_GPS_CORRECTION_DEG,
                "maxLargeGpsDifferenceDeg", HeadingReading.MAX_LARGE_GPS_DIFFERENCE_DEG,
                "gpsCorrectionDegPerSecond", HeadingReading.GPS_CORRECTION_DEG_PER_SECOND);
        subscribeSensors(false);
        if (compass != null) handler.postDelayed(compassCheck, 100);
    }

    private void subscribeSensors(boolean retainRecentHeading) {
        subscribeCompass(retainRecentHeading);
        vibrationSensor = subscribe(Sensor.TYPE_LINEAR_ACCELERATION, 25_000);
        if (vibrationSensor == null) vibrationSensor = subscribe(Sensor.TYPE_ACCELEROMETER, 25_000);
        if (compass != null) {
            gravitySensor = subscribe(Sensor.TYPE_GRAVITY, 50_000);
            if (gravitySensor == null) gravitySensor = vibrationSensor != null
                    && vibrationSensor.getType() == Sensor.TYPE_ACCELEROMETER ? vibrationSensor
                    : subscribe(Sensor.TYPE_ACCELEROMETER, 50_000);
            magneticSensor = subscribe(Sensor.TYPE_MAGNETIC_FIELD, 50_000);
            gyroscope = subscribe(Sensor.TYPE_GYROSCOPE, 25_000);
        }
        RideLog.event("sensors.subscribed", "compassType", compassType, "compass", compass != null,
                "gravity", gravitySensor != null, "magnetic", magneticSensor != null, "gyro", gyroscope != null);
    }

    private void subscribeCompass(boolean retainRecentHeading) {
        registeredAt = SystemClock.elapsedRealtime();
        headingReading.beginSensors(registeredAt, retainRecentHeading);
        // Use a north-referenced rotation matrix, never a drifting game rotation vector.
        for (int type : new int[]{Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                Sensor.TYPE_ORIENTATION}) {
            Sensor candidate = manager.getDefaultSensor(type);
            if (candidate != null && manager.registerListener(this, candidate, 50_000, handler)) {
                compass = candidate;
                compassType = type;
                break;
            }
        }
    }

    void stop() {
        if (!running) return;
        RideLog.event("sensors.stop", "heading", reportedHeading, "rotation", displayRotation.getAsInt());
        running = false;
        stoppedAt = SystemClock.elapsedRealtime();
        recoveringHeading = false;
        handler.removeCallbacks(compassCheck);
        handler.removeCallbacks(compassRestart);
        manager.unregisterListener(this);
        compass = null;
        gravitySensor = magneticSensor = gyroscope = vibrationSensor = null;
        gravityAt = magneticAt = gyroAt = quietSince = headingAt = speedAt = -1;
        measuredSpeed = fusedHeading = Float.NaN;
        compassAccuracy = magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
        compassAt = 0;
        reportedHeading = Float.NaN;
        vibration.reset();
    }

    float vibration(long now) { return vibration.valueAt(now); }

    void setSpeed(float metersPerSecond, long now) {
        measuredSpeed = metersPerSecond;
        speedAt = now;
    }

    void calibrateGps(Location fix, float validatedSpeed, long now) {
        long fixTime = fix.getElapsedRealtimeNanos() / 1_000_000;
        if (fix.hasAccuracy() && fix.getAccuracy() <= 50 && now >= fixTime && now - fixTime <= 1500
                && (declinationFix == null || fix.getTime() - declinationFix.getTime() >= 86_400_000L
                || declinationFix.distanceTo(fix) >= 10_000)) {
            float altitude = fix.hasAltitude() && Double.isFinite(fix.getAltitude()) ? (float) fix.getAltitude() : 0;
            float degrees = new GeomagneticField((float) fix.getLatitude(), (float) fix.getLongitude(),
                    altitude, fix.getTime()).getDeclination();
            headingReading.setDeclination(degrees);
            declinationFix = new Location(fix);
            RideLog.event("heading.declination", "degrees", degrees);
        }
        float bearing = fix.hasBearing() ? fix.getBearing() : Float.NaN;
        float bearingAccuracy = fix.hasBearingAccuracy() ? fix.getBearingAccuracyDegrees() : Float.NaN;
        if (fix.hasBearingAccuracy() && !Float.isFinite(bearingAccuracy)) bearingAccuracy = Float.POSITIVE_INFINITY;
        float speedAccuracy = fix.hasSpeedAccuracy() ? fix.getSpeedAccuracyMetersPerSecond() : Float.NaN;
        float positionAccuracy = fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN;
        headingReading.gps(validatedSpeed, speedAccuracy, bearing, bearingAccuracy, positionAccuracy,
                fix.getLongitude(), fix.getLatitude(), fix.getElapsedRealtimeNanos() / 1_000_000, now,
                fresh(gyroAt, now) ? gyroRate : Float.NaN);
        emitHeading(headingReading.valueAt(now), now);
        RideLog.event("heading.gps", "decision", headingReading.gpsDecision(), "speedMps", validatedSpeed,
                "bearing", bearing, "bearingAccuracyDeg", bearingAccuracy, "positionAccuracyM", positionAccuracy,
                "targetCorrectionDeg", headingReading.targetCorrection(), "appliedCorrectionDeg", headingReading.correction());
    }

    private Sensor subscribe(int type, int period) {
        Sensor sensor = manager.getDefaultSensor(type);
        return sensor != null && manager.registerListener(this, sensor, period, handler) ? sensor : null;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        long time = event.timestamp / 1_000_000;
        if (time < registeredAt) return;
        int rotation = displayRotation.getAsInt();
        if (rotation != loggedRotation) {
            RideLog.event("sensors.display_rotation", "before", loggedRotation, "after", rotation,
                    "headingBefore", reportedHeading, "sensorTimeMs", time);
            loggedRotation = rotation;
        }
        if (event.sensor == gravitySensor) {
            float weight = event.sensor.getType() == Sensor.TYPE_GRAVITY || gravityAt < 0 ? 1f
                    : 1f - (float) Math.exp(-Math.max(0, time - gravityAt) / 250f);
            for (int i = 0; i < 3; i++) gravity[i] += (event.values[i] - gravity[i]) * weight;
            gravityAt = time;
        }
        if (event.sensor == magneticSensor) {
            System.arraycopy(event.values, 0, magnetic, 0, 3);
            magneticAt = time;
            magneticAccuracy = event.accuracy;
        }
        if (event.sensor == gyroscope) {
            float[] value = event.values;
            System.arraycopy(value, 0, gyroValues, 0, 3);
            double turn = Math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
            gyroRate = (float) turn;
            if (Float.isFinite(gyroRate)) peakGyroRate = Math.max(peakGyroRate, gyroRate);
            if (!Double.isFinite(turn) || turn > .025 || gyroAt < 0 || time - gyroAt > 350) quietSince = -1;
            else if (quietSince < 0) quietSince = time;
            gyroAt = time;
        }
        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
            case Sensor.TYPE_ACCELEROMETER:
                if (event.sensor == vibrationSensor) {
                    vibration.record(event.values[0], event.values[1], event.values[2], time);
                    listener.phoneVibration(vibration.valueAt(time), time);
                }
                break;
            case Sensor.TYPE_ORIENTATION:
                if (event.sensor != compass) break;
                compassAccuracy = event.accuracy;
                fusedHeading = event.values[0] + displayRotation.getAsInt() * 90f;
                headingAt = time;
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) publishHeading(Float.NaN, time);
                else publishHeading(fusedHeading, time);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                if (event.sensor != compass) break;
                java.util.Arrays.fill(rotationVector, Float.NaN);
                System.arraycopy(event.values, 0, rotationVector, 0, Math.min(5, event.values.length));
                compassAccuracy = event.accuracy;
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                        || (event.values.length >= 5 && (!Float.isFinite(event.values[4])
                        || event.values[4] > Math.toRadians(25)))) {
                    publishHeading(Float.NaN, time);
                    fusedHeading = Float.NaN;
                    break;
                }
                SensorManager.getRotationMatrixFromVector(compassMatrix, event.values);
                fusedHeading = mountedHeading(compassMatrix, displayRotation.getAsInt());
                headingAt = time;
                publishHeading(fusedHeading, time);
                break;
            default:
                break;
        }
    }

    private void checkCompass() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        float magneticHeading = Float.NaN;
        double strength = Math.sqrt(magnetic[0] * magnetic[0] + magnetic[1] * magnetic[1] + magnetic[2] * magnetic[2]);
        if (fresh(gravityAt, now) && fresh(magneticAt, now)
                && SensorManager.getRotationMatrix(magneticMatrix, null, gravity, magnetic)) {
            magneticHeading = mountedHeading(magneticMatrix, displayRotation.getAsInt());
        }
        if (RideLog.due("sensors.sample", peakGyroRate > .03f || recoveringHeading ? 100 : 500)) {
            RideLog.event("sensors.sample", "fused", fusedHeading, "reported", reportedHeading,
                    "magneticHeading", magneticHeading, "compassAccuracy", compassAccuracy,
                    "source", headingReading.sourceAt(now), "gpsDecision", headingReading.gpsDecision(),
                    "holding", headingReading.holding(),
                    "fusedMagneticDifferenceDeg", Float.isFinite(fusedHeading) && Float.isFinite(magneticHeading)
                            ? HeadingReading.delta(fusedHeading, magneticHeading) : Float.NaN,
                    "correctionDeg", headingReading.correction(), "targetCorrectionDeg", headingReading.targetCorrection(),
                    "gpsCourseAgeMs", headingReading.gpsAge(now), "sensorReady", headingReading.ready(now),
                    "magneticAccuracy", magneticAccuracy, "magneticStrengthUt", strength,
                    "quaternion", RideLog.vector(rotationVector),
                    "gravity", RideLog.vector(gravity), "magnetic", RideLog.vector(magnetic),
                    "gyro", RideLog.vector(gyroValues), "gyroRate", gyroRate, "peakGyroRate", peakGyroRate,
                    "rotation", displayRotation.getAsInt(), "quietMs", quietSince < 0 ? -1 : now - quietSince,
                    "headingAgeMs", headingAt < 0 ? -1 : now - headingAt,
                    "magneticAgeMs", magneticAt < 0 ? -1 : now - magneticAt,
                    "gravityAgeMs", gravityAt < 0 ? -1 : now - gravityAt,
                    "gyroAgeMs", gyroAt < 0 ? -1 : now - gyroAt,
                    "speedMps", measuredSpeed, "speedAgeMs", speedAt < 0 ? -1 : now - speedAt,
                    "vibrationMps2", vibration.valueAt(now),
                    "recovering", recoveringHeading, "nextRefreshMs", Math.max(0, COMPASS_REFRESH_MS - (now - refreshAt)));
            peakGyroRate = 0;
        }
        emitHeading(headingReading.valueAt(now), now);
        if (now - refreshAt >= COMPASS_REFRESH_MS) {
            refreshAt = now;
            boolean stale = headingAt >= 0 ? now - headingAt > 3000 : now - registeredAt > 3000;
            RideLog.event("compass.health", "stale", stale, "source", headingReading.sourceAt(now),
                    "fused", fusedHeading, "magneticHeading", magneticHeading,
                    "reported", reportedHeading, "sensorReady", headingReading.ready(now));
            if (compass != null) {
                refreshStartedAt = now;
                headingBeforeRefresh = reportedHeading;
                recoveringHeading = true;
                RideLog.event("compass.refresh_start", "reason", "periodic_5s", "stale", stale, "before", reportedHeading,
                        "fused", fusedHeading, "magneticHeading", magneticHeading, "rotation", displayRotation.getAsInt(),
                        "restartScope", "all_sensors");
                // Other subscribed motion channels can keep Huawei's fusion running across a compass-only restart.
                manager.unregisterListener(this);
                compass = null;
                gravitySensor = magneticSensor = gyroscope = vibrationSensor = null;
                gravityAt = magneticAt = gyroAt = quietSince = -1;
                compassAccuracy = magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
                fusedHeading = Float.NaN;
                headingAt = -1;
                handler.postDelayed(compassRestart, 200);
            }
        }
        handler.postDelayed(compassCheck, 100);
    }

    private void restartCompass() {
        if (!running) return;
        subscribeSensors(true);
        if (compass == null) {
            manager.unregisterListener(this);
            gravitySensor = magneticSensor = gyroscope = vibrationSensor = null;
            RideLog.event("compass.subscribe_failed", "retryMs", 1000);
            publishHeading(Float.NaN, SystemClock.elapsedRealtime());
            handler.postDelayed(compassRestart, 1000);
        }
    }

    private static boolean fresh(long time, long now) { return time >= 0 && now >= time && now - time <= 350; }

    private void publishHeading(float heading, long time) {
        long now = SystemClock.elapsedRealtime();
        headingReading.sensor(heading, fresh(gyroAt, now) ? gyroRate : Float.NaN,
                Float.isFinite(heading) && compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM, time);
        float result = headingReading.valueAt(now);
        if (headingReading.ready(now) && !Float.isFinite(reportedHeading)) {
            RideLog.event("compass.first_heading", "heading", result, "raw", heading, "accuracy", compassAccuracy,
                    "sinceSubscribeMs", now - registeredAt, "sensorTimeMs", time, "rotation", displayRotation.getAsInt());
        }
        if (recoveringHeading && headingReading.ready(now)) {
            RideLog.event("compass.refresh_result", "phase", "stable", "before", headingBeforeRefresh,
                    "after", result, "raw", heading, "quaternion", RideLog.vector(rotationVector),
                    "gravity", RideLog.vector(gravity), "magnetic", RideLog.vector(magnetic),
                    "gapMs", now - refreshStartedAt, "accuracy", compassAccuracy, "rotation", displayRotation.getAsInt());
            recoveringHeading = false;
        }
        emitHeading(result, now);
    }

    private void emitHeading(float heading, long time) {
        if (Float.isFinite(heading)) heading = HeadingReading.normalize(heading);
        if (Float.isFinite(heading) == Float.isFinite(reportedHeading)) {
            if (!Float.isFinite(heading) || time - compassAt < 100) return;
        }
        compassAt = time;
        reportedHeading = heading;
        listener.phoneHeading(heading);
    }

    static float mountedHeading(float[] matrix, int rotation) {
        // Project the dashboard's forward edge onto the ground, accounting for mounting tilt.
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int column = landscape ? 0 : 1;
        float east = matrix[column], north = matrix[column + 3];
        float direction = rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270 ? -1 : 1;
        if (Math.hypot(east, north) < .35) {
            // With the top edge nearly vertical, use the perpendicular horizontal edge instead.
            column = landscape ? 1 : 0;
            east = matrix[column];
            north = matrix[column + 3];
            if (Math.hypot(east, north) < .35) return Float.NaN;
            float offset = landscape ? 90 : -90;
            return ((float) Math.toDegrees(Math.atan2(east, north)) + offset + (direction < 0 ? 180 : 0)
                    + 360) % 360;
        }
        return ((float) Math.toDegrees(Math.atan2(direction * east, direction * north)) + 360) % 360;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        RideLog.event("sensors.accuracy", "type", sensor.getType(), "accuracy", accuracy);
        if (sensor == magneticSensor) magneticAccuracy = accuracy;
        if (running && sensor == compass && accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            compassAccuracy = accuracy;
            fusedHeading = Float.NaN;
            publishHeading(Float.NaN, android.os.SystemClock.elapsedRealtime());
        }
    }
}
