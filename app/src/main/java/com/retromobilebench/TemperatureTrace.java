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
        Point(long timestamp, boolean plugged, int temperature) {
            this.timestamp = timestamp;
            this.plugged = plugged;
            this.temperature = temperature;
        }
    }
    static boolean validTemperature(int value) { return value != 0 && value >= -200 && value <= 1000; }
    private static final int INTERVAL_MS = 2000;
    private final Source source;
    private final boolean owner;
    private final int first;
    private JSONObject result;

    TemperatureTrace(Context context) {
        this(() -> {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return battery == null ? null : new Point(System.currentTimeMillis(),
                    battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
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

    JSONObject finish() throws JSONException {
        if (result != null) return result;
        Point[] points;
        try { points = source.copy(first, source.mark(false, owner)); }
        finally { if (owner) source.close(); }
        JSONArray samples = new JSONArray();
        Integer start = null, end = null, max = null;
        for (Point point : points) {
            JSONObject value = new JSONObject().put("timestamp_ms", point.timestamp).put("plugged", point.plugged);
            // Includes the legacy absent-sensor zero sentinel. Never invent a zero temperature.
            if (validTemperature(point.temperature)) {
                value.put("tenths_c", point.temperature);
                if (start == null) start = point.temperature;
                end = point.temperature;
                max = max == null ? end : Math.max(max, end);
            }
            samples.put(value);
        }
        result = new JSONObject().put("sensor", "battery/ ACTION_BATTERY_CHANGED")
                .put("sample_interval_ms", source.intervalMs).put("samples", samples).put("available", start != null);
        if (start != null) result.put("start_tenths_c", start).put("end_tenths_c", end)
                .put("sampled_max_tenths_c", max).put("delta_tenths_c", end - start);
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
                if (point != null && !stopped) synchronized (points) { points.add(point); }
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
