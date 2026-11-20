package com.nova5bike.dashboard;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.RelativeSizeSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DashboardActivity extends Activity implements LocationListener {
    private static final IntentFilter BATTERY_STATE = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private static final int UPCOMING_TURN_COUNT = 2;
    private static final int STATUS_ICON_SLOT_DP = 24;
    private static final int EXPANDED_STATUS_HEIGHT_DP = 24;
    private static final int EXPANDED_CHART_MIN_DP = 30;
    private static final int TRIP_FADE_DP = 12;
    private static final int TRIP_CHART_TOP_INSET_DP = 16;
    private static final int COMPACT_MOTION_HEIGHT_DP = 46;
    private static final int COMPACT_VEHICLE_HEIGHT_DP = 64;
    private static final int COMPACT_READINGS_GAP_DP = 7;
    private static final int EXPANDED_READINGS_WIDTH_DP = 148;
    private static final int EXPANDED_READINGS_GAP_DP = 7;
    private static final float EXPANDED_SPEED_LETTER_SPACING = -.04f;
    private static final int SPEED_UNIT_HIDE_FROM = 100;
    private static final long SPEED_UNIT_RESTORE_MS = 240;
    private static final int READING_VALUE_SP = 23;
    private static final float READING_UNIT_SCALE = .72f;
    private static final long ALTITUDE_INTERVAL_MS = 1000;
    private static final long VIBRATION_READOUT_INTERVAL_MS = 250;
    private static final long MUSIC_INTERVAL_MS = 1000;
    private static final long DEVICE_STATUS_INTERVAL_MS = 5000;
    private static final long NOTICE_DISPLAY_MS = 10_000;
    private static final int NOTICE_ICON_RADIUS_DP = 10;
    private static final long FIX_MAX_AGE = 10_000;
    private static final long LAST_MAP_AREA_MAX_AGE = 30 * 60_000;
    private static final long GPS_GAP_MS = 5_000;
    private static final long OFF_ROUTE_CONFIRMATION_MS = 8_000;
    private static final long PLACE_MIN_INTERVAL_MS = 15_000;
    private static final long PLACE_RETRY_INTERVAL_MS = 45_000;
    private static final double PLACE_LOOKUP_DISTANCE_METERS = 65;
    private static final double PLACE_MAX_AGE_DISTANCE_METERS = 160;
    private static final int REQUEST_LOCATION = 10;
    private static final int REQUEST_PAIRED_BLUETOOTH = 12;
    private static final int REQUEST_NINEBOT_WIDGET = 13;
    private static final String LOCATION_REQUESTED = "locationPermissionRequested";
    private static final String PAIRED_DEVICE = "pairedIPhone";
    private static final String MANUAL_THEME = "manualDayNightTheme";
    private static final String SYSTEM_NIGHT_AT_SELECTION = "systemNightAtSelection";
    private static final String IDLE_MAP_HEADING_UP = "idleMapHeadingUp";
    private static final String ACTIVE_NAV_POINT = "activeNavPoint";
    private static final String ACTIVE_NAV_NAME = "activeNavName";
    private static final String ACTIVE_NAV_TIME = "activeNavTime";
    private static final String ACTIVE_NAV_COVERED = "activeNavCoveredMeters";
    private static final String HOME_POINT = "homePoint";
    private static final String HOME_NAME = "homeName";
    private static final String HOME_ADDRESS = "homeAddress";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
    private final CoverLookup coverLookup = new CoverLookup();
    private final LruCache<CoverLookup.Track, Bitmap> artworkCache = new LruCache<>(16);
    private final SimpleDateFormat arrivalTime = new SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE);

    private AmapClient amap;
    private BikeMap map;
    private TextView searchButton, compassButton, mapBadge, locationButton, currentPlaceLabel;
    private View homeSwipeHint;
    private TextView homeSwipeAddress;
    private ImageView homeSwipeIcon;
    private HomeSwipe homeSwipe;
    private ImageView endNavigationButton;
    private TextView action, road, speed, distance;
    private LinearLayout speedReadout;
    private TextView speedUnit;
    private SpeedUnitDust speedUnitDust;
    private float preferredSpeedSize;
    private SpeedChart speedChart;
    private RouteSpeedChart routeSpeedChart;
    private Theme theme;
    private int systemNight;
    private View dashboardRoot, mapSurface;
    private DashboardStage dashboardStage;
    private FrameLayout compactLayer, mapChrome;
    private CompactPanel compactPanel;
    private View compactPanelBackground, mapEdgeShade, expandMap;
    private TextView compactGuidance;
    private View compactVehicle;
    private ProgressBar compactCharge;
    private LinearLayout compactReadings, compactMotion;
    private CompactReading compactRange, compactBattery, compactVibration, compactAltitude;
    private TextView expandedVibration, expandedAltitude;
    private ReadingSizes compactReadingSizes, expandedReadingSizes;
    private View[] movableInstruments;
    private ViewGroup[] expandedParents;
    private ViewGroup.LayoutParams[] expandedPositions;
    private int[] expandedIndices;
    private float expandedSpeedSize, expandedSpeedLimit;
    private int expandedSpeedHeight;
    private boolean compactDashboard;
    private int screenLeftInset, screenRightInset;
    private final Map<TextView, TextTone> textTones = new WeakHashMap<>();
    private FrameLayout lowerLeft;
    private View musicPanel;
    private LinearLayout speedCard;
    private View guidancePanel, tripPanel;
    private LinearLayout guidanceButton, turnPreview;
    private final TextView[] upcomingDistances = new TextView[UPCOMING_TURN_COUNT];
    private final TextView[] upcomingInstructions = new TextView[UPCOMING_TURN_COUNT];
    private ImageView recenterButton, settingsButton, themeButton, turnBadge, receivedPin;
    private TextView confirmRouteButton;
    private String shownGuidanceDetail = "";
    private DashIcon.Kind shownTurn;
    private double coveredMetersBeforeRoute;
    private ScrollingTitle musicTitle;
    private TextView musicArtist, rideDuration, routeTotal, routeArrival, currentTime;
    private LinearLayout statusIcons;
    private ImageView gpsIndicator, bluetoothIndicator, networkIndicator, batteryIndicator;
    private View vehiclePanel;
    private final Rect statusInkBounds = new Rect();
    private final TextPaint statusInkPaint = new TextPaint();
    private TextView vehicleBattery, vehicleRange;
    private Switch ninebotLinkSwitch;
    private NinebotVehicle ninebot;
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private TelephonyManager telephony;
    private BatteryManager battery;
    private ImageView musicCover;
    private SeekBar musicProgress;
    private TextView musicPosition, musicLength;
    private View musicTimes;
    private MediaController musicSeekSession;
    private MediaMetadata musicSeekTrack;
    private ImageView prev, play, next;
    private DashIcon turnIcon, playIcon;
    private LinearLayout receivedCard;
    private TextView receivedName;
    private NoticeView expandedNotice, compactNotice;
    private NoticeCardContent shownNotice;
    private final ArrayDeque<NoticeCardContent> recentNotices = new ArrayDeque<>();
    private AppleBle.Media bluetoothMedia;
    private int artworkAttempts;
    private long artworkRetryAt;
    private CoverLookup.Track artworkTrack;
    private Bitmap foundArtwork;
    private Uri artworkUri;
    private Future<?> artworkRequest;
    private boolean bluetoothNotifications;
    private final Runnable noticeTick = this::updateNoticeCountdown;
    private BikeBluetooth bluetooth;
    private BikeBluetooth.Listener bluetoothListener;
    private String bluetoothStatus = "未连接蓝牙设备";
    private final BroadcastReceiver shutdownEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SHUTDOWN.equals(intent.getAction())) return;
            saveNavigation(true);
            recordPhonePower(true);
            RideLog.event("app.system_shutdown", "navigating", destination != null);
            RideLog.flush(300);
        }
    };
    private final BroadcastReceiver bluetoothConnections = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!visible) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    bluetooth.radioChanged(false);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    bluetooth.radioChanged(true);
                    reconnectBluetooth();
                }
                refreshMusic();
                refreshBluetoothIndicator();
                return;
            }
            if (bluetooth.enabled() || getPreferences(MODE_PRIVATE).contains(PAIRED_DEVICE)) return;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    != BluetoothDevice.BOND_BONDED) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (bluetooth.canAutoConnect(device)) bluetooth.connectDevice(device);
        }
    };
    private AlertDialog bluetoothDialog;
    private TextView bluetoothDetails;
    private AmapClient.Place received;
    private boolean resolvingImport;
    private final Runnable speedExpiry = this::displayGpsSpeed;
    private final GpsSpeedReading gpsSpeed = new GpsSpeedReading();
    private RideSensors rideSensors;
    private float phoneHeadingDegrees;
    private long phoneHeadingAt = -1;
    private VolumeKeys volumeKeys;
    private AudioManager audioManager;
    private final Runnable volumeResolve = this::resolveVolumeKey;
    private LocationManager locations;
    private boolean trackingLocation;
    private Location lastFix;
    private Route.Point current;
    private Route.Point currentPlacePoint, placeLookupPoint;
    private boolean placeLookupRunning;
    private long placeLookupAt;
    private Route route;
    private AmapClient.Place destination;
    private final NavigationPlan navigation = new NavigationPlan();
    private long offRouteSince = -1;
    private boolean recoveringRoute;
    private long nextReroute;
    private long navigationSavedAt = -1;
    private boolean visible, mapReady;
    private boolean headingUp = true, idleHeadingUp = true, routeOverview, overviewHeadingUp, turnsExpanded;
    private static final long OVERVIEW_TIMEOUT_MS = 15_000;
    private final Runnable overviewExit = this::overviewTimedOut;
    private MediaController media;
    private final Runnable diagnosticsTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = SystemClock.elapsedRealtime();
            saveNavigation(false);
            recordPhonePower(false);
            NinebotVehicle.Reading vehicle = ninebot.reading();
            RideLog.event("dashboard.sample", "compact", compactDashboard, "mapReady", mapReady,
                    "headingUp", mapHeadingUp(), "idleHeadingUp", idleHeadingUp, "overview", routeOverview,
                    "navigating", route != null, "planning", navigation.planning(), "recoveringRoute", recoveringRoute,
                    "heading", phoneHeadingDegrees, "headingAgeMs", phoneHeadingAt < 0 ? -1 : now - phoneHeadingAt,
                    "gpsSpeedMps", gpsSpeedAt(now), "displayKmh", speed.getText().toString(),
                    "gpsAgeMs", lastFix == null ? -1 : fixAge(lastFix), "speedDecision", gpsSpeed.reason(),
                    "btLinked", bluetooth.linked(), "ancs", bluetoothNotifications, "ams", bluetoothMedia != null,
                    "musicSource", bluetoothMedia != null ? "iphone" : expectsBluetoothPhone() ? "iphone_waiting" : "local",
                    "notificationShowing", shownNotice != null, "notificationQueue", recentNotices.size(),
                    "vehicleLinked", ninebot.connected(), "vehiclePercent", vehicle.percent,
                    "vehicleRange", vehicle.range, "vehicleReadWallMs", vehicle.readAt, "vehicleOld", vehicle.old(),
                    "routeTotalM", route == null ? 0 : route.totalMeters(),
                    "routeCoveredM", route == null ? 0 : route.coveredMeters(), "previousCoveredM", coveredMetersBeforeRoute);
            if (mapReady) map.recordDiagnostics();
            if (RideLog.due("layout.sample", 2000)) {
                RideLog.event("layout.sample", "compact", compactDashboard, "rotation", getWindowManager().getDefaultDisplay().getRotation(),
                        "root", RideLog.view(dashboardRoot), "map", RideLog.view(map), "speed", RideLog.view(speed),
                        "unit", RideLog.view(speedUnit), "music", RideLog.view(musicPanel), "title", RideLog.view(musicTitle),
                        "artist", RideLog.view(musicArtist), "musicTime", RideLog.view(musicTimes),
                        "notice", RideLog.view(compactDashboard ? compactNotice : expandedNotice));
            }
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable altitudeTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshAltitudeReading();
            ui.postDelayed(this, ALTITUDE_INTERVAL_MS);
        }
    };
    private final Runnable vibrationTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshVibrationReading();
            ui.postDelayed(this, VIBRATION_READOUT_INTERVAL_MS);
        }
    };
    private final Runnable musicTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshMusic();
            ui.postDelayed(this, MUSIC_INTERVAL_MS);
        }
    };
    private final Runnable deviceStatusTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshDeviceStatus();
            ui.postDelayed(this, DEVICE_STATUS_INTERVAL_MS);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            syncLocation();
            refreshLocationStatus();
            refreshMap();
            updateBluetoothDetails();
            ui.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RideLog.init(this);
        RideLog.event("app.create", "restored", state != null);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(shutdownEvents, new IntentFilter(Intent.ACTION_SHUTDOWN), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(shutdownEvents, new IntentFilter(Intent.ACTION_SHUTDOWN));
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        systemNight = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        theme = systemTheme();
        if (getPreferences(MODE_PRIVATE).getInt(SYSTEM_NIGHT_AT_SELECTION, -1) == systemNight) {
            theme = readChoice(MANUAL_THEME, theme);
        } else {
            getPreferences(MODE_PRIVATE).edit().remove(MANUAL_THEME).remove(SYSTEM_NIGHT_AT_SELECTION).apply();
        }
        idleHeadingUp = getPreferences(MODE_PRIVATE).getBoolean(IDLE_MAP_HEADING_UP, true);
        if (state != null) idleHeadingUp = state.getBoolean(IDLE_MAP_HEADING_UP, idleHeadingUp);
        headingUp = idleHeadingUp;
        locations = getSystemService(LocationManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        wifi = getApplicationContext().getSystemService(WifiManager.class);
        telephony = getSystemService(TelephonyManager.class);
        battery = getSystemService(BatteryManager.class);
        amap = new AmapClient(this);
        audioManager = getSystemService(AudioManager.class);
        volumeKeys = new VolumeKeys(new VolumeKeys.Listener() {
            @Override public void single(boolean up) {
                if (bluetoothMedia == null) adjustVolume(up);
            }
            @Override public void skip(boolean up) {
                skipTrack(up);
            }
        });
        ninebot = new NinebotVehicle(this, ui, this::refreshVehicle);
        makeDashboard();
        rideSensors = new RideSensors(getSystemService(SensorManager.class),
                () -> getWindowManager().getDefaultDisplay().getRotation(), ui, new RideSensors.Listener() {
            @Override public void phoneHeading(float degrees) {
                phoneHeadingDegrees = degrees;
                phoneHeadingAt = SystemClock.elapsedRealtime();
                if (mapReady) paintPhoneHeading();
            }

            @Override public void phoneVibration(float metersPerSecondSquared, long time) {
                speedChart.recordVibration(metersPerSecondSquared / SensorManager.GRAVITY_EARTH, time);
            }
        });
        bluetooth = BikeBluetooth.shared(this);
        bluetoothListener = new BikeBluetooth.Listener() {
            @Override public void statusChanged(String status) {
                bluetoothStatus = status;
                refreshMusic();
                refreshBluetoothIndicator();
                updateBluetoothDetails();
                if (!bluetooth.enabled()) toast(status);
            }
            @Override public void servicesChanged(boolean notifications, AppleBle.Media music) {
                bluetoothNotifications = notifications;
                bluetoothMedia = music;
                refreshMusic();
                refreshBluetoothIndicator();
                updateBluetoothDetails();
            }
            @Override public void notificationReceived(AppleBle.Notice notice) {
                if (!visible || !bluetoothNotifications) return;
                showNotice(notice.source(), notice.title, notice.subtitle, notice.message, bluetoothNoticeIcon(notice.app));
            }
            @Override public void notificationRemoved(int id) {
                // Once displayed, the card follows its local expiry even if iOS withdraws it.
            }
            @Override public void servicesDisconnected(boolean setupStalled) {
                statusChanged("蓝牙服务已断开，请重新连接设备");
            }
        };
        fullscreen();
        if (state != null) dashboardStage.showCompact(state.getBoolean("compactDashboard"), false);
        restoreNavigation(state);
        if (state == null) acceptShare(getIntent());
        if (!permitted() && !getPreferences(MODE_PRIVATE).getBoolean(LOCATION_REQUESTED, false)) {
            requestLocationPermission();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptShare(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        RideLog.event("app.start");
        ui.post(diagnosticsTick);
        displayGpsSpeed();
        MediaAccess.setNoticeListener((app, title, conversation, message, icon) -> {
            if (!visible || bluetoothNotifications) return;
            showNotice(app, title, conversation, message, icon);
        });
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothConnections, bluetoothFilter(), Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothConnections, bluetoothFilter());
        bluetooth.attach(bluetoothListener);
        reconnectBluetooth();
        refreshLowerCard();
        rideSensors.start();
        syncLocation();
        altitudeTick.run();
        vibrationTick.run();
        musicTick.run();
        deviceStatusTick.run();
        ui.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        RideLog.event("app.resume");
        ninebot.setForeground(true);
    }

    @Override protected void onPause() {
        RideLog.event("app.pause");
        ninebot.setForeground(false);
        super.onPause();
    }

    @Override protected void onStop() {
        saveNavigation(true);
        recordPhonePower(true);
        RideLog.event("app.stop");
        ui.removeCallbacks(diagnosticsTick);
        visible = false;
        clearArtworkRequest();
        MediaAccess.setNoticeListener(null);
        unregisterReceiver(bluetoothConnections);
        ui.removeCallbacks(tick);
        ui.removeCallbacks(speedExpiry);
        ui.removeCallbacks(noticeTick);
        ui.removeCallbacks(altitudeTick);
        ui.removeCallbacks(vibrationTick);
        ui.removeCallbacks(musicTick);
        ui.removeCallbacks(deviceStatusTick);
        rideSensors.stop();
        phoneHeadingAt = -1;
        if (mapReady) map.setPhoneHeading(Float.NaN);
        stopLocation();
        bluetooth.detach(bluetoothListener);
        if (!bluetooth.enabled()) bluetoothStatus = "未连接蓝牙设备";
        super.onStop();
    }

    @Override protected void onDestroy() {
        RideLog.event("app.destroy");
        unregisterReceiver(shutdownEvents);
        bluetooth.detach(bluetoothListener);
        requests.shutdownNow();
        artworkWorker.shutdownNow();
        coverLookup.close();
        map.destroy();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("compactDashboard", dashboardStage.compact());
        state.putBoolean(IDLE_MAP_HEADING_UP, idleHeadingUp);
        // The checkpoint is authoritative even if an in-flight request fails after this callback.
        saveNavigation(true);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        int nextNight = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (systemNight == nextNight) return;
        systemNight = nextNight;
        getPreferences(MODE_PRIVATE).edit().remove(MANUAL_THEME).remove(SYSTEM_NIGHT_AT_SELECTION).apply();
        changeTheme(systemTheme());
    }

    private Theme systemTheme() {
        return systemNight == Configuration.UI_MODE_NIGHT_YES ? Theme.NIGHT : Theme.DAY;
    }

    private void changeTheme(Theme selected) {
        if (selected == theme) return;
        theme = selected;
        applyTheme();
    }

    private void toggleTheme() {
        Theme selected = theme.dark ? Theme.DAY : Theme.NIGHT;
        getPreferences(MODE_PRIVATE).edit().putString(MANUAL_THEME, selected.name())
                .putInt(SYSTEM_NIGHT_AT_SELECTION, systemNight).apply();
        changeTheme(selected);
    }

    private void refreshThemeButton() {
        DashIcon glyph = (DashIcon) themeButton.getDrawable();
        glyph.setKind(theme.dark ? DashIcon.Kind.MOON : DashIcon.Kind.SUN);
        glyph.setColor(theme.accent);
        String action = "当前" + theme.label + "，切换为" + (theme.dark ? Theme.DAY.label : Theme.NIGHT.label);
        themeButton.setContentDescription(action);
        themeButton.setTooltipText(action);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    private void fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(theme.dark ? 0 : lightBars, lightBars);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            int lightBars = theme.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(5894 | lightBars);
        }
    }

    private void makeDashboard() {
        FrameLayout root = new FrameLayout(this);
        dashboardStage = new DashboardStage(this, dp(10), dp(18), dp(17));
        root.addView(dashboardStage, new FrameLayout.LayoutParams(-1, -1));
        dashboardRoot = root;
        root.setBackgroundColor(theme.background);
        styleSystemBars();
        applyDashboardPadding();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                int left = cutout == null ? 0 : cutout.getSafeInsetLeft();
                boolean leftChanged = screenLeftInset != left;
                screenLeftInset = left;
                screenRightInset = cutout == null ? 0 : cutout.getSafeInsetRight();
                applyDashboardPadding();
                if (leftChanged && compactDashboard) positionCompactDashboard();
                return insets;
            });
        }
        setContentView(root);

        LinearLayout instruments = group(true);
        FrameLayout mapPanel = new DashboardStage.MapPanel(this);
        mapSurface = mapPanel;
        mapPanel.setBackgroundColor(theme.mapBackground);
        FrameLayout mapVisuals = new FrameLayout(this);
        mapPanel.addView(mapVisuals, new FrameLayout.LayoutParams(-1, -1));
        map = new BikeMap(this, amap, theme.dark);
        map.setOnDestinationPick(this::confirmMapDestination);
        mapVisuals.addView(map, new FrameLayout.LayoutParams(-1, -1));
        mapEdgeShade = new View(this);
        mapPanel.addView(mapEdgeShade, new FrameLayout.LayoutParams(0, -1, Gravity.LEFT));
        mapChrome = new FrameLayout(this);
        mapPanel.addView(mapChrome, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout mapHeader = group(false);
        mapHeader.setGravity(Gravity.TOP);
        mapHeader.setBaselineAligned(false);
        FrameLayout.LayoutParams headerBox = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        headerBox.setMargins(dp(10), dp(9), dp(10), 0);
        mapChrome.addView(mapHeader, headerBox);
        FrameLayout mapLead = new FrameLayout(this);
        mapHeader.addView(mapLead, new LinearLayout.LayoutParams(0, -2, 1));
        addHomeSwipeHint(mapLead);

        searchButton = button("搜索目的地", 14, this::showSearch);
        searchButton.setCompoundDrawablePadding(dp(6));
        searchButton.setCompoundDrawables(icon(DashIcon.Kind.SEARCH, theme.accent, 17), null, null, null);
        searchButton.setMaxWidth(dp(188));
        oneLine(searchButton);
        homeSwipe = new HomeSwipe();
        searchButton.setOnTouchListener(homeSwipe);
        mapLead.addView(searchButton, new FrameLayout.LayoutParams(-2, dp(44), Gravity.START));

        compassButton = button("N", 18, () -> setMapHeadingUp(!mapHeadingUp()));
        compassButton.setFocusable(true);
        pad(compassButton, 0, 0, 0, 0);
        refreshMapDirectionButton();
        mapHeader.addView(compassButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams controlsBox = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.END);
        controlsBox.setMargins(0, 0, dp(10), dp(14));
        ImageView recenter = iconButton(DashIcon.Kind.TARGET, "回到当前位置", 22, theme.accent);
        recenterButton = recenter;
        recenter.setOnClickListener(view -> handleLocationClick());
        mapChrome.addView(recenter, controlsBox);
        currentPlaceLabel = label("", 10, TextTone.PRIMARY, false);
        currentPlaceLabel.setVisibility(View.GONE);
        currentPlaceLabel.setMaxWidth(dp(164));
        currentPlaceLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        oneLine(currentPlaceLabel);
        FrameLayout.LayoutParams placeBox = new FrameLayout.LayoutParams(-2, dp(17), Gravity.BOTTOM | Gravity.END);
        placeBox.setMargins(0, 0, dp(10), dp(6));
        mapChrome.addView(currentPlaceLabel, placeBox);
        locationButton = button("", 13, this::handleLocationClick);
        locationButton.setTextColor(theme.accent);
        locationButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams locationBox = new FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.START);
        locationBox.setMargins(dp(10), 0, 0, dp(14));
        mapChrome.addView(locationButton, locationBox);

        mapBadge = button("正在加载地图…", 13, () -> {
            mapBadge.setText("正在加载地图…");
            mapBadge.setClickable(false);
            mapReady = false;
            map.reload();
        });
        mapBadge.setClickable(false);
        mapVisuals.addView(mapBadge, new FrameLayout.LayoutParams(-2, dp(42), Gravity.CENTER));
        LinearLayout guidanceStack = group(true);
        guidancePanel = guidanceStack;
        guidanceStack.setVisibility(View.GONE);
        mapLead.addView(guidanceStack, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        LinearLayout guidance = group(false);
        guidanceButton = guidance;
        guidance.setGravity(Gravity.CENTER_VERTICAL);
        guidance.setOnClickListener(v -> {
            if (navigation.failed()) {
                requestNavigation();
                return;
            }
            setRouteOverview(!routeOverview);
        });
        guidance.setClickable(false);
        pad(guidance, 5, 3, 8, 3);
        FrameLayout guidanceCard = new FrameLayout(this);
        guidanceStack.addView(guidanceCard, new LinearLayout.LayoutParams(-1, dp(44)));
        guidanceCard.addView(guidance, new FrameLayout.LayoutParams(-1, -1));
        ImageView turn = new ImageView(this);
        turnBadge = turn;
        turn.setScaleType(ImageView.ScaleType.CENTER);
        turn.setBackground(round(theme.accent, 9));
        turnIcon = icon(DashIcon.Kind.ROUTE, theme.onAccent, 21);
        turn.setImageDrawable(turnIcon);
        guidance.addView(turn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout turnCopy = group(true);
        turnCopy.setMinimumWidth(dp(80));
        LinearLayout.LayoutParams copyBox = new LinearLayout.LayoutParams(-2, -2);
        copyBox.leftMargin = dp(7);
        guidance.addView(turnCopy, copyBox);
        action = label("导航", 17, TextTone.PRIMARY, true);
        oneLine(action);
        action.setMaxWidth(dp(205));
        pad(action, 0, 0, 21, 0);
        turnCopy.addView(action, new LinearLayout.LayoutParams(-2, -2));
        road = label("选择目的地开始", 10, TextTone.SECONDARY, false);
        road.setMaxWidth(dp(205));
        oneLine(road);
        pad(road, 0, 1, 0, 0);
        turnCopy.addView(road, new LinearLayout.LayoutParams(-2, -2));

        endNavigationButton = iconButton(DashIcon.Kind.CLOSE, "结束导航", 18, theme.muted);
        endNavigationButton.setBackground(clickable(Color.TRANSPARENT, 8));
        endNavigationButton.setTooltipText("结束导航");
        endNavigationButton.setOnClickListener(v -> clearRoute());
        endNavigationButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams endBox = new FrameLayout.LayoutParams(dp(28), dp(24), Gravity.TOP | Gravity.END);
        endBox.setMarginEnd(dp(2));
        guidanceCard.addView(endNavigationButton, endBox);
        addUpcomingTurns(guidanceStack);
        styleMapControls();

        LinearLayout metrics = group(true);
        speedCard = metrics;
        metrics.setBackground(speedSurface());
        metrics.setGravity(Gravity.TOP);
        pad(metrics, 15, 8, 15, 7);
        LinearLayout.LayoutParams metricBox = new LinearLayout.LayoutParams(-1, 0, 1);
        metricBox.bottomMargin = dp(9);
        instruments.addView(metrics, metricBox);
        addSpeedStatusRow(metrics);

        LinearLayout speedRow = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (!compactDashboard) {
                    int width = MeasureSpec.getSize(widthSpec);
                    int normal = width - dp(EXPANDED_READINGS_WIDTH_DP + EXPANDED_READINGS_GAP_DP);
                    int twoDigits = textWidth(speed, "88") + textWidth(speedUnit, speedUnit.getText());
                    int threeDigits = textWidth(speed, "888");
                    int minimum = Math.max(normal, Math.max(twoDigits, threeDigits));
                    if (speedReadout.getMinimumWidth() != minimum) speedReadout.setMinimumWidth(minimum);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.setBaselineAligned(false);
        speedRow.setClipChildren(false);
        speedReadout = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (compactDashboard && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    fitCompactSpeedNumber(MeasureSpec.getSize(widthSpec));
                }
                super.onMeasure(widthSpec, heightSpec);
            }

            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (!speedUnitDust.active()) return;
                float left = speed.getRight() + dp(1);
                float right = getWidth();
                if (!compactDashboard) {
                    right += dp(24);
                    right = Math.min(right, readingStart(expandedAltitude, speedRow) - dp(3));
                    right = Math.min(right, readingStart(vehicleRange, speedRow) - dp(3));
                }
                speedUnitDust.draw(canvas, left, right, speed.getTop() + speed.getBaseline(), compactDashboard,
                        speedUnit.getCurrentTextColor());
            }
        };
        speedUnitDust = new SpeedUnitDust(speedReadout);
        speedReadout.setGravity(Gravity.START | Gravity.BOTTOM);
        speedReadout.setBaselineAligned(true);
        speedRow.addView(speedReadout, new LinearLayout.LayoutParams(-2, -2));
        boolean compactSpeed = getResources().getConfiguration().screenHeightDp < 340;
        speed = label("—", compactSpeed ? 58 : 103, TextTone.PRIMARY, true);
        speed.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        speed.setFontFeatureSettings("'tnum'");
        speed.setLetterSpacing(EXPANDED_SPEED_LETTER_SPACING);
        speed.setMaxLines(1);
        speed.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                alignStatusClock());
        expandedSpeedLimit = expandedSpeedSize = preferredSpeedSize = speed.getTextSize();
        expandedSpeedHeight = speed.getLineHeight();
        speedReadout.setMinimumHeight(expandedSpeedHeight);
        speedReadout.addView(speed);
        speedUnit = label("km/h", 14, TextTone.PRIMARY, true);
        speedUnit.setMaxLines(1);
        pad(speedUnit, 7, 0, 0, 2);
        speedReadout.addView(speedUnit);
        speedReadout.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (compactDashboard) alignStatusClock();
        });
        addRideReadings(speedRow);
        metrics.addView(speedRow, new LinearLayout.LayoutParams(-1, -2));
        speedRow.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (speedRow.isShown()) alignExpandedVehicleBottom();
            return true;
        });

        speedChart = new SpeedChart(this, theme.accent);
        speedChart.setVibrationColor(theme.muted);
        metrics.addView(speedChart, new LinearLayout.LayoutParams(-1, 0, 1));
        metrics.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) fitExpandedInstruments(bottom - top);
        });

        LinearLayout trip = group(false);
        trip.setBaselineAligned(false);
        routeTotal = metric(trip, "全程距离");
        distance = metric(trip, "剩余距离");
        rideDuration = metric(trip, "剩余用时");
        routeArrival = metric(trip, "预计到达");
        routeArrival.setTextColor(theme.accent);
        FrameLayout tripFooter = new FrameLayout(this);
        tripPanel = tripFooter;
        tripFooter.setVisibility(View.GONE);
        tripFooter.setBackground(tripSurface());
        mapChrome.addView(tripFooter, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        currentPlaceLabel.bringToFront();
        tripFooter.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                updateMapOverlayInsets());

        routeSpeedChart = new RouteSpeedChart(this, theme.accent);
        FrameLayout.LayoutParams chartBox = new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM);
        chartBox.setMargins(dp(12), 0, dp(12), 0);
        tripFooter.addView(routeSpeedChart, chartBox);

        LinearLayout tripStack = group(true);
        tripStack.setGravity(Gravity.BOTTOM);
        // The former progress slot still contributes to the footer height and map insets.
        pad(tripStack, 12, 8 + TRIP_FADE_DP, 12, 3);
        tripStack.setMinimumHeight(dp(44 + TRIP_FADE_DP));
        tripStack.addView(trip, new LinearLayout.LayoutParams(-1, -2));
        tripFooter.addView(tripStack, new FrameLayout.LayoutParams(-1, -2));
        tripStack.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = Math.max(0, bottom - top - dp(TRIP_CHART_TOP_INSET_DP));
            if (chartBox.height == height) return;
            chartBox.height = height;
            routeSpeedChart.setLayoutParams(chartBox);
        });

        lowerLeft = new FrameLayout(this);
        instruments.addView(lowerLeft, new LinearLayout.LayoutParams(-1, dp(130)));
        buildMusicCard();
        addReceivedCard(lowerLeft);
        expandedNotice = addNotificationCard(lowerLeft, false);
        setupCompactDashboard(instruments, mapPanel, mapVisuals);
    }

    private void applyDashboardPadding() {
        dashboardRoot.setPadding(dp(11), dp(12), Math.max(screenRightInset, dp(16)), dp(12));
    }

    @Override public void onBackPressed() {
        if (dashboardStage.compact()) dashboardStage.showCompact(false, true);
        else super.onBackPressed();
    }

    private void setupCompactDashboard(LinearLayout instruments, FrameLayout mapPanel, View mapVisuals) {
        compactLayer = new FrameLayout(this);
        compactNotice = addNotificationCard(compactLayer, true);
        compactPanelBackground = new View(this);
        compactPanel = new CompactPanel(this);
        compactLayer.addView(compactPanel, 0, new FrameLayout.LayoutParams(0, -1, Gravity.LEFT));
        compactReadings = group(true);
        compactPanel.addView(compactReadings, new FrameLayout.LayoutParams(0, 0));

        compactMotion = group(false);
        compactMotion.setBaselineAligned(false);
        compactAltitude = new CompactReading(compactMotion, "海拔高度");
        compactVibration = new CompactReading(compactMotion, "震动幅度");
        compactReadings.addView(compactMotion, new LinearLayout.LayoutParams(-1, 0, COMPACT_MOTION_HEIGHT_DP));

        LinearLayout readings = group(true);
        compactVehicle = readings;
        LinearLayout vehiclePair = group(false);
        vehiclePair.setBaselineAligned(false);
        readings.addView(vehiclePair, new LinearLayout.LayoutParams(-1, 0, 1));
        compactRange = new CompactReading(vehiclePair, "车辆续航");
        compactBattery = new CompactReading(vehiclePair, "车辆电量");
        compactReadingSizes = new ReadingSizes(readings, compactAltitude.value, compactVibration.value,
                compactRange.value, compactBattery.value);
        compactCharge = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        compactCharge.setMax(100);
        compactCharge.setContentDescription("车辆电量");
        readings.addView(compactCharge, new LinearLayout.LayoutParams(-1, dp(3)));
        LinearLayout.LayoutParams vehicleBox = new LinearLayout.LayoutParams(-1, 0, COMPACT_VEHICLE_HEIGHT_DP);
        vehicleBox.topMargin = dp(COMPACT_READINGS_GAP_DP);
        compactReadings.addView(readings, vehicleBox);
        refreshVehicle();

        expandMap = new View(this);
        expandMap.setContentDescription("展开地图");
        expandMap.setOnClickListener(view -> dashboardStage.showCompact(false, true));
        expandMap.setVisibility(View.GONE);
        mapPanel.addView(expandMap, new FrameLayout.LayoutParams(-1, -1));
        compactGuidance = button("", 11, () -> dashboardStage.showCompact(false, true));
        oneLine(compactGuidance);
        compactGuidance.setVisibility(View.GONE);
        FrameLayout.LayoutParams guideBox = new FrameLayout.LayoutParams(-2, dp(29), Gravity.RIGHT | Gravity.BOTTOM);
        guideBox.setMargins(dp(7), 0, dp(8), dp(15));
        mapPanel.addView(compactGuidance, guideBox);

        movableInstruments = new View[]{speedChart, currentTime, statusIcons, speedReadout, lowerLeft};
        expandedParents = new ViewGroup[movableInstruments.length];
        expandedPositions = new ViewGroup.LayoutParams[movableInstruments.length];
        expandedIndices = new int[movableInstruments.length];
        for (int i = 0; i < movableInstruments.length; i++) {
            View view = movableInstruments[i];
            expandedParents[i] = (ViewGroup) view.getParent();
            expandedPositions[i] = view.getLayoutParams();
            expandedIndices[i] = expandedParents[i].indexOfChild(view);
        }
        dashboardStage.attachPanels(mapPanel, mapVisuals, instruments, compactLayer, mapChrome,
                compactPanelBackground, mapEdgeShade,
                new DashboardStage.ModeListener() {
                    @Override public void layoutInstruments(boolean compact) {
                        setCompactDashboard(compact);
                    }

                    @Override public void settleMode(boolean compact) {
                        settleCompactMode(compact);
                    }

                    @Override public DashboardTransition.Scene captureInstruments(View pane) {
                        return captureDashboardInstruments(pane);
                    }
                });
        dashboardStage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (compactDashboard) positionCompactDashboard();
                updateMapOverlayInsets();
            }
        });
        styleCompactDashboard();
    }

    private final class CompactReading {
        final TextView title, value;

        CompactReading(LinearLayout readings, String name) {
            LinearLayout column = group(true);
            boolean first = readings.getChildCount() == 0;
            int alignment = first ? Gravity.START : Gravity.END;
            if (!first) column.setPaddingRelative(dp(6), 0, 0, 0);
            title = label(name, 11, TextTone.SECONDARY, false);
            // Scrolling single-line text settles its end alignment after the first transition snapshot.
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(alignment);
            column.addView(title, new LinearLayout.LayoutParams(-1, -2));

            value = label("—", READING_VALUE_SP, TextTone.PRIMARY, true);
            value.setFontFeatureSettings("'tnum'");
            value.setMaxLines(1);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setGravity(alignment | Gravity.CENTER_VERTICAL);
            column.addView(value, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(0, -1, 1);
            if (first) box.setMarginEnd(dp(7));
            readings.addView(column, box);
        }

        boolean show(String text) {
            return setReadingText(value, text, title.getText().toString());
        }
    }

    private final class ReadingSizes {
        private final View vehicle;
        private final TextView[] values;
        private final TextPaint measure = new TextPaint();

        ReadingSizes(View vehicle, TextView... values) {
            this.vehicle = vehicle;
            this.values = values;
            for (TextView value : values) {
                value.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fit();
                });
            }
        }

        void fit() {
            int count = vehicle.getVisibility() == View.VISIBLE ? values.length : 2;
            float maximum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, READING_VALUE_SP,
                    getResources().getDisplayMetrics());
            float scale = 1f;
            for (int i = 0; i < count; i++) {
                TextView value = values[i];
                int width = value.getWidth() - value.getCompoundPaddingLeft() - value.getCompoundPaddingRight();
                int height = value.getHeight() - value.getCompoundPaddingTop() - value.getCompoundPaddingBottom();
                if (width <= 0 || height <= 0) continue;
                measure.set(value.getPaint());
                measure.setTextSize(maximum);
                float textWidth = Layout.getDesiredWidth(value.getText(), measure);
                if (textWidth > width) scale = Math.min(scale, width / textWidth);
                float lineHeight = measure.descent() - measure.ascent();
                if (lineHeight > height) scale = Math.min(scale, height / lineHeight);
            }
            float size = maximum * scale;
            for (int i = 0; i < count; i++) {
                TextView value = values[i];
                if (Math.abs(value.getTextSize() - size) > .1f) value.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
        }
    }

    private void refreshAltitudeReading() {
        boolean altitudeFresh = lastFix != null && fixAge(lastFix) >= 0 && fixAge(lastFix) <= FIX_MAX_AGE;
        double altitude = altitudeFresh && lastFix.hasAltitude() ? lastFix.getAltitude() : Double.NaN;
        if (Build.VERSION.SDK_INT >= 34 && altitudeFresh && lastFix.hasMslAltitude()) {
            altitude = lastFix.getMslAltitudeMeters();
        }
        String altitudeText = Double.isFinite(altitude) ? Math.round(altitude) + " m" : "—";
        boolean compactChanged = compactAltitude.show(altitudeText);
        boolean expandedChanged = setReadingText(expandedAltitude, altitudeText, "GPS高度估算");
        if (compactChanged) compactReadingSizes.fit();
        if (expandedChanged) expandedReadingSizes.fit();
    }

    private void refreshVibrationReading() {
        long now = SystemClock.elapsedRealtime();
        float vibrationG = rideSensors.vibration(now) / SensorManager.GRAVITY_EARTH;
        String vibrationText = "—";
        if (Float.isFinite(vibrationG)) {
            String number = String.format(Locale.ROOT, "%.2f", vibrationG);
            vibrationText = (number.startsWith("0.") ? number.substring(1)
                    : String.format(Locale.ROOT, "%.1f", vibrationG)) + "g";
        }
        boolean compactChanged = compactVibration.show(vibrationText);
        boolean expandedChanged = setReadingText(expandedVibration, vibrationText, "震动幅度");
        if (compactChanged) compactReadingSizes.fit();
        if (expandedChanged) expandedReadingSizes.fit();
    }

    private void setCompactDashboard(boolean compact) {
        RideLog.event("layout.mode", "compact", compact);
        compactDashboard = compact;
        for (int i = 0; i < movableInstruments.length; i++) {
            View view = movableInstruments[i];
            ((ViewGroup) view.getParent()).removeView(view);
            if (compact) {
                FrameLayout parent = view == statusIcons || view == lowerLeft ? compactPanel : compactLayer;
                parent.addView(view, new FrameLayout.LayoutParams(0, 0));
            } else expandedParents[i].addView(view, expandedIndices[i], expandedPositions[i]);
        }

        speedChart.setEdgeFade(compact);
        speedReadout.setGravity((compact ? Gravity.CENTER_HORIZONTAL : Gravity.START) | Gravity.BOTTOM);
        speedReadout.setMinimumHeight(compact ? 0 : expandedSpeedHeight);
        speed.setLetterSpacing(compact ? 0 : EXPANDED_SPEED_LETTER_SPACING);
        currentTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 15 : 12);
        speedUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 18 : 14);
        pad(speedUnit, compact ? 13 : 7, 0, 0, 2);
        statusIcons.setTranslationX(0);
        if (!compact) {
            for (int i = 0; i < statusIcons.getChildCount(); i++) {
                View icon = statusIcons.getChildAt(i);
                icon.setPadding(0, 0, 0, 0);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(STATUS_ICON_SLOT_DP), -1));
            }
            speedReadout.setTranslationX(0);
            speedReadout.setTranslationY(0);
            speedChart.setTranslationX(0);
            speed.setMaxWidth(Integer.MAX_VALUE);
            preferredSpeedSize = expandedSpeedSize;
            speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, expandedSpeedSize);
            alignStatusClock();
            alignStatusIcons();
        }
        buildMusicCard();
        if (shownNotice != null) {
            (compact ? expandedNotice : compactNotice).icon.setImageDrawable(null);
            renderNotice(activeNoticeView(), shownNotice);
        }
        updateNoticeVisibility(false);
        updateNoticeProgress();
        if (bluetooth != null) refreshMusic();
    }

    private void settleCompactMode(boolean compact) {
        RideLog.event("layout.settled", "compact", compact);
        mapChrome.setVisibility(compact ? View.INVISIBLE : View.VISIBLE);
        expandMap.setVisibility(compact ? View.VISIBLE : View.GONE);
        styleCompactDashboard();
        updateCompactGuidance();
        updateMapOverlayInsets();
    }

    private DashboardTransition.Scene captureDashboardInstruments(View pane) {
        alignExpandedVehicleBottom();
        DashboardTransition.Scene scene = new DashboardTransition.Scene(dashboardStage, pane,
                compactDashboard ? compactPanel : null);
        scene.speedBackground(compactDashboard ? compactReadings : speedCard);
        scene.text("time", currentTime);
        for (int i = 0; i < statusIcons.getChildCount(); i++) {
            scene.icon("status " + i, (ImageView) statusIcons.getChildAt(i));
        }
        scene.text("speed", speed);
        scene.text("unit", speedUnit);
        scene.add("speed curve", speedChart);
        scene.text("range", compactDashboard ? compactRange.value : vehicleRange);
        scene.text("battery", compactDashboard ? compactBattery.value : vehicleBattery);
        if (compactDashboard) {
            scene.add("range label", compactRange.title);
            scene.add("battery label", compactBattery.title);
            scene.add("battery progress", compactCharge);
            scene.text("altitude label", compactAltitude.title);
            scene.text("vibration label", compactVibration.title);
        }
        scene.text("altitude", compactDashboard ? compactAltitude.value : expandedAltitude);
        scene.text("vibration", compactDashboard ? compactVibration.value : expandedVibration);
        scene.mediaBackground(musicPanel);
        scene.media(musicTitle, musicArtist);
        scene.cover(musicCover);
        scene.icon("previous", prev);
        scene.add("play", play);
        scene.icon("next", next);
        scene.add("media progress", musicProgress);
        scene.text("media elapsed", musicPosition);
        scene.text("media duration", musicLength);
        scene.add("shared destination", receivedCard);
        if (compactDashboard) scene.add("compact notification", compactNotice);
        else scene.add("notification", expandedNotice);
        return scene;
    }

    private void positionCompactDashboard() {
        int width = dashboardStage.getWidth();
        int height = dashboardStage.getHeight();
        if (!compactDashboard || width <= 0 || height <= 0) return;
        int x = Math.max(Math.round(width * .031f), screenLeftInset + dp(7) - dashboardRoot.getPaddingLeft());
        int column = Math.round(width * .25f) + dp(6);
        int top = Math.round(height * .019f);
        placeCompact(compactPanel, DashboardStage.compactPanelWidth(width), -1, 0, 0, 0, Gravity.LEFT);
        placeCompact(currentTime, -2, dp(26), 0, top, 0, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        placeCompact(compactNotice, Math.min(dp(380), Math.round(width * .46f)), -2, 0,
                top + dp(35), 0, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        placeCompact(statusIcons, column, dp(26), x, top, 0, Gravity.TOP | Gravity.LEFT);
        distributeCompactStatusIcons(column);
        // Keep the playback time inside the card even when the song text uses taller glyphs.
        musicPanel.measure(View.MeasureSpec.makeMeasureSpec(column, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int musicHeight = Math.max(Math.round(height * .357f), musicPanel.getMeasuredHeight());
        int musicBottom = dp(5);
        int iconsBottom = top + dp(26);
        int preferredHeight = dp(COMPACT_MOTION_HEIGHT_DP);
        if (compactVehicle.getVisibility() == View.VISIBLE) {
            preferredHeight += dp(COMPACT_READINGS_GAP_DP) + dp(COMPACT_VEHICLE_HEIGHT_DP);
        }
        int musicTop = height - musicBottom - musicHeight;
        int railSpace = Math.max(0, musicTop - iconsBottom);
        int readingsHeight = Math.min(preferredHeight, Math.max(0, railSpace - dp(12)));
        int readingsTop = iconsBottom + (railSpace - readingsHeight) / 2;
        placeCompact(compactReadings, column, readingsHeight, x,
                readingsTop, 0, Gravity.TOP | Gravity.LEFT);
        placeCompact(lowerLeft, column, musicHeight, x, 0, musicBottom, Gravity.BOTTOM | Gravity.LEFT);
        placeCompact(speedReadout, Math.round(width * .52f), -2, 0, 0, 0, Gravity.CENTER);
        float centerOffset = (dashboardRoot.getPaddingRight() - dashboardRoot.getPaddingLeft()) / 2f;
        currentTime.setTranslationX(centerOffset);
        compactNotice.setTranslationX(centerOffset);
        speedChart.setTranslationX(centerOffset);
        int chartHeight = Math.round(height * .28f);
        placeCompact(speedChart, Math.round(width * .51f), chartHeight, 0, 0,
                dp(5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        float size = Math.min(width * .16f, height * .34f);
        if (preferredSpeedSize != size) {
            preferredSpeedSize = size;
            speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        compactGuidance.setMaxWidth(Math.max(dp(40),
                DashboardStage.compactPanelWidth(width) - dp(22)));
    }

    private void distributeCompactStatusIcons(int width) {
        int count = statusIcons.getChildCount();
        int glyphWidth = settingsButton.getDrawable().getIntrinsicWidth();
        float spacing = (float) Math.max(0, width - glyphWidth) / Math.max(1, count - 1);
        for (int i = 0; i < count; i++) {
            ImageView icon = (ImageView) statusIcons.getChildAt(i);
            int start = width * i / count;
            int slotWidth = width * (i + 1) / count - start;
            if (icon.getLayoutParams().width != slotWidth) {
                icon.setLayoutParams(new LinearLayout.LayoutParams(slotWidth, -1));
            }
            // Move the visible glyph inside its slot while keeping the entire slot tappable.
            int slack = Math.max(0, slotWidth - glyphWidth);
            int glyphLeft = Math.max(0, Math.min(slack, Math.round(i * spacing) - start));
            int bias = 2 * glyphLeft - slack;
            icon.setPadding(Math.max(0, bias), 0, Math.max(0, -bias), 0);
        }
    }

    private void placeCompact(View view, int width, int height, int left, int top, int bottom, int gravity) {
        FrameLayout.LayoutParams box = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (box.width == width && box.height == height && box.leftMargin == left
                && box.topMargin == top && box.bottomMargin == bottom && box.gravity == gravity) return;
        box.width = width;
        box.height = height;
        box.leftMargin = left;
        box.topMargin = top;
        box.bottomMargin = bottom;
        box.gravity = gravity;
        view.setLayoutParams(box);
    }

    private void styleCompactDashboard() {
        mapSurface.setBackgroundColor(theme.mapBackground);
        int panelColor = theme.surface;
        compactPanel.setBackgroundColor(panelColor);
        compactPanelBackground.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{panelColor, panelColor, panelColor, panelColor & 0x00ffffff}));
        mapEdgeShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{theme.background, theme.background & 0x00ffffff}));
        compactCharge.setProgressBackgroundTintList(ColorStateList.valueOf(theme.line));
        settingsButton.setBackground(clickable(Color.TRANSPARENT, 7));
        compactGuidance.setBackground(clickable((theme.chip & 0x00ffffff) | 0xe0000000, 8));
        if (shownTurn != null) updateCompactGuidance();
    }

    private void updateCompactGuidance() {
        boolean active = dashboardStage.compact() && guidancePanel.getVisibility() == View.VISIBLE
                && destination != null;
        compactGuidance.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;
        compactGuidance.setText(action.getText() + " · " + road.getText());
        Drawable[] drawables = compactGuidance.getCompoundDrawables();
        DashIcon glyph = drawables[0] instanceof DashIcon ? (DashIcon) drawables[0] : null;
        if (glyph == null) {
            glyph = icon(DashIcon.Kind.ROUTE, theme.accent, 14);
            compactGuidance.setCompoundDrawablePadding(dp(5));
            compactGuidance.setCompoundDrawables(glyph, null, null, null);
        }
        glyph.setKind(shownTurn == null ? DashIcon.Kind.ROUTE : shownTurn);
        glyph.setColor(theme.accent);
    }

    private void addUpcomingTurns(LinearLayout parent) {
        turnPreview = group(true);
        pad(turnPreview, 9, 3, 9, 3);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
        box.topMargin = dp(4);
        parent.addView(turnPreview, box);
        turnPreview.setVisibility(View.GONE);
        turnPreview.setOnClickListener(v -> {
            if (!navigation.failed()) setRouteOverview(!routeOverview);
        });
        for (int i = 0; i < UPCOMING_TURN_COUNT; i++) {
            LinearLayout row = group(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView instruction = label("", 11, TextTone.PRIMARY, true);
            oneLine(instruction);
            row.addView(instruction, new LinearLayout.LayoutParams(0, -2, 1));
            TextView distance = label("", 10, TextTone.SECONDARY, true);
            distance.setFontFeatureSettings("'tnum'");
            oneLine(distance);
            pad(distance, 8, 0, 0, 0);
            row.addView(distance, new LinearLayout.LayoutParams(-2, -2));
            turnPreview.addView(row, new LinearLayout.LayoutParams(-1, dp(26)));
            upcomingInstructions[i] = instruction;
            upcomingDistances[i] = distance;
        }
    }

    private void refreshUpcomingTurns() {
        boolean active = route != null && !navigation.planning();
        guidanceButton.setClickable(active || navigation.failed());
        guidanceButton.setFocusable(active || navigation.failed());
        String accessibility = action.getText() + "，" + road.getText()
                + (navigation.failed() ? "，点按重试"
                : active ? "，点按" + (routeOverview ? "返回导航跟随" : "查看路线全览") : "");
        if (!TextUtils.equals(guidanceButton.getContentDescription(), accessibility)) {
            guidanceButton.setContentDescription(accessibility);
        }
        if (!active || !turnsExpanded) {
            turnPreview.setVisibility(View.GONE);
            return;
        }
        List<Route.TurnHint> hints = route.upcomingTurns(UPCOMING_TURN_COUNT);
        for (int i = 0; i < UPCOMING_TURN_COUNT; i++) {
            View row = (View) upcomingInstructions[i].getParent();
            if (i >= hints.size()) {
                row.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
                if (i == 0) {
                    upcomingInstructions[0].setText("暂无后续提示");
                    upcomingDistances[0].setText("");
                }
                continue;
            }
            Route.TurnHint hint = hints.get(i);
            upcomingInstructions[i].setText(turnDetail(hint.action, hint.detail));
            upcomingDistances[i].setText(meters(hint.meters));
            row.setVisibility(View.VISIBLE);
        }
        turnPreview.setVisibility(View.VISIBLE);
    }

    private static String turnDetail(String action, String detail) {
        if (TextUtils.isEmpty(detail)) return action;
        return detail.contains(action) ? detail : action + " · " + detail;
    }

    private void addSpeedStatusRow(LinearLayout parent) {
        LinearLayout row = group(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusBox = new LinearLayout.LayoutParams(-1, dp(21));
        statusBox.bottomMargin = dp(3);
        parent.addView(row, statusBox);
        currentTime = label(arrivalTime.format(new Date()), 12, TextTone.PRIMARY, true);
        currentTime.setFontFeatureSettings("'tnum'");
        row.addView(currentTime, new LinearLayout.LayoutParams(-2, -2));
        // Keep the flexible gap between the clock and the right-aligned icons.
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        statusIcons = group(false);
        statusIcons.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(statusIcons, new LinearLayout.LayoutParams(-2, -1));
        statusIcons.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                alignStatusIcons());
        settingsButton = iconButton(DashIcon.Kind.SETTINGS, "设置", 15, theme.accent);
        settingsButton.setBackground(clickable(Color.TRANSPARENT, 7));
        settingsButton.setOnClickListener(v -> showSettings());
        statusIcons.addView(settingsButton, new LinearLayout.LayoutParams(dp(STATUS_ICON_SLOT_DP), -1));
        themeButton = iconButton(DashIcon.Kind.SUN, theme.label, 15, theme.accent);
        themeButton.setBackground(clickable(Color.TRANSPARENT, 7));
        themeButton.setOnClickListener(v -> toggleTheme());
        statusIcons.addView(themeButton, new LinearLayout.LayoutParams(dp(STATUS_ICON_SLOT_DP), -1));
        refreshThemeButton();
        gpsIndicator = addStatusIcon(statusIcons, DashIcon.Kind.TARGET);
        bluetoothIndicator = addStatusIcon(statusIcons, DashIcon.Kind.BLUETOOTH);
        bluetoothIndicator.setOnClickListener(v -> showBluetoothSetup());
        networkIndicator = addStatusIcon(statusIcons, DashIcon.Kind.CELLULAR);
        batteryIndicator = addStatusIcon(statusIcons, DashIcon.Kind.BATTERY);
    }

    private void alignStatusClock() {
        String number = speed.getText().toString();
        if (compactDashboard) {
            if (speed.getHeight() > 0 && speedReadout.getHeight() > 0) {
                speed.getPaint().getTextBounds(number, 0, number.length(), statusInkBounds);
                float centerOffset = (dashboardRoot.getPaddingRight() - dashboardRoot.getPaddingLeft()) / 2f;
                float lineLeft = speed.getLayout() == null ? 0 : speed.getLayout().getLineLeft(0);
                float inkCenterX = speed.getLeft() + speed.getTotalPaddingLeft() + lineLeft
                        - speed.getScrollX() + statusInkBounds.exactCenterX();
                speedReadout.setTranslationX(centerOffset + speedReadout.getWidth() / 2f - inkCenterX);
                float inkCenter = speed.getTop() + speed.getBaseline() + statusInkBounds.exactCenterY();
                speedReadout.setTranslationY(speedReadout.getHeight() / 2f - inkCenter);
            }
            return;
        }
        String clock = currentTime.getText().toString();
        speed.getPaint().getTextBounds(number, 0, number.length(), statusInkBounds);
        int speedInkLeft = statusInkBounds.left;
        currentTime.getPaint().getTextBounds(clock, 0, clock.length(), statusInkBounds);
        int offset = speedInkLeft - statusInkBounds.left;
        currentTime.setTranslationX(offset);
    }

    private void alignStatusIcons() {
        if (compactDashboard || batteryIndicator.getWidth() == 0) return;
        boolean vehicle = vehiclePanel != null && vehiclePanel.getVisibility() == View.VISIBLE;
        float iconInset = (batteryIndicator.getWidth() - batteryIndicator.getDrawable().getIntrinsicWidth()) / 2f;
        statusIcons.setTranslationX(iconInset - (vehicle ? vehicleEndInset() : 0));
    }

    private float vehicleEndInset() {
        CharSequence text = vehicleBattery.getText();
        int end = text.length();
        if (end == 0) return 0;
        statusInkPaint.set(vehicleBattery.getPaint());
        if (text.charAt(end - 1) == '%') {
            statusInkPaint.setTextSize(statusInkPaint.getTextSize() * READING_UNIT_SCALE);
        }
        String lastGlyph = text.subSequence(end - 1, end).toString();
        statusInkPaint.getTextBounds(lastGlyph, 0, lastGlyph.length(), statusInkBounds);
        return statusInkPaint.measureText(lastGlyph) - statusInkBounds.right;
    }

    private void alignExpandedVehicleBottom() {
        if (compactDashboard || vehiclePanel == null || vehiclePanel.getVisibility() != View.VISIBLE
                || speed.getHeight() == 0 || vehicleRange.getHeight() == 0 || vehicleBattery.getHeight() == 0) return;
        View row = (View) speedReadout.getParent();
        int bottom = readingInkBottom(speed, row);
        int rangeBottom = readingInkBottom(vehicleRange, row);
        int batteryBottom = readingInkBottom(vehicleBattery, row);
        if (bottom < 0 || rangeBottom < 0 || batteryBottom < 0) return;
        vehicleRange.setTranslationY(bottom - rangeBottom);
        vehicleBattery.setTranslationY(bottom - batteryBottom);
    }

    private int readingInkBottom(TextView value, View row) {
        int bottom = value.getBaseline();
        if (bottom < 0) return -1;
        for (View view = value; view != row; view = (View) view.getParent()) bottom += view.getTop();
        value.getPaint().getTextBounds("0", 0, 1, statusInkBounds);
        return bottom + statusInkBounds.bottom;
    }

    private void addRideReadings(LinearLayout speedRow) {
        LinearLayout details = group(true);
        LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(0, -1, 1);
        detailSize.setMarginStart(dp(EXPANDED_READINGS_GAP_DP));
        speedRow.addView(details, detailSize);

        LinearLayout motion = group(false);
        motion.setBaselineAligned(false);
        details.addView(motion, new LinearLayout.LayoutParams(-1, 0, 1));
        expandedAltitude = addRideReading(motion);
        expandedVibration = addRideReading(motion);

        LinearLayout vehicle = group(false);
        vehicle.setBaselineAligned(false);
        vehicle.setVisibility(View.GONE);
        vehiclePanel = vehicle;
        details.addView(vehicle, new LinearLayout.LayoutParams(-1, 0, 1));
        vehicleRange = addRideReading(vehicle);
        vehicleBattery = addRideReading(vehicle);
        expandedReadingSizes = new ReadingSizes(vehicle, expandedAltitude, expandedVibration, vehicleRange, vehicleBattery);
        vehicleBattery.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                alignStatusIcons());
    }

    private TextView addRideReading(LinearLayout panel) {
        TextView value = label("—", READING_VALUE_SP, TextTone.PRIMARY, true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setFontFeatureSettings("'tnum'");
        value.setMaxLines(1);
        value.setEllipsize(TextUtils.TruncateAt.END);
        boolean firstColumn = panel.getChildCount() == 0;
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(0, -1, firstColumn ? 1.1f : .9f);
        if (firstColumn) box.setMarginEnd(dp(5));
        panel.addView(value, box);
        return value;
    }

    private ImageView addStatusIcon(LinearLayout row, DashIcon.Kind kind) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageDrawable(icon(kind, theme.dim, 15));
        row.addView(view, new LinearLayout.LayoutParams(dp(STATUS_ICON_SLOT_DP), -1));
        return view;
    }

    private void statusAppearance(ImageView view, int color, String description) {
        ((DashIcon) view.getDrawable()).setColor(color);
        if (!TextUtils.equals(view.getContentDescription(), description)) view.setContentDescription(description);
    }

    private Integer batteryProperty(int property) {
        if (battery == null) return null;
        int value = battery.getIntProperty(property);
        return value == Integer.MIN_VALUE ? null : value;
    }

    private void recordPhonePower(boolean force) {
        if (!force && !RideLog.due("phone.power", 5000)) return;
        Intent state = registerReceiver(null, BATTERY_STATE);
        if (state == null) return;
        int voltage = state.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plug = state.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean warning = plug == 0 && voltage > 0 && voltage <= 3300 && scale > 0 && level * 100 / scale >= 20;
        RideLog.event("phone.power", "percent", scale > 0 ? level * 100 / scale : -1,
                "voltageMv", voltage, "temperatureC", state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
                "health", state.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
                "status", state.getIntExtra(BatteryManager.EXTRA_STATUS, 0), "plugged", plug,
                "currentUa", batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                "chargeCounterUah", batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                "lowVoltageWithRemainingCharge", warning);
    }

    private void refreshDeviceStatus() {
        String clock = arrivalTime.format(new Date());
        if (!TextUtils.equals(currentTime.getText(), clock)) {
            currentTime.setText(clock);
            alignStatusClock();
        }
        refreshVehicle();

        refreshBluetoothIndicator();
        refreshNetworkIndicator();

        Intent batteryState = registerReceiver(null, BATTERY_STATE);
        int level;
        boolean charging;
        if (batteryState != null) {
            int rawLevel = batteryState.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryState.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            level = rawLevel >= 0 && scale > 0 ? Math.round(100f * rawLevel / scale) : -1;
            charging = batteryState.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    == BatteryManager.BATTERY_STATUS_CHARGING;
        } else {
            level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            charging = battery != null && battery.isCharging();
        }
        if (level > 100) level = -1;
        ((DashIcon) batteryIndicator.getDrawable()).setBattery(level, charging);
        String batteryText = level < 0 ? "手机电量未知" : "手机电量 " + level + "%";
        int batteryColor = level >= 0 || charging ? theme.accent : theme.dim;
        statusAppearance(batteryIndicator, batteryColor, charging ? "充电中，" + batteryText : batteryText);
    }

    private boolean expectsBluetoothPhone() {
        return bluetooth.enabled()
                || (!bluetooth.requiresManualReconnectAfterUpdate()
                    && !TextUtils.isEmpty(getPreferences(MODE_PRIVATE).getString(PAIRED_DEVICE, null)));
    }

    private int bluetoothWarningColor() { return theme.dark ? 0xffff9186 : 0xffac2d27; }

    private void refreshBluetoothIndicator() {
        boolean warning = expectsBluetoothPhone() && !bluetoothNotifications;
        String connection = bluetooth.linked() ? connectedBluetoothStatus() : bluetoothStatus;
        String notifications = bluetoothNotifications ? "，蓝牙通知已同步" : warning ? "，蓝牙通知未同步" : "";
        statusAppearance(bluetoothIndicator,
                warning ? bluetoothWarningColor() : bluetooth.linked() ? theme.accent : theme.dim,
                connection + notifications);
    }

    private void refreshVehicle() {
        boolean connected = ninebot.connected();
        int visibility = connected ? View.VISIBLE : View.GONE;
        if (vehiclePanel.getVisibility() != visibility) {
            vehiclePanel.setVisibility(visibility);
            alignStatusIcons();
            expandedReadingSizes.fit();
        }
        if (ninebotLinkSwitch != null) ninebotLinkSwitch.setChecked(connected);
        if (compactVehicle != null && compactVehicle.getVisibility() != visibility) {
            compactVehicle.setVisibility(visibility);
            if (compactDashboard) positionCompactDashboard();
            compactReadingSizes.fit();
        }
        if (!connected) return;
        NinebotVehicle.Reading reading = ninebot.reading();
        boolean old = reading.old();
        String battery = reading.percent < 0 ? "—" : reading.percent + "%";
        String range = reading.range.isEmpty() ? "—" : reading.range;
        boolean expandedChanged = setReadingText(vehicleBattery, battery, "车辆电量");
        expandedChanged |= setReadingText(vehicleRange, range, "预计续航");
        if (expandedChanged) expandedReadingSizes.fit();
        int vehicleTextColor = old ? theme.muted : theme.text;
        vehicleBattery.setTextColor(vehicleTextColor);
        vehicleRange.setTextColor(vehicleTextColor);
        if (compactVehicle != null) {
            boolean compactChanged = compactBattery.show(battery);
            compactChanged |= setReadingText(compactRange.value, range, "预计续航");
            if (compactChanged) compactReadingSizes.fit();
            compactBattery.value.setTextColor(vehicleTextColor);
            compactRange.value.setTextColor(vehicleTextColor);
            compactCharge.setVisibility(reading.percent < 0 ? View.INVISIBLE : View.VISIBLE);
            if (reading.percent >= 0) compactCharge.setProgress(reading.percent);
            compactCharge.setProgressTintList(ColorStateList.valueOf(old ? theme.muted : theme.accent));
        }
    }

    private static boolean setReadingText(TextView view, String text, String label) {
        boolean changed = !TextUtils.equals(view.getText(), text);
        if (changed) {
            SpannableString value = new SpannableString(text);
            int unit = text.endsWith("%") || text.endsWith("g") ? text.length() - 1 : text.indexOf(' ');
            if (unit > 0) {
                value.setSpan(new RelativeSizeSpan(READING_UNIT_SCALE), unit, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            view.setText(value);
        }
        String description = label + ("—".equals(text) ? "未知" : " " + text);
        if (!TextUtils.equals(view.getContentDescription(), description)) view.setContentDescription(description);
        return changed;
    }

    private void refreshNetworkIndicator() {
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        boolean wireless = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean cellular = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean online = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        DashIcon glyph = (DashIcon) networkIndicator.getDrawable();
        glyph.setKind(wireless ? DashIcon.Kind.WIFI : DashIcon.Kind.CELLULAR);
        int level = wireless ? wifiSignalLevel(capabilities) : cellular ? cellSignalLevel() : -1;
        glyph.setSignalLevel(level);
        String type = wireless ? "Wi-Fi" : cellular ? "移动网络" : "网络";
        String detail = online ? "可用" : "不可用";
        if (level >= 0) detail += "，信号 " + level + "/4";
        statusAppearance(networkIndicator, online ? theme.accent : theme.dim, type + detail);
    }

    private int wifiSignalLevel(NetworkCapabilities capabilities) {
        try {
            WifiInfo info = Build.VERSION.SDK_INT >= 29 && capabilities.getTransportInfo() instanceof WifiInfo
                    ? (WifiInfo) capabilities.getTransportInfo() : wifi == null ? null : wifi.getConnectionInfo();
            int rssi = info == null ? -127 : info.getRssi();
            return rssi <= -127 ? -1 : WifiManager.calculateSignalLevel(rssi, 5);
        } catch (SecurityException ignored) {
            return -1;
        }
    }

    private int cellSignalLevel() {
        if (telephony == null || Build.VERSION.SDK_INT < 28) return -1;
        try {
            int dataSubscription = SubscriptionManager.getDefaultDataSubscriptionId();
            TelephonyManager radio = dataSubscription < 0 ? telephony : telephony.createForSubscriptionId(dataSubscription);
            SignalStrength strength = radio.getSignalStrength();
            return strength == null ? -1 : strength.getLevel();
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1;
        }
    }

    private void buildMusicCard() {
        if (musicPanel != null) lowerLeft.removeView(musicPanel);
        LinearLayout music = group(true);
        musicPanel = music;
        music.setBackground(compactDashboard ? null : musicSurface());
        if (compactDashboard) pad(music, 0, 4, 0, 2);
        else padMediaCard(music);
        lowerLeft.addView(music, 0, new FrameLayout.LayoutParams(-1, -1));
        if (received != null || (!compactDashboard && shownNotice != null)) music.setVisibility(View.INVISIBLE);

        musicCover = new ImageView(this);
        musicCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        showMusicCover(null);
        musicCover.setContentDescription("音乐封面，长按查看蓝牙设备");
        musicCover.setOnLongClickListener(v -> {
            showBluetoothSetup();
            return true;
        });

        LinearLayout song = group(true);
        song.setGravity(Gravity.CENTER_VERTICAL);
        if (musicTitle == null) {
            musicTitle = new ScrollingTitle(this);
            styleLabel(musicTitle, "未在播放", 22, TextTone.PRIMARY, true);
            musicTitle.setGravity(Gravity.START);
            pad(musicTitle, 0, 2, 0, 0);
        } else {
            ((ViewGroup) musicTitle.getParent()).removeView(musicTitle);
        }
        song.addView(musicTitle, new LinearLayout.LayoutParams(-1, -2));
        musicArtist = label("", 12, TextTone.SECONDARY, false);
        oneLine(musicArtist);
        musicArtist.setGravity(Gravity.START);
        pad(musicArtist, 0, 2, 0, 0);
        if (compactDashboard) {
            musicArtist.setIncludeFontPadding(true);
            pad(musicArtist, 0, 2, 0, 1);
        }
        song.addView(musicArtist, new LinearLayout.LayoutParams(-1, -2));
        song.setOnClickListener(v -> {
            if (expectsBluetoothPhone() && (!bluetoothNotifications || bluetoothMedia == null)) showBluetoothSetup();
            else musicSettings();
        });
        song.setOnLongClickListener(v -> {
            showBluetoothSetup();
            return true;
        });

        play = transportButton(DashIcon.Kind.PLAY, "播放或暂停", PlaybackState.ACTION_PLAY_PAUSE, true, () -> {
            PlaybackState state = media.getPlaybackState();
            if (state == null) return;
            MediaController.TransportControls controls = media.getTransportControls();
            long separateAction = isPlaying(media) ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY;
            if ((state.getActions() & separateAction) == 0) {
                long time = SystemClock.uptimeMillis();
                media.dispatchMediaButtonEvent(new android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                media.dispatchMediaButtonEvent(new android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            } else if (isPlaying(media)) controls.pause();
            else controls.play();
        });
        playIcon = (DashIcon) play.getDrawable();
        prev = transportButton(DashIcon.Kind.PREVIOUS, "上一首", PlaybackState.ACTION_SKIP_TO_PREVIOUS, false,
                () -> media.getTransportControls().skipToPrevious());
        next = transportButton(DashIcon.Kind.NEXT, "下一首", PlaybackState.ACTION_SKIP_TO_NEXT, false,
                () -> media.getTransportControls().skipToNext());
        LinearLayout controls = group(false);
        controls.setGravity(Gravity.CENTER);
        if (compactDashboard) {
            LinearLayout row = group(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int coverSize = Math.max(31, Math.min(43, getResources().getConfiguration().screenHeightDp / 8));
            music.addView(row, new LinearLayout.LayoutParams(-1, -2));
            addMusicCover(row, coverSize);
            row.addView(song, new LinearLayout.LayoutParams(0, -2, 1));
            music.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            music.addView(controls, new LinearLayout.LayoutParams(-1, dp(36)));
            music.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        } else {
            LinearLayout row = new LinearLayout(this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    // Keep the art square and reserve space for the controls on narrow dashboards.
                    int side = Math.min(MeasureSpec.getSize(heightSpec),
                            Math.round(MeasureSpec.getSize(widthSpec) * .36f));
                    ViewGroup.LayoutParams art = getChildAt(0).getLayoutParams();
                    art.height = art.width = side;
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            row.setGravity(Gravity.CENTER_VERTICAL);
            music.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            addMusicCover(row, 0);
            LinearLayout details = group(true);
            row.addView(details, new LinearLayout.LayoutParams(0, -1, 1));
            details.addView(song, new LinearLayout.LayoutParams(-1, 0, 1));
            details.addView(controls, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        controls.addView(prev, new LinearLayout.LayoutParams(0, -1, 1));
        int playSize = dp(compactDashboard ? 34 : 38);
        controls.addView(play, new LinearLayout.LayoutParams(playSize, playSize));
        controls.addView(next, new LinearLayout.LayoutParams(0, -1, 1));

        musicSeekSession = null;
        musicSeekTrack = null;
        musicProgress = new SeekBar(this);
        musicProgress.setContentDescription("音乐播放进度");
        musicProgress.setMax(1000);
        musicProgress.setThumb(null);
        musicProgress.setThumbOffset(0);
        musicProgress.setPadding(0, 0, 0, 0);
        LayerDrawable track = new LayerDrawable(new Drawable[]{round(theme.line, 3),
                new ClipDrawable(round(theme.accent, 3), Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < track.getNumberOfLayers(); i++) {
            track.setLayerHeight(i, dp(3));
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        musicProgress.setProgressDrawable(track);
        musicProgress.setEnabled(false);
        musicProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                musicSeekSession = media;
                musicSeekTrack = media == null ? null : media.getMetadata();
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && musicSeekSession == null) seekMusic(media, media == null ? null : media.getMetadata(), value);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekMusic(musicSeekSession, musicSeekTrack, bar.getProgress());
                musicSeekSession = null;
                musicSeekTrack = null;
            }
        });
        musicProgress.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                musicSeekSession = null;
                musicSeekTrack = null;
            }
            return false;
        });
        LinearLayout.LayoutParams progressBox = new LinearLayout.LayoutParams(-1, dp(compactDashboard ? 14 : 18));
        progressBox.topMargin = dp(1);
        LinearLayout times = group(false);
        times.setBaselineAligned(false);
        times.setGravity(Gravity.CENTER_VERTICAL);
        musicTimes = times;
        musicPosition = musicTimeLabel();
        musicLength = musicTimeLabel();
        if (compactDashboard) {
            music.addView(musicProgress, progressBox);
            times.addView(musicPosition, new LinearLayout.LayoutParams(0, -2, 1));
            times.addView(musicLength, new LinearLayout.LayoutParams(-2, -2));
            music.addView(times, new LinearLayout.LayoutParams(-1, -2));
            positionCompactDashboard();
        } else {
            music.setPadding(music.getPaddingLeft(), music.getPaddingTop(), music.getPaddingRight(), dp(4));
            pad(musicPosition, 0, 0, 5, 0);
            pad(musicLength, 5, 0, 0, 0);
            times.addView(musicPosition, new LinearLayout.LayoutParams(-2, -2));
            times.addView(musicProgress, new LinearLayout.LayoutParams(0, -1, 1));
            times.addView(musicLength, new LinearLayout.LayoutParams(-2, -2));
            music.addView(times, progressBox);
        }
    }

    private TextView musicTimeLabel() {
        TextView label = label("0:00", 10, TextTone.SECONDARY, false);
        oneLine(label);
        label.setFontFeatureSettings("'tnum'");
        label.setVisibility(compactDashboard ? View.INVISIBLE : View.GONE);
        return label;
    }

    private void addMusicCover(LinearLayout row, int size) {
        LinearLayout.LayoutParams coverBox = new LinearLayout.LayoutParams(dp(size), dp(size));
        coverBox.setMarginEnd(dp(9));
        row.addView(musicCover, coverBox);
    }

    private enum TextTone { PRIMARY, SECONDARY }

    private enum Theme {
        DAY("日间", false, 0xffe8eef1, 0xfff7fafb, 0xffeaf1f4, 0xffc7d5dd, 0xffe9eeeb,
                0xff102937, 0xff476170, 0xff607783, 0xff006872, 0xffffffff,
                0xffcaede7, 0xffedf6f5, 0xff24586d),
        NIGHT("夜间", true, 0xff080d14, 0xff111b27, 0xff1a2937, 0xff2b3f4c, 0xff121d29,
                0xfff0f8fa, 0xffa2b6c3, 0xff718797, 0xff65e2ce, 0xff082620,
                0xff1b4649, 0xff142934, 0xff223b59);

        final String label;
        final boolean dark;
        final int background, surface, chip, line, mapBackground;
        final int text, muted, dim, accent, onAccent;
        final int speedTop, speedMid, coverDark;

        Theme(String label, boolean dark, int background, int surface, int chip, int line, int mapBackground,
                int text, int muted, int dim, int accent, int onAccent,
                int speedTop, int speedMid, int coverDark) {
            this.label = label;
            this.dark = dark;
            this.background = background;
            this.surface = surface;
            this.chip = chip;
            this.line = line;
            this.mapBackground = mapBackground;
            this.text = text;
            this.muted = muted;
            this.dim = dim;
            this.accent = accent;
            this.onAccent = onAccent;
            this.speedTop = speedTop;
            this.speedMid = speedMid;
            this.coverDark = coverDark;
        }
    }

    private <T extends Enum<T>> T readChoice(String key, T fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), getPreferences(MODE_PRIVATE).getString(key, fallback.name()));
        } catch (IllegalArgumentException | ClassCastException exception) {
            return fallback;
        }
    }

    private void showSettings() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout entries = group(true);
        pad(entries, 20, 0, 20, 4);
        scroller.addView(entries, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = dialogBuilder().setTitle("设置").setView(scroller)
                .setNegativeButton("返回", null).create();
        AmapClient.Place home = homePlace();
        addSettingsRow(entries, "家的地址", home == null ? "未设置" : home.name, () -> {
            dialog.dismiss();
            showPlaceSearch(true);
        });
        Switch association = new Switch(dialog.getContext());
        ninebotLinkSwitch = association;
        styleLabel(association, "九号APP关联", 15, TextTone.PRIMARY, false);
        association.setGravity(Gravity.CENTER_VERTICAL);
        association.setBackground(clickable(Color.TRANSPARENT, 8));
        pad(association, 4, 0, 4, 0);
        association.setChecked(ninebot.connected());
        association.setOnClickListener(v -> changeNinebotAssociation(association.isChecked()));
        entries.addView(association, new LinearLayout.LayoutParams(-1, dp(40)));
        addSettingsRow(entries, "版本号", installedVersion(), null);
        dialog.setOnDismissListener(v -> ninebotLinkSwitch = null);
        showCenteredDialog(dialog);
    }

    private void changeNinebotAssociation(boolean enabled) {
        if (!enabled) {
            ninebot.disconnect();
            return;
        }
        if (!ninebot.available()) {
            refreshVehicle();
            toast("请先安装并登录支持车辆小组件的九号出行");
            return;
        }
        Intent permission = ninebot.connect();
        if (permission == null) return;
        try {
            startActivityForResult(permission, REQUEST_NINEBOT_WIDGET);
        } catch (ActivityNotFoundException ignored) {
            ninebot.disconnect();
            toast("系统无法添加小组件");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_NINEBOT_WIDGET) ninebot.bindingFinished();
    }

    private void addSettingsRow(LinearLayout parent, String labelText, String valueText, Runnable action) {
        LinearLayout row = group(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 4, 0, 4, 0);
        TextView name = label(labelText, 15, TextTone.PRIMARY, false);
        oneLine(name);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label(valueText, 14, TextTone.SECONDARY, false);
        oneLine(value);
        value.setMaxWidth(dp(180));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        if (action != null) {
            row.setBackground(clickable(Color.TRANSPARENT, 8));
            row.setFocusable(true);
            row.setOnClickListener(v -> action.run());
            TextView arrow = label("›", 18, TextTone.SECONDARY, false);
            pad(arrow, 8, 0, 0, 0);
            row.addView(arrow);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "—";
        }
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, theme.dark ? R.style.NightDialog : R.style.DayDialog);
    }

    private void styleSystemBars() {
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
    }

    private GradientDrawable tripSurface() {
        int rgb = theme.surface & 0x00ffffff;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{rgb, 0x67000000 | rgb, 0xc8000000 | rgb, 0xf2000000 | rgb});
    }

    private void showCenteredDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        dialog.show();
    }

    private GradientDrawable speedSurface() {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{theme.speedTop, theme.speedMid, theme.surface});
        shape.setCornerRadius(dp(17));
        shape.setStroke(dp(1), theme.line);
        return shape;
    }

    private GradientDrawable musicSurface() {
        GradientDrawable shape = card();
        shape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shape.setGradientCenter(0f, 1f);
        shape.setGradientRadius(dp(156));
        shape.setColors(new int[]{theme.speedTop, theme.speedMid, theme.surface, theme.surface});
        return shape;
    }

    private void applyTheme() {
        int accent = theme.accent;
        for (Map.Entry<TextView, TextTone> label : textTones.entrySet()) {
            TextView view = label.getKey();
            if (view != null) view.setTextColor(textColor(label.getValue()));
        }
        dashboardRoot.setBackgroundColor(theme.background);
        styleCompactDashboard();
        styleSystemBars();
        fullscreen();
        ((DashIcon) endNavigationButton.getDrawable()).setColor(theme.muted);
        styleMapControls();
        tripPanel.setBackground(tripSurface());
        routeSpeedChart.setAccent(accent);
        searchButton.setCompoundDrawables(icon(DashIcon.Kind.SEARCH, accent, 17), null, null, null);
        refreshMapDirectionButton();
        ((DashIcon) settingsButton.getDrawable()).setColor(accent);
        refreshThemeButton();
        recenterButton.setImageDrawable(icon(DashIcon.Kind.TARGET, accent, 22));
        locationButton.setTextColor(accent);
        turnBadge.setBackground(round(accent, 9));
        turnIcon.setColor(theme.onAccent);
        routeArrival.setTextColor(accent);
        confirmRouteButton.setBackground(clickable(accent, 8));
        confirmRouteButton.setTextColor(theme.onAccent);
        receivedPin.setBackground(round(accent, 10));
        ((DashIcon) receivedPin.getDrawable()).setColor(theme.onAccent);
        speedCard.setBackground(speedSurface());
        styleNotice(expandedNotice, false);
        styleNotice(compactNotice, true);
        receivedCard.setBackground(card());
        speedChart.setAccent(accent);
        speedChart.setVibrationColor(theme.muted);
        map.setTheme(accent, theme.dark);
        refreshDeviceStatus();
        refreshLocationStatus();
        buildMusicCard();
        refreshMusic();
    }

    private final class NoticeView extends FrameLayout {
        final TextView source, title, message;
        final ImageView icon;
        final View progress;
        private final int dismissDirection, touchSlop;
        private NoticeCardContent touchedNotice;
        private float touchX, touchY;
        private boolean dragging;

        NoticeView(boolean compact) {
            super(DashboardActivity.this);
            dismissDirection = compact ? -1 : 1;
            touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            source = label("", compact ? 10 : 11, TextTone.SECONDARY, true);
            title = label("", compact ? 14 : 16, TextTone.PRIMARY, true);
            message = label("", compact ? 13 : 17, TextTone.PRIMARY, false);
            icon = new ImageView(DashboardActivity.this) {
                private final Path clip = new Path();

                @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                    super.onSizeChanged(width, height, oldWidth, oldHeight);
                    clip.reset();
                    float radius = dp(NOTICE_ICON_RADIUS_DP);
                    clip.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
                }

                @Override protected void onDraw(Canvas canvas) {
                    if (canvas.isHardwareAccelerated()) {
                        super.onDraw(canvas);
                        return;
                    }
                    // The software canvas used during dashboard transitions does not clip to the view outline.
                    int save = canvas.save();
                    canvas.clipPath(clip);
                    super.onDraw(canvas);
                    canvas.restoreToCount(save);
                }
            };
            progress = new View(DashboardActivity.this);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchedNotice = shownNotice;
                touchX = event.getRawX();
                touchY = event.getRawY();
                dragging = false;
                return touchedNotice != null;
            }
            if (touchedNotice == null || touchedNotice != shownNotice) return true;
            float distance = (event.getRawY() - touchY) * dismissDirection;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        if (distance <= touchSlop || distance <= 1.25f * Math.abs(event.getRawX() - touchX)) return true;
                        animate().cancel();
                        dragging = true;
                    }
                    float drag = Math.max(0, Math.min(getHeight(), distance));
                    setTranslationY(dismissDirection * drag);
                    setAlpha(Math.max(.16f, 1f - drag / getHeight()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (dragging) {
                        boolean dismiss = event.getActionMasked() == MotionEvent.ACTION_UP
                                && distance >= Math.max(dp(18), getHeight() * .3f);
                        finishSwipe(dismiss, touchedNotice);
                    }
                    dragging = false;
                    touchedNotice = null;
                    break;
            }
            return true;
        }

        private void finishSwipe(boolean dismiss, NoticeCardContent notice) {
            animate().translationY(dismiss ? dismissDirection * getHeight() : 0)
                    .alpha(dismiss ? 0 : 1).setDuration(150).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        if (!dismiss || this != activeNoticeView() || shownNotice != notice) return;
                        setVisibility(View.INVISIBLE);
                        dismissNotice();
                    }).start();
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setDismissable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_DISMISS && this == activeNoticeView() && shownNotice != null) {
                dismissNotice();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    private NoticeView addNotificationCard(FrameLayout parent, boolean compact) {
        NoticeView notice = new NoticeView(compact);
        notice.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        notice.icon.setClipToOutline(true);
        notice.icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        oneLine(notice.source);
        oneLine(notice.title);

        if (compact) {
            LinearLayout row = group(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            pad(row, 14, 7, 22, 9);
            row.addView(notice.icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            LinearLayout copy = group(true);
            LinearLayout.LayoutParams copyBox = new LinearLayout.LayoutParams(0, -2, 1);
            copyBox.setMarginStart(dp(9));
            row.addView(copy, copyBox);

            LinearLayout heading = group(false);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            copy.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            notice.source.setMaxWidth(dp(80));
            LinearLayout.LayoutParams sourceBox = new LinearLayout.LayoutParams(-2, -2);
            sourceBox.setMarginEnd(dp(7));
            heading.addView(notice.source, sourceBox);
            heading.addView(notice.title, new LinearLayout.LayoutParams(0, -2, 1));
            oneLine(notice.message);
            pad(notice.message, 0, 3, 0, 1);
            copy.addView(notice.message, new LinearLayout.LayoutParams(-1, -2));
            notice.addView(row, new FrameLayout.LayoutParams(-1, -2));
        } else {
            addExpandedNoticeContent(notice);
        }

        FrameLayout progress = new FrameLayout(this);
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams progressBox = new FrameLayout.LayoutParams(-1, dp(compact ? 1 : 2), Gravity.BOTTOM);
        progressBox.setMarginStart(dp(compact ? 57 : 12));
        progressBox.setMarginEnd(dp(compact ? 22 : 12));
        progressBox.bottomMargin = dp(compact ? 5 : 7);
        notice.addView(progress, progressBox);
        notice.progress.setPivotX(0f);
        progress.addView(notice.progress, new FrameLayout.LayoutParams(-1, -1));
        styleNotice(notice, compact);
        notice.setVisibility(View.INVISIBLE);
        parent.addView(notice, compact ? new FrameLayout.LayoutParams(0, 0) : new FrameLayout.LayoutParams(-1, -1));
        return notice;
    }

    private void addExpandedNoticeContent(NoticeView notice) {
        LinearLayout content = group(true);
        padMediaCard(content);
        notice.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = group(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));
        header.addView(notice.icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout heading = group(true);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleBox = new LinearLayout.LayoutParams(0, -2, 1);
        titleBox.setMarginStart(dp(9));
        header.addView(heading, titleBox);
        heading.addView(notice.source, new LinearLayout.LayoutParams(-1, -2));
        pad(notice.title, 0, 2, 0, 0);
        heading.addView(notice.title, new LinearLayout.LayoutParams(-1, -2));

        notice.message.setMaxLines(2);
        notice.message.setEllipsize(TextUtils.TruncateAt.END);
        notice.message.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2);
        body.topMargin = dp(8);
        content.addView(notice.message, body);
    }

    private void styleNotice(NoticeView notice, boolean compact) {
        GradientDrawable surface = card();
        if (compact) {
            int rgb = theme.speedMid & 0x00ffffff;
            surface = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{rgb, 0x9c000000 | rgb, 0xc4000000 | rgb, 0xce000000 | rgb,
                            0xc4000000 | rgb, 0x9c000000 | rgb, rgb});
            surface.setCornerRadius(dp(13));
        }
        notice.setBackground(surface);
        notice.progress.setBackground(round(theme.accent, 3));
        notice.progress.setAlpha(compact ? .65f : 1f);
        ((View) notice.progress.getParent()).setBackground(round(theme.line, 3));
    }

    private Drawable bluetoothNoticeIcon(String identifier) {
        String installedPackage;
        String id = identifier.toLowerCase(Locale.ROOT);
        switch (id) {
            case "com.tencent.xin":
            case "com.tencent.mm":
                Drawable wechat = MediaAccess.applicationIcon(this, "com.tencent.mm");
                return wechat != null ? wechat : icon(DashIcon.Kind.WECHAT, Color.WHITE, 38);
            case "ph.telegra.telegraph":
            case "org.telegram.messenger":
            case "org.telegram.messenger.web":
                Drawable telegram = MediaAccess.applicationIcon(this, "org.telegram.messenger");
                if (telegram == null) telegram = MediaAccess.applicationIcon(this, "org.telegram.messenger.web");
                return telegram != null ? telegram : icon(DashIcon.Kind.TELEGRAM, Color.WHITE, 38);
            case "app.swiftgram.ios":
                return getDrawable(R.drawable.ic_swiftgram);
            case "com.tencent.mqq": installedPackage = "com.tencent.mobileqq"; break;
            case "com.whatsapp.whatsapp": installedPackage = "com.whatsapp"; break;
            case "com.alibaba.dingtalk": installedPackage = "com.alibaba.android.rimet"; break;
            case "com.ss.iphone.ugc.aweme": installedPackage = "com.ss.android.ugc.aweme"; break;
            case "com.xingin.discover": installedPackage = "com.xingin.xhs"; break;
            default: installedPackage = identifier;
        }
        return MediaAccess.applicationIcon(this, installedPackage);
    }

    private void showNotice(String source, String title, String conversation, String message, Drawable logo) {
        RideLog.event("notice.received", "viaBluetooth", bluetoothNotifications, "compact", compactDashboard,
                "titleLength", title == null ? 0 : title.length(), "bodyLength", message == null ? 0 : message.length());
        if (recentNotices.size() >= 48) recentNotices.removeFirst();
        recentNotices.addLast(new NoticeCardContent(source, noticeDetails(source, title, conversation), message, logo));
        refreshLowerCard();
    }

    private static final class NoticeCardContent {
        final String source, details, message;
        final Drawable logo;
        final long deadline = SystemClock.elapsedRealtime() + NOTICE_DISPLAY_MS;

        NoticeCardContent(String source, String details, String message, Drawable logo) {
            this.source = source;
            this.details = details;
            this.message = message;
            this.logo = logo;
        }
    }

    private void renderNotice(NoticeView view, NoticeCardContent notice) {
        view.source.setText(notice.source);
        view.source.setVisibility(notice.source.isEmpty() ? View.GONE : View.VISIBLE);
        view.title.setText(notice.details);
        view.title.setVisibility(notice.details.isEmpty() ? View.GONE : View.VISIBLE);
        Drawable logo = notice.logo;
        boolean bubble = "信息".equals(notice.source);
        int badge = logo != null ? Color.TRANSPARENT : bubble ? 0xff23b44c : theme.coverDark;
        view.icon.setBackground(round(badge, NOTICE_ICON_RADIUS_DP));
        view.icon.setScaleType(logo == null ? ImageView.ScaleType.CENTER : ImageView.ScaleType.FIT_CENTER);
        view.icon.setImageDrawable(logo != null ? logo : icon(DashIcon.Kind.MESSAGE, Color.WHITE, 24));
        view.message.setText(notice.message);
        view.message.setVisibility(notice.message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String noticeDetails(String source, String title, String conversation) {
        if (title.equals(source)) title = "";
        if (conversation.equals(source)) conversation = "";
        if (title.isEmpty()) return conversation;
        if (conversation.isEmpty() || title.contains(conversation)
                || title.contains("→") || title.contains("->")) return title;
        return title + " → " + conversation;
    }

    private void dismissNotice() {
        if (shownNotice == null) return;
        recentNotices.removeLastOccurrence(shownNotice);
        refreshLowerCard();
    }

    private void refreshLowerCard() {
        ui.removeCallbacks(noticeTick);
        long now = SystemClock.elapsedRealtime();
        while (!recentNotices.isEmpty() && recentNotices.peekFirst().deadline <= now) recentNotices.removeFirst();
        NoticeCardContent card = recentNotices.peekLast();
        if (card != null && shownNotice != card) renderNotice(activeNoticeView(), card);
        if (shownNotice != card) RideLog.event("notice.visibility", "showing", card != null,
                "compact", compactDashboard, "queue", recentNotices.size(),
                "remainingMs", card == null ? 0 : card.deadline - now);
        shownNotice = card;
        updateNoticeVisibility(true);
        updateNoticeCountdown();
    }

    private NoticeView activeNoticeView() {
        return compactDashboard ? compactNotice : expandedNotice;
    }

    private void updateNoticeVisibility(boolean animate) {
        boolean coversMedia = !compactDashboard && shownNotice != null;
        setNoticePanelVisible(musicPanel, received == null && !coversMedia, animate);
        setNoticePanelVisible(receivedCard, received != null && !coversMedia, animate);
        setNoticePanelVisible(expandedNotice, coversMedia, animate && !compactDashboard);
        setNoticePanelVisible(compactNotice, compactDashboard && shownNotice != null, animate && compactDashboard);
    }

    private void setNoticePanelVisible(View panel, boolean show, boolean animate) {
        if (animate) {
            setCardVisible(panel, show);
            return;
        }
        panel.animate().cancel();
        panel.setAlpha(show ? 1f : 0f);
        panel.setTranslationY(0);
        panel.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        panel.setImportantForAccessibility(show ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void updateNoticeProgress() {
        if (shownNotice == null) return;
        long remaining = Math.max(0, shownNotice.deadline - SystemClock.elapsedRealtime());
        activeNoticeView().progress.setScaleX((float) remaining / NOTICE_DISPLAY_MS);
    }

    private void updateNoticeCountdown() {
        if (!visible || shownNotice == null) return;
        long remaining = shownNotice.deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            refreshLowerCard();
            return;
        }
        updateNoticeProgress();
        ui.postDelayed(noticeTick, Math.min(remaining, 50));
    }

    private void addReceivedCard(FrameLayout lowerLeft) {
        receivedCard = group(true);
        receivedCard.setBackground(card());
        pad(receivedCard, 11, 7, 10, 7);
        lowerLeft.addView(receivedCard, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = group(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        receivedCard.addView(header, new LinearLayout.LayoutParams(-1, dp(39)));
        header.addView(label("高德分享", 12, TextTone.SECONDARY, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = button("×", 21, this::dismissReceived);
        close.setContentDescription("关闭分享卡片");
        close.setBackgroundColor(Color.TRANSPARENT);
        pad(close, 0, 0, 0, 0);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), -1));
        LinearLayout message = group(false);
        message.setGravity(Gravity.CENTER_VERTICAL);
        receivedCard.addView(message, new LinearLayout.LayoutParams(-1, 0, 1));
        ImageView pin = new ImageView(this);
        pin.setScaleType(ImageView.ScaleType.CENTER);
        pin.setImageDrawable(icon(DashIcon.Kind.PIN, theme.onAccent, 22));
        receivedPin = pin;
        pin.setBackground(round(theme.accent, 10));
        message.addView(pin, new LinearLayout.LayoutParams(dp(39), dp(39)));

        LinearLayout copy = group(true);
        pad(copy, 8, 0, 6, 0);
        message.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        receivedName = label("", 15, TextTone.PRIMARY, true);
        oneLine(receivedName);
        pad(receivedName, 0, 3, 0, 0);
        copy.addView(receivedName, new LinearLayout.LayoutParams(-1, -2));

        TextView confirm = button("导航", 13, () -> {
            if (received == null) return;
            AmapClient.Place place = received;
            dismissReceived();
            selectPlace(place);
        });
        confirm.setTextColor(theme.onAccent);
        confirmRouteButton = confirm;
        confirm.setBackground(clickable(theme.accent, 8));
        message.addView(confirm, new LinearLayout.LayoutParams(dp(49), dp(44)));
        receivedCard.setVisibility(View.GONE);
    }

    private void dismissReceived() {
        received = null;
        refreshLowerCard();
    }

    private void acceptShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence content = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        Uri link = AmapShare.findLink(content == null ? null : content.toString());
        if (link == null) toast("请分享高德地图的地点链接");
        else importLink(link);
    }

    private void importLink(Uri link) {
        if (resolvingImport) {
            toast("正在读取上一条链接");
            return;
        }
        resolvingImport = true;
        requests.execute(() -> {
            AmapClient.Place place;
            try {
                place = AmapShare.resolve(link);
            } catch (IOException | IllegalArgumentException exception) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    resolvingImport = false;
                    toast("读不到目的地，请检查链接或网络");
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                resolvingImport = false;
                showReceived(place);
            });
        });
    }

    private void showReceived(AmapClient.Place place) {
        received = place;
        receivedName.setText(place.name);
        refreshLowerCard();
    }

    private void setCardVisible(View card, boolean show) {
        card.animate().cancel();
        if (show) {
            if (card.getVisibility() != View.VISIBLE) {
                card.setAlpha(0);
                card.setTranslationY(dp(5));
            }
            card.setVisibility(View.VISIBLE);
            if (card == tripPanel) updateMapOverlayInsets();
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            card.animate().alpha(1).translationY(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            if (card.getVisibility() != View.VISIBLE) return;
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            card.animate().alpha(0).translationY(dp(5)).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        card.setVisibility(card == tripPanel ? View.GONE : View.INVISIBLE);
                        if (card == tripPanel) updateMapOverlayInsets();
                    }).start();
        }
    }

    private void updateMapOverlayInsets() {
        int footer = !dashboardStage.compact() && tripPanel.getVisibility() == View.VISIBLE ? tripPanel.getHeight() : 0;
        int inset = Math.max(0, footer - dp(TRIP_FADE_DP));
        boolean hasPlace = currentPlaceLabel.getVisibility() == View.VISIBLE;
        int placeMargin = footer > 0 ? inset : dp(6);
        setMapButtonBottomMargin(currentPlaceLabel, placeMargin);
        setMapButtonBottomMargin(recenterButton, hasPlace ? placeMargin + dp(20) : inset + dp(14));
        setMapButtonBottomMargin(locationButton, inset + dp(14));
        int viewportInset = dashboardStage.compact() && compactGuidance.getVisibility() == View.VISIBLE ? dp(42) : inset;
        map.setViewportInsets(viewportInset, dashboardStage.mapSideInset());
    }

    private void setMapButtonBottomMargin(View button, int margin) {
        FrameLayout.LayoutParams box = (FrameLayout.LayoutParams) button.getLayoutParams();
        if (box.bottomMargin == margin) return;
        box.bottomMargin = margin;
        button.setLayoutParams(box);
    }

    private static IntentFilter bluetoothFilter() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        return filter;
    }

    private void showBluetoothSetup() {
        LinearLayout content = group(true);
        pad(content, 22, 4, 22, 10);
        bluetoothDetails = label("", 15, TextTone.PRIMARY, false);
        bluetoothDetails.setLineSpacing(dp(4), 1);
        content.addView(bluetoothDetails);
        bluetoothDialog = dialogBuilder().setTitle("蓝牙设备")
                .setView(content).setNegativeButton("返回", null)
                .setPositiveButton("连接设备", null).setNeutralButton("蓝牙设置", null).create();
        bluetoothDialog.show();
        bluetoothDialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
        bluetoothDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (bluetooth.enabled() && (!bluetoothNotifications || bluetoothMedia == null)) bluetooth.retryPhone();
            else chooseBluetoothDevice();
        });
        bluetoothDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (ActivityNotFoundException exception) {
                toast("无法打开蓝牙设置，请从系统设置进入");
            }
        });
        bluetoothDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (!bluetooth.enabled()) {
                bluetoothDialog.dismiss();
                return;
            }
            getPreferences(MODE_PRIVATE).edit().putString(PAIRED_DEVICE, "").apply();
            bluetooth.stop();
            bluetoothStatus = "未连接蓝牙设备";
            refreshMusic();
            refreshBluetoothIndicator();
            updateBluetoothDetails();
        });
        updateBluetoothDetails();
    }

    private void chooseBluetoothDevice() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PAIRED_BLUETOOTH);
            return;
        }
        List<BluetoothDevice> devices = bluetooth.pairedDevices();
        if (devices.isEmpty()) {
            bluetoothStatus = "暂无已配对设备，请先在系统蓝牙中配对";
            updateBluetoothDetails();
            return;
        }
        if (devices.size() == 1) {
            useBluetoothDevice(devices.get(0));
            return;
        }
        CharSequence[] names = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String name = devices.get(i).getName();
            names[i] = TextUtils.isEmpty(name) ? "已配对设备 " + (i + 1) : name;
        }
        dialogBuilder().setTitle("选择已配对设备")
                .setItems(names, (dialog, which) -> useBluetoothDevice(devices.get(which)))
                .setNegativeButton("返回", null).show();
    }

    private void useBluetoothDevice(BluetoothDevice device) {
        getPreferences(MODE_PRIVATE).edit().putString(PAIRED_DEVICE, device.getAddress()).apply();
        bluetooth.connectDeviceManually(device);
    }

    private void reconnectBluetooth() {
        if (bluetooth.requiresManualReconnectAfterUpdate() || bluetooth.enabled() || (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        String address = getPreferences(MODE_PRIVATE).getString(PAIRED_DEVICE, null);
        if ("".equals(address)) return;
        if (!TextUtils.isEmpty(address)) {
            for (BluetoothDevice device : bluetooth.pairedDevices()) {
                if (address.equals(device.getAddress())) {
                    bluetooth.connectDevice(device);
                    return;
                }
            }
        }
        BluetoothDevice connected = bluetooth.connectedSource();
        if (connected != null) {
            bluetooth.connectDevice(connected);
            return;
        }
        BluetoothDevice candidate = null;
        for (BluetoothDevice device : bluetooth.pairedDevices()) {
            if (!bluetooth.canAutoConnect(device)) continue;
            if (candidate != null) return;
            candidate = device;
        }
        if (candidate != null) bluetooth.connectDevice(candidate);
    }

    private void updateBluetoothDetails() {
        if (bluetoothDialog == null || !bluetoothDialog.isShowing()) return;
        boolean active = bluetooth.enabled();
        boolean expected = expectsBluetoothPhone();
        String status = bluetooth.linked() ? connectedBluetoothStatus() : bluetoothStatus;
        String hint = bluetooth.requiresManualReconnectAfterUpdate()
                ? "应用更新后需要手动连接一次。连接未完成时会停止自动重试，连接成功后恢复自动重连。"
                : expected && !bluetoothNotifications
                ? "车机会自动重连。若持续未同步，请打开手机的蓝牙设置，再点「立即重连」。"
                : bluetoothMedia != null ? "播放声音仍由发送设备输出。"
                : active ? "设备提供音乐或通知共享时，车机会自动使用对应来源。"
                : "先在系统蓝牙中配对。设备提供音乐或通知共享时，车机会自动使用对应来源。";
        String details = status + "\n通知：" + (bluetoothNotifications ? "蓝牙" : expected ? "未同步" : "本机")
                + "  ·  音乐：" + (bluetoothMedia != null ? "蓝牙" : expected ? "未同步" : "本机") + "\n\n" + hint;
        if (!TextUtils.equals(bluetoothDetails.getText(), details)) bluetoothDetails.setText(details);
        bluetoothDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setText(active && (!bluetoothNotifications || bluetoothMedia == null) ? "立即重连" : "连接设备");
        bluetoothDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(active ? "断开" : "返回");
    }

    private String connectedBluetoothStatus() {
        String name = bluetooth.connectedName();
        return name.isEmpty() ? "蓝牙设备已连接" : "已连接 · " + name;
    }

    private TextView metric(LinearLayout row, String title) {
        LinearLayout column = group(true);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView caption = label(title, 10, TextTone.SECONDARY, false);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        TextView value = label("—", 16, TextTone.PRIMARY, true);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setFontFeatureSettings("'tnum'");
        oneLine(value);
        column.addView(value, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    private ImageView transportButton(DashIcon.Kind kind, String name, long command, boolean primary, Runnable run) {
        ImageView control = iconButton(kind, name, primary ? 20 : 16, primary ? theme.onAccent : theme.accent);
        control.setBackground(clickable(primary ? theme.accent : Color.TRANSPARENT, 24));
        control.setOnClickListener(v -> {
            if (bluetoothMedia != null) {
                int remote = command == PlaybackState.ACTION_SKIP_TO_PREVIOUS ? AppleBle.PREVIOUS
                        : command == PlaybackState.ACTION_SKIP_TO_NEXT ? AppleBle.NEXT : bluetoothMedia.playPauseCommand();
                if (!bluetooth.command(remote)) toast("蓝牙设备暂未提供此控制");
                return;
            }
            if (!allows(media, command)) return;
            run.run();
            ui.postDelayed(() -> { if (visible) refreshMusic(); }, 250);
        });
        return control;
    }

    private DashIcon icon(DashIcon.Kind kind, int color, int size) {
        return new DashIcon(kind, color, dp(size));
    }

    private ImageView iconButton(DashIcon.Kind kind, String name, int size, int color) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageDrawable(icon(kind, color, size));
        view.setContentDescription(name);
        view.setBackground(clickable(theme.chip));
        view.setFocusable(true);
        return view;
    }

    private GradientDrawable card() {
        GradientDrawable shape = round(theme.surface, 15);
        shape.setStroke(dp(1), theme.line);
        return shape;
    }

    private void guidance(DashIcon.Kind kind, String title, String detail) {
        boolean instructionChanged = shownTurn != kind || !shownGuidanceDetail.equals(detail);
        if (guidancePanel.getVisibility() != View.VISIBLE || instructionChanged) {
            guidancePanel.setAlpha(.35f);
            setCardVisible(guidancePanel, true);
        }
        shownTurn = kind;
        shownGuidanceDetail = detail;
        turnIcon.setKind(kind);
        action.setText(title);
        road.setText(detail);
        updateCompactGuidance();
        refreshUpcomingTurns();
    }

    private static DashIcon.Kind turnKind(String instruction) {
        if (instruction.contains("掉头")) return DashIcon.Kind.RETURN;
        if (instruction.contains("左前")) return DashIcon.Kind.LEFT_AHEAD;
        if (instruction.contains("右前")) return DashIcon.Kind.RIGHT_AHEAD;
        if (instruction.contains("左")) return DashIcon.Kind.LEFT;
        if (instruction.contains("右")) return DashIcon.Kind.RIGHT;
        return DashIcon.Kind.NORTH;
    }

    private float gpsSpeedAt(long now) {
        return gpsSpeed.valueAt(now);
    }

    private void displayGpsSpeed() {
        long now = SystemClock.elapsedRealtime();
        float metersPerSecond = gpsSpeedAt(now);
        if (rideSensors != null) rideSensors.setSpeed(metersPerSecond, now);
        ui.removeCallbacks(speedExpiry);
        displaySpeed(Float.isFinite(metersPerSecond) ? Math.round(metersPerSecond * 3.6f) : -1);
        if (!Float.isFinite(metersPerSecond)) {
            speedChart.breakTrace();
            speedChart.invalidate();
        }
        else if (visible) ui.postDelayed(speedExpiry, gpsSpeed.expiresIn(now));
    }

    private void displaySpeed(int value) {
        String text = value < 0 ? "—" : Integer.toString(value);
        if (TextUtils.equals(speed.getText(), text)) return;
        boolean showUnit = value < SPEED_UNIT_HIDE_FROM;
        if (showUnit != (speedUnit.getVisibility() == View.VISIBLE)) {
            speedUnit.animate().cancel();
            if (showUnit) {
                speedUnitDust.clear();
                speedUnit.setAlpha(0f);
                speedUnit.setVisibility(View.VISIBLE);
                speedUnit.animate().alpha(1f).setDuration(SPEED_UNIT_RESTORE_MS).start();
            } else {
                speedUnitDust.start(speedUnit);
                speedUnit.setVisibility(View.GONE);
            }
        }
        speed.setText(text);
        if (compactDashboard) fitCompactSpeedNumber(speedReadout.getWidth());
        alignStatusClock();
    }

    private float readingStart(TextView reading, View row) {
        Layout layout = reading.getLayout();
        if (!reading.isShown() || layout == null) return Float.POSITIVE_INFINITY;
        float left = reading.getTotalPaddingLeft() + layout.getLineLeft(0) - reading.getScrollX();
        for (View view = reading; view != row; view = (View) view.getParent()) left += view.getLeft();
        return left - speedReadout.getLeft();
    }

    private void fitExpandedInstruments(int height) {
        int available = height - speedCard.getPaddingTop() - speedCard.getPaddingBottom()
                - dp(EXPANDED_STATUS_HEIGHT_DP + EXPANDED_CHART_MIN_DP);
        float lineHeightRatio = speed.getLineHeight() / speed.getTextSize();
        float size = Math.min(expandedSpeedLimit, Math.max(dp(28), (available - dp(2)) / lineHeightRatio));
        if (Math.abs(expandedSpeedSize - size) < .5f) return;
        expandedSpeedSize = size;
        expandedSpeedHeight = Math.round(size * lineHeightRatio);
        if (!compactDashboard) {
            preferredSpeedSize = size;
            speedReadout.setMinimumHeight(expandedSpeedHeight);
            speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
    }

    private void fitCompactSpeedNumber(int readoutWidth) {
        if (readoutWidth <= 0 || speedUnit == null) return;
        int unitWidth = speedUnit.getVisibility() == View.GONE ? 0 : textWidth(speedUnit, speedUnit.getText());
        int available = Math.max(1, readoutWidth - unitWidth - dp(2));
        if (speed.getMaxWidth() != available) speed.setMaxWidth(available);
        float width = Layout.getDesiredWidth(speed.getText(), speed.getPaint());
        if (width <= 0) return;
        float size = Math.min(preferredSpeedSize, speed.getTextSize() * available / width);
        if (Math.abs(speed.getTextSize() - size) > .5f) speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private int textWidth(TextView view, CharSequence text) {
        return (int) Math.ceil(Layout.getDesiredWidth(text, view.getPaint()))
                + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
    }

    private void refreshMusic() {
        boolean missingNotifications = expectsBluetoothPhone() && !bluetoothNotifications;
        musicArtist.setTextColor(missingNotifications ? bluetoothWarningColor() : theme.muted);
        List<MediaController> sessions;
        try {
            sessions = getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, MediaAccess.class));
        } catch (SecurityException exception) {
            sessions = null;
        }
        media = sessions == null || sessions.isEmpty() ? null : sessions.get(0);
        if (sessions != null) {
            for (MediaController candidate : sessions) {
                if (isPlaying(candidate)) {
                    media = candidate;
                    break;
                }
            }
        }
        if (bluetoothMedia != null) {
            musicProgress.setEnabled(false);
            showMusicText(bluetoothMedia.title.isEmpty() ? "等待音乐" : bluetoothMedia.title,
                    missingNotifications ? "蓝牙通知未同步"
                    : bluetoothMedia.artist.isEmpty() ? bluetoothMedia.player : bluetoothMedia.artist);
            String title = bluetoothMedia.titleForLookup();
            String artist = bluetoothMedia.artistForLookup();
            MediaMetadata supplied = null;
            if (sessions != null && !title.isEmpty() && !artist.isEmpty()) {
                for (MediaController session : sessions) {
                    MediaMetadata item = session.getMetadata();
                    if (item != null && title.equalsIgnoreCase(item.getString(MediaMetadata.METADATA_KEY_TITLE))
                            && artist.equalsIgnoreCase(item.getString(MediaMetadata.METADATA_KEY_ARTIST))
                            && bluetoothMedia.matchesArtworkAlbum(item.getString(MediaMetadata.METADATA_KEY_ALBUM))
                            && (item.getDescription().getIconBitmap() != null || item.getDescription().getIconUri() != null)) {
                        supplied = item;
                        break;
                    }
                }
            }
            refreshArtwork(supplied == null ? null : supplied.getDescription().getIconBitmap(),
                    supplied == null ? null : supplied.getDescription().getIconUri(), title, artist, bluetoothMedia.albumForLookup());
            setMusicProgress(bluetoothMedia.progress(SystemClock.elapsedRealtime()),
                    Math.round(bluetoothMedia.duration * 1000));
            equip(prev, bluetoothMedia.supports(AppleBle.PREVIOUS));
            equip(next, bluetoothMedia.supports(AppleBle.NEXT));
            equip(play, bluetoothMedia.playPauseCommand() >= 0);
            playIcon.setKind(bluetoothMedia.playing ? DashIcon.Kind.PAUSE : DashIcon.Kind.PLAY);
            return;
        }
        if (expectsBluetoothPhone() && !isPlaying(media)) {
            clearArtworkRequest();
            media = null;
            musicProgress.setEnabled(false);
            showMusicCover(null);
            showMusicText("蓝牙音乐未同步", missingNotifications ? "蓝牙通知未同步" : "手机通知已连接");
            setMusicProgress(0, 0);
            equip(prev, false);
            equip(next, false);
            equip(play, false);
            playIcon.setKind(DashIcon.Kind.PLAY);
            return;
        }
        MediaMetadata metadata = media == null ? null : media.getMetadata();
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap cover = metadata == null ? null : metadata.getDescription().getIconBitmap();
        if (metadata != null) {
            if (TextUtils.isEmpty(title)) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (TextUtils.isEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        refreshArtwork(cover, metadata == null ? null : metadata.getDescription().getIconUri(), title, artist,
                metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        boolean playing = isPlaying(media);
        showMusicText(sessions == null ? "启用本机音乐与通知"
                : media == null ? "未在播放"
                : TextUtils.isEmpty(title) ? "正在播放" : title, artist);

        PlaybackState state = media == null ? null : media.getPlaybackState();
        long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        musicProgress.setEnabled(duration > 0 && allows(media, PlaybackState.ACTION_SEEK_TO));
        long position = state == null ? 0 : state.getPosition();
        if (position >= 0 && playing && state != null && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        setMusicProgress(duration > 0 ? Math.max(0, Math.min(1f, (float) position / duration)) : 0, duration);
        equip(prev, allows(media, PlaybackState.ACTION_SKIP_TO_PREVIOUS));
        equip(next, allows(media, PlaybackState.ACTION_SKIP_TO_NEXT));
        equip(play, allows(media, PlaybackState.ACTION_PLAY_PAUSE));
        playIcon.setKind(playing ? DashIcon.Kind.PAUSE : DashIcon.Kind.PLAY);
    }

    private void clearArtworkRequest() {
        if (artworkRequest != null) artworkRequest.cancel(true);
        artworkRequest = null;
        artworkTrack = null;
        artworkUri = null;
        foundArtwork = null;
        artworkAttempts = 0;
    }

    private void refreshArtwork(Bitmap embedded, Uri iconUri, String title, String artist, String album) {
        if (embedded != null || !visible) {
            clearArtworkRequest();
            showMusicCover(embedded);
            return;
        }
        Uri sourceUri = iconUri != null && ("https".equalsIgnoreCase(iconUri.getScheme()) || "content".equals(iconUri.getScheme())
                || "android.resource".equals(iconUri.getScheme())) ? iconUri : null;
        CoverLookup.Track track = new CoverLookup.Track(title, artist, album);
        if (sourceUri == null && !track.searchable()) {
            clearArtworkRequest();
            showMusicCover(null);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!track.equals(artworkTrack) || !java.util.Objects.equals(sourceUri, artworkUri)) {
            clearArtworkRequest();
            artworkTrack = track;
            artworkUri = sourceUri;
            foundArtwork = sourceUri == null ? artworkCache.get(track) : null;
            artworkRetryAt = now + 650; // AMS delivers individual fields in separate callbacks.
        }
        showMusicCover(foundArtwork);
        if (foundArtwork != null || artworkRequest != null || now < artworkRetryAt || artworkAttempts >= 3) return;
        CoverLookup.Track requested = artworkTrack;
        artworkAttempts++;
        artworkRequest = artworkWorker.submit(() -> {
            Bitmap image = null;
            boolean retry = false;
            try {
                if (sourceUri != null) image = coverLookup.fromPlayer(getContentResolver(), sourceUri);
                if (image == null && requested.searchable()) image = coverLookup.find(requested);
            } catch (InterruptedException canceled) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | JSONException transientFailure) {
                RideLog.event("artwork.unavailable", "type", transientFailure.getClass().getSimpleName());
                retry = true;
            }
            Bitmap result = image;
            boolean retryable = retry;
            ui.post(() -> {
                if (artworkTrack != requested) return;
                artworkRequest = null;
                RideLog.event("artwork.lookup", "found", result != null, "attempt", artworkAttempts,
                        "retryable", retryable);
                if (result != null) {
                    if (sourceUri == null) artworkCache.put(requested, result);
                    foundArtwork = result;
                    showMusicCover(result);
                } else {
                    artworkRetryAt = retryable ? SystemClock.elapsedRealtime() + 15_000L * artworkAttempts
                            : Long.MAX_VALUE;
                }
            });
        });
    }

    private void showMusicCover(Bitmap cover) {
        if (cover == musicCover.getTag() && musicCover.getDrawable() != null) return;
        musicCover.setTag(cover);
        if (cover != null) musicCover.setImageDrawable(new MusicCoverDrawable(cover, dp(11)));
        else musicCover.setImageResource(R.drawable.ic_apple_music);
    }

    private void showMusicText(String title, String artist) {
        String name = artist == null ? "" : artist;
        boolean titleChanged = !TextUtils.equals(musicTitle.getText(), title);
        boolean artistChanged = !TextUtils.equals(musicArtist.getText(), name);
        int visibility = name.isEmpty() ? View.GONE : View.VISIBLE;
        boolean visibilityChanged = musicArtist.getVisibility() != visibility;
        if (titleChanged) musicTitle.setText(title);
        if (artistChanged) musicArtist.setText(name);
        if (visibilityChanged) musicArtist.setVisibility(visibility);
        if (compactDashboard && (titleChanged || artistChanged || visibilityChanged)) positionCompactDashboard();
    }

    private void setMusicProgress(float fraction, long duration) {
        if (musicSeekSession == null || !musicProgress.isEnabled()) {
            musicProgress.setProgress(Math.round(musicProgress.getMax() * fraction));
        }
        int visibility = duration > 0 ? View.VISIBLE : compactDashboard ? View.INVISIBLE : View.GONE;
        musicPosition.setVisibility(visibility);
        musicLength.setVisibility(visibility);
        if (duration <= 0) {
            if (!TextUtils.equals(musicProgress.getContentDescription(), "音乐播放进度")) {
                musicProgress.setContentDescription("音乐播放进度");
            }
            return;
        }
        long position = Math.round(duration * (double) musicProgress.getProgress() / musicProgress.getMax());
        String elapsed = mediaTime(position);
        String total = mediaTime(duration);
        if (!TextUtils.equals(musicPosition.getText(), elapsed)) musicPosition.setText(elapsed);
        if (!TextUtils.equals(musicLength.getText(), total)) musicLength.setText(total);
        String description = "音乐播放进度 " + elapsed + " / " + total;
        if (!TextUtils.equals(musicProgress.getContentDescription(), description)) {
            musicProgress.setContentDescription(description);
        }
    }

    private static String mediaTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long remainder = seconds % 60;
        return seconds / 60 + (remainder < 10 ? ":0" : ":") + remainder;
    }

    private void seekMusic(MediaController controller, MediaMetadata track, int value) {
        if (!musicProgress.isEnabled() || !allows(media, PlaybackState.ACTION_SEEK_TO) || controller == null
                || !media.getSessionToken().equals(controller.getSessionToken())) return;
        MediaMetadata latest = media.getMetadata();
        if (latest == null || track == null) return;
        long duration = latest.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0 || duration != track.getLong(MediaMetadata.METADATA_KEY_DURATION)) return;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM}) {
            if (!TextUtils.equals(latest.getString(key), track.getString(key))) return;
        }
        media.getTransportControls().seekTo(Math.round((double) duration * value / musicProgress.getMax()));
    }

    private static boolean allows(MediaController controller, long action) {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        if (state == null) return false;
        if (action == PlaybackState.ACTION_PLAY_PAUSE) {
            action |= isPlaying(controller) ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY;
        }
        return (state.getActions() & action) != 0;
    }

    private static boolean isPlaying(MediaController controller) {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void musicSettings() {
        if (bluetoothMedia != null) {
            showBluetoothSetup();
            return;
        }
        try {
            getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, MediaAccess.class));
            Intent player = getPackageManager().getLaunchIntentForPackage("com.unicornsonlsd.finamp");
            if (player != null) startActivity(player);
        } catch (SecurityException exception) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void showSearch() {
        showPlaceSearch(false);
    }

    private AmapClient.Place homePlace() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Route.Point point = Route.Point.parse(prefs.getString(HOME_POINT, ""));
        return point == null ? null : new AmapClient.Place(
                prefs.getString(HOME_NAME, "家"), prefs.getString(HOME_ADDRESS, ""), point);
    }

    private void saveHome(AmapClient.Place place) {
        getPreferences(MODE_PRIVATE).edit().putString(HOME_POINT, place.point.parameter())
                .putString(HOME_NAME, place.name).putString(HOME_ADDRESS, place.address).apply();
        toast("家的地址已保存");
    }

    private void navigateHome() {
        if (searchButton.getVisibility() != View.VISIBLE) return;
        AmapClient.Place home = homePlace();
        if (home == null) {
            toast("请先在设置中保存家的地址");
            return;
        }
        selectPlace(new AmapClient.Place("回家 · " + home.name, home.address, home.point));
    }

    private void addHomeSwipeHint(FrameLayout mapLead) {
        LinearLayout hint = group(false);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setVisibility(View.INVISIBLE);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        pad(hint, 6, 0, 9, 0);
        mapLead.addView(hint, new FrameLayout.LayoutParams(-2, dp(44), Gravity.START));
        homeSwipeHint = hint;

        homeSwipeIcon = new ImageView(this);
        homeSwipeIcon.setScaleType(ImageView.ScaleType.CENTER);
        hint.addView(homeSwipeIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout copy = group(true);
        LinearLayout.LayoutParams copyBox = new LinearLayout.LayoutParams(-2, -2);
        copyBox.leftMargin = dp(7);
        hint.addView(copy, copyBox);
        copy.addView(label("回家", 13, TextTone.PRIMARY, true), new LinearLayout.LayoutParams(-2, -2));
        homeSwipeAddress = label("", 10, TextTone.SECONDARY, false);
        oneLine(homeSwipeAddress);
        homeSwipeAddress.setMaxWidth(dp(154));
        copy.addView(homeSwipeAddress, new LinearLayout.LayoutParams(-2, -2));
    }

    private final class HomeSwipe implements View.OnTouchListener {
        private final float slop = ViewConfiguration.get(DashboardActivity.this).getScaledTouchSlop();
        private final int[] mapOrigin = new int[2];
        private final Rect reveal = new Rect();
        private ValueAnimator spring;
        private float startX, startY, threshold, distance;
        private boolean moved, rightDrag, multiplePointers;

        @Override public boolean onTouch(View view, MotionEvent event) {
            float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    clear();
                    startX = event.getRawX();
                    startY = event.getRawY();
                    mapSurface.getLocationOnScreen(mapOrigin);
                    threshold = mapOrigin[0] + mapSurface.getWidth() * .5f;
                    moved = rightDrag = multiplePointers = false;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiplePointers = true;
                    view.setPressed(false);
                    returnToSearch();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiplePointers) return true;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        rightDrag = isRightDrag(dx, dy);
                        view.setPressed(false);
                        if (rightDrag) {
                            AmapClient.Place home = homePlace();
                            homeSwipeAddress.setText(home == null ? "请先在设置中保存家的地址"
                                    : home.name.isEmpty() ? home.address : home.name);
                        }
                    }
                    if (rightDrag) paint(dx);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    returnToSearch();
                    if (multiplePointers) return true;
                    if (rightDrag && dx >= dp(44) && isRightDrag(dx, dy) && event.getRawX() > threshold) {
                        navigateHome();
                    } else if (!moved && Math.hypot(dx, dy) <= slop) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    returnToSearch();
                    return true;
                default:
                    return true;
            }
        }

        private boolean isRightDrag(float dx, float dy) {
            return dx > 0 && dx >= Math.abs(dy) * 1.6f;
        }

        private void paint(float dx) {
            distance = Math.max(0, dx);
            View lead = (View) searchButton.getParent();
            float offset = Math.min(distance, Math.max(0, lead.getWidth() - searchButton.getWidth() - dp(4)));
            float progress = Math.min(1f, distance / Math.max(dp(44), threshold - startX));
            searchButton.setTranslationX(offset);
            homeSwipeHint.setAlpha(progress);
            homeSwipeHint.setTranslationX(-dp(7) * (1 - progress));
            homeSwipeAddress.setAlpha(Math.min(1f, Math.max(0f, (progress - .15f) * 1.6f)));
            reveal.set(0, 0, Math.min(homeSwipeHint.getWidth(), Math.round(offset)), homeSwipeHint.getHeight());
            homeSwipeHint.setClipBounds(reveal);
            homeSwipeHint.setVisibility(offset > 0 ? View.VISIBLE : View.INVISIBLE);
        }

        private void stopSpring() {
            if (spring != null) {
                spring.cancel();
                spring = null;
            }
        }

        void clear() {
            stopSpring();
            searchButton.setPressed(false);
            paint(0);
        }

        private void returnToSearch() {
            if (distance == 0) return;
            stopSpring();
            spring = ValueAnimator.ofFloat(distance, 0f);
            spring.setDuration(240);
            spring.setInterpolator(new DecelerateInterpolator());
            spring.addUpdateListener(animator -> paint((float) animator.getAnimatedValue()));
            spring.start();
        }
    }

    private void showPlaceSearch(boolean settingHome) {
        AmapClient.Place savedHome = homePlace();
        ScrollView scroller = new ScrollView(this);
        LinearLayout content = group(true);
        pad(content, 18, 0, 18, 8);
        scroller.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout inputRow = group(false);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(inputRow);
        EditText input = new EditText(dialogBuilder().getContext());
        input.setTextSize(17);
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});
        input.setHint("地点、地址或高德分享链接");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView resultsStatus = label("", 14, TextTone.SECONDARY, false);
        pad(resultsStatus, 4, 9, 4, 8);
        resultsStatus.setVisibility(View.GONE);
        content.addView(resultsStatus);
        LinearLayout results = group(true);
        content.addView(results, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder builder = dialogBuilder().setTitle(settingHome ? "设置家的地址" : "目的地")
                .setView(scroller).setNegativeButton("返回", null);
        if (settingHome && savedHome != null) {
            builder.setNeutralButton("清除地址", (dialog, which) -> {
                getPreferences(MODE_PRIVATE).edit().remove(HOME_POINT).remove(HOME_NAME).remove(HOME_ADDRESS).apply();
                toast("家的地址已清除");
            });
        } else if (!settingHome && savedHome != null) {
            builder.setNeutralButton("回家", (dialog, which) -> navigateHome());
        }
        AlertDialog dialog = builder.create();
        final int[] query = {0};
        Runnable search = () -> {
            String term = input.getText().toString().trim();
            int number = ++query[0];
            results.removeAllViews();
            resultsStatus.setVisibility(View.VISIBLE);
            if (term.isEmpty()) {
                resultsStatus.setText("请输入地点或地址");
                return;
            }
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            Uri link = AmapShare.findLink(term);
            if (link != null && !settingHome) {
                dialog.dismiss();
                importLink(link);
                return;
            }
            if (term.matches("[0-9]{6}")) {
                resultsStatus.setText("请在高德打开位置口令，再分享地点链接");
                return;
            }
            if (link == null && term.length() > 80) {
                resultsStatus.setText("地点名称过长，或链接格式不支持");
                return;
            }
            resultsStatus.setText("正在搜索…");
            Route.Point near = freshFix() ? current : null;
            requests.execute(() -> {
                List<AmapClient.Place> places;
                try {
                    if (link != null) places = Collections.singletonList(AmapShare.resolve(link));
                    else {
                        places = amap.search(term, near);
                        if (settingHome && places.isEmpty()) places = amap.geocode(term);
                    }
                } catch (IOException | JSONException | IllegalArgumentException exception) {
                    String error = publicError(exception, "搜索失败，请重试");
                    runOnUiThread(() -> {
                        if (dialog.isShowing() && number == query[0]) resultsStatus.setText(error);
                    });
                    return;
                }
                List<AmapClient.Place> matches = places;
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || number != query[0]) return;
                    resultsStatus.setText(matches.isEmpty() ? "没有找到，试试加上城市名"
                            : settingHome ? "选择家的地址" : "选择一个地点开始导航");
                    for (AmapClient.Place place : matches) {
                        TextView row = label(place.address.isEmpty() ? place.name : place.name + "\n" + place.address,
                                15, TextTone.PRIMARY, true);
                        row.setMaxLines(2);
                        row.setEllipsize(TextUtils.TruncateAt.END);
                        row.setBackground(clickable(theme.surface));
                        pad(row, 10, 6, 10, 6);
                        row.setMinHeight(dp(50));
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams rowBox = new LinearLayout.LayoutParams(-1, -2);
                        rowBox.bottomMargin = dp(4);
                        results.addView(row, rowBox);
                        row.setOnClickListener(v -> {
                            dialog.dismiss();
                            if (settingHome) saveHome(place);
                            else selectPlace(place);
                        });
                    }
                });
            });
        };
        inputRow.addView(button("搜索", 14, search), new LinearLayout.LayoutParams(-2, dp(42)));
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            search.run();
            return true;
        });
        Window searchWindow = dialog.getWindow();
        searchWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        showCenteredDialog(dialog);
    }

    private void confirmMapDestination(Route.Point point) {
        if (!visible || isFinishing()) return;
        map.showPickedPoint(point);
        AlertDialog dialog = dialogBuilder()
                .setTitle("导航到此处？")
                .setMessage(destination != null ? "确认后将更换当前目的地。" : "从当前位置规划路线。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导航", (prompt, which) ->
                        selectPlace(new AmapClient.Place("地图选点", "", point)))
                .create();
        dialog.setOnDismissListener(prompt -> map.showPickedPoint(null));
        showCenteredDialog(dialog);
    }

    private void saveNavigation(boolean force) {
        if (destination == null || !navigation.resumable()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && navigationSavedAt >= 0 && now - navigationSavedAt < 5000) return;
        navigationSavedAt = now;
        double covered = coveredMetersBeforeRoute + (route == null ? 0 : route.coveredMeters());
        getPreferences(MODE_PRIVATE).edit().putString(ACTIVE_NAV_POINT, destination.point.parameter())
                .putString(ACTIVE_NAV_NAME, destination.name).putLong(ACTIVE_NAV_TIME, System.currentTimeMillis())
                .putString(ACTIVE_NAV_COVERED, Double.toString(covered)).apply();
        if (force) RideLog.event("nav.checkpoint", "coveredM", covered);
    }

    private void discardNavigationCheckpoint() {
        getPreferences(MODE_PRIVATE).edit().remove(ACTIVE_NAV_POINT).remove(ACTIVE_NAV_NAME)
                .remove(ACTIVE_NAV_TIME).remove(ACTIVE_NAV_COVERED).apply();
        navigationSavedAt = -1;
    }

    private void restoreNavigation(Bundle state) {
        Route.Point point = state == null ? null : Route.Point.parse(state.getString("targetPoint", ""));
        String name = state == null ? "目的地" : state.getString("targetName", "目的地");
        double covered = state == null ? 0 : state.getDouble("coveredMeters", 0);
        String source = "activity_state";
        if (point != null && state.getBoolean("targetWgs84")) point = GpsCoordinates.toAmap(point.lon, point.lat);
        if (point == null) {
            long saved = getPreferences(MODE_PRIVATE).getLong(ACTIVE_NAV_TIME, 0);
            long age = System.currentTimeMillis() - saved;
            if (saved <= 0 || age < -300_000 || age > 24 * 60 * 60_000L) {
                if (saved > 0) discardNavigationCheckpoint();
                return;
            }
            point = Route.Point.parse(getPreferences(MODE_PRIVATE).getString(ACTIVE_NAV_POINT, ""));
            name = getPreferences(MODE_PRIVATE).getString(ACTIVE_NAV_NAME, "目的地");
            try { covered = Double.parseDouble(getPreferences(MODE_PRIVATE).getString(ACTIVE_NAV_COVERED, "0")); }
            catch (NumberFormatException invalid) { covered = 0; }
            source = "persistent_checkpoint";
        }
        if (point == null || !Double.isFinite(covered) || covered < 0 || covered > 1_000_000) {
            discardNavigationCheckpoint();
            return;
        }
        destination = new AmapClient.Place(name, "", point);
        coveredMetersBeforeRoute = covered;
        routeSpeedChart.breakTrace();
        setMapHeadingUp(true);
        RideLog.event("nav.restored", "source", source, "coveredM", covered, "waitingForGps", !freshFix());
        requestNavigation();
    }

    private void selectPlace(AmapClient.Place place) {
        clearRoute();
        destination = place;
        requestNavigation();
    }

    private void requestNavigation() {
        navigation.queue();
        saveNavigation(true);
        updateMapActions();
        if (freshFix()) {
            plan();
        } else {
            refreshLocationStatus();
        }
    }

    private void clearRoute() {
        discardNavigationCheckpoint();
        RideLog.event("nav.clear", "active", route != null, "idleHeadingUp", idleHeadingUp, "overview", routeOverview);
        boolean wasNavigating = destination != null || route != null;
        navigation.clear();
        route = null;
        destination = null;
        routeOverview = false;
        turnsExpanded = false;
        offRouteSince = -1;
        recoveringRoute = false;
        coveredMetersBeforeRoute = 0;
        nextReroute = 0;
        map.showRoute(null, false);
        if (wasNavigating) setMapHeadingUp(idleHeadingUp);
        updateMapActions();
        tripPanel.animate().cancel();
        tripPanel.setVisibility(View.GONE);
        updateMapOverlayInsets();
        routeSpeedChart.reset();
        routeTotal.setText("—");
        routeArrival.setText("—");
        distance.setText("—");
        rideDuration.setText("—");
    }

    private void updateMapActions() {
        boolean showNavigation = destination != null;
        if (showNavigation && searchButton.getVisibility() == View.VISIBLE) homeSwipe.clear();
        searchButton.setVisibility(showNavigation ? View.GONE : View.VISIBLE);
        endNavigationButton.setVisibility(showNavigation ? View.VISIBLE : View.GONE);
        refreshMapDirectionButton();
        if (!showNavigation) {
            guidancePanel.animate().cancel();
            guidancePanel.setVisibility(View.GONE);
        }
        updateCompactGuidance();
    }

    private void styleMapControls() {
        int fill = (theme.chip & 0x00ffffff) | 0xc0000000;
        searchButton.setBackground(clickable(fill, 10));
        homeSwipeHint.setBackground(round(fill, 10));
        homeSwipeIcon.setBackground(round((theme.accent & 0x00ffffff) | 0x28000000, 7));
        homeSwipeIcon.setImageDrawable(icon(DashIcon.Kind.HOME, theme.accent, 17));
        guidanceButton.setBackground(clickable(fill, 12));
        turnPreview.setBackground(round(fill, 10));
        compassButton.setBackground(clickable(fill));
        recenterButton.setBackground(clickable(fill));
        currentPlaceLabel.setShadowLayer(dp(2), 0, dp(1), theme.dark ? 0xb0000000 : 0xeeffffff);
        locationButton.setBackground(clickable(fill));
        mapBadge.setBackground(clickable(fill));
    }

    private void setMapHeadingUp(boolean enabled) {
        RideLog.event("nav.heading_mode", "enabled", enabled, "overview", routeOverview, "navigating", route != null);
        if (routeOverview) {
            // In overview, the direction button means "follow again": exit overview with the chosen direction.
            headingUp = enabled;
            setRouteOverview(false);
            return;
        }
        headingUp = enabled;
        if (destination == null && route == null) {
            idleHeadingUp = enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean(IDLE_MAP_HEADING_UP, enabled).apply();
        }
        refreshMapDirectionButton();
        map.setHeadingUp(enabled);
    }

    private boolean mapHeadingUp() { return routeOverview ? overviewHeadingUp : headingUp; }

    private void refreshMapDirectionButton() {
        boolean forward = mapHeadingUp();
        compassButton.setTextColor(theme.accent);
        compassButton.setText(forward ? "↑" : "N");
        compassButton.setContentDescription(forward
                ? "地图车头朝上，切换为北朝上" : "地图北朝上，切换为车头朝上");
    }

    private void setRouteOverview(boolean enabled) {
        RideLog.event("nav.overview", "enabled", enabled, "hasRoute", route != null);
        if (route == null || routeOverview == enabled) return;
        routeOverview = enabled;
        turnsExpanded = enabled;
        if (enabled) overviewHeadingUp = headingUp;
        map.setOverview(enabled, mapHeadingUp());
        refreshUpcomingTurns();
        updateMapActions();
        scheduleOverviewAutoExit(enabled);
    }

    private void overviewTimedOut() {
        if (routeOverview) setRouteOverview(false);
    }

    private void scheduleOverviewAutoExit(boolean enabled) {
        ui.removeCallbacks(overviewExit);
        if (enabled) ui.postDelayed(overviewExit, OVERVIEW_TIMEOUT_MS);
    }

    private void plan() {
        if (destination == null || navigation.planning() || !freshFix()) return;
        boolean reroute = route != null;
        RideLog.event("nav.plan_start", "reroute", reroute, "coveredM", coveredMetersBeforeRoute,
                "oldTotalM", route == null ? 0 : route.totalMeters(), "mode", "electrobike");
        int number = navigation.start();
        Route.Point from = current;
        AmapClient.Place target = destination;
        guidance(DashIcon.Kind.ROUTE, reroute ? "重新规划" : "电动车算路", target.name);
        requests.execute(() -> {
            Route result;
            try {
                result = amap.plan(from, target.point);
            } catch (IOException | JSONException exception) {
                RideLog.event("nav.plan_error", "request", number, "reroute", reroute, "type", exception.getClass().getSimpleName());
                String error = publicError(exception, "请检查网络或换个目的地");
                runOnUiThread(() -> {
                    if (isDestroyed() || !navigation.finish(number, route != null)) return;
                    offRouteSince = -1;
                    updateMapActions();
                    if (route == null) {
                        discardNavigationCheckpoint();
                        guidance(DashIcon.Kind.ROUTE, "算路失败 · 点按重试", error);
                    } else {
                        guidance(DashIcon.Kind.ROUTE, "重算失败", error);
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || !navigation.finish(number, true)) return;
                if (reroute && (lastFix == null || fixAge(lastFix) > GPS_GAP_MS)) {
                    navigation.queue();
                    return;
                }
                if (reroute) {
                    coveredMetersBeforeRoute += route.coveredMeters();
                    routeSpeedChart.breakTrace();
                }
                RideLog.event("nav.plan_result", "request", number, "reroute", reroute,
                        "newTotalM", result.totalMeters(), "coveredBeforeM", coveredMetersBeforeRoute);
                route = result;
                saveNavigation(true);
                offRouteSince = -1;
                recoveringRoute = false;
                nextReroute = SystemClock.elapsedRealtime() + 18_000;
                map.showRoute(route, true);
                if (!reroute) setMapHeadingUp(true);
                double tripMeters = coveredMetersBeforeRoute + route.totalMeters();
                routeSpeedChart.setTotalMeters(tripMeters);
                routeTotal.setText(meters(tripMeters));
                setCardVisible(tripPanel, true);
                updateMapActions();
                showGuidance(freshFix() ? route.update(current, lastFix.getAccuracy(), false) : route.preview());
            });
        });
    }

    private static String publicError(Exception error, String fallback) {
        String message = error.getMessage();
        return message != null && (message.equals("高德没有找到电动车路线")
                || message.equals("缺少高德 Web Service Key") || message.equals("高德服务暂时不可用")
                || message.matches("高德请求失败(?: · [0-9]{5})?"))
                ? message : fallback;
    }

    private void showGuidance(Route.Update update) {
        RideLog.event("nav.progress", "offRoute", update.offRoute, "arrived", update.arrived,
                "remainingM", update.remaining, "etaSeconds", update.eta,
                "recovering", recoveringRoute, "offRouteSinceMs", offRouteSince,
                "nextRerouteMs", nextReroute, "coveredM", route == null ? 0 : route.coveredMeters());
        if (update.arrived) {
            RideLog.event("nav.arrived", "coveredM", coveredMetersBeforeRoute, "remainingRouteM", route == null ? 0 : route.totalMeters());
            clearRoute();
            return;
        }
        if (update.offRoute) routeSpeedChart.breakTrace();
        if (recoveringRoute && update.offRoute) {
            guidance(DashIcon.Kind.TARGET, "定位已恢复", "正在匹配原路线");
            return;
        }
        if (!update.offRoute && freshFix()) {
            float kmh = gpsSpeedAt(SystemClock.elapsedRealtime()) * 3.6f;
            routeSpeedChart.record(coveredMetersBeforeRoute + route.coveredMeters(),
                    kmh, lastFix.getElapsedRealtimeNanos() / 1_000_000,
                    GPS_GAP_MS);
        }
        map.trimRoute(route);
        if (!update.offRoute) map.setNextTurnDistance(route.nextTurnMeters());
        String arrival = update.eta > 0
                ? arrivalTime.format(new Date(System.currentTimeMillis() + update.eta * 1000L))
                : "—";
        if (!TextUtils.equals(routeArrival.getText(), arrival)) routeArrival.setText(arrival);
        distance.setText(meters(update.remaining));
        rideDuration.setText(update.eta > 0 ? ((update.eta + 59) / 60) + " min" : "—");
        if (update.offRoute) {
            guidance(DashIcon.Kind.ROUTE, "偏离路线", "正在确认当前位置");
        } else {
            guidance(turnKind(update.action), meters(update.turnMeters), turnDetail(update.action, update.detail));
        }
    }

    private void followRoute(Location fix) {
        Route.Update update = route.update(current, fix.hasAccuracy() ? fix.getAccuracy() : 50, recoveringRoute);
        if (!update.offRoute) recoveringRoute = false;
        showGuidance(update);
        if (update.arrived || !update.offRoute || !fix.hasAccuracy() || fix.getAccuracy() > 35) {
            offRouteSince = -1;
            return;
        }
        long fixTime = fix.getElapsedRealtimeNanos() / 1_000_000;
        if (offRouteSince < 0) offRouteSince = fixTime;
        long now = SystemClock.elapsedRealtime();
        if (fixTime - offRouteSince >= OFF_ROUTE_CONFIRMATION_MS && now >= nextReroute) {
            nextReroute = now + 24_000;
            plan();
        }
    }

    private boolean permitted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private enum LocationStatus {
        NEEDS_PERMISSION("授权精准定位"), DISABLED("开启系统定位"), SEARCHING("GPS 搜星中"), READY("");

        final String label;

        LocationStatus(String label) {
            this.label = label;
        }
    }

    private boolean gpsEnabled() {
        return locations != null && locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private LocationStatus locationStatus() {
        if (!permitted()) return LocationStatus.NEEDS_PERMISSION;
        if (!gpsEnabled()) return LocationStatus.DISABLED;
        return freshFix() ? LocationStatus.READY : LocationStatus.SEARCHING;
    }

    private void refreshLocationStatus() {
        LocationStatus status = locationStatus();
        refreshGpsIndicator(status);
        if (status == LocationStatus.READY) {
            locationButton.setVisibility(View.GONE);
            return;
        }
        if (!TextUtils.equals(locationButton.getText(), status.label)) locationButton.setText(status.label);
        locationButton.setVisibility(View.VISIBLE);
        if ((navigation.waiting() || route != null) && !navigation.planning()) {
            String detail = status == LocationStatus.SEARCHING
                    ? (route == null ? "取得定位后开始算路" : "定位恢复后继续导航")
                    : "点地图左下角处理";
            guidance(DashIcon.Kind.TARGET, status.label, detail);
        }
    }

    private void refreshGpsIndicator(LocationStatus status) {
        String description = status == LocationStatus.READY ? "GPS 已定位" : status.label;
        int color = status == LocationStatus.READY ? theme.accent : status == LocationStatus.SEARCHING ? theme.muted : theme.dim;
        statusAppearance(gpsIndicator, color, description);
    }

    private void handleLocationClick() {
        switch (locationStatus()) {
            case NEEDS_PERMISSION:
                if (getPreferences(MODE_PRIVATE).getBoolean(LOCATION_REQUESTED, false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                } else {
                    requestLocationPermission();
                }
                break;
            case DISABLED:
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                break;
            case SEARCHING:
                toast("GPS 搜星中，建议到开阔处");
                break;
            case READY:
                if (routeOverview) setRouteOverview(false);
                else map.recenter();
                break;
        }
    }

    private void requestLocationPermission() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(LOCATION_REQUESTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
    }

    private boolean freshFix() {
        return current != null && lastFix != null && fixAge(lastFix) <= FIX_MAX_AGE;
    }

    private static long fixAge(Location position) {
        return SystemClock.elapsedRealtime() - position.getElapsedRealtimeNanos() / 1_000_000;
    }

    private void syncLocation() {
        if (!visible) return;
        if (!permitted() || !gpsEnabled()) {
            stopLocation();
            lastFix = null;
            return;
        }
        if (trackingLocation) return;
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GpsSpeedReading.UPDATE_INTERVAL_MS, 0, this);
            trackingLocation = true;
            RideLog.event("gps.subscribe", "requestedMs", GpsSpeedReading.UPDATE_INTERVAL_MS);
            Location last = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException ignored) {
            stopLocation();
            lastFix = null;
        }
    }

    private void stopLocation() {
        if (!trackingLocation) return;
        trackingLocation = false;
        RideLog.event("gps.unsubscribe");
        locations.removeUpdates(this);
        gpsSpeed.reset();
        speedChart.breakTrace();
        displayGpsSpeed();
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request == REQUEST_LOCATION) {
            syncLocation();
            refreshLocationStatus();
        } else if (request == REQUEST_PAIRED_BLUETOOTH) {
            if (grants.length == 1 && grants[0] == PackageManager.PERMISSION_GRANTED) chooseBluetoothDevice();
            else toast("允许附近设备权限后可连接蓝牙设备");
        }
    }

    @Override public void onLocationChanged(Location fix) {
        if (!visible || !trackingLocation) return;
        if (!LocationManager.GPS_PROVIDER.equals(fix.getProvider()) || fixAge(fix) < 0) return;
        long now = SystemClock.elapsedRealtime();
        float speedAccuracy = fix.hasSpeedAccuracy() ? fix.getSpeedAccuracyMetersPerSecond() : Float.NaN;
        if (fix.hasSpeedAccuracy() && !Float.isFinite(speedAccuracy)) speedAccuracy = Float.POSITIVE_INFINITY;
        if (gpsSpeed.record(fix.hasSpeed() ? fix.getSpeed() : Float.NaN, speedAccuracy,
                fix.getElapsedRealtimeNanos() / 1_000_000, now)) {
            float metersPerSecond = gpsSpeedAt(now);
            if (Float.isFinite(metersPerSecond)) {
                speedChart.record(metersPerSecond * 3.6f, fix.getElapsedRealtimeNanos() / 1_000_000);
            } else speedChart.breakTrace();
            displayGpsSpeed();
        }
        if (rideSensors != null) rideSensors.calibrateGps(fix, gpsSpeedAt(now), now);
        RideLog.event("gps.fix", "fixTimeMs", fix.getElapsedRealtimeNanos() / 1_000_000,
                "ageMs", fixAge(fix), "rawSpeedMps", fix.hasSpeed() ? fix.getSpeed() : Float.NaN,
                "speedAccuracyMps", speedAccuracy, "hasSpeedAccuracy", fix.hasSpeedAccuracy(),
                "decision", gpsSpeed.reason(), "acceptedSpeedMps", gpsSpeedAt(now),
                "positionAccuracyM", fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN,
                "latitude", fix.getLatitude(), "longitude", fix.getLongitude(),
                "gpsBearing", fix.hasBearing() ? fix.getBearing() : Float.NaN,
                "gpsBearingAccuracyDeg", fix.hasBearingAccuracy() ? fix.getBearingAccuracyDegrees() : Float.NaN,
                "positionUsable", fixAge(fix) <= FIX_MAX_AGE && (!fix.hasAccuracy() || fix.getAccuracy() <= 50));
        if (fixAge(fix) > FIX_MAX_AGE || (fix.hasAccuracy() && fix.getAccuracy() > 50)) return;
        if (lastFix != null && fix.getElapsedRealtimeNanos() <= lastFix.getElapsedRealtimeNanos()) return;
        if (route != null && (lastFix == null
                || (fix.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000 >= GPS_GAP_MS)) {
            recoveringRoute = true;
            offRouteSince = -1;
            if (navigation.planning()) {
                navigation.keepExistingRoute();
            }
        }
        lastFix = fix;
        current = GpsCoordinates.toAmap(fix.getLongitude(), fix.getLatitude());
        updateMapPosition();
        refreshLocationStatus();
        refreshCurrentPlace(fix, now);
        if (navigation.waiting()) plan();
        else if (route != null && !navigation.planning()) followRoute(fix);
    }

    private void paintPhoneHeading() {
        if (phoneHeadingAt >= 0 && SystemClock.elapsedRealtime() - phoneHeadingAt <= 3000) {
            map.setPhoneHeading(phoneHeadingDegrees);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (volumeKeys != null) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    volumeKeys.down(up, event.getRepeatCount() > 0, SystemClock.elapsedRealtime());
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    volumeKeys.up(up, SystemClock.elapsedRealtime());
                }
                scheduleVolumeResolve();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void resolveVolumeKey() {
        if (volumeKeys == null) return;
        volumeKeys.resolve(SystemClock.elapsedRealtime());
        scheduleVolumeResolve();
    }

    private void scheduleVolumeResolve() {
        ui.removeCallbacks(volumeResolve);
        long deadline = volumeKeys == null ? -1 : volumeKeys.resolveAt();
        if (deadline > 0) ui.postDelayed(volumeResolve, Math.max(0, deadline - SystemClock.elapsedRealtime()));
    }

    private void adjustVolume(boolean up) {
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException ignored) {}
    }

    private void skipTrack(boolean next) {
        if (bluetoothMedia != null) {
            int remote = next ? AppleBle.NEXT : AppleBle.PREVIOUS;
            if (!bluetooth.command(remote)) toast("蓝牙设备暂未提供此控制");
            return;
        }
        if (!allows(media, next ? PlaybackState.ACTION_SKIP_TO_NEXT : PlaybackState.ACTION_SKIP_TO_PREVIOUS)) return;
        if (next) media.getTransportControls().skipToNext();
        else media.getTransportControls().skipToPrevious();
        ui.postDelayed(() -> { if (visible) refreshMusic(); }, 250);
    }

    private void refreshCurrentPlace(Location fix, long now) {
        if (currentPlacePoint != null && current.distance(currentPlacePoint) > PLACE_MAX_AGE_DISTANCE_METERS) {
            currentPlacePoint = null;
            currentPlaceLabel.setVisibility(View.GONE);
            updateMapOverlayInsets();
        }
        if (!fix.hasAccuracy() || !(fix.getAccuracy() <= 35)) return;
        if (placeLookupRunning || (placeLookupPoint != null && now - placeLookupAt < PLACE_MIN_INTERVAL_MS)) return;
        if (placeLookupPoint != null && current.distance(placeLookupPoint) < PLACE_LOOKUP_DISTANCE_METERS
                && (currentPlacePoint == placeLookupPoint || now - placeLookupAt < PLACE_RETRY_INTERVAL_MS)) return;

        Route.Point point = current;
        placeLookupPoint = point;
        placeLookupAt = now;
        placeLookupRunning = true;
        requests.execute(() -> {
            String name;
            try {
                name = amap.placeLabel(point);
            } catch (IOException | JSONException ignored) {
                name = "";
            }
            String label = name;
            runOnUiThread(() -> {
                placeLookupRunning = false;
                if (!visible || isDestroyed() || label.isEmpty()
                        || !freshFix() || current.distance(point) > PLACE_MAX_AGE_DISTANCE_METERS) return;
                currentPlacePoint = point;
                boolean wasHidden = currentPlaceLabel.getVisibility() != View.VISIBLE;
                currentPlaceLabel.setText(label);
                if (wasHidden) {
                    currentPlaceLabel.setVisibility(View.VISIBLE);
                    updateMapOverlayInsets();
                }
            });
        });
    }

    private void updateMapPosition() {
        map.setPosition(current, lastFix, gpsSpeedAt(SystemClock.elapsedRealtime()));
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {
        stopLocation();
        lastFix = null;
        offRouteSince = -1;
        refreshLocationStatus();
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void refreshMap() {
        map.evaluateJavascript("window.mapState || 'loading'", value -> {
            if (isDestroyed()) return;
            boolean ready = "\"ready\"".equals(value);
            mapBadge.setVisibility(ready ? View.GONE : View.VISIBLE);
            if (!ready) {
                boolean failed = "\"error\"".equals(value);
                mapBadge.setText(failed ? "地图加载失败 · 点此重试" : "正在加载地图…");
                mapBadge.setClickable(failed);
            }
            if (ready && !mapReady) {
                updateMapOverlayInsets();
                map.setTheme(theme.accent, theme.dark);
                if (route != null) {
                    map.showRoute(route, true);
                    map.trimRoute(route);
                    map.setNextTurnDistance(route.nextTurnMeters());
                }
                map.setHeadingUp(mapHeadingUp());
                if (freshFix()) updateMapPosition();
                else previewLastMapArea();
                paintPhoneHeading();
                if (routeOverview) map.setOverview(true, mapHeadingUp());
            }
            mapReady = ready;
        });
    }

    private void previewLastMapArea() {
        if (locations == null || !permitted()) return;
        Location previous;
        try {
            previous = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException ignored) {
            return;
        }
        if (previous == null || previous.isFromMockProvider() || fixAge(previous) > LAST_MAP_AREA_MAX_AGE
                || !previous.hasAccuracy() || previous.getAccuracy() > 50) return;
        map.previewArea(GpsCoordinates.toAmap(previous.getLongitude(), previous.getLatitude()));
    }

    private String meters(double value) {
        return value >= 1000 ? String.format(Locale.CHINA, "%.1f km", value / 1000) : Math.round(value) + " m";
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void equip(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : .42f);
    }

    private LinearLayout group(boolean vertical) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        return view;
    }

    private int textColor(TextTone tone) {
        return tone == TextTone.PRIMARY ? theme.text : theme.muted;
    }

    private TextView label(String text, int size, TextTone tone, boolean bold) {
        TextView view = new TextView(this);
        styleLabel(view, text, size, tone, bold);
        return view;
    }

    private void styleLabel(TextView view, String text, int size, TextTone tone, boolean bold) {
        view.setText(text);
        view.setTextSize(size);
        textTones.put(view, tone);
        view.setTextColor(textColor(tone));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    private void oneLine(TextView view) {
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    private TextView button(String text, int size, Runnable click) {
        TextView view = label(text, size, TextTone.PRIMARY, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(clickable(theme.chip));
        pad(view, 11, 0, 11, 0);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private RippleDrawable clickable(int color) {
        return clickable(color, 12);
    }

    private RippleDrawable clickable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x359edfff), round(color, radius), round(Color.WHITE, radius));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void pad(View view, int l, int t, int r, int b) {
        view.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    private void padMediaCard(View view) {
        pad(view, 12, 8, 12, 10);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
