package com.retromobilebench;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** API 8-10 need the full-screen window in the disposable GPU process. */
public final class LegacyGpuActivity extends Activity {
    private static final class Work {
        final String test, config, data, output; final int duration;
        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Work(String id, String cfg, String assets, String out, int ms) { test = id; config = cfg; data = assets; output = out; duration = ms; }
        void publish(String value) { if (result.compareAndSet(null, value)) done.countDown(); }
    }
    private static volatile Work pending;
    private Work work; private boolean started;
    private GpuHud hud;
    static String run(Context context, String test, String config, String data, String output, int duration) throws Exception {
        Work value = new Work(test, config, data, output, duration); pending = value;
        try {
            context.startActivity(new Intent(context, LegacyGpuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!value.done.await(230, TimeUnit.SECONDS)) value.publish("ERROR: GPU worker window deadline");
            return value.result.get();
        } finally { pending = null; }
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); work = pending;
        if (work == null) { finish(); return; }
        FullscreenWindow.configure(this);
        FrameLayout frame = new FrameLayout(this);
        SurfaceView output = new SurfaceView(this);
        output.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { }
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (started || width <= height || height <= 0 || work.result.get() != null) return;
                started = true;
                new Thread(() -> {
                    String raw;
                    try {
                        JavaEglContext.window = holder.getSurface();
                        raw = GfxProbe.runTest(work.config, work.data, work.output, width, height, work.duration);
                        if (JavaEglContext.windowError.length() != 0) raw = "ERROR: " + JavaEglContext.windowError;
                    } catch (Exception | LinkageError e) { raw = "ERROR: " + e; }
                    final String value = raw; runOnUiThread(() -> complete(value));
                }, "rmb-legacy-gpu").start();
            }
            public void surfaceDestroyed(SurfaceHolder holder) { if (started && !isFinishing()) complete("INTERRUPTED: GPU 窗口 Surface 丢失"); }
        });
        frame.addView(output, new FrameLayout.LayoutParams(-1, -1));
        hud = new GpuHud(this, frame); hud.setVisible(true);
        FullscreenWindow.cancel(this, frame, () -> complete("CANCELLED: 用户停止 GPU 测试"));
        setContentView(frame);
    }
    private void complete(String raw) { if (hud != null) hud.setVisible(false); if (work != null) work.publish(raw); finish(); }
    @Override public void onBackPressed() { complete("CANCELLED: 用户返回停止 GPU 测试"); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) FullscreenWindow.immersive(this); }
    @Override protected void onStop() { if (hud != null) hud.setVisible(false); if (!isFinishing() && !PlatformCompat.changingConfigurations(this)) complete("INTERRUPTED: GPU 窗口退到后台"); super.onStop(); }
    @Override protected void onDestroy() { if (hud != null) hud.setVisible(false); super.onDestroy(); }
}
