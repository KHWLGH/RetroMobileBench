package com.retromobilebench;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real JNI + SQLite regression checks using only the API 16 framework. */
public final class BackendInstrumentation extends Instrumentation {
    private boolean gpuOnly, fullGpu, production;
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        gpuOnly = arguments != null && "true".equals(arguments.getString("gpuOnly"));
        fullGpu = arguments != null && "true".equals(arguments.getString("fullGpu"));
        production = arguments != null && "true".equals(arguments.getString("production"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (production) {
                String summary = ProductionFlowChecks.run(this, this::progress);
                result.putString("stream", "\nPASS: production UI all-tests, official GPU durations, full CPU parameters and saved history\n" + summary);
                finish(Activity.RESULT_OK, result);
                return;
            }
            check(NativeLoader.load() != null, "native library loads");
            if (gpuOnly) {
                GpuBackendChecks.run(context, fullGpu, this::progress);
                result.putString("stream", "\nPASS: GPU isolated processes, data validation, cancellation, timeout and restart\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            progress("API 8 compatibility and native build identity");
            LegacyCompatibilityChecks.run(context);
            progress("storage");
            JSONObject measurement = testStorage(context);
            testCancellation(context);
            testSummary(measurement);
            progress("zlib");
            JSONObject cpu = testCpu();
            testCpuCancellation();
            testCpuSummary(cpu);
            testPreflight();
            testPersistence(context, measurement, cpu);
            progress("CoreMark/SciMark/memory/zstd/SQLite");
            AdditionalBackendChecks.run(context);
            progress("GPU" + (fullGpu ? " full six tests" : " available tests"));
            GpuBackendChecks.run(context, fullGpu, this::progress);
            result.putString("stream", "\nPASS: CoreMark formal double-seed validation, SciMark, memory/STREAM, zstd/SQLite + storage/CPU JNI, 1/2/4 threads, cancellation/cleanup, "
                    + "medians, preflight, mixed history and interrupted-run recovery, GPU isolated processes/data validation/restart\nCPU fixture: " + cpu + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void progress(String stage) {
        Bundle progress = new Bundle();
        progress.putString("stream", "\nRUNNING: " + stage + "\n");
        sendStatus(0, progress);
    }

    private static JSONObject testStorage(Context context) throws Exception {
        File directory = context.getCacheDir();
        NativeBridge.resetStorageCancellation();
        JSONObject invalid = new JSONObject(NativeBridge.runStorage(directory.getAbsolutePath(), 0, 32));
        check("failed".equals(invalid.getString("status")), "invalid parameters rejected");
        JSONObject missing = new JSONObject(NativeBridge.runStorage(
                new File(directory, "nonexistent-parent/missing").getAbsolutePath(), 4, 32));
        check("failed".equals(missing.getString("status")), "missing directory rejected");
        JSONObject sample = new JSONObject(NativeBridge.runStorage(directory.getAbsolutePath(), 4, 32));
        check("completed".equals(sample.getString("status")), "storage completes: " + sample);
        check(sample.getInt("completed_metrics") == 4, "four measurements");
        for (String metric : BenchmarkReport.METRICS)
            check(sample.getDouble(metric) > 0, "positive measurement " + metric);
        check(sample.getLong("file_bytes") == 4 * 1024 * 1024, "size recorded");
        check("O_DIRECT".equals(sample.getString("read_mode"))
                || "cached".equals(sample.getString("read_mode")), "cache mode recorded");
        checkNoTemporaryFiles(directory);
        return sample;
    }

    private static void testCancellation(Context context) throws Exception {
        File directory = context.getCacheDir();
        NativeBridge.resetStorageCancellation();
        NativeBridge.cancelStorage();
        JSONObject early = new JSONObject(NativeBridge.runStorage(directory.getAbsolutePath(), 4, 32));
        check("cancelled".equals(early.getString("status")), "cancellation before entry");
        NativeBridge.resetStorageCancellation();
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Thread io = new Thread(() -> {
            entered.countDown();
            raw.set(NativeBridge.runStorage(directory.getAbsolutePath(), 32, 4096));
        }, "storage-cancel-test");
        io.start();
        check(entered.await(2, TimeUnit.SECONDS), "worker entered");
        SystemClock.sleep(50);
        NativeBridge.cancelStorage();
        io.join(20000);
        check(!io.isAlive(), "native cancellation returns promptly");
        JSONObject cancelled = new JSONObject(raw.get());
        check("cancelled".equals(cancelled.getString("status")), "in-flight cancellation: " + cancelled);
        checkNoTemporaryFiles(directory);
        NativeBridge.resetStorageCancellation();
    }

    private static void checkNoTemporaryFiles(File directory) {
        String[] names = directory.list();
        check(names != null, "cache directory readable");
        for (String name : names) check(!name.startsWith("rmb-io-"), "no leaked file: " + name);
    }

    private static void testSummary(JSONObject sample) throws Exception {
        JSONObject report = report("completed");
        report.put("repetitions", 3);
        JSONArray samples = report.getJSONArray("samples");
        for (double value : new double[]{100, 1, 9}) {
            JSONObject copy = new JSONObject(sample.toString());
            for (String metric : BenchmarkReport.METRICS) copy.put(metric, value);
            samples.put(copy);
        }
        BenchmarkReport.summarize(report);
        check(report.getJSONObject("median").getDouble(BenchmarkReport.METRICS[0]) == 9,
                "outlier-resistant median");
        JSONObject changed = samples.getJSONObject(2);
        String mode = changed.getString("read_mode");
        changed.put("read_mode", "cached".equals(mode) ? "O_DIRECT" : "cached");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "mixed cache modes do not aggregate");
        changed.put("read_mode", mode);
        changed.put("random_operations", sample.getInt("random_operations") + 1);
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "different parameters do not aggregate");
        changed.put("random_operations", sample.getInt("random_operations"));
        changed.put("status", "cancelled");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "partial samples do not count as zero/success");
    }

    private static JSONObject testCpu() throws Exception {
        NativeBridge.resetCpuCancellation();
        for (int[] parameters : new int[][]{{0, 50}, {5, 50}, {1, 49}, {1, 10001}}) {
            JSONObject invalid = new JSONObject(NativeBridge.runCpu(parameters[0], parameters[1]));
            check("failed".equals(invalid.getString("status")), "CPU invalid parameters rejected");
            check(invalid.getInt("completed_metrics") == 0, "invalid CPU has no metrics");
        }
        JSONObject reference = null;
        for (int threads : new int[]{1, 2, 4}) {
            JSONObject sample = new JSONObject(NativeBridge.runCpu(threads, 75));
            check("completed".equals(sample.getString("status")), "CPU completes: " + sample);
            check(sample.getInt("completed_metrics") == 4, "four CPU metrics");
            check("1.3.1".equals(sample.getString("codec_version")), "bundled zlib version");
            check(sample.getInt("parallel_threads") == threads, "CPU thread count recorded");
            check(sample.getInt("input_bytes") == 1024 * 1024, "fixed corpus size");
            check(sample.getInt("compressed_bytes") > 0
                    && sample.getInt("compressed_bytes") < sample.getInt("input_bytes"), "compressed length");
            for (int i = 0; i < BenchmarkSuite.CPU.metrics.length; i++) {
                double duration = sample.getJSONArray("elapsed_seconds").getDouble(i);
                long iterations = sample.getJSONArray("iterations").getLong(i);
                double rate = sample.getDouble(BenchmarkSuite.CPU.metrics[i]);
                check(duration >= 0.075 && iterations >= (i < 2 ? 1 : threads), "CPU timing/iterations");
                double calculated = iterations * 1048576.0 / duration / 1000000.0;
                check(Math.abs(rate - calculated) / calculated < 0.00001, "wall time throughput units");
            }
            if (reference != null) {
                check(reference.getLong("input_crc32") == sample.getLong("input_crc32"), "deterministic corpus");
                check(reference.getLong("compressed_bytes") == sample.getLong("compressed_bytes"), "stable compressed data");
            }
            reference = sample;
        }
        return reference;
    }

    private static void testCpuCancellation() throws Exception {
        NativeBridge.resetCpuCancellation();
        NativeBridge.cancelCpu();
        JSONObject early = new JSONObject(NativeBridge.runCpu(2, 75));
        check("cancelled".equals(early.getString("status")), "CPU cancellation before entry");
        check(early.getInt("completed_metrics") == 0, "pre-cancel CPU has no metrics");
        NativeBridge.resetCpuCancellation();
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Thread cpu = new Thread(() -> {
            entered.countDown();
            raw.set(NativeBridge.runCpu(4, 10000));
        }, "cpu-cancel-test");
        cpu.start();
        check(entered.await(2, TimeUnit.SECONDS), "CPU worker entered");
        SystemClock.sleep(100);
        NativeBridge.cancelCpu();
        cpu.join(10000);
        check(!cpu.isAlive(), "CPU cancellation joins all native workers");
        JSONObject cancelled = new JSONObject(raw.get());
        check("cancelled".equals(cancelled.getString("status")), "CPU in-flight cancellation: " + cancelled);
        check(cancelled.getInt("completed_metrics") < 4, "cancelled run is incomplete");
        NativeBridge.resetCpuCancellation();
        check("completed".equals(new JSONObject(NativeBridge.runCpu(1, 50)).getString("status")),
                "CPU can restart after cancellation");
    }

    private static void testCpuSummary(JSONObject sample) throws Exception {
        JSONObject report = report("completed").put("suite_version", "cpu-zlib-v1").put("repetitions", 3);
        JSONArray samples = report.getJSONArray("samples");
        for (double value : new double[]{100, 1, 9}) {
            JSONObject copy = new JSONObject(sample.toString());
            for (String key : BenchmarkSuite.CPU.metrics) copy.put(key, value);
            samples.put(copy);
        }
        BenchmarkReport.summarize(report);
        for (String key : BenchmarkSuite.CPU.metrics)
            check(report.getJSONObject("median").getDouble(key) == 9, "CPU median " + key);
        check(BenchmarkReport.format(report).contains("CPU 压缩测试"), "CPU history label");
        JSONObject changed = samples.getJSONObject(2);
        for (String key : BenchmarkSuite.CPU.parameters) {
            Object original = changed.get(key);
            changed.put(key, "changed");
            BenchmarkReport.summarize(report);
            check(!report.has("median"), "mismatched CPU parameter rejected: " + key);
            changed.put(key, original);
        }
        changed.put("test_version", "cpu-zlib-v2");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "unknown CPU version rejected");
        changed.put("test_version", "cpu-zlib-v1").put("status", "cancelled");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "cancelled CPU round not aggregated");
        report.put("suite_version", "future-suite");
        check(BenchmarkReport.format(report).contains("未知测试版本"), "unknown history stays readable");
    }

