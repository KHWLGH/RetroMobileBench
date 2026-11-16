package com.retromobilebench;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;
import org.json.JSONObject;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-side synchronous facade over a disposable, explicitly bound GPU process. */
final class GpuClient {
    static final String[] TESTS = {"gl_fill_off", "gl_trex_off", "gl_egypt_off", "gl_trex", "gl_egypt"};
    private static final WorkerRequest.Cancellation cancellation = new WorkerRequest.Cancellation();
    private static volatile boolean externalDisplay;
    private static volatile boolean cleanupFailed;
    static boolean externalDisplayActive() { return externalDisplay; }
    static void resetCancellation() { cancellation.reset(); }
    static void cancel() { cancellation.cancel(); }
    static boolean isTest(String test) { for (String id : TESTS) if (id.equals(test)) return true; return false; }
    static String run(Context context, String test, int diagnosticMs) {
        long preparationDeadline = SystemClock.elapsedRealtime() + 180000;
        try {
            if (cleanupFailed) return failure("failed", "此前 GPU 工作进程未能回收；请重启应用后再测").toString();
            if (!isTest(test)) return failure("failed", "arguments").toString();
            if (cancellation.isCancelled()) return failure("cancelled", "preflight").toString();
            File pack = GpuDataPack.ensure(context, test, () -> cancellation.isCancelled() || SystemClock.elapsedRealtime() >= preparationDeadline);
            if (pack == null) return failure("skipped", "内置固定资源不完整").toString();
            JSONObject envelope = invoke(context, pack, test, diagnosticMs, 240000);
            envelope.put("test_version", "gpu-" + test + "-v1");
            envelope.put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
            envelope.put("native_library", NativeLoader.load());
            envelope.put("native_abi", android.os.Build.CPU_ABI);
            envelope.put("source_revision", GpuDataPack.SOURCE);
            envelope.put("data_sha256", GpuDataPack.digest(pack));
            envelope.put("test_id", test).put("texture_type", "ETC1");
            envelope.put("offscreen_width", 1920).put("offscreen_height", 1080);
            envelope.put("pbuffer_width", test.endsWith("_off") ? 800 : 0).put("pbuffer_height", test.endsWith("_off") ? 480 : 0);
            envelope.put("diagnostic_duration_ms", diagnosticMs);
            envelope.put("presentation", test.endsWith("_off") ? "offscreen" : "window_submission");
            envelope.put("window_owner", test.endsWith("_off") ? "none" : Build.VERSION.SDK_INT < 11 ? "worker_activity" : "display_activity");
            envelope.put("window_mode", test.endsWith("_off") ? "offscreen" : "fullscreen");
            envelope.put("refresh_hz", DisplaySurface.refreshHz).put("swap_interval_request", test.endsWith("_off") ? JSONObject.NULL : 1);
            return envelope.toString();
        } catch (InterruptedIOException e) {
            return failure(cancellation.isCancelled() ? "cancelled" : "timeout", e.getMessage()).toString();
        } catch (Exception e) { return failure("failed", e.toString()).toString(); }
    }

