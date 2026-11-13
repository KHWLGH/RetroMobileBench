package com.retromobilebench;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Serial-worker-only verified immutable packs. A failed import never replaces a good pack. */
final class GpuDataPack {
    interface Cancellation { boolean cancelled(); }
    static final String SOURCE = "89aa956f43836798d1eaaab8ec32d3dc8b02eead";
    static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 3000;
    private GpuDataPack() { }

    static File ensure(Context context, String test, Cancellation cancellation) throws Exception {
        File parent = new File(context.getFilesDir(), "gpu-packs");
        File[] packs = parent.listFiles();
        File best = null;
        if (packs != null) for (File pack : packs) {
            if (!pack.getName().startsWith("pack-")) continue;
            try {
                JSONObject manifest = manifest(pack);
                if (contains(manifest.getJSONArray("tests"), test)
                        && (best == null || pack.lastModified() > best.lastModified())) best = pack;
            } catch (Exception ignored) { }
        }
        if (best != null) return best;
        // Avoid repeatedly installing the lite pack when a scene is unavailable.
        try (ZipInputStream source = new ZipInputStream(context.getAssets().open("gpu-data.zip"))) {
            ZipEntry entry = source.getNextEntry();
            if (entry == null || !"manifest.json".equals(entry.getName())) throw new IOException("内置数据包无清单");
            JSONObject bundled = new JSONObject(new String(readBounded(source, 2 * 1024 * 1024), "UTF-8"));
            if (!contains(bundled.getJSONArray("tests"), test)) return null;
        }
        try (InputStream source = context.getAssets().open("gpu-data.zip")) {
            File installed = install(parent, source, cancellation);
            return contains(manifest(installed).getJSONArray("tests"), test) ? installed : null;
        }
    }

