package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses upstream screenshot_frames on a separate config copy; never produces a score. */
final class GpuSceneCaptureChecks {
    static String run(Instrumentation test, String id, GpuBackendChecks.Progress progress) throws Exception {
        check(android.os.Build.VERSION.SDK_INT >= 11, "shared Surface capture needs API 11");
        check("gl_egypt".equals(id) || "gl_trex".equals(id), "known screen scene");
        Context context = test.getTargetContext();
        android.app.Activity main = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicBoolean focused = new AtomicBoolean();
        long focusDeadline = SystemClock.elapsedRealtime() + 10000;
        do {
            test.runOnMainSync(() -> focused.set(main.hasWindowFocus()));
            if (focused.get()) break;
            check(SystemClock.elapsedRealtime() < focusDeadline, "unlock device and focus main window");
            SystemClock.sleep(50);
        } while (true);
        check(NativeLoader.load() != null, "native host loads");
        File pack = GpuDataPack.ensure(context, id, () -> false);
        check(pack != null, "verified original data pack");
        File output = new File(context.getFilesDir(), "gpu-scene-diagnostic/" + id);
        check(output.isDirectory() || output.mkdirs(), "diagnostic output directory");
        JSONObject config = new JSONObject(new String(read(new File(pack, "config/" + id + ".json")), "UTF-8"));
        String frames = "gl_egypt".equals(id) ? "12000,35000,65000,95000" : "12000,25000,35000,45000";
        File[] captures = new File[4];
        String[] times = frames.split(",");
        for (int i = 0; i < times.length; i++) {
            captures[i] = new File(output, String.format(java.util.Locale.US, "scr_%s_%06dms.tga", id, Integer.parseInt(times[i])));
            for (File old : new File[]{captures[i], new File(output, captures[i].getName() + ".png")})
                check(!old.exists() || old.delete(), "remove previous diagnostic capture");
        }
        File reportFile = new File(output, "capture-report.json");
        check(!reportFile.exists() || reportFile.delete(), "remove previous diagnostic report");
        config.getJSONObject("raw_config").put("screenshot_frames", frames);
        File copy = new File(output, "capture-config.json");
        write(copy, config.toString(2));
        try {
            JavaEglContext.window = DisplaySurface.acquire(context, () -> false, true);
            progress.stage("diagnostic only: upstream GL frame readback at " + frames + " ms; no performance score");
            String raw = GfxProbe.runTest(copy.getPath(), new File(pack, "data/gfx").getPath() + "/", output.getPath() + "/",
                    DisplaySurface.width(), DisplaySurface.height(), 0);
            JSONObject result = GpuClient.parse(raw, id);
            check("completed".equals(result.optString("status")), result.toString());
            for (File capture : captures) {
                check(capture.isFile(), "fresh fixed animation-time GL capture: " + capture.getName());
                convertTga(capture);
            }
            int images = captures.length;
            JSONObject report = new JSONObject().put("diagnostic_only", true).put("score_eligible", false)
                    .put("native_build_id", BuildConfig.NATIVE_BUILD_ID).put("test_id", id)
                    .put("screenshot_frames_ms", frames).put("images", images).put("data_sha256", GpuDataPack.digest(pack))
                    .put("measurement_with_readback_not_for_comparison", result);
            write(reportFile, report.toString(2));
            return report.toString();
        } finally { JavaEglContext.window = null; DisplaySurface.hide(); }
    }
    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[65536]; int count;
            while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
            return out.toByteArray();
        }
    }
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes("UTF-8")); }
    }
    private static void convertTga(File file) throws Exception {
        byte[] bytes = read(file);
        check(bytes.length >= 18 && bytes[1] == 0 && bytes[2] == 2, "uncompressed true-color upstream TGA");
        int width = (bytes[12] & 255) | (bytes[13] & 255) << 8, height = (bytes[14] & 255) | (bytes[15] & 255) << 8;
        int channels = (bytes[16] & 255) / 8, offset = 18 + (bytes[0] & 255);
        check(width > 0 && height > 0 && width <= 4096 && height <= 4096 && (channels == 3 || channels == 4)
                && bytes.length == offset + width * height * channels, "bounded TGA dimensions and payload");
        int[] pixels = new int[width * height];
        boolean top = (bytes[17] & 32) != 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int at = offset + (y * width + x) * channels;
            pixels[(top ? y : height - 1 - y) * width + x] = 0xff000000 | (bytes[at + 2] & 255) << 16
                    | (bytes[at + 1] & 255) << 8 | (bytes[at] & 255);
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(new File(file.getParentFile(), file.getName() + ".png"))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "save opaque GL RGB capture");
        } finally { bitmap.recycle(); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private GpuSceneCaptureChecks() { }
}
