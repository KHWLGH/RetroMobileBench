package com.retromobilebench;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Dedicated landscape window for parcelled GPU/video Surfaces on newer systems. */
public final class DisplayActivity extends Activity implements DisplaySurface.Host {
    private SurfaceView output;
    private GpuHud hud;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); FullscreenWindow.configure(this);
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK);
        output = new SurfaceView(this);
        output.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { }
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                DisplaySurface.changed(DisplayActivity.this, holder.getSurface(), width, height);
            }
            public void surfaceDestroyed(SurfaceHolder holder) { DisplaySurface.lost(DisplayActivity.this); }
        });
        frame.addView(output, new FrameLayout.LayoutParams(-1, -1));
        hud = new GpuHud(this, frame);
        FullscreenWindow.cancel(this, frame, this::cancel);
        setContentView(frame);
        DisplaySurface.refreshHz = getWindowManager().getDefaultDisplay().getRefreshRate();
        if (!DisplaySurface.attach(this, getIntent().getLongExtra("request", -1))) finish();
    }
    @Override public void gpuHud(boolean visible) { hud.setVisible(visible); }
    @Override public void display(boolean visible) {
        if (!visible) { hud.setVisible(false); output.setVisibility(View.GONE); finish(); return; }
        if (isFinishing()) return;
        output.setVisibility(View.VISIBLE);
        SurfaceHolder holder = output.getHolder();
        if (holder.getSurface().isValid()) {
            android.graphics.Rect frame = holder.getSurfaceFrame();
            DisplaySurface.changed(this, holder.getSurface(), frame.width(), frame.height());
        }
    }
    @Override public boolean ready() {
        return !isFinishing() && hasWindowFocus() && output.getWidth() > output.getHeight()
                && output.getWidth() == getWindow().getDecorView().getWidth()
                && output.getHeight() == getWindow().getDecorView().getHeight();
    }
    private void cancel() {
        BenchmarkRunner.get(this).cancel(); GpuClient.cancel(); MediaClient.cancel();
        DisplaySurface.hide(); finish();
    }
    @Override public void onBackPressed() { cancel(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused) FullscreenWindow.immersive(this);
    }
    @Override protected void onStop() {
        if (!isFinishing() && !PlatformCompat.changingConfigurations(this)) {
            BenchmarkRunner.surfaceInterrupted(); GpuClient.cancel(); MediaClient.cancel(); DisplaySurface.hide(); finish();
        }
        if (hud != null) hud.setVisible(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override protected void onDestroy() { if (hud != null) hud.setVisible(false); DisplaySurface.detach(this); super.onDestroy(); }
}