    static JSONObject invoke(Context context, File pack, String test, int duration, long timeoutMs) throws Exception {
        final boolean legacyWindow = !test.endsWith("_off") && Build.VERSION.SDK_INT < 11;
        final android.view.Surface surface = test.endsWith("_off") || legacyWindow ? null : DisplaySurface.acquire(context, cancellation::isCancelled, true);
        externalDisplay = legacyWindow;
        CountDownLatch died = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
        WorkerRequest pending = new WorkerRequest(failure("cancelled", "GPU 测试已取消"));
        AtomicBoolean closed = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger hudUpdates = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<IBinder> binder = new AtomicReference<>();
        IBinder.DeathRecipient death = () -> {
            died.countDown();
            pending.complete(failure("failed", "GPU 进程退出（驱动崩溃或系统回收）"));
        };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what == GpuService.READY && !closed.get()) { pid.set(message.getData().getInt("pid")); return; }
                if (message.what == GpuService.STATS) {
                    if (!closed.get() && !pending.isComplete() && !test.endsWith("_off") && GpuHud.publish(message.getData().getDouble("fps"))) hudUpdates.incrementAndGet();
                    return;
                }
                if (message.what != GpuService.RESULT || pending.isComplete() || closed.get()) return;
                try {
                    String raw = message.getData().getString("raw");
                    JSONObject parsed = parse(raw, test);
                    long swaps = message.getData().getLong("window_swaps");
                    if (!test.endsWith("_off") && "completed".equals(parsed.optString("status")) && swaps < 1)
                        parsed = failure("failed", "屏上测试未成功交换窗口缓冲区");
                    parsed.put("successful_window_swaps", swaps);
                    String eglConfig = message.getData().getString("egl_config");
                    parsed.put("egl_config", new JSONObject(eglConfig == null ? "{}" : eglConfig));
                    parsed.put("onscreen_hud", test.endsWith("_off") ? "none" : GpuHud.VERSION)
                            .put("hud_interval_ms", test.endsWith("_off") ? 0 : GpuHud.INTERVAL_MS)
                            .put("hud_updates", legacyWindow ? message.getData().getInt("hud_updates") : hudUpdates.get());
                    parsed.put("worker_pid", message.getData().getInt("pid"));
                    final String status = parsed.optString("status");
                    pending.complete(parsed, () -> {
                        if (legacyWindow && "cancelled".equals(status)) BenchmarkRunner.get(context).cancel();
                        if (legacyWindow && "interrupted".equals(status)) BenchmarkRunner.get(context).interrupt();
                    });
                } catch (Exception error) { pending.complete(failure("failed", error.toString())); }
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (closed.get()) return;
                binder.set(service); remote.set(new Messenger(service));
                try {
                    service.linkToDeath(death, 0);
                    if (pending.isComplete()) return;
                    Message request = Message.obtain(null, GpuService.RUN);
                    request.replyTo = reply;
                    request.getData().putString("test", test);
                    request.getData().putString("root", pack.getPath());
                    request.getData().putInt("duration", duration);
                    if (surface != null) {
                        request.getData().putParcelable("surface", surface);
                        request.getData().putInt("width", DisplaySurface.width());
                        request.getData().putInt("height", DisplaySurface.height());
                    }
                    remote.get().send(request);
                } catch (RemoteException error) { pending.complete(failure("failed", error.toString())); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { death.binderDied(); }
        };
        boolean bound = false;
        cancellation.attach(pending);
        try {
            if (!pending.isComplete()) {
                bound = context.bindService(new Intent(context, GpuService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound) pending.complete(failure("failed", "无法绑定 GPU 进程"));
            }
            JSONObject value = pending.await(SystemClock.elapsedRealtime() + timeoutMs, failure("timeout", "GPU 测试超时"));
            if (pid.get() > 0) value.put("worker_pid", pid.get());
            return value;
        } finally {
            // Seal an exceptional/interrupted request before any late Binder callback.
            pending.complete(failure("failed", "GPU 请求已关闭"));
            cancellation.detach(pending);
            externalDisplay = false;
            try {
                if (bound) {
                    closed.set(true);
                    try { WorkerProcess.close(context, connection, remote.get(), binder.get(), died, pid.get()); }
                    catch (Exception e) { cleanupFailed = true; throw e; }
                }
            } finally { if (surface != null) DisplaySurface.hide(); }
        }
    }

    static JSONObject parse(String raw, String test) throws Exception {
        if (raw != null && raw.startsWith("CANCELLED:")) return failure("cancelled", raw.substring(10).trim());
        if (raw != null && raw.startsWith("INTERRUPTED:")) return failure("interrupted", raw.substring(12).trim());
        if (raw != null && raw.startsWith("SKIP:")) return failure("skipped", raw.substring(5).trim());
        if (raw == null || raw.startsWith("ERROR:")) return failure("failed", raw == null ? "GPU 无结果" : raw);
        JSONObject original = new JSONObject(raw);
        JSONObject measurement = original.getJSONArray("results").getJSONObject(0);
        JSONObject result = new JSONObject().put("raw_result", original).put("completed_metrics", 0);
        if (!"OK".equals(measurement.optString("status"))) {
            String error = measurement.optString("error_string", "GPU validation");
            boolean unsupported = "OUT_OF_VMEMORY".equals(error) || "OUT_OF_MEMORY".equals(error)
                    || "OFFSCREEN_NOT_SUPPORTED".equals(error) || "UNSUPPORTED_TC_TYPE".equals(error)
                    || "OFFSCREEN_NOT_SUPPORTED_IN_MSAA".equals(error)
                    || "Z24_NOT_SUPPORTED".equals(error) || "REQUIRED_FSAA_LEVEL_NOT_SUPPORTED".equals(error);
            return result.put("status", unsupported ? "skipped" : "failed").put("error_stage", error);
        }
        if (!test.equals(measurement.getString("test_id")))
            return result.put("status", "failed").put("error_stage", "GPU 返回了不同测试的结果");
        double score = measurement.getDouble("score");
        if (score <= 0 || Double.isNaN(score) || Double.isInfinite(score))
            return result.put("status", "failed").put("error_stage", "Invalid GPU score: " + score);
        JSONObject gfx = measurement.getJSONObject("gfx_result");
        int width = gfx.getInt("surface_width"), height = gfx.getInt("surface_height");
        if (width < 1 || height < 1 || test.endsWith("_off") && (width != 1920 || height != 1080))
            return result.put("status", "failed").put("error_stage", "GPU 实际渲染分辨率不是 1920×1080");
        if (gfx.optDouble("fps", -1) > 0) result.put("fps", gfx.getDouble("fps"));
        if (gfx.optInt("frame_count", -1) > 0) result.put("frames", gfx.getInt("frame_count"));
        return result.put("status", "completed").put("completed_metrics", 1).put("score", score)
                .put("render_width", width).put("render_height", height)
                .put("unit", measurement.getString("unit")).put("renderer", gfx.optString("renderer"))
                .put("vendor", gfx.optString("vendor")).put("gl_version", gfx.optString("graphics_version"))
                .put("error_stage", "");
    }
    private static JSONObject failure(String status, String reason) {
        JSONObject result = new JSONObject();
        try { result.put("status", status).put("error_stage", reason).put("completed_metrics", 0); }
        catch (Exception ignored) { }
        return result;
    }
}
