package com.retromobilebench;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Battery telemetry only. Never consulted by preflight or the measurement loop. */
final class TemperatureTrace {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray samples = new JSONArray();
    private volatile boolean stopped;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!stopped) { sample(); handler.postDelayed(this, 2000); } }
    };
    TemperatureTrace(Context context) { this.context = context; sample(); handler.postDelayed(tick, 2000); }
    private synchronized void sample() {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return;
            JSONObject point = new JSONObject().put("timestamp_ms", System.currentTimeMillis())
                    .put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                int value = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                // Reject implausible/missing values, including the common absent-sensor zero sentinel.
                if (value != 0 && value >= -200 && value <= 1000) point.put("tenths_c", value);
            }
            samples.put(point);
        } catch (Exception ignored) { /* Telemetry failure cannot fail a measurement. */ }
    }
    synchronized JSONObject finish() throws Exception {
        stopped = true; handler.removeCallbacks(tick); sample();
        JSONObject result = new JSONObject().put("sensor", "battery/ ACTION_BATTERY_CHANGED")
                .put("sample_interval_ms", 2000).put("samples", samples);
        Integer first = null, last = null, max = null;
        for (int i = 0; i < samples.length(); i++) {
            JSONObject p = samples.getJSONObject(i);
            if (!p.has("tenths_c")) continue;
            int value = p.getInt("tenths_c");
            if (first == null) first = value;
            last = value; max = max == null ? value : Math.max(max, value);
        }
        result.put("available", first != null);
        if (first != null) result.put("start_tenths_c", first).put("end_tenths_c", last)
                .put("sampled_max_tenths_c", max).put("delta_tenths_c", last - first);
        return result;
    }
}
