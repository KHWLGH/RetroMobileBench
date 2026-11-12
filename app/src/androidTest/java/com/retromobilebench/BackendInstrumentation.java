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
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(NativeLoader.load() != null, "native library loads");
            JSONObject measurement = testStorage(context);
            testCancellation(context);
            testSummary(measurement);
            testPreflight();
            testPersistence(context, measurement);
            result.putString("stream", "\nPASS: JNI I/O, validation, cancellation/cleanup, medians, "
                    + "preflight, SQLite persistence and interrupted-run recovery\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
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
    }

    private static void testPersistence(Context context, JSONObject sample) throws Exception {
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
