package com.retromobilebench;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped serial worker; owns no Activity. UI methods are main-thread only. */
final class BenchmarkRunner {
    interface Listener { void onState(State state); }

    static final class State {
        final boolean busy;
        final String message;
        final String text;
        final String exportJson;

        State(boolean busy, String message, String text, String exportJson) {
            this.busy = busy;
            this.message = message;
            this.text = text;
            this.exportJson = exportJson;
        }
    }

    private static BenchmarkRunner instance;
    private static final long MIB = 1024L * 1024L;
    private static final long RESERVE_BYTES = 16 * MIB;
    private final Application context;
    private final ResultStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelRequested;
    private volatile boolean nativeReady;
    private Listener listener;
    private State state = new State(true, "正在读取本地记录", "", "");

    static synchronized BenchmarkRunner get(Context context) {
        if (instance == null) instance = new BenchmarkRunner((Application) context.getApplicationContext());
        return instance;
    }

    private BenchmarkRunner(Application context) {
        this.context = context;
        store = new ResultStore(context);
        worker.execute(() -> {
            try {
                store.recoverInterrupted();
                showHistory(1);
            } catch (Exception e) {
                publish(false, "读取记录失败", e.toString(), "");
            }
        });
    }

    void attach(Listener listener) {
        this.listener = listener;
        listener.onState(state);
    }

    void detach(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    boolean isBusy() { return state.busy; }

    void startStorage(boolean full) { start(BenchmarkSuite.STORAGE, full); }

    void startCpu(boolean full) { start(BenchmarkSuite.CPU, full); }

    private void start(BenchmarkSuite suite, boolean full) {
        if (state.busy) return;
        cancelRequested = false;
        update(new State(true, "准备" + suite.label, state.text, ""));
        worker.execute(() -> run(suite, full ? 3 : 1));
    }

    void licenses() {
        if (state.busy) return;
        update(new State(true, "正在读取开源许可", state.text, ""));
        worker.execute(() -> {
            try (Reader reader = new InputStreamReader(context.getAssets().open("licenses.txt"), "UTF-8")) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                publish(false, "开源许可", text.toString(), "");
            } catch (Exception e) { publish(false, "读取许可失败", e.toString(), ""); }
        });
    }

    void cancel() {
        cancelRequested = true;
        if (nativeReady) {
            NativeBridge.cancelStorage();
            NativeBridge.cancelCpu();
        }
    }

    void history() {
        if (state.busy) return;
        update(new State(true, "正在读取历史", state.text, state.exportJson));
        worker.execute(() -> {
            try { showHistory(20); }
            catch (Exception e) { publish(false, "读取记录失败", e.toString(), ""); }
        });
    }

    void probe() {
        if (state.busy) return;
        update(new State(true, "正在采集设备信息", state.text, ""));
        worker.execute(() -> {
            try {
                JSONObject device = DeviceSnapshot.collect(context);
                nativeReady = NativeLoader.load() != null;
                String text = device.toString(2) + "\n\n";
                text += nativeReady ? NativeBridge.probeReport() : String.valueOf(NativeLoader.error());
                publish(false, "设备探针完成", text, device.toString(2));
                Log.i("RMB", "PROBE_DONE");
            } catch (Exception | LinkageError e) {
                publish(false, "设备探针失败", e.toString(), "");
            }
        });
    }

