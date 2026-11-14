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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real one-click journey with full production timing and the whole-session export. */
final class ProductionFlowChecks {
    interface Progress { void stage(String message); }
    static String run(Instrumentation instrumentation, Progress progress) throws Exception {
        Context context = instrumentation.getTargetContext();
        Instrumentation.ActivityMonitor windows = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        BenchmarkRunner runner = BenchmarkRunner.get(context); waitIdle(instrumentation, runner, 60000);
        try {
            AtomicBoolean focused = new AtomicBoolean(); long foregroundDeadline = SystemClock.elapsedRealtime() + 10000;
            do {
                instrumentation.runOnMainSync(() -> focused.set(activity(windows).hasWindowFocus()));
                if (focused.get()) break;
                check(SystemClock.elapsedRealtime() < foregroundDeadline, "unlock device and bring MainActivity to foreground"); SystemClock.sleep(50);
            } while (true);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> { click(activity(windows).getWindow().getDecorView(), "开始测试"); check(runner.isBusy(), "one click starts fixed suite"); });
            long deadline = SystemClock.elapsedRealtime() + 30 * 60 * 1000; String last = "";
            BenchmarkRunner.State state;
            while ((state = state(instrumentation, runner)).busy) {
                check(state.exportJson.length() == 0, "running progress has no full-session serialization");
                if (!last.equals(state.message)) { last = state.message; progress.stage(last); }
                check(SystemClock.elapsedRealtime() < deadline, "production session deadline"); SystemClock.sleep(500);
            }
            JSONObject session = new JSONObject(state.exportJson);
            check(BenchmarkSuite.SESSION_VERSION.equals(session.getString("suite_version")), "exports current session, not last twenty history rows");
            JSONArray results = session.getJSONArray("results"); check(results.length() == BenchmarkSuite.DEFAULT.length, "all fixed stages attempted");
            check(session.has("temperature"), "session telemetry saved"); HashSet<Integer> gpuPids = new HashSet<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject report = results.getJSONObject(i); BenchmarkSuite suite = BenchmarkSuite.DEFAULT[i];
                check(suite.version.equals(report.getString("suite_version")), "stable order and protocol identity");
                check(report.getInt("repetitions") == suite.repetitions() && report.has("temperature"), "automatic repetitions and stage telemetry");
                check(report.getInt("repeat_pause_ms") == (suite.repetitions() == 3 ? 1000 : 0), "fixed one-second round gaps");
                check(!"running".equals(report.getString("status")), "stage not left running");
                if ("skipped".equals(report.optString("status"))) { check(report.optString("message").length() > 0, "skip explained"); continue; }
                JSONArray samples = report.getJSONArray("samples");
                if ("completed".equals(report.optString("status"))) {
                    check(samples.length() == suite.repetitions() && report.has("median"), "all rounds completed and summarized: " + suite);
                    for (int r = 0; r < samples.length(); r++) check("completed".equals(samples.getJSONObject(r).getString("status")), "each raw round completed");
                } else check(!report.has("median"), "partial stage cannot have normal median");
                check(samples.length() > 0, "at least one attempted sample"); JSONObject sample = samples.getJSONObject(0);
                if (suite.isGpu() && "completed".equals(sample.optString("status"))) {
                    check(sample.getInt("diagnostic_duration_ms") == 0, "full GPU timing");
                    check(gpuPids.add(sample.getInt("worker_pid")), "disposable GPU worker");
                    if (suite.gpuTest().endsWith("_off")) check(sample.getInt("render_width") == 1920 && sample.getInt("render_height") == 1080, "fixed offscreen dimensions");
                    else check("window_submission".equals(sample.getString("presentation")), "real window protocol");
                    long seconds = suite == BenchmarkSuite.GPU_FILL ? 60000 : suite.gpuTest().contains("egypt") ? 113000 : 56000;
                    check(report.getLong("ended_ms") - report.getLong("started_ms") >= seconds - 2000, "full scene duration");
                }
                if (suite == BenchmarkSuite.VIDEO) {
                    JSONArray clips = sample.getJSONArray("results"); check(clips.length() == 7, "all seven clips attempted independently");
                    for (int c = 0; c < clips.length(); c++) for (String path : new String[]{"software", "system", "playback"}) check(clips.getJSONObject(c).has(path), "all decode/play paths recorded");
                }
                progress.stage(suite.name() + ": " + report.optString("status"));
            }
            ResultStore store = new ResultStore(context);
            try { JSONObject saved = store.latest(1).getJSONObject(0); check(saved.getString("session_id").equals(session.getString("session_id")), "whole session persisted"); }
            finally { store.close(); }
            return session.optString("status") + ": " + results.length() + " fixed stages; " + gpuPids.size() + " completed GPU workers; wall_ms=" + session.getLong("wall_ms");
        } finally {
            instrumentation.runOnMainSync(() -> { if (runner.isBusy()) runner.cancel(); });
            instrumentation.removeMonitor(windows);
        }
    }
    private static MainActivity activity(Instrumentation.ActivityMonitor windows) {
        check(windows.getLastActivity() instanceof MainActivity, "current Activity across recreation");
        return (MainActivity)windows.getLastActivity();
    }
    private static BenchmarkRunner.State state(Instrumentation test, BenchmarkRunner runner) {
        AtomicReference<BenchmarkRunner.State> value = new AtomicReference<>();
        test.runOnMainSync(() -> value.set(runner.currentState())); return value.get();
    }
    private static void waitIdle(Instrumentation instrumentation, BenchmarkRunner runner, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout; AtomicBoolean busy = new AtomicBoolean(true);
        do { instrumentation.runOnMainSync(() -> busy.set(runner.isBusy())); if (!busy.get()) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("runner idle timeout");
    }
    private static void click(View view, String label) { Button button = find(view, label); check(button != null && button.isEnabled() && button.performClick(), "click " + label); }
    private static Button find(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { Button b = find(((ViewGroup)view).getChildAt(i), label); if (b != null) return b; }
        return null;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
