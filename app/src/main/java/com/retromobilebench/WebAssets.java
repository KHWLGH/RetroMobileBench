package com.retromobilebench;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Verify the entire offline pack before launching a round; never inside the JS timer. */
final class WebAssets {
    static final String VERSION = "web-daily-v1";
    static JSONObject verify(Context context) throws Exception {
        byte[] manifest = read(context, "manifest.json");
        JSONObject value = new JSONObject(new String(manifest, "UTF-8"));
        if (!VERSION.equals(value.getString("protocol"))) throw new IllegalStateException("Web pack protocol");
        JSONArray files = value.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i); String path = item.getString("path");
            if (path.contains("/") || path.contains("\\") || path.contains("..")) throw new IllegalStateException("Web asset path");
            byte[] bytes = read(context, path);
            if (bytes.length != item.getInt("bytes") || !sha(bytes).equals(item.getString("sha256")))
                throw new IllegalStateException("Web asset checksum: " + path);
        }
        return new JSONObject().put("resource_sha256", sha(manifest)).put("resource_manifest", value)
                .put("host_build_id", BuildConfig.WEB_BUILD_ID);
    }
    private static byte[] read(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open("web/" + name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) { output.write(buffer, 0, n); if (output.size() > 2000000) throw new IllegalStateException("Web asset bound"); }
            return output.toByteArray();
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.US, "%02x", b & 255)); return text.toString();
    }
}
