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
    private static boolean inUse;
    static float refreshHz;
    static synchronized void attach(Host value) { host = value; if (requested) value.display(true); }
    static synchronized void detach(Host value) { if (host == value) host = null; }
    static synchronized void changed(Surface value, int w, int h) { surface = value; width = w; height = h; }
    static synchronized void lost() {
        surface = null;
        if (inUse) BenchmarkRunner.surfaceInterrupted();
        inUse = false;
    }
    static Surface acquire() throws Exception {
        requested = true;
        MAIN.post(() -> { synchronized (DisplaySurface.class) { if (host != null) host.display(true); } });
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized (DisplaySurface.class) { if (surface != null && surface.isValid() && width > 0 && height > 0) { inUse = true; return surface; } }
            SystemClock.sleep(50);
        }
        hide();
        throw new java.io.IOException("窗口 Surface 未就绪或应用不在前台");
    }
    static synchronized int width() { return width; }
    static synchronized int height() { return height; }
    static synchronized void hide() { requested = false; inUse = false; surface = null; width = height = 0; MAIN.post(() -> { synchronized (DisplaySurface.class) { if (host != null) host.display(false); } }); }
}