    private static void testPreflight() throws Exception {
        JSONObject device = new JSONObject().put("available_storage_bytes", 64L * 1024 * 1024)
                .put("battery_percent", 50).put("plugged", false);
        check(BenchmarkRunner.preflight(device, 32) == null, "normal preflight");
        device.put("battery_percent", 20);
        check(BenchmarkRunner.preflight(device, 32) != null, "low battery blocked");
        device.put("plugged", true);
        check(BenchmarkRunner.preflight(device, 32) == null, "charging accepted");
        device.put("battery_temperature_tenths_c", 450);
        check(BenchmarkRunner.preflight(device, 32) != null, "hot battery blocked");
        device.put("battery_temperature_tenths_c", 250).put("available_storage_bytes", 19L * 1024 * 1024);
        check(BenchmarkRunner.preflight(device, 4) != null, "storage reserve enforced");
        check(BenchmarkRunner.cpuPreflight(device) == null, "CPU does not require storage test space");
        device.put("low_memory", true);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU low-memory blocked");
        device.put("low_memory", false).put("available_memory_bytes", 31L * 1024 * 1024);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU memory reserve enforced");
        device.put("available_memory_bytes", 32L * 1024 * 1024).put("available_processors", 32);
        check(BenchmarkRunner.cpuPreflight(device) == null, "CPU memory threshold accepted");
        check(BenchmarkSuite.CPU.parameter(device) == 4, "CPU thread cap");
        device.put("available_processors", 0);
        check(BenchmarkSuite.CPU.parameter(device) == 1, "CPU thread minimum");
        device.put("battery_temperature_tenths_c", 450);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU hot battery blocked");
        device.put("battery_temperature_tenths_c", 250).put("plugged", false);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU low battery blocked");
    }

