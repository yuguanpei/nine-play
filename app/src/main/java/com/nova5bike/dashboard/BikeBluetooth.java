package com.nova5bike.dashboard;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("MissingPermission") // DashboardActivity checks and requests the required permissions.
final class BikeBluetooth {
    private static final long BACKGROUND_LINK_MS = 60_000;
    private static final long UPDATE_QUIET_MS = 15_000;
    private static final String ACCEPTED_INSTALL = "acceptedInstallTime";
    private static final String UPDATE_RECONNECT_HINT = "应用已更新，请手动连接蓝牙设备";
    private static BikeBluetooth shared;
    private final Context context;
    private final Handler ui;
    private final SharedPreferences recoveryPrefs;
    private Listener dashboard;
    private boolean notifications;
    private AppleBle.Media media;
    private String status = "未连接蓝牙设备";
    private final Listener listener = new Listener() {
        @Override public void statusChanged(String value) {
            RideLog.event("bt.status", "state", value, "foreground", foreground, "failures", directFailures,
                    "stalledSetups", stalledSetups, "updateRecovery", updateRecovery);
            status = value;
            if (dashboard != null) dashboard.statusChanged(value);
        }
        @Override public void servicesChanged(boolean ready, AppleBle.Media player) {
            RideLog.event("bt.services", "ancs", ready, "ams", player != null);
            notifications = ready;
            media = player;
            if (dashboard != null) dashboard.servicesChanged(ready, player);
        }
        @Override public void notificationReceived(AppleBle.Notice notice) {
            if (dashboard != null) dashboard.notificationReceived(notice);
        }
        @Override public void notificationRemoved(int id) { if (dashboard != null) dashboard.notificationRemoved(id); }
        @Override public void servicesDisconnected(boolean setupStalled) {
            if (dashboard != null) dashboard.servicesDisconnected(setupStalled);
        }
    };
    private BluetoothDevice directPhone;
    private IPhoneServices phoneServices;
    private boolean foreground;
    private int directFailures;
    private int stalledSetups;
    private long connectAfter;
    private long installedAt;
    private boolean updateRecovery, manualRecoveryAttempt;
    private final Runnable retryDirect = this::openDirectConnection;
    private final Runnable releaseInBackground = () -> {
        if (!foreground) disconnectPhoneServices();
    };

    interface Listener extends IPhoneServices.Listener {
        void statusChanged(String status);
    }

    static BikeBluetooth shared(Context context) {
        if (shared == null) shared = new BikeBluetooth(context.getApplicationContext());
        return shared;
    }

