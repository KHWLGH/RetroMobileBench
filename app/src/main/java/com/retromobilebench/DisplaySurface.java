package com.retromobilebench;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

/** A dedicated UI window owns the Surface; disposable workers only borrow it. */
final class DisplaySurface {
    interface Host {
        void display(boolean visible);
        default void gpuHud(boolean visible) { }
        default boolean ready() { return true; }
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Host host;
    private static Context context;
    private static Surface surface;
    private static int width, height;
    static volatile boolean requested;
    private static boolean inUse, shown, launching, gpuHud;
    private static long generation, handoffUntil;
    static float refreshHz;
    static synchronized boolean consumeHandoff() {
        boolean allowed = handoffUntil > SystemClock.elapsedRealtime(); handoffUntil = 0; return allowed;
    }
    static synchronized boolean attach(Host value, long request) {
        launching = false;
        if (!requested || generation != request) {
            if (requested) MAIN.post(() -> show(generation));
            return false;
        }
        if (host != value) { invalidate(); host = value; }
        value.gpuHud(gpuHud); value.display(true); shown = true; return true;
    }
    static synchronized void detach(Host value) {
        if (host == value) {
            invalidate(); host = null; shown = false;
            if (requested) MAIN.post(() -> show(generation));
        }
    }
    private static synchronized void show(long request) {
        if (!requested || generation != request) return;
        if (host != null) { host.gpuHud(gpuHud); host.display(true); shown = true; return; }
        if (launching || context == null) return;
        launching = true; handoffUntil = SystemClock.elapsedRealtime() + 10000;
        try {
            context.startActivity(new Intent(context, DisplayActivity.class).putExtra("request", request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) { launching = false; handoffUntil = 0; requested = false; }
    }
    static synchronized void changed(Host owner, Surface value, int w, int h) {
        if (host != owner) return;
        if (inUse && (surface != value || width != w || height != h)) invalidate();
        surface = value; width = w; height = h;
    }
    static synchronized void lost(Host owner) { if (host == owner) invalidate(); }
    private static void invalidate() {
        surface = null; width = height = 0;
        boolean interrupted = inUse; inUse = false;
        if (interrupted) BenchmarkRunner.surfaceInterrupted();
    }
    static Surface acquire(Context value) throws Exception { return acquire(value, () -> false); }
    static Surface acquire(Context value, GpuDataPack.Cancellation cancellation) throws Exception { return acquire(value, cancellation, false); }
    static Surface acquire(Context value, GpuDataPack.Cancellation cancellation, boolean showGpuHud) throws Exception {
        final long request;
        synchronized (DisplaySurface.class) {
            context = value.getApplicationContext(); gpuHud = showGpuHud; requested = true; shown = false; request = ++generation;
        }
        MAIN.post(() -> show(request));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancellation.cancelled()) { hide(); throw new java.io.InterruptedIOException(I18n.t("等待窗口时已取消", "Cancelled while waiting for display window")); }
            synchronized (DisplaySurface.class) {
                if (request != generation || !requested) throw new java.io.InterruptedIOException(I18n.t("窗口请求已关闭", "Display window request closed"));
                if (host != null && host.ready() && shown && surface != null && surface.isValid() && width > height && height > 0) { inUse = true; return surface; }
            }
            SystemClock.sleep(50);
        }
        hide();
        throw new java.io.IOException(I18n.t("窗口 Surface 未就绪或应用不在前台", "Display Surface not ready or app is not in foreground"));
    }
    /** Wait for the actual old Surface destruction before a codec fallback. */
    static void retire(GpuDataPack.Cancellation cancellation) throws java.io.IOException {
        final long request;
        synchronized (DisplaySurface.class) { requested = shown = inUse = false; request = ++generation; }
        MAIN.post(() -> {
            synchronized (DisplaySurface.class) {
                if (generation == request && !requested && host != null) host.display(false);
            }
        });
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancellation.cancelled()) throw new java.io.InterruptedIOException(I18n.t("等待失败窗口销毁时已取消", "Cancelled while waiting for window surface retirement"));
            synchronized (DisplaySurface.class) {
                if (generation != request) throw new java.io.InterruptedIOException(I18n.t("窗口销毁请求已被替换", "Window retirement request superseded"));
                if (surface == null) return;
            }
            SystemClock.sleep(20);
        }
        throw new java.io.IOException(I18n.t("失败解码窗口未完成销毁，拒绝复用给后备解码器", "Failed decoder surface did not retire in time; refusing reuse"));
    }
    static synchronized int width() { return width; }
    static synchronized int height() { return height; }
    static synchronized void hide() {
        requested = shown = inUse = false; ++generation;
        MAIN.post(() -> { synchronized (DisplaySurface.class) { if (!requested && host != null) host.display(false); } });
    }
}
