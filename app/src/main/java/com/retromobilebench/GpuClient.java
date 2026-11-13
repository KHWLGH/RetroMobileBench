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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Worker-side synchronous facade over a disposable, explicitly bound GPU process. */
final class GpuClient {
    static final String[] TESTS = {"gl_alu_off", "gl_fill_off", "gl_driver_off", "gl_blending_off", "gl_trex_off", "gl_egypt_off"};
    private static volatile boolean cancelled;
    static void resetCancellation() { cancelled = false; }
    static void cancel() { cancelled = true; }
    static boolean isTest(String test) { for (String id : TESTS) if (id.equals(test)) return true; return false; }
    static String run(Context context, String test, int diagnosticMs) {
        try {
            if (!isTest(test)) return failure("failed", "arguments").toString();
            if (cancelled) return failure("cancelled", "preflight").toString();
            File pack = GpuDataPack.ensure(context, test, () -> cancelled);
            if (pack == null) return failure("skipped", "缺少场景数据包，请导入完整数据包").toString();
            JSONObject envelope = invoke(context, pack, test, diagnosticMs, 240000);
            envelope.put("test_version", "gpu-" + test + "-v1");
            envelope.put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
            envelope.put("native_library", NativeLoader.load());
            envelope.put("native_abi", android.os.Build.CPU_ABI);
            envelope.put("source_revision", GpuDataPack.SOURCE);
            envelope.put("data_sha256", GpuDataPack.digest(pack));
            envelope.put("test_id", test).put("texture_type", "ETC1");
            envelope.put("offscreen_width", 1920).put("offscreen_height", 1080);
            envelope.put("pbuffer_width", 800).put("pbuffer_height", 480);
            envelope.put("diagnostic_duration_ms", diagnosticMs);
            return envelope.toString();
        } catch (InterruptedIOException e) {
            return failure("cancelled", e.getMessage()).toString();
        } catch (Exception e) { return failure("failed", e.toString()).toString(); }
    }

    static JSONObject invoke(Context context, File pack, String test, int duration, long timeoutMs) throws Exception {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<IBinder> binder = new AtomicReference<>();
        IBinder.DeathRecipient death = () -> {
            result.compareAndSet(null, failure("failed", "GPU 进程退出（驱动崩溃或系统回收）")); done.countDown();
        };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what != GpuService.RESULT) return;
                try {
                    String raw = message.getData().getString("raw");
                    JSONObject parsed = parse(raw, test);
                    parsed.put("worker_pid", message.getData().getInt("pid"));
                    result.compareAndSet(null, parsed);
                } catch (Exception error) { result.compareAndSet(null, failure("failed", error.toString())); }
                done.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service); remote.set(new Messenger(service));
                try {
                    service.linkToDeath(death, 0);
                    Message request = Message.obtain(null, GpuService.RUN);
                    request.replyTo = reply;
                    request.getData().putString("test", test);
                    request.getData().putString("root", pack.getPath());
                    request.getData().putInt("duration", duration);
                    remote.get().send(request);
                } catch (RemoteException error) { result.compareAndSet(null, failure("failed", error.toString())); done.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { death.binderDied(); }
        };
        boolean bound = false;
        try {
            bound = context.bindService(new Intent(context, GpuService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) return failure("failed", "无法绑定 GPU 进程");
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                if (cancelled) { result.compareAndSet(null, failure("cancelled", "GPU 测试已取消")); break; }
                if (SystemClock.elapsedRealtime() >= deadline) { result.compareAndSet(null, failure("failed", "GPU 测试超时")); break; }
            }
            return result.get() == null ? failure("failed", "GPU 无结果") : result.get();
        } finally {
            if (bound) {
                main.post(() -> {
                    try {
                        try { if (remote.get() != null) remote.get().send(Message.obtain(null, GpuService.STOP)); }
                        catch (RemoteException ignored) { }
                        context.unbindService(connection);
                    } finally { cleaned.countDown(); }
                });
                if (!cleaned.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("GPU 进程解绑超时");
                // Keep the death notification registered until exit. On old Binder,
                // isBinderAlive() alone can stay cached after unlinkToDeath().
                IBinder service = binder.get();
                try {
                    long deadline = SystemClock.elapsedRealtime() + 5000;
                    while (service != null && service.pingBinder() && SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(20);
                    if (service != null && service.pingBinder())
                        throw new java.io.IOException("GPU 进程未退出，结果已丢弃");
                } finally {
                    if (service != null) {
                        try { service.unlinkToDeath(death, 0); }
                        catch (java.util.NoSuchElementException ignored) { /* Already dead. */ }
                    }
                }
            }
        }
    }

    static JSONObject parse(String raw, String test) throws Exception {
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
        if (gfx.getInt("surface_width") != 1920 || gfx.getInt("surface_height") != 1080)
            return result.put("status", "failed").put("error_stage", "GPU 实际渲染分辨率不是 1920×1080");
        return result.put("status", "completed").put("completed_metrics", 1).put("score", score)
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
