package com.nova5bike.dashboard;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, local-only road-test evidence. No notification bodies, media titles, or credentials. */
final class RideLog {
    private static final long FILE_BYTES = 4 * 1024 * 1024, TOTAL_BYTES = 128 * 1024 * 1024;
    private static final long RETAIN_MS = TimeUnit.DAYS.toMillis(7);
    private static final ConcurrentHashMap<String, Long> samples = new ConcurrentHashMap<>();
    private static volatile RideLog shared;
    private final ThreadPoolExecutor writer;
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final File directory;
    private final String session = System.currentTimeMillis() + "-" + Process.myPid();
    private final SimpleDateFormat wall = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    private File current;
    private FileOutputStream output;
    private long size;
    private int part, ioErrors;
    private long build;

    private RideLog(Context context) {
        File external = context.getExternalFilesDir("diagnostics");
        directory = external != null ? external : new File(context.getFilesDir(), "diagnostics");
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(() -> {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        runnable.run();
                    }, "NinePlayLogs");
                    thread.setDaemon(true);
                    return thread;
                }, (task, executor) -> dropped.incrementAndGet());
    }

    static synchronized void init(Context context) {
        if (shared != null) return;
        shared = new RideLog(context.getApplicationContext());
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            shared.build = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            event("session.start", "version", info.versionName, "build", shared.build,
                    "sdk", android.os.Build.VERSION.SDK_INT, "model", android.os.Build.MODEL,
                    "installedAt", info.lastUpdateTime, "directory", shared.directory.getAbsolutePath(),
                    "fileBytes", FILE_BYTES, "totalBytes", TOTAL_BYTES, "retentionDays", 7);
        } catch (Exception error) { event("session.info_error", "type", error.getClass().getSimpleName()); }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            event("app.crash", "thread", thread.getName(), "type", error.getClass().getName(),
                    "stack", Arrays.toString(error.getStackTrace()));
            shared.writer.shutdown();
            try { shared.writer.awaitTermination(300, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static boolean due(String key, long interval) {
        if (shared == null) return false;
        long now = SystemClock.elapsedRealtime();
        Long before = samples.get(key);
        if (before != null && now >= before && now - before < interval) return false;
        samples.put(key, now);
        return true;
    }

    static void event(String name, Object... fields) {
        JSONObject data = new JSONObject();
        try {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                Object value = fields[i + 1];
                if (value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) value = null;
                data.put(String.valueOf(fields[i]), value == null ? JSONObject.NULL : value);
            }
        } catch (JSONException ignored) { return; }
        json(name, data.toString());
    }

    static void json(String name, String json) {
        RideLog log = shared;
        if (log == null || json == null || json.length() > 24_000 || "null".equals(json)) return;
        long wallMs = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        long seq = log.sequence.incrementAndGet();
        log.writer.execute(() -> log.write(name, json, wallMs, elapsed, seq));
    }

    private void write(String name, String json, long wallMs, long elapsed, long seq) {
        try {
            JSONObject record = new JSONObject();
            record.put("time", wall.format(new Date(wallMs)));
            record.put("wallMs", wallMs).put("elapsedMs", elapsed).put("session", session).put("seq", seq);
            record.put("build", build).put("event", name).put("data", new JSONObject(json));
            record.put("dropped", dropped.get()).put("ioErrors", ioErrors);
            byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (output == null || size + bytes.length > FILE_BYTES) {
                if (output != null) output.close();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostics");
                current = new File(directory, "ride-" + session + "-" + (++part) + ".jsonl");
                output = new FileOutputStream(current, true);
                size = current.length();
                prune(wallMs);
            }
            output.write(bytes);
            size += bytes.length;
        } catch (IOException | JSONException error) {
            ioErrors++;
            if (output != null) try { output.close(); } catch (IOException ignored) {}
            output = null;
            Log.e("NinePlayLog", "Local diagnostic write failed", error);
        }
    }

    private void prune(long now) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("ride-") && name.endsWith(".jsonl"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = FILE_BYTES; // Reserve space for the current segment before filling it.
        for (File file : files) if (!file.equals(current)) total += file.length();
        for (File file : files) {
            if (file.equals(current)) continue;
            if (now - file.lastModified() > RETAIN_MS || total > TOTAL_BYTES) {
                long length = file.length();
                if (file.delete()) total -= length;
            }
        }
    }

    static void flush(long timeoutMs) {
        RideLog log = shared;
        if (log == null || log.writer.isShutdown()) return;
        try { log.writer.submit(() -> {}).get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (Exception failure) { Log.w("NinePlayLog", "Diagnostic flush incomplete", failure); }
    }

    static JSONArray vector(float[] values) {
        JSONArray array = new JSONArray();
        for (float value : values) array.put(Float.isFinite(value) ? Float.valueOf(value) : JSONObject.NULL);
        return array;
    }

    static JSONObject view(View view) {
        JSONObject data = new JSONObject();
        if (view == null) return data;
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        Rect clip = new Rect();
        boolean shown = view.getGlobalVisibleRect(clip);
        try {
            data.put("x", xy[0]).put("y", xy[1]).put("w", view.getWidth()).put("h", view.getHeight())
                    .put("visibility", view.getVisibility()).put("alpha", view.getAlpha())
                    .put("shown", shown).put("clip", clip.toShortString()).put("selected", view.isSelected())
                    .put("focused", view.isFocused());
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                data.put("textLength", text.length()).put("lines", text.getLineCount())
                        .put("fontPx", text.getTextSize()).put("scrollX", text.getScrollX())
                        .put("textColor", Integer.toHexString(text.getCurrentTextColor()))
                        .put("ellipsize", String.valueOf(text.getEllipsize()));
            }
        } catch (JSONException ignored) {}
        return data;
    }
}
