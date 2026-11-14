package com.retromobilebench;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

/** UI owns the window; disposable workers receive only its parcelled Surface. */
final class DisplaySurface {
    interface Host { void display(boolean visible); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Host host;
    private static Surface surface;
    private static int width, height;
    static volatile boolean requested;
    private static boolean inUse, shown;
    private static long generation;
    static float refreshHz;
    static synchronized void attach(Host value) {
        if (host != value) { invalidate(); host = value; }
        value.display(requested); shown = requested;
    }
    static synchronized void detach(Host value) {
        if (host == value) { invalidate(); host = null; shown = false; }
    }
    static synchronized void changed(Host owner, Surface value, int w, int h) {
        if (host != owner) return; // A destroyed/recreated Activity cannot replace the new window.
        if (inUse && (surface != value || width != w || height != h)) invalidate();
        surface = value; width = w; height = h;
    }
    static synchronized void lost(Host owner) { if (host == owner) invalidate(); }
    private static void invalidate() {
        surface = null; width = height = 0;
        boolean interrupted = inUse; inUse = false;
        if (interrupted) BenchmarkRunner.surfaceInterrupted();
    }
    static Surface acquire() throws Exception { return acquire(() -> false); }
    static Surface acquire(GpuDataPack.Cancellation cancellation) throws Exception {
        final long request;
        synchronized (DisplaySurface.class) { requested = true; shown = false; request = ++generation; }
        MAIN.post(() -> {
            synchronized (DisplaySurface.class) {
                if (generation == request && requested && host != null) { host.display(true); shown = true; }
            }
        });
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancellation.cancelled()) { hide(); throw new java.io.InterruptedIOException("等待窗口时已取消"); }
            synchronized (DisplaySurface.class) {
                if (request != generation || !requested) throw new java.io.InterruptedIOException("窗口请求已关闭");
                if (host != null && shown && surface != null && surface.isValid() && width > 0 && height > 0) { inUse = true; return surface; }
            }
            SystemClock.sleep(50);
        }
        hide();
        throw new java.io.IOException("窗口 Surface 未就绪或应用不在前台");
    }
    /** A crashed codec may leave its BufferQueue connected after Binder death.
     * Wait for the actual surfaceDestroyed callback before a fallback can acquire. */
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
            if (cancellation.cancelled()) throw new java.io.InterruptedIOException("等待失败窗口销毁时已取消");
            synchronized (DisplaySurface.class) {
                if (generation != request) throw new java.io.InterruptedIOException("窗口销毁请求已被替换");
                if (surface == null) return;
            }
            SystemClock.sleep(20);
        }
        throw new java.io.IOException("失败解码窗口未完成销毁，拒绝复用给后备解码器");
    }
    static synchronized int width() { return width; }
    static synchronized int height() { return height; }
    static synchronized void hide() {
        requested = false; shown = false; inUse = false; ++generation;
        // Keep the cache until surfaceDestroyed. A subsequent acquire can supersede this
        // queued hide while the same valid Surface remains visible (no changed callback).
        MAIN.post(() -> { synchronized (DisplaySurface.class) { if (!requested && host != null) host.display(false); } });
    }
}
