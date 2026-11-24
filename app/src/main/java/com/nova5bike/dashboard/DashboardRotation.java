package com.nova5bike.dashboard;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.Surface;

/** Rechecks physical orientation on every foreground entry, respecting the system rotation lock. */
final class DashboardRotation extends OrientationEventListener {
    private final Activity activity;
    private final Handler handler;
    private final ContentObserver settingsObserver;
    private final LandscapeRotation rotation = new LandscapeRotation();
    private final boolean naturalLandscape;
    private boolean running, automatic;
    private int physicalDegrees = ORIENTATION_UNKNOWN;
    private final Runnable settleRotation = this::settlePhysicalRotation;

    DashboardRotation(Activity activity, Handler handler) {
        super(activity, SensorManager.SENSOR_DELAY_NORMAL);
        this.activity = activity;
        this.handler = handler;
        int display = displayRotation();
        boolean landscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        naturalLandscape = landscape == (display == Surface.ROTATION_0 || display == Surface.ROTATION_180);
        settingsObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { refreshPolicy(); }
        };
    }

    void start() {
        if (running) return;
        running = true;
        activity.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsObserver);
        refreshPolicy();
        enable();
    }

    void stop() {
        if (!running) return;
        running = false;
        disable();
        handler.removeCallbacks(settleRotation);
        activity.getContentResolver().unregisterContentObserver(settingsObserver);
        rotation.reset();
    }

    private void refreshPolicy() {
        if (!running) return;
        automatic = Settings.System.getInt(activity.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        rotation.reset();
        handler.removeCallbacks(settleRotation);
        request(automatic ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : orientation(displayRotation()));
        RideLog.event("display.rotation_policy", "automatic", automatic, "rotation", displayRotation());
    }

    @Override public void onOrientationChanged(int degrees) {
        if (!running || !automatic) return;
        physicalDegrees = degrees;
        handler.removeCallbacks(settleRotation);
        int selected = rotation.update(degrees, naturalLandscape, SystemClock.elapsedRealtime());
        if (selected >= 0) request(orientation(selected));
        // OrientationEventListener may report only once when the phone is already stationary.
        else if (degrees != ORIENTATION_UNKNOWN) handler.postDelayed(settleRotation, LandscapeRotation.STABLE_MS);
    }

    private void settlePhysicalRotation() {
        if (!running || !automatic) return;
        int selected = rotation.update(physicalDegrees, naturalLandscape, SystemClock.elapsedRealtime());
        if (selected >= 0) request(orientation(selected));
    }

    private int displayRotation() { return activity.getWindowManager().getDefaultDisplay().getRotation(); }

    private int orientation(int display) {
        int reverse = naturalLandscape ? Surface.ROTATION_180 : Surface.ROTATION_270;
        return display == reverse ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    private void request(int orientation) {
        if (activity.getRequestedOrientation() == orientation) return;
        RideLog.event("display.rotation_request", "orientation", orientation, "rotation", displayRotation());
        activity.setRequestedOrientation(orientation);
    }
}
