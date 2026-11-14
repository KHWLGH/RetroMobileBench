package com.retromobilebench;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;

/** Regression risks introduced by the fixed suite, not copies of the timing algorithms. */
final class FixedSuiteChecks {
    static void run(Context context) throws Exception {
        for (String invalid : new String[]{"", "0-", "-1", "3-1", "0,4096", "text", "0-1-2"}) check(CpuTopology.count(invalid) == 0, "malformed topology rejected");
        check(CpuTopology.count("0-3,2-7") == 8, "overlap does not invent CPUs");
        JSONObject device = new JSONObject().put("available_processors", 1).put("cpu_online", "0")
                .put("cpu_present", "0-7").put("configured_processors", 8).put("battery_temperature_tenths_c", 850);
        check(CpuTopology.workers(device) == 8, "offline cores included");
        check(BenchmarkRunner.cpuPreflight(device) == null, "high temperature cannot block");
        device.remove("battery_temperature_tenths_c"); check(BenchmarkRunner.cpuPreflight(device) == null, "missing sensor cannot block");
        HashSet<BenchmarkSuite> suites = new HashSet<>(); int gpu = 0;
        for (BenchmarkSuite suite : BenchmarkSuite.DEFAULT) { check(suites.add(suite), "suite not repeated"); if (suite.isGpu()) gpu++; }
        check(gpu == 5 && suites.size() == 14 && suites.contains(BenchmarkSuite.GPU_FILL) && !suites.contains(BenchmarkSuite.ZSTD) && !suites.contains(BenchmarkSuite.STREAM) && !suites.contains(BenchmarkSuite.GPU_ALU), "fixed product scope");
        int triple = 0;
        for (BenchmarkSuite suite : BenchmarkSuite.DEFAULT) { check(suite.repetitions() == (suite.isGpu() || suite == BenchmarkSuite.VIDEO ? 1 : 3), "automatic repetition policy"); if (suite.repetitions() == 3) triple++; }
        check(triple == 8 && BenchmarkSuite.DEFAULT[13] == BenchmarkSuite.GPU_FILL, "eight triple stages, Fill last");
        MediaNative.load(); NativeBridge.resetMediaCancellation();
        for (int threads : new int[]{1, 8}) {
            JSONObject crypto = new JSONObject(NativeBridge.runCrypto(threads, 50));
            check("completed".equals(crypto.optString("status")), "crypto KAT, bad-tag rejection, roundtrip and workers: " + crypto);
            check(crypto.getInt("completed_metrics") == (threads == 1 ? 3 : 6), "one-core duplicate omitted");
        }
        NativeBridge.cancelMedia(); check("cancelled".equals(new JSONObject(NativeBridge.runCrypto(8, 50)).optString("status")), "media precancel");
        NativeBridge.resetMediaCancellation();
        MediaCorpus.prepare(context, () -> false);
        java.io.File damaged = new java.io.File(context.getFilesDir(), "media-v1/jpeg-854-480.rgba");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(damaged)) { out.write(new byte[]{1, 2, 3}); }
        JSONObject images = new JSONObject(MediaCorpus.images(context, 50));
        check("completed".equals(images.optString("status")) && images.getJSONArray("results").length() == 6, "fixed JPEG/PNG reference pixels: " + images);
        imageSummary(images);
        JSONObject manifest = MediaCorpus.manifest(context);
        check(manifest.getJSONArray("videos").length() == 7, "HD/FHD and old codec corpus present");
        JSONObject session = new JSONObject().put("suite_version", BenchmarkSuite.SESSION_VERSION).put("status", "running")
                .put("started_ms", 1).put("results", new JSONArray().put(new JSONObject().put("suite_version", "cpu-zstd-v1").put("status", "skipped").put("started_ms", 1)))
                .put("active_report", new JSONObject().put("suite_version", BenchmarkSuite.IMAGE.version).put("status", "running").put("started_ms", 1)
                        .put("repetitions", 3).put("samples", new JSONArray().put(images)).put("median", new JSONObject()));
        ResultStore store = new ResultStore(context, null);
        try { long id = store.begin(session); store.recoverInterrupted(); JSONObject saved = store.latest(1).getJSONObject(0);
            check(saved.getLong("id") == id && "interrupted".equals(saved.getString("status")) && saved.getJSONArray("results").length() == 1, "interrupted session keeps all completed stages");
            JSONObject active = saved.getJSONObject("active_report");
            check("interrupted".equals(active.getString("status")) && active.getJSONArray("samples").length() == 1 && !active.has("median"), "active completed rounds retained without normal median");
            check(BenchmarkReport.format(saved).contains("本次固定套件"), "session and legacy child readable");
            saved.put("suite_version", "fixed-suite-v1");
            check(BenchmarkReport.format(saved).contains("本次固定套件"), "old fixed suite remains readable");
        } finally { store.close(); }
    }
    private static void imageSummary(JSONObject sample) throws Exception {
        JSONObject report = new JSONObject().put("suite_version", BenchmarkSuite.IMAGE.version).put("status", "completed")
                .put("started_ms", 1).put("repetitions", 3);
        JSONArray samples = new JSONArray(); report.put("samples", samples);
        for (double value : new double[]{2, 20, 7}) {
            JSONObject copy = new JSONObject(sample.toString()); JSONArray original = copy.getJSONArray("results"), reversed = new JSONArray();
            for (int i = original.length() - 1; i >= 0; i--) reversed.put(original.getJSONObject(i).put("mpixels_per_second", value).put("ms_per_image", value * 2));
            if (value == 20) copy.put("results", reversed);
            samples.put(copy);
        }
        BenchmarkReport.summarize(report);
        JSONObject medians = report.getJSONObject("median").getJSONObject("images");
        for (int i = 0; i < 6; i++) {
            JSONObject item = medians.getJSONObject(sample.getJSONArray("results").getJSONObject(i).getString("id"));
            check(item.getDouble("mpixels_per_second") == 7 && item.getDouble("ms_per_image") == 14, "image medians match IDs despite order");
        }
        check(BenchmarkReport.format(report).contains("三轮中位数"), "show compact image summary");
        JSONObject changed = samples.getJSONObject(2).getJSONArray("results").getJSONObject(0);
        for (String key : new String[]{"asset_sha256", "reference_sha256", "pixel_sha256", "library", "status", "id"}) {
            Object original = changed.get(key); changed.put(key, "changed"); BenchmarkReport.summarize(report);
            check(!report.has("median"), "reject changed image identity/output " + key); changed.put(key, original);
        }
        changed.put("mpixels_per_second", 0); BenchmarkReport.summarize(report);
        check(!report.has("median"), "reject invalid image rate");
        changed.put("mpixels_per_second", 7); samples.getJSONObject(2).put("duration_ms", 100);
        BenchmarkReport.summarize(report); check(!report.has("median"), "reject mixed image timing");
        // Pre-v2 diagnostic histories used an empty median; raw results must still display.
        report.put("median", new JSONObject()); check(BenchmarkReport.format(report).contains("第 1 轮"), "legacy image median fallback");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
