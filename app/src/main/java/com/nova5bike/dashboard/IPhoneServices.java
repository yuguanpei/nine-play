package com.nova5bike.dashboard;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.Consumer;

@SuppressLint("MissingPermission") // The dashboard checks the Bluetooth permissions before starting the session.
final class IPhoneServices extends BluetoothGattCallback {
    private static final String TAG = "BikePhoneBle";
    private static final UUID ANCS = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0");
    private static final UUID SOURCE = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd");
    private static final UUID CONTROL = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9");
    private static final UUID DATA = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb");
    static final UUID AMS = UUID.fromString("89d3502b-0f36-433a-8ef4-c502ad55f8dc");
    private static final UUID REMOTE = UUID.fromString("9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2");
    private static final UUID ENTITY = UUID.fromString("2f7cabce-808d-411f-9a0c-bb92ba96c102");
    private static final UUID ATTRIBUTE = UUID.fromString("c6b2f38c-23ab-46d8-a6ab-a3a870bbd5d7");
    private static final UUID GATT_SERVICE = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int ANCS_ADDED = 0, ANCS_REMOVED = 2, ANCS_PREEXISTING = 1 << 2;
    private static final int PREFERRED_MTU = 517;
    private static final long MTU_WAIT_MS = 1800;
    private static final long GATT_CLOSE_GRACE_MS = 750;
    private static final int MAX_PENDING_NOTICES = 48;

    interface Listener {
        void servicesChanged(boolean notifications, AppleBle.Media media);
        void notificationReceived(AppleBle.Notice notice);
        void notificationRemoved(int id);
        void servicesDisconnected(boolean setupStalled);
    }

    private final Handler ui;
    private final Listener listener;
    private final ArrayDeque<Operation> operations = new ArrayDeque<>();
    private final ArrayDeque<Integer> notices = new ArrayDeque<>();
    private final ArrayDeque<Integer> fullMediaAttributes = new ArrayDeque<>();
    private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
    private BluetoothGatt gatt, closingGatt;
    private Operation pending;
    private BluetoothGattCharacteristic notificationControl, mediaRemote, mediaAttribute;
    private Integer noticeId;
    private boolean noticeRemoved, connected, notificationsReady, mediaReady, refreshPending, servicesInvalidated;
    private boolean serviceChangeSubscribed;
    private boolean mtuRequested, waitingForMtu, deferredMediaRead;
    private int readingKey = -1;
    private boolean readSuperseded;
    private long commandIssuedAt = -1;
    private boolean mediaTrackRegistered, mediaPlayerRegistered;
    private boolean refreshCache;
    private boolean discoveredServices, foundPhoneServices;
    private long connectedAt = -1, lastPeerAt = -1;
    private int disconnectStatus = -1;
    private final Runnable inspectLink = this::inspectLink;
    private AppleBle.Media media = new AppleBle.Media();
    private final Runnable retryServices = () -> discoverWhenIdle();
    private final Runnable firstDiscovery = this::discoverWhenIdle;
    private final Runnable requestTimedOut = () -> finishNotice();
    private final Runnable operationTimedOut = () -> {
        RideLog.event("bt.timeout", "connected", connected, "ancs", notificationsReady, "ams", mediaReady,
                "queued", operations.size(), "pending", pending == null ? "none"
                        : pending.characteristic == null ? "discovery" : pending.characteristic.getUuid().toString());
        connectionLost();
    };
    private final Runnable mtuTimedOut = this::finishMtuNegotiation;
    private final Runnable finishClose = this::closeGatt;
    private final Runnable setupTimedOut = () -> {
        if (!notificationsReady && !mediaReady) {
            RideLog.event("gatt.setup_timeout", "queued", operations.size());
            Log.d(TAG, "No phone services subscribed before the setup deadline");
            connectionLost();
        }
    };

    IPhoneServices(Handler ui, Listener listener) {
        this.ui = ui;
        this.listener = listener;
    }

    boolean connect(Context context, BluetoothDevice device, boolean waitForAvailable, boolean refreshCache, int transport) {
        RideLog.event("gatt.connect", "autoConnect", waitForAvailable, "refreshCache", refreshCache, "transport", transport);
        // Direct LE uses the system pairing; the share receiver can also reuse its existing link.
        this.refreshCache = refreshCache;
        try {
            gatt = device.connectGatt(context, waitForAvailable, this, transport);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ignored) {
            return false;
        }
        if (gatt != null) ui.postDelayed(operationTimedOut, waitForAvailable ? 24_000 : 15_000);
        return gatt != null;
    }

