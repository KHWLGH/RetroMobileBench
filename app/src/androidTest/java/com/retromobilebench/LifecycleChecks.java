package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real runner cancellation/checkpoint and Activity background/Surface interruption. */
final class LifecycleChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 16) return LegacyOutputChecks.run(test, progress);
        Intent intent = new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Instrumentation.ActivityMonitor windows = test.addMonitor(MainActivity.class.getName(), null, false);
        Instrumentation.ActivityMonitor displays = test.addMonitor(DisplayActivity.class.getName(), null, false);
        test.startActivitySync(intent);
        BenchmarkRunner runner = BenchmarkRunner.get(test.getTargetContext());
        try {
            AtomicBoolean focused = new AtomicBoolean();
            long foregroundDeadline = SystemClock.elapsedRealtime() + 10000;
            do {
                test.runOnMainSync(() -> focused.set(activity(windows).hasWindowFocus()));
                if (focused.get()) break;
                check(SystemClock.elapsedRealtime() < foregroundDeadline, "unlock device and bring MainActivity to foreground before lifecycle checks");
                SystemClock.sleep(50);
            } while (true);
            test.waitForIdleSync();
            waitIdle(test, runner, 60000); progress.stage("cancel during a three-round stage after its first saved round");
            test.runOnMainSync(runner::startAll);
            long until = SystemClock.elapsedRealtime() + 180000;
            while (true) {
                BenchmarkRunner.State state = state(test, runner);
                check(state.busy, "session must still run until cancelled: " + state.message);
                check(state.exportJson.length() == 0, "busy state never serializes full session");
                if (state.savedRounds > 0) {
                    check(BenchmarkSuite.COREMARK.version.equals(state.suiteVersion) && state.stageIndex == 0
                            && state.repetitions == 3 && state.round >= state.savedRounds, "lightweight progress identifies stage and saved rounds");
                    ResultStore store = new ResultStore(test.getTargetContext());
                    try { check(store.latest(1).getJSONObject(0).getJSONObject("active_report").getJSONArray("samples").length() >= state.savedRounds, "round checkpoint persisted before progress notification"); }
                    finally { store.close(); }
                    break;
                }
                check(SystemClock.elapsedRealtime() < until, "first stage deadline"); SystemClock.sleep(25);
            }
            test.runOnMainSync(() -> { runner.cancel(); check(runner.currentState().busy && runner.currentState().message.contains("正在停止"), "immediate stopping feedback"); });
            waitIdle(test, runner, 45000);
            JSONObject cancelled = new JSONObject(state(test, runner).exportJson);
            check("cancelled".equals(cancelled.optString("status")), "whole session cancelled");
            JSONObject partial = cancelled.getJSONArray("results").getJSONObject(0);
            check(partial.getInt("repetitions") == 3 && !partial.has("median") && "completed".equals(partial.getJSONArray("samples").getJSONObject(0).optString("status")), "completed first round retained without scoring cancelled stage");
            check(cancelled.has("cancel_stop_latency_ms"), "actual stop latency recorded");
            progress.stage("background interrupts real GPU window");
            test.runOnMainSync(() -> runner.start(BenchmarkSuite.GPU_EGYPT_SCREEN, false));
            until = SystemClock.elapsedRealtime() + 20000;
            while (!DisplaySurface.requested || DisplaySurface.width() == 0) {
                check(state(test, runner).busy && SystemClock.elapsedRealtime() < until, "window acquired for interruption test"); SystemClock.sleep(50);
            }
            SystemClock.sleep(500);
            check(displays.getLastActivity() instanceof DisplayActivity, "dedicated full-screen GPU Activity launched");
            DisplayActivity background = (DisplayActivity)displays.getLastActivity();
            test.runOnMainSync(() -> check(background.hasWindowFocus() && background.moveTaskToBack(true), "real full-screen activity moved to background"));
            waitIdle(test, runner, 30000);
            JSONObject interrupted = new JSONObject(state(test, runner).exportJson);
            check("interrupted".equals(interrupted.optString("status")), "background is interruption, not normal completion");
            check(!interrupted.has("median"), "interrupted GPU does not produce a normal score");
            test.runOnMainSync(() -> check((background.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0, "background releases keep-screen-on"));
            return "cancelled three-round stage kept " + partial.getJSONArray("samples").length() + " raw rounds; stop latency=" + cancelled.getLong("cancel_stop_latency_ms") + " ms; GPU background interrupted; screen-on flag released";
        } finally {
            test.runOnMainSync(() -> { if (runner.isBusy()) runner.cancel(); });
            test.runOnMainSync(() -> test.getTargetContext().startActivity(intent));
            test.removeMonitor(windows); test.removeMonitor(displays);
        }
    }
    private static MainActivity activity(Instrumentation.ActivityMonitor monitor) {
        check(monitor.getLastActivity() instanceof MainActivity, "current MainActivity tracked across recreation");
        return (MainActivity)monitor.getLastActivity();
    }
    private static BenchmarkRunner.State state(Instrumentation test, BenchmarkRunner runner) {
        AtomicReference<BenchmarkRunner.State> value = new AtomicReference<>();
        test.runOnMainSync(() -> value.set(runner.currentState())); return value.get();
    }
    private static void waitIdle(Instrumentation test, BenchmarkRunner runner, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (state(test, runner).busy) { check(SystemClock.elapsedRealtime() < until, "runner stop/idle deadline"); SystemClock.sleep(50); }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
