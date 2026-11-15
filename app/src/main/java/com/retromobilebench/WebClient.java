package com.retromobilebench;

import android.content.*;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Parent owns every deadline, cancellation and cleanup; a blocked WebView cannot block it. */
final class WebClient {
    static final String[] IDS = {"page", "todo", "text", "data"};
    static final String[] METRICS = {"page_total_ms", "page_unit_ms", "todo_total_ms", "todo_unit_ms", "text_total_ms", "text_unit_ms", "data_total_ms", "data_unit_ms"};
    private static final WorkerRequest.Cancellation cancellation = new WorkerRequest.Cancellation();
    private static volatile long stageDeadline, handoffUntil;
    private static volatile boolean cleanupFailed;
    static volatile String diagnosticPhase = "idle";
    static void beginStage() { stageDeadline = SystemClock.elapsedRealtime() + 330000; }
    static void endStage() { stageDeadline = 0; }
    static void resetCancellation() { cancellation.reset(); }
    static void cancel() { cancellation.cancel(); }
    static boolean cleanupFailed() { return cleanupFailed; }
    /** Only the single finite main -> worker transition is exempt. Worker onStop owns Home/lock. */
    static synchronized boolean consumeHandoff() {
        boolean allowed = handoffUntil > SystemClock.elapsedRealtime(); handoffUntil = 0; return allowed;
    }
    static String run(Context context) { return run(context, "", 0).toString(); }
    static JSONObject run(Context context, String fault, long diagnosticTimeout) {
        final long roundStarted = SystemClock.elapsedRealtime();
        diagnosticPhase = "preflight";
        JSONObject snapshot = failure("failed", "not_started");
        try {
            if (cleanupFailed) return failure("failed", "Web 工作进程此前回收失败；请重启应用").put("unsafe_cleanup", true);
            if (cancellation.isCancelled()) return failure("cancelled", "preflight");
            long deadline = stageDeadline == 0 ? SystemClock.elapsedRealtime() + 330000 : stageDeadline;
            snapshot = WebAssets.verify(context);
            snapshot.put("test_version", WebAssets.VERSION).put("units_per_subtest", 20).put("prewarm_units_per_subtest", 1)
                    .put("viewport_css", "device-width/fullscreen").put("font_css", "12px/18px sans-serif")
                    .put("app_build_type", BuildConfig.BUILD_TYPE).put("app_debuggable", BuildConfig.DEBUG)
                    .put("subtest_timeout_ms", 20000).put("prepare_timeout_ms", 15000).put("stage_timeout_ms", 330000)
                    .put("measurement", "application_task_with_layout_checkpoint; lower_is_better; not_paint_or_input_latency")
                    .put("results", new JSONArray()).put("completed_metrics", 0);
            if (fault.length() > 0 || diagnosticTimeout > 0) snapshot.put("diagnostic_fault", fault).put("diagnostic_timeout_ms", diagnosticTimeout);
            return invoke(context, snapshot, fault, deadline, diagnosticTimeout, roundStarted);
        } catch (Exception error) {
            try { snapshot.put("status", "failed").put("error_stage", error.toString()); } catch (Exception ignored) { }
            return snapshot;
        }
    }
    private static JSONObject invoke(Context context, JSONObject snapshot, String fault, long deadline, long diagnosticTimeout, long roundStarted) throws Exception {
        final Object lock = new Object(); final String nonce = UUID.randomUUID().toString();
        final long started = roundStarted;
        final Handler main = new Handler(Looper.getMainLooper());
        final WorkerRequest pending = new WorkerRequest(failure("cancelled", "parent_cancel"));
        final AtomicBoolean closed = new AtomicBoolean(); final AtomicInteger pid = new AtomicInteger();
        final AtomicReference<Messenger> remote = new AtomicReference<>(); final AtomicReference<IBinder> binder = new AtomicReference<>();
        final CountDownLatch died = new CountDownLatch(1), connected = new CountDownLatch(1);
        final long[] phaseDeadline = {Math.min(deadline, started + (diagnosticTimeout > 0 ? diagnosticTimeout : 15000))};
        final int[] next = {0}; final boolean[] prepared = {false}, measuring = {false};
        Runnable watchdog = new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (closed.get() || pending.isComplete()) return;
                    long remaining = phaseDeadline[0] - SystemClock.elapsedRealtime();
                    if (remaining <= 0) { pending.complete(failure("timeout", prepared[0] ? "formal_subtest_or_stage_deadline" : "startup_prewarm_deadline")); }
                    else main.postDelayed(this, Math.min(remaining, 1000));
                }
            }
        };
        IBinder.DeathRecipient death = () -> { died.countDown(); pending.complete(failure("failed", "web_process_died")); };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                synchronized (lock) {
                    if (closed.get() || pending.isComplete() || !nonce.equals(message.getData().getString("nonce"))) return;
                    if (message.what == WebService.READY) { pid.set(message.getData().getInt("pid")); return; }
                    if (message.what != WebService.EVENT) return;
                    try {
                        if (SystemClock.elapsedRealtime() >= phaseDeadline[0]) { pending.complete(failure("timeout", "late_result")); return; }
                        String raw = message.getData().getString("raw");
                        if (raw == null || raw.length() > 50000) throw new IllegalStateException("Web result bound");
                        JSONObject event = new JSONObject(raw); String kind = event.getString("kind");
                        diagnosticPhase = kind;
                        if ("prepared".equals(kind)) {
                            if (prepared[0] || next[0] != 0 || event.getInt("viewport_width") < 1 || event.getInt("viewport_height") < 1
                                    || !"fullscreen".equals(event.getString("window_mode"))
                                    || event.getInt("physical_width") != event.getInt("window_width") || event.getInt("physical_height") != event.getInt("window_height")
                                    || !positive(event.getDouble("clock_resolution_ms")) || !positive(event.getDouble("dpr"))) throw new IllegalStateException("Web environment");
                            prepared[0] = true;
                            for (String key : new String[]{"bridge_origin", "clock_source", "clock_resolution_ms", "viewport_width", "viewport_height", "dpr", "user_agent", "text_css", "scheduling", "physical_width", "physical_height", "window_mode", "window_width", "window_height", "density", "initial_scale_percent", "webview_scale", "css_to_physical_x", "css_to_physical_y", "layout_algorithm", "hardware_accelerated", "provider", "os_fingerprint", "text_zoom_percent"}) snapshot.put(key, event.get(key));
                            phaseDeadline[0] = Math.min(deadline, SystemClock.elapsedRealtime() + 20000);
                        } else if ("begin".equals(kind)) {
                            if (!prepared[0] || measuring[0] || next[0] >= 4 || event.getInt("index") != next[0] || !IDS[next[0]].equals(event.getString("id"))) throw new IllegalStateException("Web phase order");
                            measuring[0] = true; snapshot.put("active_subtest", IDS[next[0]]);
                            phaseDeadline[0] = Math.min(deadline, SystemClock.elapsedRealtime() + 20000);
                        } else if ("metric".equals(kind)) {
                            int index = next[0];
                            if (!measuring[0] || index >= 4 || event.getInt("index") != index || !IDS[index].equals(event.getString("id")) || event.getInt("units") != 20
                                    || event.getJSONObject("proof").getInt("validated_units") != 20) throw new IllegalStateException("Web correctness envelope");
                            double total = event.getDouble("total_ms"), unit = event.getDouble("unit_ms");
                            if (!positive(total) || !positive(unit) || total < 20 * snapshot.getDouble("clock_resolution_ms") || total > 20000 || Math.abs(total / 20 - unit) > 0.000001) throw new IllegalStateException("Web timing");
                            snapshot.getJSONArray("results").put(event);
                            snapshot.put(METRICS[index * 2], total).put(METRICS[index * 2 + 1], unit).put("completed_metrics", (index + 1) * 2);
                            next[0]++; measuring[0] = false; snapshot.remove("active_subtest");
                            phaseDeadline[0] = Math.min(deadline, SystemClock.elapsedRealtime() + 20000);
                        } else if ("done".equals(kind)) {
                            if (next[0] != 4 || measuring[0]) throw new IllegalStateException("Web incomplete done");
                            pending.complete(failure("completed", ""));
                        } else if ("error".equals(kind) || "cancelled".equals(kind) || "interrupted".equals(kind)) {
                            String status = "error".equals(kind) ? "failed" : kind;
                            pending.complete(failure(status, event.optString("reason")), () -> {
                                if ("cancelled".equals(status)) BenchmarkRunner.get(context).cancel();
                                if ("interrupted".equals(status)) BenchmarkRunner.get(context).interrupt();
                            });
                        } else throw new IllegalStateException("Web event kind");
                    } catch (Exception error) { pending.complete(failure("failed", error.toString())); }
                }
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (closed.get()) return;
                binder.set(service); remote.set(new Messenger(service)); connected.countDown();
                try {
                    service.linkToDeath(death, 0); if (pending.isComplete()) return;
                    Message message = Message.obtain(null, WebService.RUN); message.replyTo = reply;
                    message.getData().putString("nonce", nonce); message.getData().putString("fault", fault); remote.get().send(message);
                } catch (Exception error) { pending.complete(failure("failed", error.toString())); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { death.binderDied(); }
        };
        BroadcastReceiver foreground = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String reason = intent.getStringExtra("reason");
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || "homekey".equals(reason) || "recentapps".equals(reason))
                    pending.complete(failure("interrupted", "parent_foreground_broadcast:" + intent.getAction()), () -> BenchmarkRunner.get(context).interrupt());
            }
        };
        boolean bound = false, receiverRegistered = false; JSONObject outcome;
        cancellation.attach(pending);
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            if (Build.VERSION.SDK_INT >= 33) Api33.register(context, foreground, filter);
            else context.registerReceiver(foreground, filter);
            receiverRegistered = true;
            if (!pending.isComplete() && SystemClock.elapsedRealtime() < deadline) {
                handoffUntil = SystemClock.elapsedRealtime() + 15000;
                bound = context.bindService(new Intent(context, WebService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound) pending.complete(failure("failed", "web_bind_failed"));
            }
            main.post(watchdog);
            outcome = pending.await(deadline, failure("timeout", "whole_web_stage_deadline"));
        } finally {
            long cleanupDeadline = SystemClock.elapsedRealtime() + 10000;
            pending.complete(failure("failed", "web_request_closed")); main.removeCallbacks(watchdog); cancellation.detach(pending);
            if (receiverRegistered) try { context.unregisterReceiver(foreground); }
            catch (RuntimeException error) { snapshot.put("receiver_cleanup_warning", error.toString()); }
            try {
                if (bound && binder.get() == null && !connected.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    cleanupFailed = true; snapshot.put("unsafe_cleanup", true).put("cleanup_error", "Web binding did not acknowledge before cleanup");
                }
            } catch (InterruptedException error) { cleanupFailed = true; snapshot.put("unsafe_cleanup", true).put("cleanup_error", "Interrupted binding cleanup"); }
            closed.set(true);
            try {
                if (bound) WorkerProcess.close(context, connection, remote.get(), binder.get(), died, pid.get(), cleanupDeadline);
            } catch (Exception error) {
                cleanupFailed = true;
                synchronized (lock) { snapshot.put("unsafe_cleanup", true).put("cleanup_error", error.toString()); }
            } finally { handoffUntil = 0; }
        }
        synchronized (lock) {
            snapshot.put("worker_pid", pid.get()).put("round_wall_ms", SystemClock.elapsedRealtime() - started)
                    .put("cleanup_confirmed", !cleanupFailed).put("status", cleanupFailed ? "failed" : outcome.getString("status"))
                    .put("error_stage", cleanupFailed ? "web_cleanup_failed" : outcome.optString("error_stage"));
            return snapshot;
        }
    }
    @android.annotation.TargetApi(33) private static final class Api33 {
        static void register(Context context, BroadcastReceiver receiver, IntentFilter filter) { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); }
    }
    private static boolean positive(double value) { return value > 0 && !Double.isInfinite(value) && !Double.isNaN(value); }
    static JSONObject failure(String status, String reason) {
        JSONObject value = new JSONObject();
        try { value.put("test_version", WebAssets.VERSION).put("status", status).put("error_stage", reason).put("completed_metrics", 0); } catch (Exception ignored) { }
        return value;
    }
}
