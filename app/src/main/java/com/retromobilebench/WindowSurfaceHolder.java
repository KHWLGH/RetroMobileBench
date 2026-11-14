package com.retromobilebench;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceHolder;

/** Borrowed IPC Surface exposed as a holder for old EGL10/MediaPlayer implementations. */
@SuppressWarnings("deprecation")
final class WindowSurfaceHolder implements SurfaceHolder {
    private final Surface surface;
    private final Rect frame;
    WindowSurfaceHolder(Surface surface, int width, int height) { this.surface = surface; frame = new Rect(0, 0, width, height); }
    public void addCallback(Callback callback) { }
    public void removeCallback(Callback callback) { }
    public boolean isCreating() { return false; }
    public void setType(int type) { }
    public void setFixedSize(int width, int height) { }
    public void setSizeFromLayout() { }
    public void setFormat(int format) { }
    public void setKeepScreenOn(boolean screenOn) { }
    public Canvas lockCanvas() { return null; }
    public Canvas lockCanvas(Rect dirty) { return null; }
    public void unlockCanvasAndPost(Canvas canvas) { }
    public Rect getSurfaceFrame() { return frame; }
    public Surface getSurface() { return surface; }
}