    private static void testPersistence(Context context, JSONObject sample, JSONObject cpu) throws Exception {
        String name = "backend-test-" + System.nanoTime() + ".db";
        ResultStore store = new ResultStore(context, name);
        try {
            JSONObject interrupted = report("running");
            interrupted.getJSONArray("samples").put(sample);
            long id = store.begin(interrupted);
            store.close();
            store = new ResultStore(context, name);
            store.recoverInterrupted();
            JSONObject recovered = store.latest(1).getJSONObject(0);
            check("interrupted".equals(recovered.getString("status")), "stale running recovered");
            check(recovered.getLong("id") == id, "run id persisted");
            check(recovered.getJSONArray("samples").length() == 1, "partial round retained");
            JSONObject complete = report("running");
            long completeId = store.begin(complete);
            complete.put("status", "completed");
            complete.getJSONArray("samples").put(sample);
            store.save(completeId, complete);
            store.recoverInterrupted();
            JSONArray history = store.latest(20);
            check(history.length() == 2, "both records retained");
            check("completed".equals(history.getJSONObject(0).getString("status")), "completed untouched");
            JSONObject cpuReport = report("running").put("suite_version", "cpu-zlib-v1");
            long cpuId = store.begin(cpuReport);
            cpuReport.put("status", "completed");
            cpuReport.getJSONArray("samples").put(cpu);
            BenchmarkReport.summarize(cpuReport);
            store.save(cpuId, cpuReport);
            store.close();
            store = new ResultStore(context, name);
            history = store.latest(20);
            check(history.length() == 3, "mixed suite history survives reopen");
            check(BenchmarkReport.format(history.getJSONObject(0)).contains("CPU 压缩测试"), "CPU history formatting");
            check(BenchmarkReport.format(history.getJSONObject(1)).contains("存储测试"), "legacy storage history formatting");
        } finally {
            store.close();
            context.deleteDatabase(name); // Only the uniquely named test database.
        }
    }

    private static JSONObject report(String status) throws Exception {
        return new JSONObject().put("started_ms", System.currentTimeMillis()).put("status", status)
                .put("repetitions", 1).put("samples", new JSONArray());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
