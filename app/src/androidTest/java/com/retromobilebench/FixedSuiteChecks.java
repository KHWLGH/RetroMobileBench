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
        check(gpu == 5 && suites.size() == 12 && BenchmarkSuite.values().length == 12 && suites.contains(BenchmarkSuite.WEB) && suites.contains(BenchmarkSuite.GPU_FILL), "fixed product scope");
        int triple = 0;
        for (BenchmarkSuite suite : BenchmarkSuite.DEFAULT) { check(suite.repetitions() == (suite.isGpu() || suite == BenchmarkSuite.VIDEO ? 1 : 3), "automatic repetition policy"); if (suite.repetitions() == 3) triple++; }
        check(triple == 6 && BenchmarkSuite.DEFAULT[11] == BenchmarkSuite.GPU_FILL, "six triple stages, Fill last");
        MediaNative.load(); NativeBridge.resetMediaCancellation();
        MediaCorpus.prepare(context, () -> false);
        JSONObject manifest = MediaCorpus.manifest(context);
        check(manifest.getJSONArray("videos").length() == 7, "HD/FHD and old codec corpus present");
        NativeBridge.resetScimarkCancellation();
        JSONObject scimark = new JSONObject(NativeBridge.runScimark(2, 50));
        check("completed".equals(scimark.optString("status")), "scimark multi-thread sample");
        JSONObject session = new JSONObject().put("suite_version", BenchmarkSuite.SESSION_VERSION).put("status", "running")
                .put("started_ms", 1).put("results", new JSONArray().put(new JSONObject().put("suite_version", "cpu-zstd-v1").put("status", "skipped").put("started_ms", 1)))
                .put("active_report", new JSONObject().put("suite_version", BenchmarkSuite.SCIMARK.version).put("status", "running").put("started_ms", 1)
                        .put("repetitions", 3).put("samples", new JSONArray().put(scimark)).put("median", new JSONObject()));
        ResultStore store = new ResultStore(context, null);
        try { long id = store.begin(session); store.recoverInterrupted(); JSONObject saved = store.latest(1).getJSONObject(0);
            check(saved.getLong("id") == id && "interrupted".equals(saved.getString("status")) && saved.getJSONArray("results").length() == 1, "interrupted session keeps all completed stages");
            JSONObject active = saved.getJSONObject("active_report");
            check("interrupted".equals(active.getString("status")) && active.getJSONArray("samples").length() == 1 && !active.has("median"), "active completed rounds retained without normal median");
            check(BenchmarkReport.format(saved).contains("本次固定套件"), "session readable");
        } finally { store.close(); }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
