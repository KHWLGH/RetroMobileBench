package com.retromobilebench;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class GpuBackendChecks {
    interface Progress { void stage(String message); }
    static void run(Context context, boolean full, Progress progress) throws Exception {
        progress.stage("GPU pack validation");
        packValidation(context);
        GpuClient.resetCancellation();
        File pack = GpuDataPack.ensure(context, "gl_egypt_off", () -> false);
        check(pack != null, "built-in fixed scene pack installed");
        try (FileOutputStream out = new FileOutputStream(new File(pack, "config/gl_egypt_off.json"))) { out.write(new byte[]{1, 2, 3}); }
        GpuDataPack.beginSession();
        File repaired = GpuDataPack.ensure(context, "gl_egypt_off", () -> false);
        check(repaired != null && !repaired.equals(pack), "damaged installed resource restored automatically");
        int lastPid = 0;
        for (String test : new String[]{"gl_egypt_off", "gl_egypt", "gl_trex_off", "gl_trex", "gl_fill_off"}) {
            progress.stage(test);
            File available = GpuDataPack.ensure(context, test, () -> false);
            if (available == null) {
                check(!full, "full pack missing " + test);
                continue;
            }
            JSONObject result = new JSONObject(GpuClient.run(context, test, "gl_fill_off".equals(test) ? 0 : 5000));
            check("completed".equals(result.getString("status")), "GPU " + test + ": " + result);
            check(result.getDouble("score") > 0 && result.getInt("completed_metrics") == 1, "GPU score");
            if ("gl_fill_off".equals(test)) check(result.getInt("diagnostic_duration_ms") == 0 && "MTexels/s".equals(result.getString("unit")), "full Fill timing and unit");
            if (!test.endsWith("_off")) {
                check(result.getLong("successful_window_swaps") > 0, "actual window buffers swapped");
                check("fullscreen".equals(result.optString("window_mode")), "on-screen GPU uses a full-screen window");
                check(GpuHud.VERSION.equals(result.optString("onscreen_hud")) && result.optInt("hud_updates") > 0, "on-screen HUD receives frame-rate samples");
                if (android.os.Build.VERSION.SDK_INT < 11) check("worker_activity".equals(result.optString("window_owner")), "old GPU owns a local window");
            } else check("none".equals(result.optString("onscreen_hud")) && result.optInt("hud_updates") == 0, "offscreen tests have no HUD publisher");
            int pid = result.getInt("worker_pid");
            check(pid != Process.myPid() && pid != lastPid, "each GPU test gets a separate process");
            lastPid = pid;
            check(result.getString("data_sha256").length() == 64, "GPU data identity");
            check(result.getString("test_version").equals("gpu-" + test + "-v1"), "GPU version");
            JSONObject report = new JSONObject().put("started_ms", System.currentTimeMillis()).put("status", "completed")
                    .put("repetitions", 1).put("suite_version", "gpu-" + test + "-v1")
                    .put("samples", new JSONArray().put(result));
            BenchmarkReport.summarize(report);
            check(report.has("median") && BenchmarkReport.format(report).contains("ETC1"), "GPU report");
            JSONObject wrong = new JSONObject(result.getJSONObject("raw_result").toString());
            wrong.getJSONArray("results").getJSONObject(0).put("test_id", "other_test");
            check("failed".equals(GpuClient.parse(wrong.toString(), test).getString("status")), "wrong test identity rejected");
            wrong = new JSONObject(result.getJSONObject("raw_result").toString());
            wrong.getJSONArray("results").getJSONObject(0).getJSONObject("gfx_result").put("surface_width", 800);
            if (test.endsWith("_off")) check("failed".equals(GpuClient.parse(wrong.toString(), test).getString("status")), "wrong offscreen render resolution rejected");
        }
        progress.stage("GPU cancellation/timeout/restart");
        GpuClient.cancel();
        check("cancelled".equals(new JSONObject(GpuClient.run(context, "gl_egypt_off", 5000)).getString("status")), "GPU pre-cancel");
        GpuClient.resetCancellation();
        AtomicReference<String> raw = new AtomicReference<>();
        Thread worker = new Thread(() -> raw.set(GpuClient.run(context, "gl_egypt_off", 10000)));
        worker.start(); SystemClock.sleep(500); GpuClient.cancel(); worker.join(15000);
        check(!worker.isAlive() && "cancelled".equals(new JSONObject(raw.get()).getString("status")), "GPU active cancel");
        GpuClient.resetCancellation();
        pack = GpuDataPack.ensure(context, "gl_egypt_off", () -> false);
        JSONObject timeout = GpuClient.invoke(context, pack, "gl_egypt_off", 10000, 400);
        check("timeout".equals(timeout.getString("status")) && timeout.getString("error_stage").contains("超时"), "GPU timeout");
        check("completed".equals(new JSONObject(GpuClient.run(context, "gl_egypt_off", 5000)).getString("status")), "GPU restart after kill");
        check("failed".equals(GpuClient.parse("ERROR: init failed", "gl_alu_off").getString("status")), "GPU native failure");
    }

    private static void packValidation(Context context) throws Exception {
        File parent = new File(context.getCacheDir(), "pack-check-" + System.nanoTime());
        check(parent.mkdir(), "pack test directory");
        try {
            byte[] valid = fixture("config/gl_alu_off.json", false, false);
            File installed = GpuDataPack.install(parent, new ByteArrayInputStream(valid), () -> false);
            check(new File(installed, "manifest.json").isFile(), "pack commit marker");
            for (byte[] invalid : new byte[][]{
                    fixture("data/gfx/../../escape", false, false), fixture("config/gl_alu_off.json", true, false),
                    fixture("config/gl_alu_off.json", false, true), new byte[]{1, 2, 3}}) {
                boolean rejected = false;
                try { GpuDataPack.install(parent, new ByteArrayInputStream(invalid), () -> false); }
                catch (Exception expected) { rejected = true; }
                check(rejected, "corrupt/path/missing-file pack rejected");
                check(new File(installed, "manifest.json").isFile(), "existing pack preserved");
            }
            boolean rejected = false;
            try { GpuDataPack.install(parent, new ByteArrayInputStream(valid), () -> true); }
            catch (InterruptedIOException expected) { rejected = true; }
            check(rejected, "pack cancellation");
            File[] remaining = parent.listFiles();
            check(remaining != null && remaining.length == 1 && remaining[0].equals(installed), "no partial imports remain");
            File replacement = GpuDataPack.install(parent, new ByteArrayInputStream(valid), () -> false);
            check(!installed.exists() && new File(replacement, "manifest.json").isFile(), "successful replacement removes obsolete pack");
            remaining = parent.listFiles();
            check(remaining != null && remaining.length == 1, "repeated imports do not accumulate packs");
        } finally { GpuDataPack.removeTree(parent); }
    }

    private static byte[] fixture(String path, boolean corrupt, boolean missing) throws Exception {
        byte[] data = "{}".getBytes("UTF-8");
        String sha = GpuDataPack.hex(MessageDigest.getInstance("SHA-256").digest(data));
        JSONObject file = new JSONObject().put("path", path).put("size", data.length)
                .put("sha256", corrupt ? "0000000000000000000000000000000000000000000000000000000000000000" : sha);
        JSONObject manifest = new JSONObject().put("format", 2).put("source", GpuDataPack.SOURCE)
                .put("texture_type", "ETC1").put("license", "fixture").put("tests", new JSONArray().put("gl_alu_off"))
                .put("files", new JSONArray().put(file));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes("UTF-8")); zip.closeEntry();
            if (!missing) { zip.putNextEntry(new ZipEntry(path)); zip.write(data); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