    private BikeBluetooth(Context context) {
        this.context = context;
        ui = new Handler(Looper.getMainLooper());
        recoveryPrefs = context.getSharedPreferences("phoneBluetooth", Context.MODE_PRIVATE);
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            installedAt = info.lastUpdateTime;
            updateRecovery = needsUpdateRecovery(info.firstInstallTime, installedAt,
                    recoveryPrefs.getLong(ACCEPTED_INSTALL, 0));
            long age = Math.max(0, System.currentTimeMillis() - installedAt);
            if (updateRecovery && age < UPDATE_QUIET_MS) {
                connectAfter = SystemClock.uptimeMillis() + UPDATE_QUIET_MS - age;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        if (updateRecovery) status = UPDATE_RECONNECT_HINT;
    }

    static boolean needsUpdateRecovery(long firstInstalled, long updated, long accepted) {
        return updated > firstInstalled && accepted != updated;
    }

    void attach(Listener listener) {
        dashboard = listener;
        listener.servicesChanged(notifications, media);
        if (enabled() || updateRecovery) listener.statusChanged(status);
        setForeground(true);
    }

    void detach(Listener listener) {
        if (dashboard != listener) return;
        dashboard = null;
        setForeground(false);
    }

    boolean enabled() { return directPhone != null; }
    boolean requiresManualReconnectAfterUpdate() { return updateRecovery; }
    boolean linked() { return phoneServices != null && phoneServices.connected(); }
    String connectedName() {
        if (!linked()) return "";
        BluetoothDevice device = directPhone;
        try {
            String name = device == null ? null : device.getName();
            return name == null ? "" : name.trim();
        } catch (SecurityException ignored) {
            return "";
        }
    }
    boolean command(int command) { return phoneServices != null && phoneServices.command(command); }

    List<BluetoothDevice> pairedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter != null && adapter.isEnabled()) devices.addAll(adapter.getBondedDevices());
        } catch (SecurityException ignored) {}
        return devices;
    }

    boolean canAutoConnect(BluetoothDevice device) {
        try {
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) return false;
            BluetoothClass kind = device.getBluetoothClass();
            return kind != null && (kind.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE
                    || kind.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    BluetoothDevice connectedSource() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null) return null;
        try {
            for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
                if (canAutoConnect(device)) return device;
            }
            for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)) {
                if (canAutoConnect(device)) return device;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    void connectDevice(BluetoothDevice device) {
        if (updateRecovery) return;
        stop();
        directPhone = device;
        openDirectConnection();
    }

    void connectDeviceManually(BluetoothDevice device) {
        stop();
        manualRecoveryAttempt = updateRecovery;
        directPhone = device;
        openDirectConnection();
    }

    private void setForeground(boolean foreground) {
        RideLog.event("bt.foreground", "foreground", foreground);
        this.foreground = foreground;
        ui.removeCallbacks(releaseInBackground);
        ui.removeCallbacks(retryDirect);
        if (!foreground && updateRecovery) {
            manualRecoveryAttempt = false;
            listener.statusChanged(UPDATE_RECONNECT_HINT);
        }
        if (foreground) {
            if (directPhone != null && phoneServices == null) openDirectConnection();
        } else if (directPhone != null && phoneServices != null) {
            if (notifications || media != null) ui.postDelayed(releaseInBackground, BACKGROUND_LINK_MS);
            else disconnectPhoneServices();
        }
    }

    void retryPhone() {
        if (!enabled() || !foreground) return;
        ui.removeCallbacks(retryDirect);
        disconnectPhoneServices();
        directFailures = 0;
        manualRecoveryAttempt = updateRecovery;
        openDirectConnection();
    }

    void radioChanged(boolean enabled) {
        if (!enabled()) return;
        if (enabled) {
            if (phoneServices == null && !updateRecovery) retryPhone();
        } else {
            manualRecoveryAttempt = false;
            ui.removeCallbacks(retryDirect);
            disconnectPhoneServices();
            listener.statusChanged("系统蓝牙已关闭");
        }
    }

    private void openDirectConnection() {
        RideLog.event("bt.connect_attempt", "failures", directFailures, "updateRecovery", updateRecovery,
                "waitMs", Math.max(0, connectAfter - SystemClock.uptimeMillis()), "manual", manualRecoveryAttempt);
        BluetoothDevice device = directPhone;
        if (!foreground || device == null || phoneServices != null) return;
        if (updateRecovery && !manualRecoveryAttempt) return;
        if (SystemClock.uptimeMillis() < connectAfter) {
            if (updateRecovery) listener.statusChanged("等待旧蓝牙连接释放后再连接");
            ui.removeCallbacks(retryDirect);
            ui.postAtTime(retryDirect, connectAfter);
            return;
        }
        manualRecoveryAttempt = false;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                listener.statusChanged("等系统蓝牙恢复后自动连接");
                scheduleDirectRetry();
                return;
            }
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                directPhone = null;
                listener.statusChanged("请先在系统设置中配对设备");
                return;
            }
        } catch (SecurityException ignored) {
            directPhone = null;
            listener.statusChanged("请授予蓝牙权限");
            return;
        }
        phoneServices = new IPhoneServices(ui, new IPhoneServices.Listener() {
            @Override public void servicesChanged(boolean notifications, AppleBle.Media media) {
                if (notifications || media != null) {
                    directFailures = stalledSetups = 0;
                    acceptUpdatedConnection();
                }
                listener.servicesChanged(notifications, media);
            }

            @Override public void notificationReceived(AppleBle.Notice notice) {
                listener.notificationReceived(notice);
            }

            @Override public void notificationRemoved(int id) {
                listener.notificationRemoved(id);
            }

            @Override public void servicesDisconnected(boolean setupStalled) {
                phoneServices = null;
                if (setupStalled) stalledSetups++;
                if (!updateRecovery) listener.statusChanged("正在重新连接设备");
                scheduleDirectRetry();
            }
        });
        listener.statusChanged("正在连接蓝牙设备");
        // Give the platform's availability-driven reconnect a turn after repeated direct failures.
        boolean trySystemTransport = !updateRecovery && Build.VERSION.SDK_INT <= 30
                && stalledSetups >= 2 && directFailures % 2 == 0;
        boolean waitForAvailable = !updateRecovery && !trySystemTransport
                && directFailures >= 2 && directFailures % 2 == 0;
        int transport = trySystemTransport ? BluetoothDevice.TRANSPORT_AUTO : BluetoothDevice.TRANSPORT_LE;
        if (trySystemTransport) Log.d("BikePhoneBle", "Retrying with Android's transport selection");
        if (!phoneServices.connect(context, device, waitForAvailable,
                false, transport)) {
            disconnectPhoneServices();
            listener.statusChanged("正在重新连接设备");
            scheduleDirectRetry();
        }
    }

    private void scheduleDirectRetry() {
        RideLog.event("bt.retry", "failures", directFailures, "stalledSetups", stalledSetups,
                "blockedByUpdate", updateRecovery, "foreground", foreground);
        ui.removeCallbacks(retryDirect);
        if (updateRecovery) {
            manualRecoveryAttempt = false;
            connectAfter = Math.max(connectAfter, SystemClock.uptimeMillis() + UPDATE_QUIET_MS);
            listener.statusChanged("更新后连接未完成，已停止自动重试，请稍后手动重连");
            return;
        }
        if (foreground && directPhone != null) {
            long delay = 2_000L << Math.min(directFailures++, 2);
            ui.postDelayed(retryDirect, delay);
        }
    }

    private void acceptUpdatedConnection() {
        if (!updateRecovery) return;
        updateRecovery = false;
        manualRecoveryAttempt = false;
        recoveryPrefs.edit().putLong(ACCEPTED_INSTALL, installedAt).apply();
    }

    void stop() {
        manualRecoveryAttempt = false;
        directPhone = null;
        directFailures = 0;
        stalledSetups = 0;
        ui.removeCallbacks(retryDirect);
        ui.removeCallbacks(releaseInBackground);
        disconnectPhoneServices();
    }

    private void disconnectPhoneServices() {
        RideLog.event("bt.release", "linked", linked(), "foreground", foreground);
        IPhoneServices previous = phoneServices;
        phoneServices = null;
        if (previous != null) {
            connectAfter = Math.max(connectAfter, SystemClock.uptimeMillis()
                    + (updateRecovery ? UPDATE_QUIET_MS : 1000));
            previous.close();
        }
    }

}
