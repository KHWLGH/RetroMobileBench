package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in strict matrix on devices expected to support the bundled system video profiles. */
final class MediaRegressionChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        check(android.os.Build.VERSION.SDK_INT >= 16, "MediaCodec checks need API 16");
        accounting();
        MediaTimestampChecks.accounting();
        Context context = test.getTargetContext();
        MainActivity activity = (MainActivity)test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(() -> {
            check(awake(activity), "idle foreground window keeps screen on");
            activity.onState(new BenchmarkRunner.State(false, "媒体回归测试", "", ""));
            check(awake(activity), "idle state updates cannot clear keep-screen-on");
        });
        progress.stage("Surface reuse, obsolete callbacks and cancellation while acquiring a window");
        try {
            Surface initial = DisplaySurface.acquire();
            int width = DisplaySurface.width();
            DisplaySurface.Host obsolete = visible -> { throw new AssertionError("obsolete host used"); };
            DisplaySurface.changed(obsolete, initial, 1, 1); DisplaySurface.lost(obsolete);
            check(DisplaySurface.width() == width, "obsolete host callbacks cannot invalidate current Surface");
            for (int i = 0; i < 5; i++) {
                AtomicReference<Surface> next = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
                Thread acquirer = new Thread(() -> { try { next.set(DisplaySurface.acquire()); } catch (Throwable e) { error.set(e); } });
                // Hold the main queue until acquire supersedes the pending hide, reproducing the race.
                test.runOnMainSync(() -> {
                    DisplaySurface.hide(); acquirer.start();
                    long until = SystemClock.elapsedRealtime() + 1000;
                    while (!DisplaySurface.requested && SystemClock.elapsedRealtime() < until) SystemClock.sleep(5);
                    check(DisplaySurface.requested, "next request queued while hide is pending");
                });
                acquirer.join(3000);
                check(!acquirer.isAlive() && error.get() == null && next.get() != null && next.get().isValid(), "rapid hide/acquire reuses valid window: " + error.get());
            }
            DisplaySurface.retire(() -> false);
            check(DisplaySurface.width() == 0 && DisplaySurface.height() == 0, "retire waits for real surfaceDestroyed before fallback");
            check(DisplaySurface.acquire().isValid(), "fallback acquires a recreated Surface");
            test.runOnMainSync(() -> { DisplaySurface.hide(); DisplaySurface.detach(activity); });
            AtomicBoolean cancel = new AtomicBoolean(); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread waiting = new Thread(() -> { try { DisplaySurface.acquire(cancel::get); } catch (Throwable e) { error.set(e); } });
            waiting.start(); long until = SystemClock.elapsedRealtime() + 1000;
            while (!DisplaySurface.requested && SystemClock.elapsedRealtime() < until) SystemClock.sleep(5);
            cancel.set(true); waiting.join(1000);
            check(!waiting.isAlive() && error.get() instanceof java.io.InterruptedIOException, "cancel stops Surface acquisition promptly");
        } finally { DisplaySurface.hide(); test.runOnMainSync(() -> DisplaySurface.attach(activity)); }
        MediaClient.resetCancellation(); MediaCorpus.prepare(context, () -> false);
        progress.stage("all seven software/system/playback profiles with exact output frame counts");
        JSONObject video = new JSONObject(MediaCorpus.video(context, 2)).put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
        File evidence = new File(context.getFilesDir(), "media-regression.json");
        try (FileOutputStream out = new FileOutputStream(evidence)) { out.write(video.toString(2).getBytes("UTF-8")); }
        JSONArray clips = video.getJSONArray("results"); check(clips.length() == 7, "all seven profiles attempted: " + video);
        JSONArray manifest = MediaCorpus.manifest(context).getJSONArray("videos");
        StringBuilder summary = new StringBuilder("EOS frame accounting, Surface lifecycle, foreground keep-screen-on; 21 media paths\n");
        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.getJSONObject(i); summary.append(clip.getString("id"));
            for (String path : new String[]{"software", "system", "playback"}) {
                JSONObject value = clip.getJSONObject(path);
                check("completed".equals(value.optString("status")), clip.getString("id") + " " + path + ": " + value);
                if ("playback".equals(path)) {
                    check(value.getInt("frames") == manifest.getJSONObject(i).getInt("frames") && value.getInt("pending_frames") == 0 && value.getBoolean("output_eos"), "all submitted playback frames drained: " + value);
                    summary.append(" | playback ").append(value.getString("decoder")).append(" frames=").append(value.getInt("frames"))
                            .append(" EOS(size=").append(value.getInt("eos_size")).append(",pts=").append(value.getLong("eos_pts_us")).append(")");
                }
            }
            summary.append('\n');
        }
        check("completed".equals(video.getString("status")), "strict matrix completed");
        test.runOnMainSync(() -> check(awake(activity), "foreground window stays awake after media workers finish"));
        return summary.toString();
    }
    private static boolean awake(MainActivity activity) { return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0; }
    private static void accounting() throws Exception {
        int eos = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        MediaCodecApi16.OutputFrames f = new MediaCodecApi16.OutputFrames();
        f.submit(0); f.submit(33333);
        check(!f.consume(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0, true), "config is not a frame");
        check(f.consume(0, 0, 0, true) && f.consume(0, eos, 33333, true) && f.complete(2), "zero-sized Surface EOS carries the last submitted frame");
        check(!f.consume(0, eos, 0, true) && f.frames == 2, "empty EOS timestamp ignored, no double count");
        f.nextCycle(); f.submit(0); f.submit(33333);
        check(f.consume(10, 0, 0, false), "byte buffer frame");
        check(!f.consume(0, eos, 33333, false) && !f.complete(2), "byte-buffer empty EOS cannot fill in a missing frame");
        check(f.consume(10, eos, 33333, false) && f.complete(2), "data-bearing EOS frame");
        boolean rejected = false;
        try { f.consume(10, 0, 33333, true); } catch (IOException expected) { rejected = true; }
        check(rejected, "duplicate data output rejected");
        f.nextCycle(); f.submit(0); f.submit(33333); f.consume(0, 0, 33333, true); rejected = false;
        try { f.consume(0, eos, 0, true); } catch (IOException expected) { rejected = true; }
        check(rejected && !f.complete(2), "regressing real PTS still rejected");
        f.nextCycle(); f.submit(0); rejected = false;
        try { f.consume(10, eos, 999, true); } catch (IOException expected) { rejected = true; }
        check(rejected && !f.complete(1), "unsubmitted data-bearing output rejected");
        f.nextCycle(); f.submit(0); f.submit(33333); f.submit(66666);
        f.consume(0, 0, 0, true); f.consume(0, eos, 66666, true);
        check(!f.complete(3), "EOS cannot hide an earlier missing frame");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