    void close() {
        BluetoothGatt closing = gatt;
        gatt = null;
        connected = false;
        mtuRequested = false;
        waitingForMtu = false;
        refreshPending = false;
        servicesInvalidated = false;
        serviceChangeSubscribed = false;
        pending = null;
        operations.clear();
        resetServices();
        ui.removeCallbacks(operationTimedOut);
        ui.removeCallbacks(inspectLink);
        ui.removeCallbacks(mtuTimedOut);
        ui.removeCallbacks(retryServices);
        ui.removeCallbacks(firstDiscovery);
        ui.removeCallbacks(setupTimedOut);
        if (closing != null) {
            closingGatt = closing;
            try {
                closing.disconnect();
                ui.postDelayed(finishClose, GATT_CLOSE_GRACE_MS);
            } catch (IllegalStateException | SecurityException ignored) {
                closeGatt();
            }
        }
    }

    private void closeGatt() {
        ui.removeCallbacks(finishClose);
        if (closingGatt == null) return;
        try { closingGatt.close(); }
        catch (IllegalStateException | SecurityException ignored) {}
        closingGatt = null;
    }

    @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
        RideLog.event("gatt.connection", "status", status, "newState", newState, "currentClient", source == gatt);
        ui.post(() -> {
            if (source == closingGatt && newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt();
                return;
            }
            if (gatt != source) return;
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectStatus = status;
                Log.d(TAG, "GATT disconnected, status=" + status);
                connectionLost();
            } else if (newState == BluetoothProfile.STATE_CONNECTED && !connected) {
                ui.removeCallbacks(operationTimedOut);
                connected = true;
                connectedAt = lastPeerAt = SystemClock.elapsedRealtime();
                ui.postDelayed(inspectLink, 15_000);
                boolean refreshed = refreshCache && refreshLegacyCache(source);
                ui.postDelayed(firstDiscovery, refreshed ? 1200 : 650);
                ui.postDelayed(setupTimedOut, 16_000);
            }
        });
    }

    private void inspectLink() {
        if (!connected || gatt == null) return;
        long now = SystemClock.elapsedRealtime();
        RideLog.event("gatt.link_health", "connectedMs", now - connectedAt, "peerIdleMs", now - lastPeerAt,
                "ancs", notificationsReady, "ams", mediaReady, "queued", operations.size(), "operationPending", pending != null);
        if (pending == null && operations.isEmpty()) {
            try { RideLog.event("gatt.rssi_request", "queued", gatt.readRemoteRssi()); }
            catch (IllegalStateException | SecurityException error) {
                RideLog.event("gatt.rssi_error", "type", error.getClass().getSimpleName());
            }
        }
        ui.postDelayed(inspectLink, 15_000);
    }

    @Override public void onReadRemoteRssi(BluetoothGatt source, int rssi, int status) {
        ui.post(() -> {
            if (source != gatt) return;
            RideLog.event("gatt.rssi", "dbm", rssi, "status", status,
                    "connectedMs", SystemClock.elapsedRealtime() - connectedAt);
        });
    }

    private static boolean refreshLegacyCache(BluetoothGatt source) {
        if (Build.VERSION.SDK_INT > 30) return false;
        // Older Android can retain the previous client's service table across an APK replacement.
        try {
            boolean refreshed = Boolean.TRUE.equals(BluetoothGatt.class.getMethod("refresh").invoke(source));
            if (refreshed) Log.d(TAG, "Requested a refresh of this GATT client's service cache");
            return refreshed;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean mtuStallsQueue() {
        // The Android 10 nova 5 can stall the ATT queue during its optional MTU exchange.
        return Build.VERSION.SDK_INT == 29 && "HWSEA".equalsIgnoreCase(Build.DEVICE);
    }

    private void negotiateMtu() {
        // Wait for discovery to leave Android's discovery state before requesting a larger AMS payload.
        mtuRequested = true;
        waitingForMtu = true;
        try {
            if (gatt.requestMtu(deferredMediaRead ? 185 : PREFERRED_MTU)) {
                ui.postDelayed(mtuTimedOut, MTU_WAIT_MS);
                return;
            }
        } catch (IllegalStateException | SecurityException ignored) {}
        finishMtuNegotiation();
    }

    @Override public void onMtuChanged(BluetoothGatt source, int mtu, int status) {
        RideLog.event("gatt.mtu", "mtu", mtu, "status", status, "currentClient", source == gatt);
        ui.post(() -> { if (gatt == source) finishMtuNegotiation(); });
    }

    private void finishMtuNegotiation() {
        if (!waitingForMtu) return;
        waitingForMtu = false;
        ui.removeCallbacks(mtuTimedOut);
        if (deferredMediaRead) {
            deferredMediaRead = false;
            readFullMediaAttribute();
        } else configureServices();
        pump();
    }

    private void connectionLost() {
        RideLog.event("gatt.lost", "connected", connected, "ancs", notificationsReady, "ams", mediaReady,
                "discovered", discoveredServices, "found", foundPhoneServices, "queued", operations.size(),
                "status", disconnectStatus, "connectedMs", connectedAt < 0 ? -1 : SystemClock.elapsedRealtime() - connectedAt,
                "peerIdleMs", lastPeerAt < 0 ? -1 : SystemClock.elapsedRealtime() - lastPeerAt);
        boolean setupStalled = connected && !notificationsReady && !mediaReady
                && (!discoveredServices || foundPhoneServices);
        close();
        listener.servicesDisconnected(setupStalled);
    }

    private void resetServices() {
        notificationsReady = false;
        mediaReady = false;
        mediaTrackRegistered = false;
        mediaPlayerRegistered = false;
        notificationControl = mediaRemote = mediaAttribute = null;
        media = new AppleBle.Media();
        notices.clear();
        fullMediaAttributes.clear();
        deferredMediaRead = false;
        readingKey = -1;
        noticeId = null;
        fragments.reset();
        ui.removeCallbacks(requestTimedOut);
        publish();
    }

    private void discoverWhenIdle() {
        if (!connected) return;
        ui.removeCallbacks(firstDiscovery);
        // Complete the current subscribed notification response before rebuilding its services.
        if (waitingForMtu || pending != null || !operations.isEmpty() || noticeId != null) {
            refreshPending = true;
            return;
        }
        refreshPending = false;
        ui.removeCallbacks(retryServices);
        if (servicesInvalidated) {
            servicesInvalidated = false;
            resetServices();
        }
        enqueue(null, null, null, success -> {
            if (!success) scheduleDiscovery();
            // ANCS requests fit the default payload, and AMS already reads truncated attributes separately.
            else if (!mtuRequested && !mtuStallsQueue()) negotiateMtu();
            else configureServices();
        });
    }

    private void scheduleDiscovery() {
        ui.removeCallbacks(retryServices);
        ui.postDelayed(retryServices, 5000);
    }

    @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
        RideLog.event("gatt.discovery", "status", status, "currentClient", source == gatt);
        ui.post(() -> {
            if (gatt == source) {
                boolean notifications = source.getService(ANCS) != null;
                boolean music = source.getService(AMS) != null;
                discoveredServices |= status == BluetoothGatt.GATT_SUCCESS;
                foundPhoneServices |= notifications || music;
                Log.d(TAG, "Discovery status=" + status + ", notification=" + notifications + ", music=" + music);
            }
            complete(source, null, null, status, null);
        });
    }

    @Override public void onServiceChanged(BluetoothGatt source) {
        ui.post(() -> { if (gatt == source) servicesChanged(); });
    }

    private void servicesChanged() {
        servicesInvalidated = true;
        discoverWhenIdle();
    }

    private void configureServices() {
        // Optional on older Android builds. Re-discovery also covers late-published services.
        if (!serviceChangeSubscribed) {
            subscribe(characteristic(GATT_SERVICE, SERVICE_CHANGED), true, success -> serviceChangeSubscribed = success);
        }
        BluetoothGattCharacteristic notificationSource = characteristic(ANCS, SOURCE);
        BluetoothGattCharacteristic notificationData = characteristic(ANCS, DATA);
        notificationControl = characteristic(ANCS, CONTROL);
        if (!notificationsReady && notificationSource != null && notificationData != null && notificationControl != null) {
            subscribe(notificationData, false, dataOk -> {
                if (!dataOk) return;
                subscribe(notificationSource, false, sourceOk -> {
                    notificationsReady = sourceOk;
                    publish();
                });
            });
        } else if (notificationsReady && (notificationSource == null || notificationData == null || notificationControl == null)) {
            notificationsReady = false;
            notices.clear();
            publish();
        }
        BluetoothGattCharacteristic commands = characteristic(AMS, REMOTE);
        BluetoothGattCharacteristic entity = characteristic(AMS, ENTITY);
        mediaAttribute = characteristic(AMS, ATTRIBUTE);
        if (!mediaReady && commands != null && entity != null) {
            media = new AppleBle.Media();
            mediaTrackRegistered = mediaPlayerRegistered = false;
            subscribe(commands, false, remoteOk -> {
                mediaRemote = remoteOk ? commands : null;
                subscribe(entity, false, entityOk -> {
                    if (entityOk) {
                        enqueue(entity, null, new byte[]{0, 0, 1}, playerOk -> {
                            mediaPlayerRegistered = playerOk;
                            publishMediaAvailability();
                        });
                        enqueue(entity, null, new byte[]{2, 0, 1, 2, 3}, trackOk -> {
                            mediaTrackRegistered = trackOk;
                            publishMediaAvailability();
                        });
                    }
                });
            });
        } else if (mediaReady && (commands == null || entity == null)) {
            mediaReady = false;
            mediaRemote = null;
            media = new AppleBle.Media();
            publish();
        } else if (mediaReady && commands != null) {
            mediaRemote = commands;
        }
        if (!notificationsReady || !mediaReady || (Build.VERSION.SDK_INT < 31 && !serviceChangeSubscribed)) {
            scheduleDiscovery();
        }
    }

    private void publishMediaAvailability() {
        mediaReady = mediaTrackRegistered && mediaPlayerRegistered;
        if (mediaReady && notificationsReady && (Build.VERSION.SDK_INT >= 31 || serviceChangeSubscribed)) {
            ui.removeCallbacks(retryServices);
        }
        publish();
    }

    boolean connected() { return connected; }

    private void publish() {
        RideLog.event("gatt.services", "ancs", notificationsReady, "ams", mediaReady,
                "trackSubscription", mediaTrackRegistered, "playerSubscription", mediaPlayerRegistered,
                "serviceChangeSubscription", serviceChangeSubscribed, "playing", media.playing,
                "titleLength", media.title.length(), "progressMs", media.progress(SystemClock.elapsedRealtime()) * media.duration * 1000);
        if (notificationsReady || mediaReady) ui.removeCallbacks(setupTimedOut);
        listener.servicesChanged(notificationsReady, mediaReady ? media : null);
    }

    private BluetoothGattCharacteristic characteristic(UUID serviceId, UUID id) {
        BluetoothGattService service = gatt.getService(serviceId);
        return service == null ? null : service.getCharacteristic(id);
    }

    private void subscribe(BluetoothGattCharacteristic c, boolean indicate, Consumer<Boolean> done) {
        if (c == null || (c.getProperties() & (indicate
                ? BluetoothGattCharacteristic.PROPERTY_INDICATE : BluetoothGattCharacteristic.PROPERTY_NOTIFY)) == 0) {
            done.accept(false);
            return;
        }
        BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD);
        if (descriptor == null || !gatt.setCharacteristicNotification(c, true)) {
            done.accept(false);
            return;
        }
        enqueue(c, descriptor, indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, done);
    }

    private void enqueue(BluetoothGattCharacteristic characteristic, BluetoothGattDescriptor descriptor,
            byte[] value, Consumer<Boolean> done) {
        operations.add(new Operation(characteristic, descriptor, value, done));
        pump();
    }

    private void pump() {
        if (!connected || waitingForMtu || pending != null) return;
        pending = operations.pollFirst();
        if (pending == null) {
            if (deferredMediaRead && mediaReady && noticeId == null) negotiateMtu();
            else if (refreshPending && noticeId == null) discoverWhenIdle();
            return;
        }
        try {
            if (start(pending)) {
                ui.postDelayed(operationTimedOut, 18_000);
                return;
            }
        } catch (IllegalStateException | SecurityException ignored) {}
        complete(gatt, pending.characteristic, pending.descriptor, BluetoothGatt.GATT_FAILURE, null);
    }

    @SuppressWarnings("deprecation") // Support Android 8–12 as well as the value-taking Android 13 methods.
    private boolean start(Operation operation) {
        if (operation.characteristic == null) return gatt.discoverServices();
        if (operation.descriptor != null) {
            if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(operation.descriptor, operation.value) == 0;
            return operation.descriptor.setValue(operation.value) && gatt.writeDescriptor(operation.descriptor);
        }
        if (operation.value == null) return gatt.readCharacteristic(operation.characteristic);
        int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(operation.characteristic, operation.value, writeType) == 0;
        }
        operation.characteristic.setWriteType(writeType);
        return operation.characteristic.setValue(operation.value) && gatt.writeCharacteristic(operation.characteristic);
    }

    private void complete(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
            BluetoothGattDescriptor descriptor, int status, byte[] value) {
        if (source == null || source != gatt || pending == null || pending.characteristic != characteristic
                || pending.descriptor != descriptor) return;
        boolean success = status == BluetoothGatt.GATT_SUCCESS;
        if (success) lastPeerAt = SystemClock.elapsedRealtime();
        ui.removeCallbacks(operationTimedOut);
        if (characteristic == mediaRemote && commandIssuedAt > 0) {
            RideLog.event("bt.command_done", "elapsedMs", SystemClock.elapsedRealtime() - commandIssuedAt, "status", status);
            commandIssuedAt = -1;
        }
        if (success && value != null && characteristic == mediaAttribute && !fullMediaAttributes.isEmpty()) {
            int key = fullMediaAttributes.peekFirst();
            if (!readSuperseded) {
                media.update(key >>> 8, key & 255, AppleBle.text(value, 0, value.length), SystemClock.elapsedRealtime());
                media.markIncomplete(key >>> 8, key & 255, false);
                publish();
            }
        }
        RideLog.event("gatt.operation", "characteristic", characteristic == null ? "discovery" : characteristic.getUuid().toString(),
                "descriptor", descriptor != null, "status", status, "queued", operations.size());
        Operation done = pending;
        // Its completion may enqueue additional work. Let it do so before starting the next operation.
        done.result.accept(success);
        pending = null;
        pump();
    }

    @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
        ui.post(() -> complete(source, descriptor.getCharacteristic(), descriptor, status, null));
    }

    @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic c, int status) {
        ui.post(() -> complete(source, c, null, status, null));
    }

    @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c,
            byte[] value, int status) {
        byte[] copy = value.clone();
        ui.post(() -> complete(source, c, null, status, copy));
    }

    @Override @SuppressWarnings("deprecation")
    public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c, int status) {
        if (Build.VERSION.SDK_INT < 33) {
            byte[] value = c.getValue();
            byte[] copy = value == null ? null : value.clone();
            ui.post(() -> complete(source, c, null, status, copy));
        }
    }

    @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value) {
        postChange(source, c.getUuid(), value);
    }

    @Override @SuppressWarnings("deprecation")
    public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c) {
        if (Build.VERSION.SDK_INT < 33) postChange(source, c.getUuid(), c.getValue());
    }

    private void postChange(BluetoothGatt source, UUID id, byte[] value) {
        RideLog.event("gatt.data", "characteristic", id.toString(), "bytes", value == null ? 0 : value.length,
                "currentClient", source == gatt);
        if (value == null) return;
        byte[] copy = value.clone();
        ui.post(() -> {
            if (gatt != source) return;
            lastPeerAt = SystemClock.elapsedRealtime();
            if (SERVICE_CHANGED.equals(id)) {
                servicesChanged();
            } else if (SOURCE.equals(id)) {
                onNotificationEvent(copy);
            } else if (DATA.equals(id)) {
                onNotificationData(copy);
            } else if (REMOTE.equals(id)) {
                media.supportedCommands(copy);
                publish();
            } else if (ENTITY.equals(id) && copy.length >= 3) {
                int entity = AppleBle.unsigned(copy[0]);
                int attribute = AppleBle.unsigned(copy[1]);
                int key = (entity << 8) | attribute;
                boolean truncated = (copy[2] & 1) != 0;
                if (key == readingKey) readSuperseded = true;
                media.update(entity, attribute, AppleBle.text(copy, 3, copy.length - 3), SystemClock.elapsedRealtime());
                media.markIncomplete(entity, attribute, truncated);
                if (truncated) queueFullMediaAttribute(key);
                RideLog.event("ams.field", "entity", entity, "attribute", attribute, "truncated", truncated,
                        "bytes", copy.length - 3);
                publish();
            }
        });
    }

    private void onNotificationEvent(byte[] event) {
        if (event.length < 8) return;
        int id = AppleBle.notificationId(event, 4);
        if (event[0] == ANCS_REMOVED) {
            notices.remove(id);
            if (noticeId != null && noticeId == id) noticeRemoved = true;
            listener.notificationRemoved(id);
        } else if (event[0] == ANCS_ADDED && (event[1] & ANCS_PREEXISTING) == 0 && notificationControl != null) {
            if (noticeId != null && noticeId == id || notices.contains(id)) return;
            if (notices.size() >= MAX_PENDING_NOTICES) notices.removeFirst();
            notices.addLast(id);
            requestNotice();
        }
    }

    private void requestNotice() {
        if (noticeId != null || notificationControl == null || notices.isEmpty()) return;
        int id = notices.removeFirst();
        noticeId = id;
        noticeRemoved = false;
        fragments.reset();
        enqueue(notificationControl, null, AppleBle.notificationRequest(id), success -> {
            if (noticeId == null || noticeId != id) return;
            if (success) ui.postDelayed(requestTimedOut, 3500);
            else finishNotice();
        });
    }

    private void onNotificationData(byte[] chunk) {
        if (noticeId == null || chunk.length == 0) return;
        if (fragments.size() == 0 && (chunk.length < 5 || chunk[0] != 0
                || AppleBle.notificationId(chunk, 1) != noticeId)) return;
        if (fragments.size() + chunk.length > AppleBle.MAX_NOTICE_BYTES) {
            finishNotice();
            return;
        }
        fragments.write(chunk, 0, chunk.length);
        AppleBle.Notice notice = AppleBle.parseNotice(noticeId, fragments.toByteArray());
        if (notice != null) {
            if (!noticeRemoved) listener.notificationReceived(notice);
            finishNotice();
        }
    }

    private void finishNotice() {
        ui.removeCallbacks(requestTimedOut);
        noticeId = null;
        fragments.reset();
        requestNotice();
        pump();
    }

    private void queueFullMediaAttribute(int key) {
        if (mediaAttribute == null || fullMediaAttributes.contains(key) || fullMediaAttributes.size() >= 8) return;
        fullMediaAttributes.addLast(key);
        if (fullMediaAttributes.size() != 1) return;
        if (!mtuRequested) {
            // On the nova 5 the MTU exchange can stall the ATT queue, so read directly without it.
            if (mtuStallsQueue()) readFullMediaAttribute();
            else {
                deferredMediaRead = true;
                pump();
            }
        } else readFullMediaAttribute();
    }

    private void readFullMediaAttribute() {
        if (fullMediaAttributes.isEmpty()) return;
        BluetoothGattCharacteristic attribute = mediaAttribute;
        if (attribute == null) {
            fullMediaAttributes.clear();
            return;
        }
        int key = fullMediaAttributes.getFirst();
        readingKey = key;
        readSuperseded = false;
        enqueue(attribute, null, new byte[]{(byte) (key >>> 8), (byte) key}, success -> {
            if (success) enqueue(attribute, null, null, read -> advanceMediaAttribute());
            else advanceMediaAttribute();
        });
    }

    private void advanceMediaAttribute() {
        Integer key = fullMediaAttributes.pollFirst();
        if (key != null && readSuperseded) fullMediaAttributes.addLast(key);
        readingKey = -1;
        readFullMediaAttribute();
    }

    boolean command(int command) {
        if (mediaRemote == null || !mediaReady || command < 0 || !media.supports(command)) return false;
        // User control outranks background reads and notification fetches: run it next.
        commandIssuedAt = SystemClock.elapsedRealtime();
        RideLog.event("bt.command", "command", command, "queued", operations.size(), "pending", pending != null);
        operations.addFirst(new Operation(mediaRemote, null, new byte[]{(byte) command}, success -> {
            if (!success) servicesChanged();
        }));
        pump();
        return true;
    }

    private static final class Operation {
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] value;
        final Consumer<Boolean> result;

        Operation(BluetoothGattCharacteristic characteristic, BluetoothGattDescriptor descriptor,
                byte[] value, Consumer<Boolean> result) {
            this.characteristic = characteristic;
            this.descriptor = descriptor;
            this.value = value;
            this.result = result;
        }
    }
}
