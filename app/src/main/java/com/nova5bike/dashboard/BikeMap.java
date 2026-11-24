package com.nova5bike.dashboard;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class BikeMap extends WebView {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE = "https://" + HOST + "/map.html";
    private GestureDetector destinationGesture;

    BikeMap(Context context, AmapClient credentials, boolean dark) {
        super(context);
        setBackgroundColor(dark ? 0xff121d29 : 0xffe9eeeb);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setSupportMultipleWindows(false);
        setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (RideLog.due("map.resource_error", 2000)) RideLog.event("map.resource_error",
                        "code", error.getErrorCode(), "mainFrame", request.isForMainFrame());
            }

            @Override public void onPageFinished(WebView view, String url) {
                RideLog.event("map.page_finished");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri address = request.getUrl();
                if (!"https".equals(address.getScheme()) || !HOST.equals(address.getHost())) return null;
                String path = address.getPath();
                try {
                    if ("/map.html".equals(path) || "/map.js".equals(path)) {
                        return new WebResourceResponse(path.endsWith(".html") ? "text/html" : "application/javascript",
                                "UTF-8", context.getAssets().open(path.substring(1)));
                    }
                    if ("/config.js".equals(path)) {
                        String script = "window.bikeMapDark=" + dark + ";window.bikeMapKey=" + JSONObject.quote(credentials.jsKey)
                                + ";window._AMapSecurityConfig={serviceHost:location.origin+'/_AMapService'};";
                        return new WebResourceResponse("application/javascript", "UTF-8", bytes(script));
                    }
                    if ("GET".equals(request.getMethod()) && path != null && path.startsWith("/_AMapService/")) {
                        return proxy(address, path.substring("/_AMapService".length()), credentials);
                    }
                } catch (IOException | IllegalArgumentException ignored) {
                    if (RideLog.due("map.proxy_error", 2000)) RideLog.event("map.proxy_error", "type", ignored.getClass().getSimpleName());
                    return error(502, "Map service unavailable");
                }
                return error(404, "Not found");
            }
        });
        loadUrl(PAGE);
    }

    void setOnDestinationPick(Consumer<Route.Point> onPick) {
        destinationGesture = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public void onLongPress(MotionEvent event) {
                String pointAt = "window.bikeMap?.pointAt(" + event.getX() + "," + event.getY() + ")";
                evaluateJavascript(pointAt, value -> {
                    if (value == null || "null".equals(value)) return;
                    try {
                        JSONArray coordinates = new JSONArray(value);
                        Route.Point point = Route.Point.validated(coordinates.getDouble(0), coordinates.getDouble(1));
                        if (point != null) onPick.accept(point);
                    } catch (JSONException ignored) {
                        // Only accept a pair of coordinates from the map.
                    }
                });
            }
        });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (destinationGesture != null) destinationGesture.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    void showPickedPoint(Route.Point point) {
        String coordinates = point == null ? "null" : "[" + point.parameter() + "]";
        evaluateJavascript("window.bikeMap?.showPickedPoint(" + coordinates + ")", null);
    }

    private WebResourceResponse proxy(Uri address, String path, AmapClient credentials) throws IOException {
        if (!path.matches("/v[345]/[A-Za-z0-9/_-]+") || credentials.jsKey.isEmpty()) {
            return error(404, "Map service unavailable");
        }
        String host = path.startsWith("/v4/map/styles") ? "webapi.amap.com"
                : path.startsWith("/v3/vectormap") ? "fmap01.amap.com" : "restapi.amap.com";
        Uri.Builder upstream = new Uri.Builder().scheme("https").authority(host).path(path);
        for (String name : address.getQueryParameterNames()) {
            if ("key".equals(name) || "jscode".equals(name)) continue;
            for (String value : address.getQueryParameters(name)) upstream.appendQueryParameter(name, value);
        }
        upstream.appendQueryParameter("key", credentials.jsKey).appendQueryParameter("jscode", credentials.jsCode);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(upstream.build().toString()).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Referer", "https://" + HOST + "/");
        try {
            if (connection.getResponseCode() != 200) {
                connection.disconnect();
                return error(502, "Map service unavailable");
            }
            String content = connection.getContentType();
            String mime = content == null ? "application/json" : content.split(";", 2)[0];
            InputStream input = new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { connection.disconnect(); }
                }
            };
            return new WebResourceResponse(mime, mime.startsWith("image/") ? null : "UTF-8", input);
        } catch (IOException exception) {
            connection.disconnect();
            throw exception;
        }
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static WebResourceResponse error(int code, String message) {
        return new WebResourceResponse("text/plain", "UTF-8", code, message, Collections.emptyMap(), bytes(message));
    }

    void showRoute(Route route, boolean navigating) {
        String points = route == null ? "[]" : route.pathJson();
        evaluateJavascript("window.bikeMap?.showRoute(" + points + "," + navigating + ")", null);
    }

    void setPosition(Route.Point point, Location fix, float measuredSpeed) {
        RideLog.event("map.position_input", "lon", point.lon, "lat", point.lat, "speedMps", measuredSpeed);
        float speed = Float.isFinite(measuredSpeed) ? measuredSpeed : -1;
        float accuracy = fix.hasAccuracy() && Float.isFinite(fix.getAccuracy()) ? fix.getAccuracy() : 0;
        evaluateJavascript("window.bikeMap?.setPosition([" + point.parameter() + "]," + speed + "," + accuracy
                + ")", null);
    }

    void trimRoute(Route route) {
        evaluateJavascript("window.bikeMap?.trimRoute(" + route.progressMeters() + ")", null);
    }

    void setHeadingUp(boolean enabled) {
        evaluateJavascript("window.bikeMap?.setHeadingUp(" + enabled + ")", null);
    }

    void previewArea(Route.Point point) {
        evaluateJavascript("window.bikeMap?.previewArea([" + point.parameter() + "])", null);
    }

    void setPhoneHeading(float degrees) {
        setPhoneHeading(degrees, false);
    }

    void setPhoneHeading(float degrees, boolean held) {
        evaluateJavascript("window.bikeMap?.setPhoneHeading(" + (Float.isFinite(degrees) ? degrees : "null")
                + "," + held + ")", null);
    }

    void setOverview(boolean enabled, boolean headingUp) {
        evaluateJavascript("window.bikeMap?.setOverview(" + enabled + "," + headingUp + ")", null);
    }

    void setTheme(int color, boolean dark) {
        setBackgroundColor(dark ? 0xff121d29 : 0xffe9eeeb);
        String hex = String.format(java.util.Locale.ROOT, "#%06x", color & 0x00ffffff);
        evaluateJavascript("window.bikeMap?.setTheme('" + hex + "'," + dark + ")", null);
    }

    void setNextTurnDistance(double meters) {
        String value = Double.isFinite(meters) ? Double.toString(meters) : "null";
        evaluateJavascript("window.bikeMap?.setNextTurnDistance(" + value + ")", null);
    }

    void setViewportInsets(int bottom, float side) {
        evaluateJavascript("window.bikeMap?.setViewportInsets(" + bottom + " / window.devicePixelRatio,"
                + side + " / window.devicePixelRatio)", null);
    }

    void recordDiagnostics() {
        evaluateJavascript("({mapState:window.mapState,...window.bikeMap?.diagnostics()})",
                value -> RideLog.json("map.sample", value));
    }

    void recenter() {
        RideLog.event("map.recenter");
        evaluateJavascript("window.bikeMap?.recenter()", null);
    }
}
