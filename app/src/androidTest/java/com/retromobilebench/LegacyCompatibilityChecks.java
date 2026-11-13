package com.retromobilebench;

import android.content.Context;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression checks for API 8 fallbacks and score separation across toolchains. */
final class LegacyCompatibilityChecks {
    static void run(Context context) throws Exception {
        check(PlatformCompat.parseTotalMemory(new StringReader("MemTotal: 123456 kB\nMemFree: 1 kB\n")) == 126418944L, "meminfo KiB conversion");
        for (String value : new String[]{"", "MemFree: 42 kB", "MemTotal: -1 kB", "MemTotal: text kB", "MemTotal: 123 MB", "MemTotal: 9223372036854775807 kB"})
            check(PlatformCompat.parseTotalMemory(new StringReader(value)) == -1, "missing/malformed/overflow memory rejected");
        JSONObject device = DeviceSnapshot.collect(context);
        check(device.getString("native_build_id").equals(BuildConfig.NATIVE_BUILD_ID), "native build identity recorded");
        check(device.optLong("total_memory_bytes") > 0, "total memory collected");
        check(BuildConfig.NATIVE_BUILD_ID.startsWith("r10e-gcc49-"), "pinned legacy compiler group");
        check(NativeBridge.probeReport().contains("native build: " + BuildConfig.NATIVE_BUILD_ID),
                "packaged native binary matches the Java build identity");
        JSONObject skipped = GpuClient.parse("SKIP: ETC1 unsupported", "gl_alu_off");
        check("skipped".equals(skipped.getString("status")) && skipped.getInt("completed_metrics") == 0 && !skipped.has("score"), "unsupported GPU has no score");
        check("failed".equals(GpuClient.parse("ERROR: unexpected error", "gl_alu_off").getString("status")), "unexpected errors remain failures");
        JSONObject raw = new JSONObject().put("results", new JSONArray().put(new JSONObject()
                .put("status", "FAILED").put("error_string", "OUT_OF_VMEMORY")));
        check("skipped".equals(GpuClient.parse(raw.toString(), "gl_alu_off").getString("status")), "known GPU memory shortage skips");
        raw.getJSONArray("results").getJSONObject(0).put("error_string", "SHADER_ERROR");
        check("failed".equals(GpuClient.parse(raw.toString(), "gl_alu_off").getString("status")), "shader failures stay visible");
        JSONObject sample = new JSONObject(NativeBridge.runCpu(1, 50));
        sample.put("native_build_id", BuildConfig.NATIVE_BUILD_ID).put("native_library", NativeLoader.load())
                .put("native_abi", device.getString("abi"));
        JSONObject report = new JSONObject().put("suite_version", BenchmarkSuite.CPU.version)
                .put("status", "completed").put("repetitions", 3).put("started_ms", 0);
        JSONArray samples = new JSONArray();
        for (int i = 0; i < 3; i++) samples.put(new JSONObject(sample.toString()));
        report.put("samples", samples);
        BenchmarkReport.summarize(report);
        check(report.has("median"), "same native build aggregates");
        samples.getJSONObject(2).put("native_abi", "other-abi");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "different ABIs never aggregate");
        samples.getJSONObject(2).put("native_abi", device.getString("abi"));
        samples.getJSONObject(2).put("native_build_id", "old-compiler");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "different native builds never aggregate");
        samples.getJSONObject(2).remove("native_build_id");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "legacy and new samples never aggregate");
        for (int i = 0; i < 3; i++) {
            samples.getJSONObject(i).remove("native_build_id");
            samples.getJSONObject(i).remove("native_library");
            samples.getJSONObject(i).remove("native_abi");
        }
        BenchmarkReport.summarize(report);
        check(report.has("median") && BenchmarkReport.format(report).contains("CPU"), "legacy records remain readable");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
