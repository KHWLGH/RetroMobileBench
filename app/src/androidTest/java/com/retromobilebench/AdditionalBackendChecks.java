package com.retromobilebench;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Real codec/transaction checks; only API 16 framework dependencies. */
final class AdditionalBackendChecks {
    static void run(Context context) throws Exception {
        sqlite(context);
        memory();
        numeric();
    }

    private static void memory() throws Exception {
        NativeBridge.resetMemoryCancellation();
        for (int[] invalid : new int[][]{{0, 50}, {65, 50}, {1, 49}, {1, 10001}})
            check("failed".equals(new JSONObject(NativeBridge.runMemory(invalid[0], invalid[1])).getString("status")), "memory invalid arguments");
        for (int threads : new int[]{1, 2, 3, 4, 8}) {
            JSONObject sample = new JSONObject(NativeBridge.runMemory(threads, 75));
            check("completed".equals(sample.getString("status")), "memory validation " + sample);
            check(sample.getInt("completed_metrics") == (threads == 1 ? 4 : 5), "memory single core omits duplicate");
            for (int i = 0; i < 3; i++) {
                double seconds = sample.getJSONArray("elapsed_seconds").getDouble(i);
                long operations = sample.getJSONArray("operations").getLong(i);
                double rate = sample.getDouble(BenchmarkSuite.MEMORY.metrics[i]);
                double expected = i == 2 ? seconds * 1e9 / operations : operations * 8.388608 * (i == 0 ? 2 : 1) / seconds;
                check(seconds >= .075 && operations > 0 && Math.abs(rate - expected) / rate < .00001, "memory units");
            }
            check(sample.getInt("triad_total_bytes") == 25165824 && sample.getInt("triad_array_bytes") == 8388608, "fixed 24 MiB across thread counts");
            check(sample.getInt("triad_element_bytes") == 8 && sample.getInt("triad_bytes_per_element") == 24 && sample.getInt("triad_scalar") == 3, "Triad traffic and scalar");
            for (int i = 0; i < (threads == 1 ? 1 : 2); i++) {
                double seconds = sample.getJSONArray("triad_elapsed_seconds").getDouble(i);
                long elements = sample.getJSONArray("triad_elements").getLong(i);
                double rate = sample.getDouble(i == 0 ? "single_triad_mbps" : "parallel_triad_mbps");
                check(sample.getJSONArray("triad_created_workers").getInt(i) == (i == 0 ? 1 : threads), "all requested workers created");
                check(seconds >= .075 && elements > 0 && Math.abs(rate - elements * 24.0 / seconds / 1e6) / rate < .00001, "Triad MB/s from common wall time");
            }
            summary(BenchmarkSuite.MEMORY, sample);
        }
        NativeBridge.cancelMemory();
        check("cancelled".equals(new JSONObject(NativeBridge.runMemory(8, 75)).getString("status")), "memory precancel");
        NativeBridge.resetMemoryCancellation();
        AtomicReference<String> triadRaw = new AtomicReference<>();
        Thread triad = new Thread(() -> triadRaw.set(NativeBridge.runMemory(8, 1500)));
        triad.start(); SystemClock.sleep(6500); NativeBridge.cancelMemory(); triad.join(10000);
        check(!triad.isAlive() && "cancelled".equals(new JSONObject(triadRaw.get()).getString("status")), "memory cancellation joins workers");
        NativeBridge.resetMemoryCancellation();
        check("completed".equals(new JSONObject(NativeBridge.runMemory(8, 50)).getString("status")), "memory restart after cancellation");

        JSONObject device = new JSONObject().put("available_memory_bytes", 63L * 1024 * 1024);
        check(BenchmarkRunner.memoryPreflight(device) != null, "memory preflight reserve");
        device.put("available_memory_bytes", 64L * 1024 * 1024);
        check(BenchmarkRunner.memoryPreflight(device) == null, "memory preflight permits");
    }

