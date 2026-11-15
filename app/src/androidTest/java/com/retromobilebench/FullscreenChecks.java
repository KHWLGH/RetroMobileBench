package com.retromobilebench;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Real window geometry, portrait return, GPU swaps and a visible SD video path. */
final class FullscreenChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        Context context = test.getTargetContext();
        Instrumentation.ActivityMonitor mains = test.addMonitor(MainActivity.class.getName(), null, false);
        Instrumentation.ActivityMonitor outputs = test.addMonitor(DisplayActivity.class.getName(), null, false);
        try {
            test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            portrait(test, mains);
            int width = 0, height = 0;
            if (Build.VERSION.SDK_INT >= 11) {
                progress.stage("full-screen Surface geometry and repeated portrait return");
                for (int i = 0; i < 3; i++) {
                    Surface surface = DisplaySurface.acquire(context);
                    check(surface.isValid(), "valid full-screen Surface");
                    width = DisplaySurface.width(); height = DisplaySurface.height();
                    final int w = width, h = height;
                    test.runOnMainSync(() -> {
                        Activity output = outputs.getLastActivity();
                        check(output instanceof DisplayActivity && output.hasWindowFocus(), "dedicated foreground Activity");
                        check(w > h && output.getWindow().getDecorView().getWidth() == w
                                && output.getWindow().getDecorView().getHeight() == h, "Surface fills the complete landscape decor: surface=" + w + "x" + h
                                + "; decor=" + output.getWindow().getDecorView().getWidth() + "x" + output.getWindow().getDecorView().getHeight());
                        check((output.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0, "status bar hidden");
                    });
                    DisplaySurface.retire(() -> false); portrait(test, mains);
                }
            }
            progress.stage("five-second full-screen Egypt render and actual buffer swaps");
            GpuClient.resetCancellation();
            JSONObject gpu = new JSONObject(GpuClient.run(context, "gl_egypt", 5000));
            check("completed".equals(gpu.optString("status")) && gpu.getLong("successful_window_swaps") > 0, "real GPU completes: " + gpu);
            GpuBackendChecks.checkOpaqueWindow(gpu);
            check(GpuHud.VERSION.equals(gpu.optString("onscreen_hud")) && gpu.optInt("hud_updates") > 0
                    && gpu.optInt("hud_interval_ms") == 1000, "live HUD receives real window-submission samples: " + gpu);
            check("fullscreen".equals(gpu.getString("window_mode")) && gpu.getInt("render_width") > gpu.getInt("render_height"), "full-screen GPU geometry");
            if (Build.VERSION.SDK_INT >= 11) check(gpu.getInt("render_width") == width && gpu.getInt("render_height") == height, "GPU uses the complete display Surface");
            portrait(test, mains);
            progress.stage("full-screen SD playback then portrait controls");
            MediaCorpus.prepare(context, () -> false); MediaClient.resetCancellation();
            JSONObject clip = MediaCorpus.manifest(context).getJSONArray("videos").getJSONObject(0);
            JSONObject video = MediaClient.run(context, clip, new File(context.getFilesDir(), "media-v1/" + clip.getString("file")).getPath(), "playback", 1);
            check("completed".equals(video.optString("status")) && "fullscreen".equals(video.optString("window_mode")), "real SD playback completes full-screen: " + video);
            portrait(test, mains);
            progress.stage("runner Web three-round stage survives portrait/full-screen handoffs");
            BenchmarkRunner runner = BenchmarkRunner.get(context);
            test.runOnMainSync(() -> runner.start(BenchmarkSuite.WEB, true));
            long deadline = SystemClock.elapsedRealtime() + 180000;
            AtomicReference<BenchmarkRunner.State> state = new AtomicReference<>();
            do {
                test.runOnMainSync(() -> state.set(runner.currentState()));
                if (!state.get().busy) break;
                check(SystemClock.elapsedRealtime() < deadline, "runner Web stage deadline"); SystemClock.sleep(50);
            } while (true);
            JSONObject web = new JSONObject(state.get().exportJson);
            check("completed".equals(web.optString("status")) && web.has("median") && web.getJSONArray("samples").length() == 3,
                    "busy runner preserves three full-screen rounds: " + web);
            portrait(test, mains);
            return "portrait main; full-screen landscape GPU " + gpu.getInt("render_width") + "x" + gpu.getInt("render_height")
                    + (Build.VERSION.SDK_INT >= 11 ? "; repeated Surface create/destroy" : "; legacy worker windows")
                    + "; SD video; runner Web x3; returned to portrait";
        } finally { DisplaySurface.hide(); test.removeMonitor(mains); test.removeMonitor(outputs); }
    }
    private static void portrait(Instrumentation test, Instrumentation.ActivityMonitor mains) {
        long until = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        do {
            test.runOnMainSync(() -> {
                Activity main = mains.getLastActivity();
                ready.set(main != null && main.hasWindowFocus() && main.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
            });
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("main window returns to focused portrait");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
