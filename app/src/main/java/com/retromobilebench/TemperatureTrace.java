package com.retromobilebench;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One background sampling source per run; stages are windows into the same trace. */
final class TemperatureTrace {
    interface Reader { Point read(); }
    static final class Point {
        final long timestamp;
        final boolean plugged;
        final int temperature;
        final double batteryPercent;
        final int voltageMv;
        final String stage;

        Point(long timestamp, boolean plugged, int temperature) {
            this(timestamp, plugged, temperature, -1.0, -1, null);
        }

        Point(long timestamp, boolean plugged, int temperature, double batteryPercent, int voltageMv) {
            this(timestamp, plugged, temperature, batteryPercent, voltageMv, null);
        }

        Point(long timestamp, boolean plugged, int temperature, double batteryPercent, int voltageMv, String stage) {
            this.timestamp = timestamp;
            this.plugged = plugged;
            this.temperature = temperature;
            this.batteryPercent = batteryPercent;
            this.voltageMv = voltageMv;
            this.stage = stage;
        }
    }
    static boolean validTemperature(int value) { return value != 0 && value >= -200 && value <= 1000; }
    static boolean validBatteryPercent(double value) { return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0.0 && value <= 100.0; }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }
    private static final int INTERVAL_MS = 2000;
    private final Source source;
    private final boolean owner;
    private final int first;
    private JSONObject result;

    TemperatureTrace(Context context) {
        this(() -> {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return null;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            double pct = (level >= 0 && scale > 0) ? round1(level * 100.0 / scale) : -1.0;
            int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (voltage <= 0) voltage = -1;
            return new Point(System.currentTimeMillis(),
                    battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE),
                    pct, voltage, null);
        }, INTERVAL_MS);
    }

    // Short intervals and deterministic readings are used only by instrumentation.
    TemperatureTrace(Reader reader, int intervalMs) {
        this(new Source(reader, intervalMs), true);
    }
    private TemperatureTrace(Source source, boolean owner) {
        this.source = source;
        this.owner = owner;
        first = source.mark(true, false);
    }
    TemperatureTrace beginStage() { return new TemperatureTrace(source, false); }
    void setStage(String stageLabel) { source.activeStage = stageLabel; }

    JSONObject finish() throws JSONException {
        if (result != null) return result;
        Point[] points;
        try { points = source.copy(first, source.mark(false, owner)); }
        finally { if (owner) source.close(); }
        JSONArray samples = new JSONArray();
        Integer start = null, end = null, max = null, min = null;
        Double batStart = null, batEnd = null, batMin = null, batMax = null;
        Integer voltStart = null, voltEnd = null, voltMin = null, voltMax = null;
        boolean pluggedAny = false;
        for (Point point : points) {
            if (point.plugged) pluggedAny = true;
            JSONObject value = new JSONObject().put("timestamp_ms", point.timestamp).put("plugged", point.plugged);
            // Includes the legacy absent-sensor zero sentinel. Never invent a zero temperature.
            if (validTemperature(point.temperature)) {
                value.put("tenths_c", point.temperature);
                if (start == null) start = point.temperature;
                end = point.temperature;
                max = max == null ? end : Math.max(max, end);
                min = min == null ? end : Math.min(min, end);
            }
            if (validBatteryPercent(point.batteryPercent)) {
                double pct = round1(point.batteryPercent);
                value.put("battery_percent", pct);
                if (batStart == null) batStart = pct;
                batEnd = pct;
                batMin = batMin == null ? pct : Math.min(batMin, pct);
                batMax = batMax == null ? pct : Math.max(batMax, pct);
            }
            if (point.voltageMv > 0) {
                value.put("voltage_mv", point.voltageMv);
                if (voltStart == null) voltStart = point.voltageMv;
                voltEnd = point.voltageMv;
                voltMin = voltMin == null ? voltEnd : Math.min(voltMin, voltEnd);
                voltMax = voltMax == null ? voltEnd : Math.max(voltMax, voltEnd);
            }
            if (point.stage != null && point.stage.length() > 0) {
                value.put("stage", point.stage);
            }
            samples.put(value);
        }
        result = new JSONObject().put("sensor", "battery/ ACTION_BATTERY_CHANGED")
                .put("sample_interval_ms", source.intervalMs).put("samples", samples)
                .put("available", start != null)
                .put("battery_available", batStart != null)
                .put("plugged_any", pluggedAny);
        if (start != null) result.put("start_tenths_c", start).put("end_tenths_c", end)
                .put("sampled_max_tenths_c", max).put("sampled_min_tenths_c", min)
                .put("delta_tenths_c", end - start);
        if (batStart != null) {
            double delta = round1(batEnd - batStart);
            double consumed = round1(Math.max(0.0, batStart - batEnd));
            result.put("battery_start_percent", batStart)
                    .put("battery_end_percent", batEnd)
                    .put("battery_min_percent", batMin)
                    .put("battery_max_percent", batMax)
                    .put("battery_delta_percent", delta)
                    .put("battery_consumed_percent", consumed);
        }
        if (voltStart != null) {
            result.put("voltage_start_mv", voltStart)
                    .put("voltage_end_mv", voltEnd)
                    .put("voltage_min_mv", voltMin)
                    .put("voltage_max_mv", voltMax);
        }
        return result;
    }

    /** Also called from the runner's finally path if result construction fails. */
    void close() { if (owner) source.close(); }

    private static final class Source {
        final int intervalMs;
        final Reader reader;
        final ArrayList<Point> points = new ArrayList<>();
        final HandlerThread thread = new HandlerThread("rmb-temperature", Process.THREAD_PRIORITY_BACKGROUND);
        final Handler handler;
        volatile boolean stopped;
        volatile String activeStage;
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (stopped) return;
                sample();
                if (!stopped) handler.postDelayed(this, intervalMs);
            }
        };
        Source(Reader reader, int intervalMs) {
            if (intervalMs < 1) throw new IllegalArgumentException("sample interval");
            this.reader = reader;
            this.intervalMs = intervalMs;
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(tick, intervalMs);
        }
        void sample() {
            try {
                if (stopped) return;
                Point point = reader.read();
                if (point != null && !stopped) {
                    String stage = activeStage;
                    if (point.stage == null && stage != null) {
                        point = new Point(point.timestamp, point.plugged, point.temperature,
                                point.batteryPercent, point.voltageMv, stage);
                    }
                    synchronized (points) { points.add(point); }
                }
            } catch (Exception ignored) { /* Telemetry cannot fail a measurement. */ }
        }
        // Boundaries run on the same queue as ticks, outside all measured calls.
        int mark(boolean beginning, boolean closing) {
            AtomicInteger index = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(1);
            if (!stopped && handler.post(() -> {
                try {
                    synchronized (points) { index.set(points.size()); }
                    sample();
                    if (!beginning) synchronized (points) { index.set(points.size() - 1); }
                } finally {
                    if (closing) close();
                    ready.countDown();
                }
            })) {
                try {
                    if (ready.await(5, TimeUnit.SECONDS)) return index.get();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                close(); // A stuck sensor must not add another five-second wait at every boundary.
            }
            synchronized (points) { return beginning ? points.size() : points.size() - 1; }
        }
        Point[] copy(int first, int last) {
            synchronized (points) {
                int begin = Math.min(first, points.size());
                int end = Math.min(last + 1, points.size());
                return points.subList(begin, Math.max(begin, end)).toArray(new Point[0]);
            }
        }
        void close() {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            thread.quit();
        }
    }
}