    private static void numeric() throws Exception {
        NativeBridge.resetScimarkCancellation();
        for (int[] invalid : new int[][]{{0, 50}, {65, 50}, {1, 49}, {1, 10001}})
            check("failed".equals(new JSONObject(NativeBridge.runScimark(invalid[0], invalid[1])).getString("status")), "scimark arguments");
        double[] flops = {FFT_FLOPS, 99 * 99 * 6, 16384 * 4, 10000, 2000000.0 / 3};
        String[] singleKernels = {"fft_mflops", "sor_mflops", "monte_carlo_mflops", "sparse_mflops", "lu_mflops"};
        String[] parallelKernels = {"parallel_fft_mflops", "parallel_sor_mflops", "parallel_monte_carlo_mflops", "parallel_sparse_mflops", "parallel_lu_mflops"};
        for (int threads : new int[]{1, 4}) {
            JSONObject sample = new JSONObject(NativeBridge.runScimark(threads, 75));
            check("completed".equals(sample.getString("status")), "scimark result " + sample);
            check(sample.getInt("completed_metrics") == (threads == 1 ? 6 : 12), "scimark metrics count");
            double singleSum = 0;
            for (int i = 0; i < 5; i++) {
                double seconds = sample.getJSONArray("elapsed_seconds").getDouble(i);
                long iterations = sample.getJSONArray("iterations").getLong(i);
                double rate = sample.getDouble(singleKernels[i]);
                check(seconds >= 0.075 && iterations > 0 && rate > 0, "numeric duration");
                check(Math.abs(rate - flops[i] * iterations / seconds / 1e6) / rate < 0.00001, "numeric MFLOPS");
                singleSum += rate;
            }
            check(Math.abs(sample.getDouble("single_composite_mflops") - singleSum / 5.0) < 0.01, "single composite MFLOPS");
            if (threads > 1) {
                double parallelSum = 0;
                for (int i = 0; i < 5; i++) {
                    double seconds = sample.getJSONArray("parallel_elapsed_seconds").getDouble(i);
                    long iterations = sample.getJSONArray("parallel_iterations").getLong(i);
                    double rate = sample.getDouble(parallelKernels[i]);
                    check(seconds >= 0.075 && iterations >= threads && rate > 0, "parallel numeric duration");
                    check(Math.abs(rate - flops[i] * iterations / seconds / 1e6) / rate < 0.00001, "parallel numeric MFLOPS");
                    parallelSum += rate;
                }
                check(Math.abs(sample.getDouble("parallel_composite_mflops") - parallelSum / 5.0) < 0.01, "parallel composite MFLOPS");
            }
            summary(BenchmarkSuite.SCIMARK, sample);
        }
        NativeBridge.cancelScimark();
        check("cancelled".equals(new JSONObject(NativeBridge.runScimark(4, 50)).getString("status")), "scimark pre-cancel");
        NativeBridge.resetScimarkCancellation();
        AtomicReference<String> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(NativeBridge.runScimark(4, 10000)));
        worker.start(); SystemClock.sleep(50); NativeBridge.cancelScimark(); worker.join(5000);
        check(!worker.isAlive() && "cancelled".equals(new JSONObject(result.get()).getString("status")), "scimark active cancel");
        NativeBridge.resetScimarkCancellation();
        check("completed".equals(new JSONObject(NativeBridge.runScimark(1, 50)).getString("status")), "scimark restart");

