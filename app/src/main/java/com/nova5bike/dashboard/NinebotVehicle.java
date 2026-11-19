package com.nova5bike.dashboard;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

final class NinebotVehicle {
    private static final String PACKAGE = "cn.ninebot.ninebot";
    private static final ComponentName PROVIDER = new ComponentName(PACKAGE,
            "cn.ninebot.device.appWidget.widget.MiniDeviceAppWidget");
    private static final String WIDGET_ID = "widgetId";
    private static final String PERCENT = "percent";
    private static final String READ_AT = "readAt";
    private static final String RANGE = "range";
    private static final long REFRESH_INTERVAL_MS = 5000;
    private static final long OLD_AFTER_MS = 60_000;

    static final class Reading {
        final String range;
        final int percent;
        final long readAt;

        Reading(int percent, String range, long readAt) {
            this.percent = percent;
            this.range = range;
            this.readAt = readAt;
        }

        boolean old() {
            long age = System.currentTimeMillis() - readAt;
            return readAt > 0 && (age < 0 || age >= OLD_AFTER_MS);
        }
    }

    private final Context context;
    private final Handler ui;
    private final Runnable changed;
    private final SharedPreferences prefs;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final Runnable refresh = this::refresh;
    private int widgetId;
    private VehicleView widget;
    private Reading reading;
    private boolean foreground, listening;
    private long nextRefreshAt;

    NinebotVehicle(Context context, Handler ui, Runnable changed) {
        this.context = context;
        this.ui = ui;
        this.changed = changed;
        prefs = context.getSharedPreferences("ninebotBattery", Context.MODE_PRIVATE);
        manager = AppWidgetManager.getInstance(context);
        host = new AppWidgetHost(context, 9109) {
            @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
                try {
                    return new VehicleView(context, context.getPackageManager().getResourcesForApplication(PACKAGE));
                } catch (PackageManager.NameNotFoundException ignored) {
                    return new VehicleView(context, null);
                }
            }
        };
        widgetId = prefs.getInt(WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (prefs.contains("name")) prefs.edit().remove("name").apply();
        reading = new Reading(prefs.getInt(PERCENT, -1), prefs.getString(RANGE, ""), prefs.getLong(READ_AT, 0));
    }

    boolean connected() {
        return widget != null;
    }

    Reading reading() { return reading; }

    boolean available() {
        for (AppWidgetProviderInfo info : manager.getInstalledProvidersForPackage(PACKAGE, null)) {
            if (PROVIDER.equals(info.provider)) return true;
        }
        return false;
    }

    Intent connect() {
        disconnect();
        widgetId = host.allocateAppWidgetId();
        prefs.edit().putInt(WIDGET_ID, widgetId).apply();
        if (manager.bindAppWidgetIdIfAllowed(widgetId, PROVIDER)) {
            openWidget();
            changed.run();
            return null;
        }
        return new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, PROVIDER);
    }

    void bindingFinished() {
        if (widgetInfo() != null) {
            openWidget();
            changed.run();
        } else {
            disconnect();
        }
    }

    void disconnect() {
        stopListening();
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId);
        widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        widget = null;
        nextRefreshAt = 0;
        prefs.edit().clear().apply();
        reading = new Reading(-1, "", 0);
        changed.run();
    }

    void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (foreground) openWidget();
        else stopListening();
    }

    private void stopListening() {
        ui.removeCallbacks(refresh);
        if (listening) host.stopListening();
        listening = false;
    }

    private void openWidget() {
        if (!foreground) return;
        AppWidgetProviderInfo info = widgetInfo();
        if (info == null) return;
        if (!listening) {
            host.startListening();
            listening = true;
            nextRefreshAt = SystemClock.uptimeMillis();
        }
        if (widget == null) {
            widget = (VehicleView) host.createView(context, widgetId, info);
            nextRefreshAt = SystemClock.uptimeMillis() + 1500;
            changed.run();
        }
        scheduleRefresh();
    }

    private AppWidgetProviderInfo widgetInfo() {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
        return info != null && PROVIDER.equals(info.provider) ? info : null;
    }

    private void refresh() {
        if (!listening || widget == null) return;
        RideLog.event("vehicle.refresh_request", "readWallMs", reading.readAt);
        widget.clickRefresh();
        nextRefreshAt = SystemClock.uptimeMillis() + REFRESH_INTERVAL_MS;
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        ui.removeCallbacks(refresh);
        if (listening && widget != null) ui.postAtTime(refresh, nextRefreshAt);
    }

    private final class VehicleView extends AppWidgetHostView {
        private final int vehicle, empty, battery, charging, refreshButton, loading, range, chargingRange,
                rangeUnit;
        private boolean initial = true;

        VehicleView(Context context, Resources resources) {
            super(context);
            vehicle = id(resources, "rlDevice");
            empty = id(resources, "rlEmpty");
            battery = id(resources, "tvBattery");
            charging = id(resources, "tvBatteryCharging");
            refreshButton = id(resources, "rlRefresh");
            loading = id(resources, "pbRefresh");
            range = id(resources, "tvMileage");
            chargingRange = id(resources, "tvMileageCharging");
            rangeUnit = id(resources, "tvMileageUni");
        }

        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            RideLog.event("vehicle.widget_update", "sameWidget", getAppWidgetId() == widgetId,
                    "hasViews", views != null, "vehicleVisible", shown(findViewById(vehicle)),
                    "emptyVisible", shown(findViewById(empty)), "loadingVisible", shown(findViewById(loading)));
            if (getAppWidgetId() != widgetId || views == null || !PACKAGE.equals(views.getPackage())
                    || !shown(findViewById(vehicle))
                    || shown(findViewById(empty)) || shown(findViewById(loading))) return;
            int chargingLevel = level(charging);
            int level = chargingLevel >= 0 ? chargingLevel : level(battery);
            String distance = text(chargingRange);
            if (distance.isEmpty()) distance = text(range);
            String unit = text(rangeUnit);
            String estimated = distance.matches("[0-9]+(?:[.,][0-9]+)?") && !unit.isEmpty()
                    ? distance + " " + unit : "";
            if (level < 0 && estimated.isEmpty()) return;
            boolean cachedTime = initial && reading.readAt > 0;
            long time = cachedTime ? reading.readAt : System.currentTimeMillis();
            initial = false;
            RideLog.event("vehicle.reading", "percent", level, "range", estimated, "readWallMs", time, "isCachedTime", cachedTime);
            reading = new Reading(level, estimated, time);
            prefs.edit().putInt(PERCENT, level).putString(RANGE, estimated).putLong(READ_AT, time).apply();
            changed.run();
        }

        private void clickRefresh() {
            View button = findViewById(refreshButton);
            if (!shown(button) || !button.isEnabled() || !button.hasOnClickListeners()
                    || shown(findViewById(loading))) return;
            // Invoke only the official widget's refresh control, including its original PendingIntent.
            RideLog.event("vehicle.refresh_click");
            button.performClick();
        }

        private int level(int id) {
            String value = text(id);
            if (!value.matches("[0-9]{1,3}\\s*%?")) return -1;
            int result = Integer.parseInt(value.replace("%", "").trim());
            return result <= 100 ? result : -1;
        }

        private String text(int id) {
            View child = findViewById(id);
            return child instanceof TextView && shown(child) ? ((TextView) child).getText().toString().trim() : "";
        }

        private boolean shown(View view) {
            while (view != null && view != this && view.getVisibility() == View.VISIBLE) {
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
            return view == this;
        }
    }

    private static int id(Resources resources, String name) {
        return resources == null ? View.NO_ID : resources.getIdentifier(name, "id", PACKAGE);
    }
}
