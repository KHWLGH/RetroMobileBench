package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in foreground run through the real UI, with unchanged production parameters. */
final class ProductionFlowChecks {
    interface Progress { void stage(String message); }

    static String run(Instrumentation instrumentation, Progress progress) throws Exception {
        Context context = instrumentation.getTargetContext();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        BenchmarkRunner runner = BenchmarkRunner.get(context);
        waitIdle(instrumentation, runner, 60000);
        long previousId;
        ResultStore store = new ResultStore(context);
        try {
            JSONArray previous = store.latest(1);
            previousId = previous.length() == 0 ? 0 : previous.getJSONObject(0).getLong("id");
        } finally {
            store.close();
        }
        AtomicReference<BenchmarkRunner.State> observed = new AtomicReference<>();
        BenchmarkRunner.Listener observer = state -> {
            observed.set(state);
            activity.onState(state);
        };
        long started = SystemClock.elapsedRealtime();
        try {
            instrumentation.runOnMainSync(() -> {
                runner.attach(observer);
                click(activity.getWindow().getDecorView(), context.getString(R.string.run_all));
                check(runner.isBusy(), "all-tests button starts the production runner");
            });
            String lastMessage = "";
            while (true) {
                BenchmarkRunner.State state = observed.get();
                if (state != null && !state.message.equals(lastMessage)) {
                    progress.stage(state.message);
                    lastMessage = state.message;
                }
                if (state != null && !state.busy) break;
                check(SystemClock.elapsedRealtime() - started < 30 * 60 * 1000,
                        "production batch completes within 30 minutes");
                SystemClock.sleep(1000);
            }
            StringBuilder summary = new StringBuilder();
            store = new ResultStore(context);
            try {
                JSONArray history = store.latest(100);
                Set<BenchmarkSuite> seen = new HashSet<>();
                Set<Integer> gpuPids = new HashSet<>();
                int metrics = 0;
                for (int i = history.length() - 1; i >= 0; i--) {
                    JSONObject report = history.getJSONObject(i);
                    if (report.getLong("id") <= previousId) continue;
                    BenchmarkSuite suite = BenchmarkSuite.fromReport(report);
                    check(suite != null && seen.add(suite), "one new result per suite");
                    check("completed".equals(report.getString("status")),
                            suite + " completes: " + report);
                    check(report.getInt("repetitions") == 1, "all-tests runs one complete round");
                    JSONArray samples = report.getJSONArray("samples");
                    check(samples.length() == 1, "one saved sample");
                    JSONObject sample = samples.getJSONObject(0);
                    check("completed".equals(sample.getString("status")), "sample completes");
                    check(sample.getInt("completed_metrics") == suite.metrics.length, "all metrics saved");
                    check(BuildConfig.NATIVE_BUILD_ID.equals(sample.getString("native_build_id")),
                            "current native build identity");
                    check(sample.getString("native_abi").equals(report.getJSONObject("device").getString("abi")),
                            "sample and device ABI match");
                    JSONObject median = report.getJSONObject("median");
                    for (String metric : suite.metrics) {
                        double value = sample.getDouble(metric);
                        check(value > 0 && !Double.isNaN(value) && !Double.isInfinite(value), "valid " + metric);
                        check(value == median.getDouble(metric), "saved summary matches sample");
                    }
                    verifyParameters(suite, sample, report, gpuPids);
                    metrics += suite.metrics.length;
                    double seconds = (report.getLong("ended_ms") - report.getLong("started_ms")) / 1000.0;
                    String line = String.format(Locale.US, "%s: completed, %d metrics, %.1f s, id=%d",
                            suite.name(), suite.metrics.length, seconds, report.getLong("id"));
                    summary.append(line).append('\n');
                    progress.stage(line);
                }
                check(seen.size() == BenchmarkSuite.values().length, "all 14 production suites saved");
                check(gpuPids.size() == GpuClient.TESTS.length, "six distinct GPU worker processes");
                summary.append("Verified ").append(seen.size()).append(" suites / ").append(metrics)
                        .append(" metrics; elapsed ").append((SystemClock.elapsedRealtime() - started) / 1000)
                        .append(" s.\n");
            } finally {
                store.close();
            }
            // Exercise the history UI after closing the independent database connection.
            instrumentation.runOnMainSync(() -> click(activity.getWindow().getDecorView(),
                    context.getString(R.string.history)));
            waitIdle(instrumentation, runner, 30000);
            JSONArray exported = new JSONArray(observed.get().exportJson);
            check(exported.length() >= BenchmarkSuite.values().length, "history JSON contains the complete batch");
            return summary.toString();
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (runner.isBusy()) runner.cancel();
                runner.detach(observer);
                runner.attach(activity);
            });
        }
    }

    private static void verifyParameters(BenchmarkSuite suite, JSONObject sample, JSONObject report,
                                         Set<Integer> gpuPids) throws Exception {
        if (suite.isGpu()) {
            check(sample.getInt("diagnostic_duration_ms") == 0, "official GPU duration selected");
            check(sample.getInt("offscreen_width") == 1920 && sample.getInt("offscreen_height") == 1080,
                    "1080p GPU protocol");
            check("ETC1".equals(sample.getString("texture_type")), "ETC1 GPU data");
            check(sample.getString("data_sha256").length() == 64, "GPU data identity saved");
            check(gpuPids.add(sample.getInt("worker_pid")), "fresh GPU process");
            long expectedMs = suite == BenchmarkSuite.GPU_EGYPT ? 113000
                    : suite == BenchmarkSuite.GPU_TREX ? 56000 : 60000;
            check(report.getLong("ended_ms") - report.getLong("started_ms") >= expectedMs - 2000,
                    "full GPU wall time: " + suite);
        } else if (suite == BenchmarkSuite.COREMARK) {
            check(sample.getInt("diagnostic_iterations") == 0, "formal CoreMark mode");
            for (int i = 0; i < 4; i++)
                check(sample.getJSONArray("phase_seconds").getDouble(i) >= 10, "CoreMark phase >= 10 s");
            for (String key : new String[]{"single_output", "single_validation_output", "parallel_output", "parallel_validation_output"})
                check(sample.getString(key).contains("Correct operation validated."), "CoreMark seed validation");
        } else if (suite == BenchmarkSuite.STORAGE) {
            check(sample.getInt("random_operations") == 256, "production random I/O count");
            check(sample.getLong("file_bytes") == suite.parameter(report.getJSONObject("device")) * 1048576L,
                    "production storage size");
        } else if (suite == BenchmarkSuite.SQLITE) {
            check(sample.getInt("transactions") == 300, "production transaction count");
            for (int i = 0; i < 3; i++)
                check(sample.getJSONArray("completed_transactions").getInt(i) == 300, "all transactions committed");
        } else {
            check(sample.getInt("duration_ms") == 1500, "production CPU/memory duration");
            JSONArray seconds = sample.getJSONArray("elapsed_seconds");
            for (int i = 0; i < suite.metrics.length; i++)
                check(seconds.getDouble(i) >= 1.5, "full metric duration");
        }
    }

    private static void waitIdle(Instrumentation instrumentation, BenchmarkRunner runner, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        AtomicBoolean busy = new AtomicBoolean(true);
        do {
            instrumentation.runOnMainSync(() -> busy.set(runner.isBusy()));
            if (!busy.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("runner did not become idle");
    }

    private static void click(View view, String label) {
        Button button = findButton(view, label);
        check(button != null && button.isEnabled() && button.performClick(), "click enabled button: " + label);
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button button = findButton(group.getChildAt(i), label);
                if (button != null) return button;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