    static File install(File parent, InputStream input, Cancellation cancellation) throws Exception {
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法建立数据目录");
        File staging = new File(parent, "import-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("无法建立临时目录");
        boolean installed = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry first = zip.getNextEntry();
            if (first == null || !"manifest.json".equals(first.getName())) throw new IOException("数据包首项必须是 manifest.json");
            byte[] manifestBytes = readBounded(zip, 2 * 1024 * 1024);
            JSONObject manifest = new JSONObject(new String(manifestBytes, "UTF-8"));
            validateManifest(manifest);
            JSONArray files = manifest.getJSONArray("files");
            Map<String, JSONObject> expected = new HashMap<>();
            long total = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String path = file.getString("path");
                safePath(staging, path);
                long size = file.getLong("size");
                if (size < 0 || size > 64L * 1024 * 1024 || !file.getString("sha256").matches("[0-9a-f]{64}"))
                    throw new IOException("无效文件元数据");
                if (expected.put(path, file) != null) throw new IOException("重复文件路径");
                total += size;
                if (total > MAX_BYTES) throw new IOException("数据包超过容量上限");
            }
            if (DeviceSnapshot.availableBytes(parent) < total + 16L * 1024 * 1024) throw new IOException("空间不足，需保留 16 MiB");
            byte[] buffer = new byte[32768];
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (cancellation.cancelled()) throw new InterruptedIOException("数据包导入已取消");
                String path = entry.getName();
                JSONObject file = expected.get(path);
                if (entry.isDirectory() || file == null || !seen.add(path)) throw new IOException("未声明或重复的 ZIP 条目");
                File destination = safePath(staging, path);
                if (!destination.getParentFile().isDirectory() && !destination.getParentFile().mkdirs()) throw new IOException("目录创建失败");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0, limit = file.getLong("size");
                try (FileOutputStream out = new FileOutputStream(destination)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (cancellation.cancelled()) throw new InterruptedIOException("数据包导入已取消");
                        size += count;
                        if (size > limit) throw new IOException("文件大于声明大小");
                        digest.update(buffer, 0, count); out.write(buffer, 0, count);
                    }
                }
                if (size != limit || !hex(digest.digest()).equals(file.getString("sha256"))) throw new IOException("文件校验失败：" + path);
            }
            if (seen.size() != expected.size()) throw new IOException("数据包缺少文件");
            for (int i = 0; i < manifest.getJSONArray("tests").length(); i++) {
                String test = manifest.getJSONArray("tests").getString(i);
                if (!seen.contains("config/" + test + ".json")) throw new IOException("缺少测试配置");
                if (test.contains("trex") && !seen.contains("data/gfx/trex/scene_trex.xml")) throw new IOException("缺少 T-Rex 场景");
                if (test.contains("egypt") && !seen.contains("data/gfx/egypt/scene_egypt.xml")) throw new IOException("缺少 Egypt 场景");
            }
            try (FileOutputStream out = new FileOutputStream(new File(staging, "manifest.json"))) { out.write(manifestBytes); out.getFD().sync(); }
            if (cancellation.cancelled()) throw new InterruptedIOException("数据包导入已取消");
            File destination = new File(parent, "pack-" + UUID.randomUUID());
            if (!staging.renameTo(destination)) throw new IOException("数据包提交失败");
            installed = true;
            // The serial worker cannot be using another pack here. Retain packs
            // with scenes absent from the new pack; remove only fully replaced ones.
            File[] previous = parent.listFiles();
            if (previous != null) for (File old : previous) {
                if (old.equals(destination) || !old.getName().startsWith("pack-")) continue;
                try {
                    JSONArray oldTests = manifest(old).getJSONArray("tests");
                    boolean replaced = true;
                    for (int i = 0; i < oldTests.length(); i++)
                        replaced &= contains(manifest.getJSONArray("tests"), oldTests.getString(i));
                    if (replaced) removeTree(old);
                } catch (Exception cleanupError) {
                    android.util.Log.w("RMB", "新包已安装，旧数据包清理失败", cleanupError);
                }
            }
            return destination;
        } finally { if (!installed) removeTree(staging); }
    }

    static void cleanup(Context context) throws IOException {
        File[] files = new File(context.getFilesDir(), "gpu-packs").listFiles();
        if (files != null) for (File file : files) if (file.getName().startsWith("import-")) removeTree(file);
    }

    static JSONObject manifest(File pack) throws Exception {
        try (InputStream input = new FileInputStream(new File(pack, "manifest.json"))) {
            JSONObject result = new JSONObject(new String(readBounded(input, 2 * 1024 * 1024), "UTF-8"));
            validateManifest(result); return result;
        }
    }

    static String digest(File pack) throws Exception {
        try (InputStream input = new FileInputStream(new File(pack, "manifest.json"))) {
            return hex(MessageDigest.getInstance("SHA-256").digest(readBounded(input, 2 * 1024 * 1024)));
        }
    }

    private static void validateManifest(JSONObject manifest) throws Exception {
        if (manifest.getInt("format") != 2 || !SOURCE.equals(manifest.getString("source"))
                || !"ETC1".equals(manifest.getString("texture_type")) || manifest.getString("license").length() == 0)
            throw new IOException("不支持的数据包版本/来源/纹理格式");
        JSONArray files = manifest.getJSONArray("files"), tests = manifest.getJSONArray("tests");
        if (files.length() < 1 || files.length() > MAX_FILES || tests.length() < 1 || tests.length() > 6)
            throw new IOException("数据包条目数量无效");
        for (int i = 0; i < tests.length(); i++) if (!GpuClient.isTest(tests.getString(i))) throw new IOException("未知 GPU 测试");
    }

    private static File safePath(File root, String path) throws IOException {
        if (!path.matches("(config|data/gfx)/[A-Za-z0-9_./-]+") || path.contains("..") || path.contains("//") || path.endsWith("/"))
            throw new IOException("非法数据包路径");
        File file = new File(root, path);
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("路径越界");
        return file;
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > limit) throw new IOException("文件超过上限");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) { int v = value & 255; text.append("0123456789abcdef".charAt(v >>> 4)).append("0123456789abcdef".charAt(v & 15)); }
        return text.toString();
    }
    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
    static void removeTree(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) { if (file.isDirectory()) removeTree(file); else if (!file.delete()) throw new IOException("清理失败：" + file); }
        if (directory.exists() && !directory.delete()) throw new IOException("清理失败：" + directory);
    }
}
