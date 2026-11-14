package com.retromobilebench;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** Fixed hashed offline corpus. Stream large references so API 8's small Java heap is sufficient. */
final class MediaCorpus {
    private static final int LIMIT = 16 * 1024 * 1024;
    static JSONObject manifest(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("media/manifest.json")) {
            return new JSONObject(new String(GpuDataPack.readBounded(in, 256 * 1024), "UTF-8"));
        }
    }
    private static byte[] asset(Context context, String name, String hash, int max) throws Exception {
        try (InputStream in = context.getAssets().open("media/" + name)) {
            byte[] data = GpuDataPack.readBounded(in, max);
            if (!hash.equals(GpuDataPack.hex(MessageDigest.getInstance("SHA-256").digest(data)))) throw new IOException("媒体素材 SHA-256 不匹配：" + name);
            return data;
        }
    }
    private static String copyHash(InputStream in, OutputStream out, int max, GpuDataPack.Cancellation cancellation) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768]; int count, total = 0;
        while ((count = in.read(buffer)) != -1) {
            if (cancellation.cancelled()) throw new InterruptedIOException("媒体准备取消或超时");
            total += count; if (total > max) throw new IOException("媒体文件超出固定容量");
            sha.update(buffer, 0, count); if (out != null) out.write(buffer, 0, count);
        }
        return GpuDataPack.hex(sha.digest());
    }
    private static File materialize(Context context, String name, String expected, GpuDataPack.Cancellation cancellation) throws Exception {
        if (!name.matches("[a-zA-Z0-9.-]+")) throw new IOException("媒体路径无效");
        File root = new File(context.getFilesDir(), "media-v1");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("媒体资源目录创建失败");
        File file = new File(root, name);
        if (file.isFile()) try (InputStream in = new FileInputStream(file)) {
            if (expected.equals(copyHash(in, null, LIMIT, cancellation))) return file;
        } catch (InterruptedIOException e) { throw e; }
        catch (IOException damaged) { /* Replace unreadable/oversized cached files from the bundled asset. */ }
        File part = new File(root, name + ".part");
        try {
            String hash;
            try (InputStream in = context.getAssets().open("media/" + name); FileOutputStream out = new FileOutputStream(part)) {
                // Assets are bounded by LIMIT; check incrementally as compressed assets lack a known file length.
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768]; int count, total = 0;
                while ((count = in.read(buffer)) != -1) {
                    if (cancellation.cancelled()) throw new InterruptedIOException("媒体准备取消或超时");
                    total += count; if (total > LIMIT) throw new IOException("媒体文件超出固定容量");
                    if (DeviceSnapshot.availableBytes(root) < count + 16L * 1024 * 1024) throw new IOException("媒体空间不足，需保留 16 MiB");
                    out.write(buffer, 0, count); sha.update(buffer, 0, count);
                }
                out.getFD().sync(); hash = GpuDataPack.hex(sha.digest());
            }
            if (!expected.equals(hash)) throw new IOException("媒体素材 SHA-256 不匹配：" + name);
            if (!part.renameTo(file)) throw new IOException("媒体资源提交失败");
        } finally { if (part.exists()) part.delete(); }
        return file;
    }
    static void prepare(Context context, GpuDataPack.Cancellation cancellation) throws Exception {
        JSONObject manifest = manifest(context); JSONArray images = manifest.getJSONArray("images");
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.getJSONObject(i);
            try (InputStream in = context.getAssets().open("media/" + image.getString("file"))) {
                if (!image.getString("sha256").equals(copyHash(in, null, LIMIT, cancellation))) throw new IOException("图片素材哈希不匹配");
            }
            materialize(context, image.getString("reference"), image.getString("reference_sha256"), cancellation);
        }
        JSONArray clips = manifest.getJSONArray("videos");
        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.getJSONObject(i);
            materialize(context, clip.getString("file"), clip.getString("sha256"), cancellation);
        }
    }
    static String images(Context context, int duration) {
        JSONArray results = new JSONArray();
        try {
            MediaNative.load(); JSONObject manifest = manifest(context); JSONArray list = manifest.getJSONArray("images"); boolean complete = true;
            for (int i = 0; i < list.length() && !MediaClient.cancelled(); i++) {
                JSONObject image = list.getJSONObject(i), result;
                try {
                    byte[] compressed = asset(context, image.getString("file"), image.getString("sha256"), LIMIT);
                    File reference = materialize(context, image.getString("reference"), image.getString("reference_sha256"), MediaClient::cancelled);
                    result = new JSONObject(NativeBridge.runImage(compressed, reference.getPath(), "png".equals(image.getString("format")), image.getInt("width"), image.getInt("height"), duration));
                    if ("completed".equals(result.optString("status")) && !image.getString("reference_sha256").equals(result.optString("pixel_sha256")))
                        result.put("status", "failed").put("error_stage", "timed_output_pixels_mismatch");
                } catch (Exception e) { result = MediaClient.failure(MediaClient.cancelled() ? "cancelled" : "failed", e.toString()); }
                result.put("id", image.getString("id")).put("asset_sha256", image.getString("sha256"))
                        .put("reference_sha256", image.getString("reference_sha256")).put("reference_validation", "byte_exact_RGBA8");
                if (!"completed".equals(result.optString("status"))) { complete = false; result.remove("mpixels_per_second"); result.remove("ms_per_image"); }
                results.put(result); if ("cancelled".equals(result.optString("status"))) break;
            }
            return new JSONObject().put("test_version", "image-decode-v1").put("status", MediaClient.cancelled() ? "cancelled" : complete ? "completed" : "partial")
                    .put("completed_metrics", 0).put("corpus_version", manifest.getString("version")).put("duration_ms", duration).put("results", results).toString();
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            try { return MediaClient.failure("failed", e.toString()).put("results", results).toString(); }
            catch (Exception ignored) { return MediaClient.failure("failed", e.toString()).toString(); }
        }
    }
    static String video(Context context, int threads) {
        JSONArray results = new JSONArray(); boolean complete = true; long start = android.os.SystemClock.elapsedRealtime();
        try {
            JSONObject manifest = manifest(context); JSONArray clips = manifest.getJSONArray("videos");
            for (int i = 0; i < clips.length() && !MediaClient.cancelled(); i++) {
                JSONObject clip = clips.getJSONObject(i);
                JSONObject record = new JSONObject().put("id", clip.getString("id")).put("asset_sha256", clip.getString("sha256"));
                results.put(record); // Keep already completed paths if a later Surface/worker fails.
                File path;
                try { path = materialize(context, clip.getString("file"), clip.getString("sha256"), MediaClient::cancelled); }
                catch (IOException e) {
                    complete = false;
                    for (String mode : new String[]{"software", "system", "playback"}) record.put(mode, MediaClient.failure(MediaClient.cancelled() ? "cancelled" : "failed", "prepare: " + e));
                    continue;
                }
                for (String mode : new String[]{"software", "system", "playback"}) {
                    if (MediaClient.cancelled()) break;
                    JSONObject result;
                    try { result = MediaClient.run(context, clip, path.getPath(), mode, threads); }
                    catch (Exception e) { record.put(mode, MediaClient.failure(MediaClient.cancelled() ? "cancelled" : "failed", e.toString())); throw e; }
                    if (!"completed".equals(result.optString("status"))) { complete = false; result.remove("fps"); }
                    if (clip.getInt("frames") == 30) {
                        result.put("compatibility_probe", true); result.remove("fps");
                    }
                    record.put(mode, result);
                }
            }
            return new JSONObject().put("test_version", "video-decode-v1").put("status", MediaClient.cancelled() ? "cancelled" : complete ? "completed" : "partial")
                    .put("completed_metrics", 0).put("corpus_version", manifest.getString("version")).put("corpus", manifest)
                    .put("results", results).put("wall_ms", android.os.SystemClock.elapsedRealtime() - start).toString();
        } catch (Exception | LinkageError e) {
            try { return MediaClient.failure("failed", e.toString()).put("results", results).toString(); }
            catch (Exception ignored) { return MediaClient.failure("failed", e.toString()).toString(); }
        } finally { DisplaySurface.hide(); }
    }
}
