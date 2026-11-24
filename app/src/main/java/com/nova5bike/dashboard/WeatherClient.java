package com.nova5bike.dashboard;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

final class WeatherClient {
    private final String host, apiKey;
    private final Map<String, double[]> centers = new HashMap<>();

    WeatherClient(Context context) {
        JSONObject config = ServiceConfig.section(context, "qweather");
        String candidate = config.optString("host").trim();
        Uri uri = Uri.parse(candidate);
        String domain = uri.getHost();
        boolean valid = "https".equals(uri.getScheme()) && domain != null
                && domain.endsWith(".qweatherapi.com") && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null && uri.getPort() == -1
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        host = valid ? "https://" + domain : "";
        apiKey = config.optString("apiKey").trim();
    }

    boolean configured() { return !host.isEmpty() && !apiKey.isEmpty(); }

    WeatherReading fetch(AmapClient.District district) throws IOException, JSONException {
        double[] center = centers.get(district.code);
        if (center == null) {
            JSONObject geo = request(Uri.parse(host + "/geo/v2/city/lookup").buildUpon()
                    .appendQueryParameter("location", district.name)
                    .appendQueryParameter("adm", district.city)
                    .appendQueryParameter("range", "cn")
                    .appendQueryParameter("number", "5")
                    .appendQueryParameter("lang", "zh").build());
            JSONArray matches = geo.optJSONArray("location");
            if (!"200".equals(geo.optString("code")) || matches == null) throw new IOException("天气地区查询失败");
            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.optJSONObject(i);
                if (match == null || !areaName(district.name).equals(areaName(match.optString("name")))) continue;
                String city = areaName(district.city);
                if (!city.equals(areaName(match.optString("adm2")))
                        && !city.equals(areaName(match.optString("adm1")))) continue;
                double lat = match.optDouble("lat", Double.NaN), lon = match.optDouble("lon", Double.NaN);
                if (Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                    center = new double[]{lat, lon};
                    centers.put(district.code, center);
                    break;
                }
            }
            if (center == null) throw new IOException("天气地区未匹配");
        }
        // GeoAPI supplies district coordinates, avoiding mixed GPS/map coordinate systems.
        String path = String.format(Locale.ROOT, "/weather/v1/current/%.2f/%.2f", center[0], center[1]);
        JSONObject data = request(Uri.parse(host + path).buildUpon().appendQueryParameter("lang", "zh").build());
        JSONObject condition = data.optJSONObject("condition"), temperature = data.optJSONObject("temperature");
        if (condition == null || temperature == null || !"°C".equals(temperature.optString("unit"))) {
            throw new IOException("天气响应不完整");
        }
        long observed;
        try { observed = WeatherReading.observationTime(data.optString("validTime")); }
        catch (DateTimeParseException error) { throw new IOException("天气时间无效"); }
        JSONObject wind = data.optJSONObject("wind");
        JSONObject direction = wind == null ? null : wind.optJSONObject("direction");
        JSONObject speed = wind == null ? null : wind.optJSONObject("speed");
        float windKmh = number(speed, "value");
        if (speed != null && "m/s".equals(speed.optString("unit"))) windKmh *= 3.6f;
        else if (speed == null || !"km/h".equals(speed.optString("unit"))) windKmh = Float.NaN;
        float humidity = number(data, "humidity");
        if (humidity < 0 || humidity > 1) humidity = Float.NaN;
        WeatherReading reading = new WeatherReading(district.name, district.city, district.code, condition.optString("text"),
                condition.optInt("code", -1), number(temperature, "value"), number(data.optJSONObject("feelsLike"), "value"),
                humidity * 100, direction == null ? "" : WeatherReading.windDirection(direction.optString("compass")),
                wind == null ? -1 : wind.optInt("scale", -1), windKmh, observed, SystemClock.elapsedRealtime());
        if (!reading.validAt(System.currentTimeMillis())) throw new IOException("天气数据已过期或无效");
        return reading;
    }

    private static float number(JSONObject object, String key) {
        return object == null ? Float.NaN : (float) object.optDouble(key, Double.NaN);
    }

    private static String areaName(String name) {
        return name.replaceAll("(?:特别行政区|自治区|自治县|自治州|新区|林区|省|市|区|县)$", "");
    }

    private JSONObject request(Uri uri) throws IOException, JSONException {
        if (!configured()) throw new IOException("天气未配置");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-QW-Api-Key", apiKey);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("天气服务响应 " + status);
            try (InputStream raw = connection.getInputStream();
                    InputStream body = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw) {
                return new JSONObject(AmapClient.read(body));
            }
        } finally {
            connection.disconnect();
        }
    }
}
