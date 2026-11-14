package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

/** Real runner cancellation/checkpoint and Activity background/Surface interruption. */
final class LifecycleChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 16) return LegacyOutputChecks.run(test, progress);
        Intent intent = new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity)test.startActivitySync(intent);
        BenchmarkRunner runner = BenchmarkRunner.get(test.getTargetContext());
        AtomicReference<BenchmarkRunner.State> observed = new AtomicReference<>();
        BenchmarkRunner.Listener observer = state -> { observed.set(state); activity.onState(state); };
        test.runOnMainSync(() -> runner.attach(observer));
        try {
            waitIdle(observed, 60000); progress.stage("cancel during a three-round stage after its first saved round");
            test.runOnMainSync(runner::startAll);
            long until = SystemClock.elapsedRealtime() + 180000;
            while (true) {
                BenchmarkRunner.State state = observed.get();
                check(state.busy, "session must still run until cancelled");
                if (state.exportJson.length() > 0) {
                    JSONObject session = new JSONObject(state.exportJson);
                    JSONObject active = session.optJSONObject("active_report");
                    if (active != null && active.getJSONArray("samples").length() > 0) {
                        ResultStore store = new ResultStore(test.getTargetContext());
                        try { check(store.latest(1).getJSONObject(0).getJSONObject("active_report").getJSONArray("samples").length() > 0, "round checkpoint persisted before stage finishes"); }
                        finally { store.close(); }
                        break;
                    }
                }
                check(SystemClock.elapsedRealtime() < until, "first stage deadline"); SystemClock.sleep(25);
            }
            test.runOnMainSync(() -> { runner.cancel(); check(observed.get().busy && observed.get().message.contains("正在停止"), "immediate stopping feedback"); });
            waitIdle(observed, 45000);
            JSONObject cancelled = new JSONObject(observed.get().exportJson);
            check("cancelled".equals(cancelled.optString("status")), "whole session cancelled");
            JSONObject partial = cancelled.getJSONArray("results").getJSONObject(0);
            check(partial.getInt("repetitions") == 3 && !partial.has("median") && "completed".equals(partial.getJSONArray("samples").getJSONObject(0).optString("status")), "completed first round retained without scoring cancelled stage");
            check(cancelled.has("cancel_stop_latency_ms"), "actual stop latency recorded");
            progress.stage("background interrupts real GPU window");
            test.runOnMainSync(() -> runner.start(BenchmarkSuite.GPU_EGYPT_SCREEN, false));
            until = SystemClock.elapsedRealtime() + 20000;
            while (!DisplaySurface.requested || DisplaySurface.width() == 0) {
                check(observed.get().busy && SystemClock.elapsedRealtime() < until, "window acquired for interruption test"); SystemClock.sleep(50);
            }
            SystemClock.sleep(500);
            test.runOnMainSync(() -> activity.moveTaskToBack(true));
            waitIdle(observed, 30000);
            JSONObject interrupted = new JSONObject(observed.get().exportJson);
            check("interrupted".equals(interrupted.optString("status")), "background is interruption, not normal completion");
            check(!interrupted.has("median"), "interrupted GPU does not produce a normal score");
            return "cancelled three-round stage kept " + partial.getJSONArray("samples").length() + " raw rounds; stop latency=" + cancelled.getLong("cancel_stop_latency_ms") + " ms; GPU background interrupted";
        } finally {
            test.runOnMainSync(() -> { if (runner.isBusy()) runner.cancel(); runner.detach(observer); });
            test.runOnMainSync(() -> test.getTargetContext().startActivity(intent));
        }
    }
    private static void waitIdle(AtomicReference<BenchmarkRunner.State> state, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (state.get() == null || state.get().busy) { check(SystemClock.elapsedRealtime() < until, "runner stop/idle deadline"); SystemClock.sleep(50); }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
