package com.nova5bike.dashboard;

import android.net.Uri;

import java.io.IOException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class AmapShare {
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_PATH = Pattern.compile("/[0-9A-Za-z]{1,80}");

    static Uri findLink(String text) {
        if (text == null || text.length() > 8192) return null;
        Matcher matches = LINK.matcher(text);
        while (matches.find()) {
            Uri url = Uri.parse(matches.group().replaceAll("[),.;!?，。；！）]+$", ""));
            if (trusted(url)) return url;
        }
        return null;
    }

    private static boolean trusted(Uri url) {
        if (url == null || url.getUserInfo() != null || url.getPort() != -1) return false;
        if (!"https".equals(url.getScheme()) && !"http".equals(url.getScheme())) return false;
        String host = url.getHost();
        return "surl.amap.com".equals(host) || "wb.amap.com".equals(host)
                || "uri.amap.com".equals(host) || "www.amap.com".equals(host);
    }

    static AmapClient.Place resolve(Uri link) throws IOException {
        if (!trusted(link)) throw new IOException("不是受支持的高德链接");
        if ("surl.amap.com".equals(link.getHost())) link = expand(link);
        AmapClient.Place destination = parse(link);
        if (destination == null) throw new IOException("没有读到目的地，请分享地点详情页的链接");
        return destination;
    }

    private static Uri expand(Uri link) throws IOException {
        String path = link.getEncodedPath();
        if (path == null || !SHORT_PATH.matcher(path).matches()) throw new IOException("高德短链接格式不正确");
        // Only the fixed high-trust origin receives a request. The redirect is parsed locally.
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://surl.amap.com" + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            int code = connection.getResponseCode();
            String target = connection.getHeaderField("Location");
            if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && target != null
                    && target.length() <= 8192) {
                Uri result = Uri.parse(target);
                if (trusted(result)) return result;
            }
            throw new IOException("高德短链接失效或格式不支持");
        } finally {
            connection.disconnect();
        }
    }

    private static AmapClient.Place parse(Uri link) {
        if ("wb.amap.com".equals(link.getHost()) && "/".equals(link.getPath())) {
            String poi = link.getQueryParameter("p");
            if (poi != null) {
                String[] fields = poi.split(",", 5);
                if (fields.length < 4) return null;
                return place(fields[2], fields[1], decodeName(fields[3]),
                        fields.length > 4 ? decodeName(fields[4]) : "", false);
            }
            String route = link.getQueryParameter("r");
            if (route != null) {
                String[] fields = route.split(",", 9);
                if (fields.length < 6) return null;
                return place(fields[4], fields[3], decodeName(fields[5]), "", false);
            }
            return null;
        }

        boolean publicUri = "uri.amap.com".equals(link.getHost());
        boolean embedded = "www.amap.com".equals(link.getHost());
        String path = link.getPath();
        if (publicUri && "/marker".equals(path)) {
            String q = link.getQueryParameter("position");
            if (q == null) return null;
            String[] fields = q.split(",", -1);
            if (fields.length != 2) return null;
            return place(fields[0], fields[1], link.getQueryParameter("name"), "",
                    "wgs84".equals(link.getQueryParameter("coordinate")));
        }
        if ((publicUri && "/navigation".equals(path)) || (embedded && "/ssr/embed/dir".equals(path))) {
            String to = link.getQueryParameter("to");
            if (to == null) return null;
            String[] fields = to.split(",", 3);
            if (fields.length < 2) return null;
            return place(fields[0], fields[1], fields.length == 3 ? fields[2] : null, "", false);
        }
        return null;
    }

    private static String decodeName(String text) {
        return text.contains("%") ? Uri.decode(text) : text;
    }

    private static AmapClient.Place place(String lon, String lat, String name, String address, boolean gps) {
        Route.Point p = Route.Point.parse(lon + "," + lat);
        if (p == null || Math.abs(p.lon) < 0.000001 && Math.abs(p.lat) < 0.000001) return null;
        if (gps) p = GpsCoordinates.toAmap(p.lon, p.lat);
        return new AmapClient.Place(clean(name, "高德分享地点", 80), clean(address, "", 120), p);
    }

    private static String clean(String value, String fallback, int max) {
        if (value == null) return fallback;
        String compact = value.replaceAll("[\\p{C}\\s]+", " ").trim();
        return compact.isEmpty() ? fallback : compact.substring(0, Math.min(max, compact.length()));
    }
}
