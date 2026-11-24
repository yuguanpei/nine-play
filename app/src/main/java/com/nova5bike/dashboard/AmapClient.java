package com.nova5bike.dashboard;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

final class AmapClient {
    private final String webKey;
    final String jsKey;
    final String jsCode;

    AmapClient(Context context) {
        JSONObject config = ServiceConfig.section(context, "amap");
        webKey = config.optString("webKey");
        jsKey = config.optString("jsKey");
        jsCode = config.optString("jsCode");
    }

    List<Place> search(String keyword, Route.Point near) throws IOException, JSONException {
        if (near != null) {
            Uri.Builder around = Uri.parse("https://restapi.amap.com/v5/place/around").buildUpon()
                    .appendQueryParameter("keywords", keyword)
                    .appendQueryParameter("location", near.parameter())
                    .appendQueryParameter("radius", "15000")
                    .appendQueryParameter("page_size", "12");
            List<Place> nearby = places(request(around).optJSONArray("pois"));
            if (!nearby.isEmpty()) return nearby;
        }
        Uri.Builder text = Uri.parse("https://restapi.amap.com/v5/place/text").buildUpon()
                .appendQueryParameter("keywords", keyword)
                .appendQueryParameter("page_size", "12");
        return places(request(text).optJSONArray("pois"));
    }

    List<Place> geocode(String address) throws IOException, JSONException {
        Uri.Builder uri = Uri.parse("https://restapi.amap.com/v3/geocode/geo").buildUpon()
                .appendQueryParameter("address", address);
        JSONArray matches = request(uri).optJSONArray("geocodes");
        List<Place> results = new ArrayList<>();
        if (matches == null) return results;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null || match.optString("level").matches("国家|省|市|区县")) continue;
            Route.Point point = Route.Point.parse(match.optString("location"));
            if (point != null) results.add(new Place(match.optString("formatted_address", address), "", point));
        }
        return results;
    }

    String placeLabel(Route.Point point) throws IOException, JSONException {
        Uri.Builder uri = Uri.parse("https://restapi.amap.com/v3/geocode/regeo").buildUpon()
                .appendQueryParameter("location", point.parameter())
                .appendQueryParameter("radius", "100")
                .appendQueryParameter("extensions", "all");
        JSONObject result = request(uri).optJSONObject("regeocode");
        JSONObject address = result == null ? null : result.optJSONObject("addressComponent");
        if (address == null) return "";
        String street = addressText(address.optJSONObject("streetNumber"), "street");
        if (!street.isEmpty()) return street;

        JSONArray roads = result.optJSONArray("roads");
        double closest = 50;
        for (int i = 0; roads != null && i < roads.length(); i++) {
            JSONObject road = roads.optJSONObject(i);
            if (road == null) continue;
            double distance = road.optDouble("distance", Double.POSITIVE_INFINITY);
            String name = addressText(road, "name");
            if (!name.isEmpty() && distance >= 0 && distance < closest) {
                street = name;
                closest = distance;
            }
        }
        if (!street.isEmpty()) return street;
        String neighborhood = addressText(address.optJSONObject("neighborhood"), "name");
        if (!neighborhood.isEmpty()) return neighborhood;
        String township = addressText(address, "township");
        return !township.isEmpty() ? township : addressText(address, "district");
    }

    District district(Route.Point point) throws IOException, JSONException {
        JSONObject result = request(Uri.parse("https://restapi.amap.com/v3/geocode/regeo").buildUpon()
                .appendQueryParameter("location", point.parameter())
                .appendQueryParameter("extensions", "base")).optJSONObject("regeocode");
        JSONObject address = result == null ? null : result.optJSONObject("addressComponent");
        if (address == null) throw new IOException("未取得区县位置");
        String name = addressText(address, "district");
        String city = addressText(address, "city");
        if (city.isEmpty()) city = addressText(address, "province");
        String code = addressText(address, "adcode");
        if (name.isEmpty() || city.isEmpty() || !code.matches("[0-9]{6}")) {
            throw new IOException("区县信息不完整");
        }
        return new District(name, city, code);
    }

    static final class District {
        final String name, city, code;

        District(String name, String city, String code) {
            this.name = name;
            this.city = city;
            this.code = code;
        }
    }

    private static String addressText(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static List<Place> places(JSONArray pois) {
        List<Place> results = new ArrayList<>();
        if (pois == null) return results;
        for (int i = 0; i < pois.length(); i++) {
            JSONObject poi = pois.optJSONObject(i);
            if (poi == null) continue;
            Route.Point point = Route.Point.parse(poi.optString("location"));
            if (point == null) continue;
            Object rawAddress = poi.opt("address");
            String address = rawAddress instanceof String ? (String) rawAddress : "";
            String area = poi.optString("cityname") + poi.optString("adname");
            results.add(new Place(poi.optString("name", "目的地"), area + address, point));
        }
        return results;
    }

    Route plan(Route.Point from, Route.Point to) throws IOException, JSONException {
        Uri.Builder uri = Uri.parse("https://restapi.amap.com/v5/direction").buildUpon()
                .appendPath("electrobike")
                .appendQueryParameter("origin", from.parameter())
                .appendQueryParameter("destination", to.parameter())
                .appendQueryParameter("show_fields", "cost,navi,polyline");
        JSONArray paths = request(uri).getJSONObject("route").optJSONArray("paths");
        if (paths == null || paths.length() == 0) throw new IOException("高德没有找到电动车路线");
        return Route.fromAmap(paths.getJSONObject(0));
    }

    private JSONObject request(Uri.Builder uri) throws IOException, JSONException {
        if (webKey.isEmpty()) throw new IOException("缺少高德 Web Service Key");
        Uri address = uri.appendQueryParameter("key", webKey).build();
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address.toString()).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("高德服务暂时不可用");
            try (InputStream body = connection.getInputStream()) {
                JSONObject result = new JSONObject(read(body));
                if (!"1".equals(result.optString("status"))) {
                    String code = result.optString("infocode");
                    throw new IOException("高德请求失败" + (code.matches("[0-9]{5}") ? " · " + code : ""));
                }
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int count; (count = input.read(chunk)) != -1; ) {
            if (bytes.size() + count > 3_000_000) throw new IOException("响应过大");
            bytes.write(chunk, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    static final class Place {
        final String name;
        final String address;
        final Route.Point point;

        Place(String name, String address, Route.Point point) {
            this.name = name;
            this.address = address;
            this.point = point;
        }
    }
}
