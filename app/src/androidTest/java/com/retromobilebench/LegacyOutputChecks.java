package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Real pre-16 output window, private file, and foreground hand-off checks. */
final class LegacyOutputChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        Context context = test.getTargetContext();
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity)test.startActivitySync(launch);
        BenchmarkRunner runner = BenchmarkRunner.get(context);
        AtomicReference<BenchmarkRunner.State> state = new AtomicReference<>();
        BenchmarkRunner.Listener observer = value -> { state.set(value); activity.onState(value); };
        try {
            waitIdle(test, runner, observer, state, 60000);
            MediaCorpus.prepare(context, () -> false); MediaClient.resetCancellation();
            JSONObject clip = MediaCorpus.manifest(context).getJSONArray("videos").getJSONObject(0);
            JSONObject playback = MediaClient.run(context, clip, new File(context.getFilesDir(), "media-v1/" + clip.getString("file")).getPath(), "playback", 1);
            progress.stage("legacy SD playback: " + playback);
            check("completed".equals(playback.optString("status")) && playback.optBoolean("surface_created_in_worker"), "legacy SD actually plays in its own window");
            check("unconfirmed".equals(playback.optString("hardware_type")), "MediaPlayer is not hardware proof");
            BenchmarkSuite[] suites = android.os.Build.VERSION.SDK_INT < 11
                    ? new BenchmarkSuite[]{BenchmarkSuite.VIDEO, BenchmarkSuite.GPU_EGYPT_SCREEN} : new BenchmarkSuite[]{BenchmarkSuite.VIDEO};
            for (BenchmarkSuite suite : suites) for (boolean background : new boolean[]{false, true}) {
                context.startActivity(launch);
                progress.stage((background ? "background " : "back cancels ") + suite.name() + " worker window");
                test.runOnMainSync(() -> runner.start(suite, false));
                long deadline = SystemClock.elapsedRealtime() + 20000;
                while (!(suite == BenchmarkSuite.VIDEO ? MediaClient.externalPlaybackActive() : GpuClient.externalDisplayActive())) {
                    check(runner.isBusy() && SystemClock.elapsedRealtime() < deadline, "legacy window launch deadline"); SystemClock.sleep(25);
                }
                SystemClock.sleep(700);
                check(runner.isBusy() && (suite == BenchmarkSuite.VIDEO ? MediaClient.externalPlaybackActive() : GpuClient.externalDisplayActive()), "internal Activity transition does not interrupt the test");
                if (background) context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                waitIdle(test, runner, observer, state, 30000);
                JSONObject report = new JSONObject(state.get().exportJson);
                check((background ? "interrupted" : "cancelled").equals(report.optString("status")), "external window stop reaches runner: " + report);
                if (suite == BenchmarkSuite.VIDEO) {
                    JSONObject firstClip = report.getJSONArray("samples").getJSONObject(0).getJSONArray("results").getJSONObject(0);
                    check("completed".equals(firstClip.getJSONObject("software").optString("status")), "completed software result survives playback stop");
                }
                check(!report.has("median"), "interrupted/cancelled output has no normal aggregate");
            }
            return "legacy SD playback in media-process window; media/GPU Back cancellation and Home interruption preserve valid results";
        } finally {
            test.runOnMainSync(() -> { if (runner.isBusy()) runner.cancel(); runner.detach(observer); });
            context.startActivity(launch);
        }
    }
    private static void waitIdle(Instrumentation test, BenchmarkRunner runner, BenchmarkRunner.Listener observer,
                                 AtomicReference<BenchmarkRunner.State> state, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            test.runOnMainSync(() -> runner.attach(observer));
            if (!state.get().busy) return;
            check(SystemClock.elapsedRealtime() < deadline, "legacy runner idle deadline"); SystemClock.sleep(50);
        } while (true);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
