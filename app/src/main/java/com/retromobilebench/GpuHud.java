package com.retromobilebench;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Locale;

/** Low-frequency window-submission telemetry, separate from the benchmark result. */
final class GpuHud {
    static final int INTERVAL_MS = 1000;
    static final String VERSION = "window-swaps+battery-v1";
    private static WeakReference<GpuHud> active = new WeakReference<>(null); // Main thread only.
    private final Activity activity;
    private final TextView text;
    private boolean visible, registered;
    private double fps = Double.NaN;
    private int temperature = Integer.MIN_VALUE;
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { readBattery(intent); }
    };

    GpuHud(Activity activity, FrameLayout frame) {
        this.activity = activity;
        text = new TextView(activity); text.setTag("rmb_gpu_hud");
        text.setTextSize(14); text.setTextColor(Color.WHITE); text.setBackgroundColor(0xb0000000);
        int padding = Math.max(1, (int)(6 * activity.getResources().getDisplayMetrics().density + 0.5f));
        text.setPadding(padding, padding, padding, padding); text.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = params.topMargin = padding;
        frame.addView(text, params);
    }
    void setVisible(boolean value) {
        visible = value;
        if (!value) {
            if (active.get() == this) active.clear();
            if (registered) {
                registered = false;
                try { activity.unregisterReceiver(battery); } catch (IllegalArgumentException ignored) { }
            }
            text.setVisibility(View.GONE); return;
        }
        active = new WeakReference<>(this); fps = Double.NaN;
        if (!registered) temperature = Integer.MIN_VALUE;
        text.setVisibility(View.VISIBLE); render();
        if (!registered) {
            try {
                Intent sticky = activity.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                registered = true; readBattery(sticky);
            } catch (RuntimeException ignored) { /* Missing telemetry must not fail a benchmark. */ }
        }
    }
    private void readBattery(Intent intent) {
        if (!visible) return;
        temperature = intent == null ? Integer.MIN_VALUE : intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        render();
    }
    private void render() {
        String rate = Double.isNaN(fps) || Double.isInfinite(fps) || fps < 0 ? "等待渲染" : String.format(Locale.US, "%.1f", fps);
        String degrees = TemperatureTrace.validTemperature(temperature) ? String.format(Locale.US, "%.1f °C", temperature / 10.0) : "不可用";
        text.setText("FPS（提交） " + rate + "\n电池温度 " + degrees);
    }
    static boolean publish(double fps) {
        GpuHud hud = active.get();
        if (hud == null || !hud.visible) return false;
        hud.fps = fps; hud.render(); return true;
    }

    /** Reads the existing volatile EGL counter once a second; no work is added per frame. */
    static final class Publisher implements Runnable {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Messenger reply;
        private final boolean localWindow;
        private volatile boolean running;
        volatile int updates;
        private long lastTime, lastSwaps;
        Publisher(Messenger reply, boolean localWindow) { this.reply = reply; this.localWindow = localWindow; }
        void start() {
            lastTime = SystemClock.elapsedRealtime(); lastSwaps = JavaEglContext.successfulWindowSwaps;
            running = true; main.postDelayed(this, INTERVAL_MS);
        }
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime(), swaps = JavaEglContext.successfulWindowSwaps;
            if (swaps > 0 && swaps >= lastSwaps && now > lastTime) {
                double fps = (swaps - lastSwaps) * 1000.0 / (now - lastTime);
                if (localWindow) {
                    if (publish(fps)) updates++;
                } else {
                    Message value = Message.obtain(null, GpuService.STATS);
                    value.getData().putDouble("fps", fps);
                    try { reply.send(value); updates++; } catch (RemoteException ignored) { stop(); }
                }
            }
            lastTime = now; lastSwaps = swaps;
            if (running) main.postDelayed(this, INTERVAL_MS);
        }
        void stop() { running = false; main.removeCallbacks(this); }
    }
}