    private void run(BenchmarkSuite suite, int repetitions) {
        JSONObject report = new JSONObject();
        long id = -1;
        String persistenceProblem = "";
        try {
            report.put("schema_version", 1);
            report.put("suite_version", suite.version);
            report.put("status", "running");
            report.put("started_ms", System.currentTimeMillis());
            report.put("repetitions", repetitions);
            report.put("repeat_pause_ms", repetitions > 1 ? 3000 : 0);
            JSONArray samples = new JSONArray();
            report.put("samples", samples);
            JSONObject device = DeviceSnapshot.collect(context);
            report.put("device", device);
            id = store.begin(report);
            report.put("id", id);
            nativeReady = NativeLoader.load() != null;
            if (!nativeReady) throw new IllegalStateException("原生库加载失败：" + NativeLoader.error());
            suite.resetCancellation();
            // Cancel may have arrived before loading the library or during reset.
            if (cancelRequested) cancel();
            int parameter = suite.parameter(device);
            String reason = suite.preflight(device, parameter);
            if (cancelRequested) {
                report.put("status", "cancelled");
            } else if (reason != null) {
                report.put("status", "skipped");
                report.put("message", reason);
            } else {
                for (int round = 0; round < repetitions; round++) {
                    if (cancelRequested) break;
                    JSONObject before = DeviceSnapshot.collect(context);
                    reason = suite.preflight(before, parameter);
                    if (reason != null) {
                        report.put("status", "skipped");
                        report.put("message", reason);
                        break;
                    }
                    publish(true, "正在运行" + suite.label + "，第 " + (round + 1) + " / " + repetitions + " 轮",
                            suite.progress(parameter), "");
                    JSONObject sample = new JSONObject(suite.run(context, parameter));
                    sample.put("conditions_start", before);
                    sample.put("conditions_end", DeviceSnapshot.collect(context));
                    samples.put(sample);
                    String status = sample.getString("status");
                    if (!"completed".equals(status)) {
                        report.put("status", status);
                        report.put("message", "本轮未完成，仅保留已完成的分项");
                    }
                    store.save(id, report); // Checkpoint each completed/partial round.
                    if (!"completed".equals(status)) break;
                    if (round + 1 < repetitions) {
                        publish(true, "轮次间休息 3 秒", BenchmarkReport.format(report), "");
                        long until = SystemClock.elapsedRealtime() + 3000;
                        while (!cancelRequested && SystemClock.elapsedRealtime() < until)
                            SystemClock.sleep(100);
                    }
                }
                if (cancelRequested) report.put("status", "cancelled");
                else if ("running".equals(report.optString("status")))
                    report.put("status", samples.length() == repetitions ? "completed" : "failed");
            }
            report.put("ended_ms", System.currentTimeMillis());
            BenchmarkReport.summarize(report);
        } catch (Exception | LinkageError e) {
            Log.e("RMB", suite.version + " run failed", e);
            try {
                report.put("status", "failed");
                report.put("ended_ms", System.currentTimeMillis());
                report.put("message", e.toString());
            } catch (JSONException ignored) { }
        }
        try {
            if (id >= 0) store.save(id, report);
            else persistenceProblem = "本次结果未写入数据库。";
        } catch (Exception e) {
            persistenceProblem = "保存失败，可先复制 JSON：" + e;
        }
        try {
            String message = persistenceProblem.isEmpty()
                    ? "结果已保存 · " + BenchmarkReport.statusLabel(report.optString("status")) : persistenceProblem;
            publish(false, message, BenchmarkReport.format(report), report.toString(2));
            Log.i("RMB", suite.name() + "_DONE status=" + report.optString("status") + " id=" + id);
        } catch (JSONException e) {
            publish(false, "结果格式错误", e.toString(), report.toString());
        }
    }

    static String preflight(JSONObject device, int fileMiB) {
        if (fileMiB < 4 || device.optLong("available_storage_bytes") < fileMiB * MIB + RESERVE_BYTES)
            return "剩余空间不足；至少保留 16 MiB 空间，测试文件至少需要 4 MiB。";
        return batteryPreflight(device);
    }

    static String cpuPreflight(JSONObject device) {
        if (device.optBoolean("low_memory")
                || (device.has("available_memory_bytes") && device.optLong("available_memory_bytes") < 32 * MIB))
            return "可用内存不足，请关闭其他应用后再运行 CPU 测试。";
        return batteryPreflight(device);
    }

    private static String batteryPreflight(JSONObject device) {
        if (device.optInt("battery_percent", 100) < 30 && !device.optBoolean("plugged"))
            return "电量低于 30%，请充电后再运行。";
        if (device.optInt("battery_temperature_tenths_c", 0) >= 450)
            return "电池温度已达 45°C，请降温后再运行。";
        return null;
    }

    private void showHistory(int limit) throws JSONException {
        JSONArray reports = store.latest(limit);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < reports.length(); i++) {
            if (i > 0) text.append("\n────────────────\n\n");
            text.append(BenchmarkReport.format(reports.getJSONObject(i)));
        }
        if (reports.length() == 0) text.append("尚无本地成绩。可开始单轮或三轮 CPU / 存储测试。\n"
                + "测试期间请保持应用在前台，屏幕会保持亮起。\n"
                + "切到后台会取消测试；旋转屏幕会保留进度。\n");
        String json = reports.length() == 1 ? reports.getJSONObject(0).toString(2)
                : reports.length() > 0 ? reports.toString(2) : "";
        publish(false, reports.length() == 0 ? "就绪" : "本地记录（最近 " + reports.length() + " 次）", text.toString(), json);
    }

    private void publish(boolean busy, String message, String text, String exportJson) {
        State next = new State(busy, message, text, exportJson);
        main.post(() -> update(next));
    }

    private void update(State next) {
        state = next;
        if (listener != null) listener.onState(next);
    }
}
