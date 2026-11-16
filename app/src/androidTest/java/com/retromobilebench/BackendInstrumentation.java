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
    private boolean gpuOnly, fullGpu, production, fixedOnly, lifecycleOnly, interferenceOnly, mediaOnly, mediaTimestamps;
    private String gpuTest, gpuDuration;
    private boolean webOnly, webFaults, displayOnly, gpuSurfaceOnly, gpuCaptureOnly;
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        gpuOnly = arguments != null && "true".equals(arguments.getString("gpuOnly"));
        gpuTest = arguments == null ? null : arguments.getString("gpuTest");
        gpuDuration = arguments == null ? null : arguments.getString("gpuDurationMs");
        fullGpu = arguments != null && "true".equals(arguments.getString("fullGpu"));
        lifecycleOnly = arguments != null && "true".equals(arguments.getString("lifecycleOnly"));
        fixedOnly = arguments != null && "true".equals(arguments.getString("fixedOnly"));
        production = arguments != null && "true".equals(arguments.getString("production"));
        interferenceOnly = arguments != null && "true".equals(arguments.getString("interferenceOnly"));
        mediaOnly = arguments != null && "true".equals(arguments.getString("mediaOnly"));
        mediaTimestamps = arguments != null && "true".equals(arguments.getString("mediaTimestamps"));
        webOnly = arguments != null && "true".equals(arguments.getString("webOnly"));
        webFaults = arguments != null && "true".equals(arguments.getString("webFaults"));
        displayOnly = arguments != null && "true".equals(arguments.getString("displayOnly"));
        gpuSurfaceOnly = arguments != null && "true".equals(arguments.getString("gpuSurfaceOnly"));
        gpuCaptureOnly = arguments != null && "true".equals(arguments.getString("gpuCaptureOnly"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (gpuSurfaceOnly) {
                result.putString("stream", "\nPASS: diagnostic pixels only, not a benchmark: " + GpuSurfaceChecks.run(this, this::progress) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (displayOnly) {
                result.putString("stream", "\nPASS: " + FullscreenChecks.run(this, this::progress) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (webOnly) {
                result.putString("stream", "\nPASS: " + WebRegressionChecks.run(this, this::progress, webFaults) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (mediaTimestamps) {
                result.putString("stream", "\nDIAGNOSTIC: extractor sample timestamps\n" + MediaTimestampChecks.run(this) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (gpuCaptureOnly) {
                result.putString("stream", "\nPASS: diagnostic captures only, not a benchmark: " + GpuSceneCaptureChecks.run(this, gpuTest, this::progress) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (gpuTest != null) {
                check(GpuClient.isTest(gpuTest), "known diagnostic GPU test");
                startActivitySync(new android.content.Intent(context, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                GpuClient.resetCancellation();
                int diagnosticMs = gpuDuration == null ? 5000 : Integer.parseInt(gpuDuration);
                check(diagnosticMs >= 0 && diagnosticMs <= 10000, "GPU diagnostic milliseconds: 0 means official duration");
                File pack = GpuDataPack.ensure(context, gpuTest, () -> false);
                JSONObject value = GpuClient.invoke(context, pack, gpuTest, diagnosticMs, diagnosticMs == 0 ? 240000 : 30000);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getFilesDir(), "gpu-diagnostic.json"))) { out.write(value.toString(2).getBytes("UTF-8")); }
                check("completed".equals(value.optString("status")), value.toString());
                result.putString("stream", "\nPASS: " + (diagnosticMs == 0 ? "official configured GPU duration" : diagnosticMs + " ms diagnostic only") + ": " + value + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (production) {
                String summary = ProductionFlowChecks.run(this, this::progress);
                result.putString("stream", "\nPASS: production UI all-tests, official GPU durations, full CPU parameters and saved history\n" + summary);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (mediaOnly) {
                result.putString("stream", "\nPASS: " + MediaRegressionChecks.run(this, this::progress) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (interferenceOnly || !lifecycleOnly && !fixedOnly && !gpuOnly) {
                InterferenceChecks.run();
                if (interferenceOnly) {
                    result.putString("stream", "\nPASS: event waits/cancellation races, stale callbacks, one background temperature source, stage windows and cleanup\n");
                    finish(Activity.RESULT_OK, result); return;
                }
            }
            check(NativeLoader.load() != null, "native library loads");
            if (lifecycleOnly) {
                result.putString("stream", "\nPASS: " + LifecycleChecks.run(this, this::progress) + "\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (fixedOnly) {
                startActivitySync(new android.content.Intent(context, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                FixedSuiteChecks.run(context);
                PersistenceFailureChecks.run(this);
                JSONObject video = new JSONObject(MediaCorpus.video(context, 2));
                check(video.getJSONArray("results").length() == 7, "all fixed video profiles attempted: " + video);
                for (int i = 0; i < 7; i++) check("completed".equals(video.getJSONArray("results").getJSONObject(i).getJSONObject("software").optString("status")), "software produces validated frames: " + video.getJSONArray("results").getJSONObject(i));
                StringBuilder summary = new StringBuilder("\nPASS: fixed 12-stage suite topology, session recovery and seven video software/system/playback paths\n");
                for (int i = 0; i < 7; i++) {
                    JSONObject clip = video.getJSONArray("results").getJSONObject(i); summary.append(clip.getString("id"));
                    for (String path : new String[]{"software", "system", "playback"}) {
                        JSONObject value = clip.getJSONObject(path);
                        check(!"interrupted".equals(value.optString("status")), "foreground media Surface must reach decoder: " + clip);
                        if (android.os.Build.VERSION.SDK_INT < 16 && "playback".equals(path))
                            check(value.optBoolean("surface_created_in_worker"), "old playback owns a real window in its process: " + value);
                        summary.append(" | ").append(path).append(": ").append(value.optString("status")).append(" ").append(value.optString("error_stage"));
                    }
                    summary.append('\n');
                }
                result.putString("stream", summary.toString());
                finish(Activity.RESULT_OK, result); return;
            }
            if (gpuOnly) {
                startActivitySync(new android.content.Intent(context, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
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
            progress("SciMark");
            JSONObject cpu = testScimark();
            testScimarkCancellation();
            testScimarkSummary(cpu);
            testPreflight();
            testPersistence(context, measurement, cpu);
            progress("CoreMark/SciMark/memory/SQLite");
            AdditionalBackendChecks.run(context);
            progress("new fixed suite regression checks");
            FixedSuiteChecks.run(context);
            PersistenceFailureChecks.run(this);
            startActivitySync(new android.content.Intent(context, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            progress("GPU scene processes");
            GpuBackendChecks.run(context, fullGpu, this::progress);
            result.putString("stream", "\nPASS: CoreMark formal double-seed validation, SciMark single+parallel, memory/Triad, SQLite + storage JNI, 1/2/4/8 workers, cancellation/cleanup, "
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

    private static JSONObject testScimark() throws Exception {
        NativeBridge.resetScimarkCancellation();
        for (int[] parameters : new int[][]{{0, 50}, {65, 50}, {1, 49}, {1, 10001}}) {
            JSONObject invalid = new JSONObject(NativeBridge.runScimark(parameters[0], parameters[1]));
            check("failed".equals(invalid.getString("status")), "SciMark invalid parameters rejected");
            check(invalid.getInt("completed_metrics") == 0, "invalid SciMark has no metrics");
        }
        JSONObject reference = null;
        for (int threads : new int[]{1, 2, 4, 8}) {
            JSONObject sample = new JSONObject(NativeBridge.runScimark(threads, 75));
            check("completed".equals(sample.getString("status")), "SciMark completes: " + sample);
            check(sample.getInt("completed_metrics") == (threads == 1 ? 6 : 12), "single core omits duplicate parallel phase");
            check(sample.getInt("parallel_threads") == threads, "SciMark thread count recorded");
            check(sample.getDouble("single_composite_mflops") > 0, "single composite MFLOPS positive");
            if (threads > 1) check(sample.getDouble("parallel_composite_mflops") > 0, "parallel composite MFLOPS positive");
            reference = sample;
        }
        return reference;
    }

    private static void testScimarkCancellation() throws Exception {
        NativeBridge.resetScimarkCancellation();
        NativeBridge.cancelScimark();
        JSONObject early = new JSONObject(NativeBridge.runScimark(2, 75));
        check("cancelled".equals(early.getString("status")), "SciMark cancellation before entry");
        check(early.getInt("completed_metrics") == 0, "pre-cancel SciMark has no metrics");
        NativeBridge.resetScimarkCancellation();
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Thread cpu = new Thread(() -> {
            entered.countDown();
            raw.set(NativeBridge.runScimark(4, 10000));
        }, "scimark-cancel-test");
        cpu.start();
        check(entered.await(2, TimeUnit.SECONDS), "SciMark worker entered");
        SystemClock.sleep(100);
        NativeBridge.cancelScimark();
        cpu.join(10000);
        check(!cpu.isAlive(), "SciMark cancellation joins all native workers");
        JSONObject cancelled = new JSONObject(raw.get());
        check("cancelled".equals(cancelled.getString("status")), "SciMark in-flight cancellation: " + cancelled);
        check(cancelled.getInt("completed_metrics") < 12, "cancelled run is incomplete");
        NativeBridge.resetScimarkCancellation();
        check("completed".equals(new JSONObject(NativeBridge.runScimark(1, 50)).getString("status")),
                "SciMark can restart after cancellation");
    }

    private static void testScimarkSummary(JSONObject sample) throws Exception {
        JSONObject report = report("completed").put("suite_version", BenchmarkSuite.SCIMARK.version).put("repetitions", 3);
        JSONArray samples = report.getJSONArray("samples");
        for (double value : new double[]{100, 1, 9}) {
            JSONObject copy = new JSONObject(sample.toString());
            for (String key : BenchmarkSuite.SCIMARK.metrics) copy.put(key, value);
            samples.put(copy);
        }
        BenchmarkReport.summarize(report);
        for (String key : BenchmarkSuite.SCIMARK.metrics)
            check(report.getJSONObject("median").getDouble(key) == 9, "SciMark median " + key);
        check(BenchmarkReport.format(report).contains("CPU 浮点测试"), "SciMark history label");
        JSONObject changed = samples.getJSONObject(2);
        for (String key : BenchmarkSuite.SCIMARK.parameters) {
            Object original = changed.get(key);
            changed.put(key, "changed");
            BenchmarkReport.summarize(report);
            check(!report.has("median"), "mismatched SciMark parameter rejected: " + key);
            changed.put(key, original);
        }
        changed.put("test_version", "cpu-scimark-future");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "unknown SciMark version rejected");
        changed.put("test_version", BenchmarkSuite.SCIMARK.version).put("status", "cancelled");
        BenchmarkReport.summarize(report);
        check(!report.has("median"), "cancelled SciMark round not aggregated");
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
        check(BenchmarkRunner.preflight(device, 32) == null, "hot battery is telemetry only");
        device.put("battery_temperature_tenths_c", 250).put("available_storage_bytes", 19L * 1024 * 1024);
        check(BenchmarkRunner.preflight(device, 4) != null, "storage reserve enforced");
        check(BenchmarkRunner.cpuPreflight(device) == null, "CPU does not require storage test space");
        device.put("low_memory", true);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU low-memory blocked");
        device.put("low_memory", false).put("available_memory_bytes", 31L * 1024 * 1024);
        check(BenchmarkRunner.cpuPreflight(device) != null, "CPU memory reserve enforced");
        device.put("available_memory_bytes", 32L * 1024 * 1024).put("available_processors", 32);
        check(BenchmarkRunner.cpuPreflight(device) == null, "CPU memory threshold accepted");
        check(BenchmarkSuite.COREMARK.parameter(device) == 32, "CPU is not capped to four or eight");
        device.put("cpu_present", "0-7").put("available_processors", 1).put("cpu_online", "0");
        check(BenchmarkSuite.COREMARK.parameter(device) == 8, "hotplugged cores are included");
        device.remove("cpu_present"); device.remove("cpu_online");
        device.put("available_processors", 0);
        check(BenchmarkSuite.COREMARK.parameter(device) == 1, "CPU thread minimum");
        device.put("battery_temperature_tenths_c", 450);
        check(BenchmarkRunner.cpuPreflight(device) == null, "CPU hot battery does not block");
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
            JSONObject cpuReport = report("running").put("suite_version", BenchmarkSuite.SCIMARK.version);
            long cpuId = store.begin(cpuReport);
            cpuReport.put("status", "completed");
            cpuReport.getJSONArray("samples").put(cpu);
            BenchmarkReport.summarize(cpuReport);
            store.save(cpuId, cpuReport);
            store.close();
            store = new ResultStore(context, name);
            history = store.latest(20);
            check(history.length() == 3, "mixed suite history survives reopen");
            check(BenchmarkReport.format(history.getJSONObject(0)).contains("CPU 浮点测试"), "SciMark history formatting");
            check(BenchmarkReport.format(history.getJSONObject(1)).contains("存储读写测试"), "storage history formatting");
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
