package com.retromobilebench;

import android.content.*;
import android.os.*;
import android.view.Surface;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Every media path gets a disposable process and a parent-side wall-clock watchdog. */
final class MediaClient {
    private static volatile boolean cancelled, externalPlayback;
    static boolean externalPlaybackActive() { return externalPlayback; }
    static void resetCancellation() { cancelled = false; }
    static void cancel() { cancelled = true; }
    static boolean cancelled() { return cancelled; }
    static JSONObject failure(String status, String reason) {
        JSONObject value = new JSONObject(); try { value.put("status", status).put("error_stage", reason); } catch (Exception ignored) { } return value;
    }
    static JSONObject run(Context context, JSONObject clip, String path, String mode, int threads) throws Exception {
        if (cancelled) return failure("cancelled", "before_start");
        boolean legacyPlayback = "playback".equals(mode) && Build.VERSION.SDK_INT < 16;
        Surface surface = "playback".equals(mode) && !legacyPlayback ? DisplaySurface.acquire() : null;
        externalPlayback = legacyPlayback;
        CountDownLatch done = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<Messenger> remote = new AtomicReference<>(); AtomicReference<IBinder> binder = new AtomicReference<>();
        IBinder.DeathRecipient death = () -> { result.compareAndSet(null, failure("failed", "媒体进程退出或解码器崩溃")); done.countDown(); };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what != 2) return;
                try {
                    JSONObject value = new JSONObject(message.getData().getString("result")).put("worker_pid", message.getData().getInt("pid"));
                    result.compareAndSet(null, value);
                    if (legacyPlayback && "cancelled".equals(value.optString("status"))) BenchmarkRunner.get(context).cancel();
                    if (legacyPlayback && "interrupted".equals(value.optString("status"))) BenchmarkRunner.get(context).interrupt();
                }
                catch (Exception e) { result.compareAndSet(null, failure("failed", e.toString())); }
                done.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service); remote.set(new Messenger(service));
                try {
                    service.linkToDeath(death, 0); Message request = Message.obtain(null, 1); request.replyTo = reply;
                    request.getData().putString("clip", clip.toString()); request.getData().putString("path", path);
                    request.getData().putString("mode", mode); request.getData().putInt("threads", threads);
                    if (surface != null) request.getData().putParcelable("surface", surface);
                    remote.get().send(request);
                } catch (Exception e) { result.compareAndSet(null, failure("failed", e.toString())); done.countDown(); }
            }
            public void onServiceDisconnected(ComponentName name) { death.binderDied(); }
        };
        boolean bound = false; long start = SystemClock.elapsedRealtime();
        try {
            bound = context.bindService(new Intent(context, MediaService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) return failure("failed", "媒体进程绑定失败");
            long limit = "software".equals(mode) ? 15000 : 12000;
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                if (cancelled) { result.compareAndSet(null, failure("cancelled", "用户停止或 Surface 丢失")); break; }
                if (SystemClock.elapsedRealtime() - start >= limit) { result.compareAndSet(null, failure("timeout", "媒体路径超过独立进程时限")); break; }
            }
            JSONObject value = result.get() == null ? failure("failed", "empty_result") : result.get();
            value.put("path_wall_ms", SystemClock.elapsedRealtime() - start); return value;
        } finally {
            try {
                if (surface != null) DisplaySurface.hide();
                if (bound) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try { if (remote.get() != null) remote.get().send(Message.obtain(null, 3)); } catch (Exception ignored) { }
                        try { context.unbindService(connection); } finally { cleaned.countDown(); }
                    });
                    if (!cleaned.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("媒体进程解绑超时");
                    IBinder service = binder.get();
                    long until = SystemClock.elapsedRealtime() + 3000;
                    while (service != null && service.pingBinder() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
                    if (service != null && service.pingBinder()) throw new java.io.IOException("媒体进程未能回收，停止后续媒体负载");
                }
            } finally { externalPlayback = false; }
        }
    }
}
