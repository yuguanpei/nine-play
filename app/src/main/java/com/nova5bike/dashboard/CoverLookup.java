package com.nova5bike.dashboard;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

final class CoverLookup {
    private static final String USER_AGENT = "NinePlay/1.0 (com.nova5bike.dashboard)";
    private final ExecutorService catalogs = Executors.newFixedThreadPool(4);

    void close() {
        catalogs.shutdownNow();
    }

    private long lastSearch = -1000;

    Bitmap fromPlayer(ContentResolver resolver, Uri uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            try {
                URL url = new URL(uri.toString());
                for (int hop = 0; hop < 4 && "https".equalsIgnoreCase(url.getProtocol()); hop++) {
                    HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(4000);
                    try {
                        int code = connection.getResponseCode();
                        if (code >= 300 && code <= 308 && connection.getHeaderField("Location") != null) {
                            url = new URL(url, connection.getHeaderField("Location"));
                            continue;
                        }
                        if (code != 200) return null;
                        return decode(readLimited(connection, 2 * 1024 * 1024));
                    } finally { connection.disconnect(); }
                }
            } catch (IOException ignored) {}
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            try (InputStream input = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (InputStream input = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    Bitmap find(Track track) throws IOException, JSONException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        CompletionService<Bitmap> results = new ExecutorCompletionService<>(catalogs);
        Map<Future<Bitmap>, String> pending = new LinkedHashMap<>();
        long start = SystemClock.elapsedRealtime();
        IOException error = null;
        try {
            pending.put(results.submit(() -> findMusicBrainz(track)), "musicbrainz");
            pending.put(results.submit(() -> findNetEase(track)), "netease");
            pending.put(results.submit(() -> findQQ(track)), "qq");
            if (!track.album.isEmpty()) pending.put(results.submit(() -> findAudioDB(track)), "theaudiodb");
            RideLog.event("artwork.search", "platforms", pending.size());
            while (!pending.isEmpty()) {
                long remaining = 12_000 - (SystemClock.elapsedRealtime() - start);
                Future<Bitmap> done = results.poll(Math.max(0, remaining), TimeUnit.MILLISECONDS);
                if (done == null) throw new IOException("Cover search timed out");
                String provider = pending.remove(done);
                try {
                    Bitmap bitmap = done.get();
                    if (bitmap != null) {
                        RideLog.event("artwork.selected", "provider", provider,
                                "ms", SystemClock.elapsedRealtime() - start);
                        return bitmap;
                    }
                } catch (ExecutionException failure) {
                    error = new IOException("Cover provider failed: " + provider, failure.getCause());
                }
            }
            if (error != null) throw error;
            return null;
        } finally {
            for (Future<Bitmap> task : pending.keySet()) task.cancel(true);
        }
    }

    private Bitmap findMusicBrainz(Track track) throws IOException, JSONException, InterruptedException {
        String query = "recording:" + quoted(track.title) + " AND artist:" + quoted(track.artist);
        boolean exactAlbum = !track.album.isEmpty();
        if (exactAlbum) query += " AND release:" + quoted(track.album);
        return findRelease(track, query, exactAlbum);
    }

    private Bitmap findRelease(Track track, String query, boolean exactAlbum)
            throws IOException, JSONException, InterruptedException {
        Uri search = Uri.parse("https://musicbrainz.org/ws/2/recording").buildUpon()
                .appendQueryParameter("query", query).appendQueryParameter("fmt", "json")
                .appendQueryParameter("limit", "8").build();
        URL searchUrl = new URL(search.toString());
        byte[] response = search(searchUrl);
        if (response == null) return null;
        JSONArray recordings = new JSONObject(new String(response, StandardCharsets.UTF_8)).optJSONArray("recordings");
        if (recordings == null) return null;
        Set<String> releaseIds = new LinkedHashSet<>();
        for (int row = 0; row < recordings.length(); row++) {
            JSONObject recording = recordings.optJSONObject(row);
            if (recording == null || !same(track.title, recording.optString("title"))
                    || !same(track.artist, artist(recording.optJSONArray("artist-credit")))) continue;
            JSONArray releases = recording.optJSONArray("releases");
            if (releases == null) continue;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || (exactAlbum && !same(track.album, release.optString("title")))) continue;
                String id = release.optString("id");
                JSONObject group = release.optJSONObject("release-group");
                if (group != null) addRelease(releaseIds, "release-group", group.optString("id"));
                addRelease(releaseIds, "release", id);
            }
        }
        int attempts = 0;
        for (String id : releaseIds) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (attempts++ >= 3) break;
            Bitmap bitmap = decode(get(new URL("https://coverartarchive.org/" + id + "/front-250"), Endpoint.ARCHIVE));
            if (bitmap != null) return bitmap;
        }
        return null;
    }

    private static void addRelease(Set<String> releases, String type, String id) {
        if (id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            releases.add(type + "/" + id);
    }

    private static Bitmap findNetEase(Track track) throws IOException, JSONException {
        Uri query = Uri.parse("https://music.163.com/api/search/get").buildUpon()
                .appendQueryParameter("s", track.artist + " " + track.title)
                .appendQueryParameter("type", "1").appendQueryParameter("limit", "12").build();
        JSONObject result = netEaseJson(query).optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        long id = netEaseSongId(track, songs);
        if (id <= 0) return null;
        Uri detail = Uri.parse("https://music.163.com/api/song/detail/").buildUpon()
                .appendQueryParameter("ids", "[" + id + "]").build();
        JSONArray details = netEaseJson(detail).optJSONArray("songs");
        JSONObject song = details == null ? null : details.optJSONObject(0);
        if (song == null || song.optLong("id") != id) return null;
        JSONObject album = song.optJSONObject("album");
        String pic = album == null ? "" : album.optString("picUrl");
        if (pic.isEmpty()) return null;
        Uri image = Uri.parse(pic);
        if (!"https".equalsIgnoreCase(image.getScheme()) && !"http".equalsIgnoreCase(image.getScheme())) return null;
        image = image.buildUpon().scheme("https").appendQueryParameter("param", "300y300").build();
        return decode(get(new URL(image.toString()), Endpoint.NETEASE_COVER));
    }

    private static Bitmap findQQ(Track track) throws IOException, JSONException {
        Uri query = Uri.parse("https://c.y.qq.com/soso/fcgi-bin/client_search_cp").buildUpon()
                .appendQueryParameter("format", "json").appendQueryParameter("n", "12")
                .appendQueryParameter("w", track.artist + " " + track.title).build();
        JSONObject response = json(query, Endpoint.QQ);
        if (response.optInt("code", -1) != 0) throw new IOException("QQ search unavailable");
        JSONObject data = response.optJSONObject("data");
        JSONObject page = data == null ? null : data.optJSONObject("song");
        JSONArray songs = page == null ? null : page.optJSONArray("list");
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null || !matches(track, song.optString("songname"), song.optJSONArray("singer"),
                    song.optString("albumname"))) continue;
            String id = song.optString("albummid");
            if (id.matches("[a-zA-Z0-9]{1,32}")) {
                Bitmap image = decode(get(new URL("https://y.gtimg.cn/music/photo_new/T002R300x300M000"
                        + id + ".jpg"), Endpoint.QQ_COVER));
                if (image != null) return image;
            }
        }
        return null;
    }

    private static Bitmap findAudioDB(Track track) throws IOException, JSONException {
        Uri query = Uri.parse("https://www.theaudiodb.com/api/v1/json/123/searchalbum.php").buildUpon()
                .appendQueryParameter("s", track.artist).appendQueryParameter("a", track.album).build();
        JSONArray albums = json(query, Endpoint.AUDIODB).optJSONArray("album");
        for (int i = 0; albums != null && i < albums.length(); i++) {
            JSONObject album = albums.optJSONObject(i);
            if (album == null || !same(track.artist, album.optString("strArtist"))
                    || !same(track.album, album.optString("strAlbum"))) continue;
            String url = album.optString("strAlbumThumb");
            if (!url.startsWith("https://")) continue;
            Bitmap image = decode(get(new URL(url + "/small"), Endpoint.AUDIODB_COVER));
            if (image != null) return image;
        }
        return null;
    }

    private static JSONObject json(Uri uri, Endpoint endpoint) throws IOException, JSONException {
        byte[] response = get(new URL(uri.toString()), endpoint);
        return response == null ? new JSONObject() : new JSONObject(new String(response, StandardCharsets.UTF_8));
    }

    private static JSONObject netEaseJson(Uri uri) throws IOException, JSONException {
        byte[] response = get(new URL(uri.toString()), Endpoint.NETEASE);
        if (response == null) return new JSONObject();
        JSONObject json = new JSONObject(new String(response, StandardCharsets.UTF_8));
        if (json.optInt("code") != 200) throw new IOException("NetEase service unavailable");
        return json;
    }

    private static long netEaseSongId(Track track, JSONArray songs) {
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            JSONObject album = song == null ? null : song.optJSONObject("album");
            if (song == null || !matches(track, song.optString("name"), song.optJSONArray("artists"),
                    album == null ? "" : album.optString("name"))) continue;
            long id = song.optLong("id");
            if (id > 0) return id;
        }
        return 0;
    }

    private static boolean matches(Track track, String title, JSONArray artists, String album) {
        if (!same(track.title, title) || (!track.album.isEmpty() && !same(track.album, album))) return false;
        StringBuilder names = new StringBuilder();
        for (int i = 0; artists != null && i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = artist.optString("name");
            if (same(track.artist, name)) return true;
            names.append(name);
        }
        return same(track.artist, names.toString());
    }

    private static Bitmap decode(byte[] image) {
        if (image == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 32768 || bounds.outHeight > 32768) return null;
        bounds.inJustDecodeBounds = false;
        bounds.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / bounds.inSampleSize > 512) bounds.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
    }

    private byte[] search(URL url) throws IOException, InterruptedException {
        synchronized (this) {
            long wait = 1300 - (SystemClock.elapsedRealtime() - lastSearch);
            if (wait > 0) Thread.sleep(wait);
            if (Thread.currentThread().isInterrupted()) return null;
            lastSearch = SystemClock.elapsedRealtime();
        }
        return get(url, Endpoint.MUSICBRAINZ);
    }

    private enum Endpoint { MUSICBRAINZ, ARCHIVE, NETEASE, NETEASE_COVER, QQ, QQ_COVER, AUDIODB, AUDIODB_COVER }

    private static byte[] get(URL url, Endpoint endpoint) throws IOException {
        boolean artwork = endpoint == Endpoint.ARCHIVE || endpoint == Endpoint.NETEASE_COVER
                || endpoint == Endpoint.QQ_COVER || endpoint == Endpoint.AUDIODB_COVER;
        for (int redirects = 0; redirects < 4; redirects++) {
            if (Thread.currentThread().isInterrupted()) return null;
            String host = url.getHost().toLowerCase(Locale.ROOT);
            boolean allowed;
            switch (endpoint) {
                case MUSICBRAINZ: allowed = host.equals("musicbrainz.org"); break;
                case ARCHIVE: allowed = host.equals("coverartarchive.org") || host.equals("archive.org")
                        || host.endsWith(".archive.org"); break;
                case NETEASE: allowed = host.equals("music.163.com"); break;
                case NETEASE_COVER: allowed = host.endsWith(".music.126.net"); break;
                case QQ: allowed = host.equals("c.y.qq.com"); break;
                case QQ_COVER: allowed = host.equals("y.gtimg.cn"); break;
                case AUDIODB: allowed = host.equals("www.theaudiodb.com"); break;
                case AUDIODB_COVER: allowed = host.equals("r2.theaudiodb.com")
                        || host.equals("www.theaudiodb.com") || host.equals("theaudiodb.com"); break;
                default: throw new AssertionError(endpoint);
            }
            if (!allowed || !"https".equals(url.getProtocol())) return null;
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int status = connection.getResponseCode();
                if (artwork && status >= 301 && status <= 308) {
                    String target = connection.getHeaderField("Location");
                    if (target == null) return null;
                    url = new URL(url, target);
                    if ("http".equals(url.getProtocol())) url = new URL("https", url.getHost(), url.getFile());
                    continue;
                }
                RideLog.event("artwork.http", "stage", artwork ? "cover" : "search", "host", host, "status", status);
                if (status == 429 || status >= 500) throw new IOException("Artwork service unavailable: " + status);
                if (status != 200) return null;
                int limit = artwork ? 512 * 1024 : 768 * 1024;
                return readLimited(connection, limit);
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    private static byte[] readLimited(HttpsURLConnection connection, int limit) throws IOException {
        if (connection.getContentLength() > limit) return null;
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = in.read(buffer)) != -1; ) {
                if (output.size() + count > limit || Thread.currentThread().isInterrupted()) return null;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean same(String lhs, String rhs) {
        String a = normalize(lhs), b = normalize(rhs);
        if (a.equals(b)) return true;
        return android.os.Build.VERSION.SDK_INT >= 29 && a.codePoints().anyMatch(Character::isIdeographic)
                && SimplifiedChinese.convert(a).equals(SimplifiedChinese.convert(b));
    }

    private static final class SimplifiedChinese {
        private static final android.icu.text.Transliterator CONVERTER =
                android.icu.text.Transliterator.getInstance("Traditional-Simplified");

        static synchronized String convert(String value) {
            return CONVERTER.transliterate(value);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String artist(JSONArray credits) {
        StringBuilder names = new StringBuilder();
        if (credits != null) {
            for (int i = 0; i < credits.length(); i++) {
                JSONObject credit = credits.optJSONObject(i);
                if (credit != null) names.append(credit.optString("name")).append(credit.optString("joinphrase"));
            }
        }
        return names.toString();
    }

    static final class Track {
        final String title, artist, album;

        Track(String title, String artist, String album) {
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.album = album == null ? "" : album.trim();
        }

        boolean searchable() {
            return !title.isEmpty() && !artist.isEmpty() && title.length() <= 200
                    && artist.length() <= 150 && album.length() <= 200;
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Track)) return false;
            Track other = (Track) value;
            return title.equals(other.title) && artist.equals(other.artist) && album.equals(other.album);
        }

        @Override public int hashCode() { return Objects.hash(title, artist, album); }
    }
}