        NativeBridge.resetCoremarkCancellation();
        check("failed".equals(new JSONObject(NativeBridge.runCoremark(0, 1)).getString("status")), "coremark arguments");
        JSONObject shortRun = new JSONObject(NativeBridge.runCoremark(4, 10));
        check("failed".equals(shortRun.getString("status")) && shortRun.getInt("completed_metrics") == 0,
                "short CoreMark run cannot score");
        check(shortRun.getString("single_output").contains("0xe9f5")
                && !shortRun.getString("single_output").contains("ERROR! list"), "CoreMark seed and kernel CRC");
        NativeBridge.cancelCoremark();
        check("cancelled".equals(new JSONObject(NativeBridge.runCoremark(4, 0)).getString("status")), "coremark pre-cancel");
        NativeBridge.resetCoremarkCancellation();
        worker = new Thread(() -> result.set(NativeBridge.runCoremark(4, 0)));
        worker.start(); SystemClock.sleep(50); NativeBridge.cancelCoremark(); worker.join(60000);
        check(!worker.isAlive() && "cancelled".equals(new JSONObject(result.get()).getString("status")), "coremark safe boundary cancel");
        NativeBridge.resetCoremarkCancellation();
        JSONObject sample = new JSONObject(NativeBridge.runCoremark(8, 0));
        check("completed".equals(sample.getString("status")), "formal CoreMark " + sample);
        for (int i = 0; i < 4; i++) check(sample.getJSONArray("phase_seconds").getDouble(i) >= 10, "CoreMark minimum time");
        for (String key : new String[]{"single_output", "single_validation_output", "parallel_output", "parallel_validation_output"})
            check(sample.getString(key).contains("Correct operation validated."), "CoreMark official validation " + key);
        summary(BenchmarkSuite.COREMARK, sample);
    }

    private static final double FFT_FLOPS = (5 * 1024 - 2) * 10 + 2 * (1024 + 1);

    private static void sqlite(Context context) throws Exception {
        File directory = new File(context.getCacheDir(), "sqlite-check-" + System.nanoTime());
        check(directory.mkdir(), "isolated sqlite test directory");
        try {
            NativeBridge.resetSqliteCancellation();
            check("failed".equals(new JSONObject(NativeBridge.runSqlite(directory.getPath(), 0)).getString("status")), "sqlite parameters");
            check("failed".equals(new JSONObject(NativeBridge.runSqlite(new File(directory, "missing").getPath(), 1)).getString("status")), "sqlite missing parent");
            // Simulate process-death leftovers; startup cleanup must preserve other files.
            File unrelated = new File(directory, "keep.txt");
            check(unrelated.createNewFile(), "unrelated file created");
            for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
                try (FileOutputStream out = new FileOutputStream(new File(directory, "rmb-sqlite-bench.db" + suffix))) {
                    out.write(new byte[]{1, 2, 3});
                }
            }
            BenchmarkRunner.cleanupSqliteFiles(directory);
            check(unrelated.exists(), "cleanup preserves unrelated files");
            JSONObject sample = new JSONObject(NativeBridge.runSqlite(directory.getPath(), 300));
            check("completed".equals(sample.getString("status")), "sqlite completes: " + sample);
            check("3.53.1".equals(sample.getString("sqlite_version")), "bundled SQLite version");
            check(sample.getInt("completed_metrics") == 4 && sample.getInt("cleanup_errno") == 0, "sqlite validation/cleanup");
            double tpsSum = 0;
            for (int i = 0; i < 3; i++) {
                check(sample.getJSONArray("completed_transactions").getInt(i) == 300, "all commits counted");
                double seconds = sample.getJSONArray("elapsed_seconds").getDouble(i);
                double tps = sample.getDouble(BenchmarkSuite.SQLITE.metrics[i + 1]);
                check(seconds > 0 && tps > 0 && Math.abs(tps * seconds - 300) < 0.01, "SQLite TPS");
                tpsSum += tps;
            }
            check(Math.abs(sample.getDouble("composite_tps") - tpsSum / 3.0) < 0.01, "SQLite composite TPS");
            summary(BenchmarkSuite.SQLITE, sample);
            NativeBridge.cancelSqlite();
            check("cancelled".equals(new JSONObject(NativeBridge.runSqlite(directory.getPath(), 300)).getString("status")), "sqlite pre-cancel");
            NativeBridge.resetSqliteCancellation();
            AtomicReference<String> raw = new AtomicReference<>();
            Thread thread = new Thread(() -> raw.set(NativeBridge.runSqlite(directory.getPath(), 3000)));
            thread.start();
            SystemClock.sleep(20);
            NativeBridge.cancelSqlite();
            thread.join(20000);
            check(!thread.isAlive(), "sqlite current transaction finishes on cancel");
            check("cancelled".equals(new JSONObject(raw.get()).getString("status")), "sqlite active cancellation");
            NativeBridge.resetSqliteCancellation();
            check("completed".equals(new JSONObject(NativeBridge.runSqlite(directory.getPath(), 3)).getString("status")), "sqlite restart");
            for (File file : directory.listFiles()) check(file.equals(unrelated), "no database/journal leaked: " + file);
            JSONObject device = new JSONObject().put("available_storage_bytes", 19L * 1024 * 1024);
            check(BenchmarkRunner.sqlitePreflight(device) != null, "sqlite space reserve");
            device.put("available_storage_bytes", 20L * 1024 * 1024);
            check(BenchmarkRunner.sqlitePreflight(device) == null, "sqlite sufficient space");
        } finally {
            BenchmarkRunner.cleanupSqliteFiles(directory);
            new File(directory, "keep.txt").delete();
            directory.delete();
        }
    }

    private static void summary(BenchmarkSuite suite, JSONObject sample) throws Exception {
        JSONObject report = new JSONObject().put("started_ms", System.currentTimeMillis())
                .put("suite_version", sample.getString("test_version")).put("status", "completed").put("repetitions", 3);
        JSONArray samples = new JSONArray();
        report.put("samples", samples);
        for (double value : new double[]{2, 20, 7}) {
            JSONObject copy = new JSONObject(sample.toString());
            for (String key : BenchmarkReport.metricKeys(suite, sample)) copy.put(key, value);
            samples.put(copy);
        }
        BenchmarkReport.summarize(report);
        for (String key : BenchmarkReport.metricKeys(suite, sample)) check(report.getJSONObject("median").getDouble(key) == 7, "median " + key);
        check(BenchmarkReport.format(report).contains(suite.label()), "suite formatting");
        JSONObject last = samples.getJSONObject(2);
        for (String key : BenchmarkReport.parameterKeys(suite)) {
            Object original = last.get(key);
            last.put(key, "changed");
            BenchmarkReport.summarize(report);
            check(!report.has("median"), "reject mixed parameter " + key);
            last.put(key, original);
        }
        last.put("status", "cancelled");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "reject partial suite");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
