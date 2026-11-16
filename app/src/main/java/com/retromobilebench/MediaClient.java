package com.retromobilebench;

import android.content.*;
import android.os.*;
import android.view.Surface;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every media path gets a disposable process and a parent-side wall-clock watchdog. */
final class MediaClient {
    private static final WorkerRequest.Cancellation cancellation = new WorkerRequest.Cancellation();
    private static volatile boolean externalPlayback;
    private static volatile boolean cleanupFailed;
    static boolean externalPlaybackActive() { return externalPlayback; }
    static void resetCancellation() { cancellation.reset(); }
    static void cancel() { cancellation.cancel(); }
    static boolean cancelled() { return cancellation.isCancelled(); }
    static JSONObject failure(String status, String reason) {
        JSONObject value = new JSONObject(); try { value.put("status", status).put("error_stage", reason); } catch (Exception ignored) { } return value;
    }
    static JSONObject run(Context context, JSONObject clip, String path, String mode, int threads) throws Exception {
        if (cleanupFailed) return failure("failed", I18n.t(context, "此前媒体工作进程未能回收；请重启应用后再测", "Previous media worker failed cleanup; please restart app"));
        JSONArray attempts = new JSONArray(); JSONObject result = null;
        int count = Build.VERSION.SDK_INT >= 16 && !"software".equals(mode) ? 2 : 1;
        for (int candidate = 0; candidate < count; candidate++) {
            JSONObject value = invoke(context, clip, path, mode, threads, candidate);
            if (value.optBoolean("candidate_missing") && result != null) break;
            result = value; attempts.put(new JSONObject(value.toString()));
            String status = value.optString("status");
            if ("completed".equals(status) || "cancelled".equals(status) || "interrupted".equals(status)) break;
            if ("playback".equals(mode) && candidate + 1 < count) {
                try {
                    DisplaySurface.retire(MediaClient::cancelled);
                    attempts.getJSONObject(attempts.length() - 1).put("surface_destroyed_before_fallback", true);
                } catch (java.io.IOException e) {
                    result.put("surface_recovery_error", e.toString());
                    break; // Do not hand a still-connected BufferQueue to another codec.
                }
            }
        }
        if (count > 1) result.put("attempts", attempts).put("selection", "vendor_or_unknown_then_known_software_name_lexical_max2")
                .put("attempt_isolation", "one_process_per_candidate");
        return result;
    }
    private static JSONObject invoke(Context context, JSONObject clip, String path, String mode, int threads, int candidate) throws Exception {
        if (cancelled()) return failure("cancelled", "before_start");
        boolean legacyPlayback = "playback".equals(mode) && Build.VERSION.SDK_INT < 16;
        final Surface surface;
        try { surface = "playback".equals(mode) && !legacyPlayback ? DisplaySurface.acquire(context, MediaClient::cancelled) : null; }
        catch (java.io.IOException e) {
            // No worker was started: retain this path's failure and allow the other clips.
            return failure(cancelled() ? "cancelled" : "interrupted", "surface_acquire: " + e.getMessage());
        }
        externalPlayback = legacyPlayback;
        CountDownLatch died = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<JSONObject> identity = new AtomicReference<>();
        WorkerRequest pending = new WorkerRequest(failure("cancelled", I18n.t(context, "用户停止或 Surface 丢失", "Stopped by user or Surface lost")));
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Messenger> remote = new AtomicReference<>(); AtomicReference<IBinder> binder = new AtomicReference<>();
        IBinder.DeathRecipient death = () -> { died.countDown(); pending.complete(failure("failed", I18n.t(context, "媒体进程退出或解码器崩溃", "Media worker exited or decoder crashed"))); };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what == 4 && !closed.get()) {
                    pid.set(message.getData().getInt("pid"));
                    try { String attempt = message.getData().getString("attempt"); if (attempt != null) identity.set(new JSONObject(attempt)); }
                    catch (Exception ignored) { }
                    return;
                }
                if (message.what != 2 || pending.isComplete()) return;
                try {
                    JSONObject value = new JSONObject(message.getData().getString("result")).put("worker_pid", message.getData().getInt("pid"));
                    pending.complete(value, () -> {
                        if (legacyPlayback && "cancelled".equals(value.optString("status"))) BenchmarkRunner.get(context).cancel();
                        if (legacyPlayback && "interrupted".equals(value.optString("status"))) BenchmarkRunner.get(context).interrupt();
                    });
                }
                catch (Exception e) { pending.complete(failure("failed", e.toString())); }
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (closed.get()) return;
                binder.set(service); remote.set(new Messenger(service));
                try {
                    service.linkToDeath(death, 0);
                    if (pending.isComplete()) return;
                    Message request = Message.obtain(null, 1); request.replyTo = reply;
                    request.getData().putString("clip", clip.toString()); request.getData().putString("path", path);
                    request.getData().putString("mode", mode); request.getData().putInt("threads", threads); request.getData().putInt("candidate", candidate);
                    if (surface != null) request.getData().putParcelable("surface", surface);
                    remote.get().send(request);
                } catch (Exception e) { pending.complete(failure("failed", e.toString())); }
            }
            public void onServiceDisconnected(ComponentName name) { death.binderDied(); }
        };
        boolean bound = false; long start = SystemClock.elapsedRealtime();
        cancellation.attach(pending);
        try {
            if (!pending.isComplete()) {
                bound = context.bindService(new Intent(context, MediaService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound) pending.complete(failure("failed", I18n.t(context, "媒体进程绑定失败", "Failed to bind media worker service")));
            }
            long limit = "software".equals(mode) ? 15000 : 12000;
            JSONObject value = pending.await(start + limit, failure("timeout", I18n.t(context, "媒体路径超过独立进程时限", "Media path exceeded worker timeout")));
            JSONObject known = identity.get();
            if (known != null) {
                java.util.Iterator<String> keys = known.keys();
                while (keys.hasNext()) { String key = keys.next(); value.put(key, known.get(key)); }
            }
            if (pid.get() > 0) value.put("worker_pid", pid.get());
            if (surface != null) value.put("output_window", "display_activity").put("window_mode", "fullscreen")
                    .put("physical_width", DisplaySurface.width()).put("physical_height", DisplaySurface.height());
            value.put("path_wall_ms", SystemClock.elapsedRealtime() - start); return value;
        } finally {
            pending.complete(failure("failed", I18n.t(context, "媒体请求已关闭", "Media request closed")));
            cancellation.detach(pending);
            try {
                if (bound) {
                    closed.set(true);
                    try { WorkerProcess.close(context, connection, remote.get(), binder.get(), died, pid.get()); }
                    catch (Exception e) { cleanupFailed = true; throw e; }
                }
            } finally { if (surface != null) DisplaySurface.hide(); externalPlayback = false; }
        }
    }
}
