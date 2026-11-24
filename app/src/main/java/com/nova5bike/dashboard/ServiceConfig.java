package com.nova5bike.dashboard;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;

/** Optional local service settings bundled at build time. Never logged. */
final class ServiceConfig {
    static JSONObject section(Context context, String name) {
        JSONObject root = read(context, "services.json");
        JSONObject value = root.optJSONObject(name);
        if (value != null) return value;
        return "amap".equals(name) ? read(context, "amap.json") : new JSONObject();
    }

    private static JSONObject read(Context context, String file) {
        try (InputStream stream = context.getAssets().open(file)) {
            return new JSONObject(AmapClient.read(stream));
        } catch (IOException | JSONException ignored) {
            return new JSONObject();
        }
    }
}
