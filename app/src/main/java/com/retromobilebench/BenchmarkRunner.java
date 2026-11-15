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

import java.io.File;
import java.io.IOException;
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
        final String suiteVersion;
        final int stageIndex, completedStages, round, repetitions, savedRounds;

        State(boolean busy, String message, String text, String exportJson) {
            this(busy, message, text, exportJson, "", -1, 0, 0, 0, 0);
        }
        State(boolean busy, String message, String text, String exportJson, String suiteVersion,
              int stageIndex, int completedStages, int round, int repetitions, int savedRounds) {
            this.busy = busy;
            this.message = message;
            this.text = text;
            this.exportJson = exportJson;
            this.suiteVersion = suiteVersion;
            this.stageIndex = stageIndex;
            this.completedStages = completedStages;
            this.round = round;
            this.repetitions = repetitions;
            this.savedRounds = savedRounds;
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
    private volatile boolean interrupted;
    private volatile long cancelRequestedElapsed = -1;
    private long sessionStartedElapsed;
    private JSONObject currentSession;
    private long sessionRow = -1;
    private volatile boolean nativeReady;
    private Listener listener;
    private State state = new State(true, "正在读取本地记录", "", "");

    static synchronized BenchmarkRunner get(Context context) {
        if (instance == null) instance = new BenchmarkRunner((Application) context.getApplicationContext());
        return instance;
    }

    private BenchmarkRunner(Application context) { this(context, new ResultStore(context)); }

    // Package-private store injection exercises real measurement when history writes fail.
    BenchmarkRunner(Application context, ResultStore resultStore) {
        this.context = context;
        store = resultStore;
        worker.execute(() -> {
            try {
                cleanupSqliteFiles(context.getCacheDir());
                GpuDataPack.cleanup(context);
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
    // Main-thread snapshot lets diagnostics observe progress without replacing the UI listener.
    State currentState() { return state; }
    void closeAfterChecks() { worker.shutdown(); store.close(); }

    void startStorage(boolean full) { start(BenchmarkSuite.STORAGE, full); }

    void startCpu(boolean full) { start(BenchmarkSuite.CPU, full); }

    void start(BenchmarkSuite suite, boolean full) {
        if (state.busy || refuseUnsafeRestart()) return;
        cancelRequested = interrupted = false; cancelRequestedElapsed = -1; currentSession = null;
        update(new State(true, "准备" + suite.label, "", ""));
        worker.execute(() -> run(suite, full ? 3 : 1, null));
    }

    void startAll() {
        if (state.busy || refuseUnsafeRestart()) return;
        cancelRequested = interrupted = false; cancelRequestedElapsed = -1; currentSession = null;
        sessionStartedElapsed = SystemClock.elapsedRealtime();
        update(new State(true, "正在准备固定套件", "自动完成固定套件；CPU、图片、内存与存储三轮采样。请保持前台；GPU 配置时长 6 分 38 秒。", ""));
        worker.execute(() -> {
            TemperatureTrace trace = new TemperatureTrace(context);
            try {
                currentSession = new JSONObject().put("schema_version", 2).put("suite_version", BenchmarkSuite.SESSION_VERSION)
                        .put("session_id", java.util.UUID.randomUUID().toString()).put("status", "running")
                        .put("started_ms", System.currentTimeMillis()).put("results", new JSONArray())
                        .put("stage_pause_ms", 1000).put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
                sessionRow = -1; checkpointSession();
                GpuClient.resetCancellation(); MediaClient.resetCancellation(); GpuDataPack.beginSession();
                long prepareDeadline = SystemClock.elapsedRealtime() + 180000;
                try { GpuDataPack.ensure(context, "gl_egypt_off", () -> cancelRequested || SystemClock.elapsedRealtime() >= prepareDeadline); }
                catch (Exception e) { currentSession.put("preparation_warning", e.toString()); }
                try { MediaCorpus.prepare(context, () -> cancelRequested || SystemClock.elapsedRealtime() >= prepareDeadline); }
                catch (Exception e) { currentSession.put("media_preparation_warning", e.toString()); }
                currentSession.put("memory_preparation", MemoryPreparation.collect(context));
                currentSession.put("prepared_ms", System.currentTimeMillis());
                JSONArray results = currentSession.getJSONArray("results");
                for (int i = 0; i < BenchmarkSuite.DEFAULT.length && !cancelRequested; i++) {
                    BenchmarkSuite suite = BenchmarkSuite.DEFAULT[i];
                    currentSession.put("active_suite", suite.version);
                    checkpointSession();
                    results.put(run(suite, suite.repetitions(), trace));
                    currentSession.remove("active_report"); checkpointSession();
                    if (i + 1 < BenchmarkSuite.DEFAULT.length && !cancelRequested) {
                        long until = SystemClock.elapsedRealtime() + 1000;
                        while (!cancelRequested && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                    }
                }
                boolean complete = results.length() == BenchmarkSuite.DEFAULT.length;
                for (int i = 0; i < results.length(); i++) complete &= "completed".equals(results.getJSONObject(i).optString("status"));
                currentSession.put("status", WebClient.cleanupFailed() ? "failed" : interrupted ? "interrupted" : cancelRequested ? "cancelled" : complete ? "completed" : "partial");
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                try { if (currentSession != null) currentSession.put("status", "failed").put("message", e.toString()); }
                catch (JSONException ignored) { }
            } finally {
                try {
                    if (currentSession != null) {
                        currentSession.put("ended_ms", System.currentTimeMillis()).put("temperature", trace.finish());
                        currentSession.put("wall_ms", SystemClock.elapsedRealtime() - sessionStartedElapsed);
                        if (cancelRequestedElapsed >= 0) currentSession
                                .put("cancel_request_elapsed_ms", cancelRequestedElapsed - sessionStartedElapsed)
                                .put("cancel_stop_latency_ms", SystemClock.elapsedRealtime() - cancelRequestedElapsed);
                        currentSession.remove("active_suite"); checkpointSession();
                        publish(false, "本次测试 · " + BenchmarkReport.statusLabel(currentSession.optString("status")),
                                BenchmarkReport.format(currentSession), currentSession.toString(2));
                    }
                } catch (Exception e) { publish(false, "结果处理失败", e.toString(), currentSession == null ? "" : currentSession.toString()); }
                trace.close();
                DisplaySurface.hide();
            }
        });
    }

    private boolean refuseUnsafeRestart() {
        if (!WebClient.cleanupFailed()) return false;
        publish(false, "工作进程回收未确认，请重启应用", state.text, state.exportJson);
        return true;
    }

    private boolean checkpointSession() {
        try {
            if (sessionRow < 0) sessionRow = store.begin(currentSession);
            else store.save(sessionRow, currentSession);
            return true;
        } catch (Exception e) {
            try { currentSession.put("persistence_warning", "保存失败，内存结果仍可导出：" + e); }
            catch (JSONException ignored) { }
            return false;
        }
    }

    static void surfaceInterrupted() { if (instance != null && instance.isBusy()) instance.interrupt(); }
    void interrupt() { interrupted = true; cancel(); }

    void licenses() {
        if (state.busy) return;
        update(new State(true, "正在读取开源许可", state.text, ""));
        worker.execute(() -> {
            try {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[2048];
                for (String asset : new String[]{"licenses.txt", "benchmark-notices.txt", "xiph-notices.txt"}) {
                    try (Reader reader = new InputStreamReader(context.getAssets().open(asset), "UTF-8")) {
                        int count;
                        while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                    }
                    text.append("\n\n");
                }
                publish(false, "开源许可", text.toString(), "");
            } catch (Exception e) { publish(false, "读取许可失败", e.toString(), ""); }
        });
    }

    void cancel() {
        if (!cancelRequested) cancelRequestedElapsed = SystemClock.elapsedRealtime();
        cancelRequested = true;
        Runnable feedback = () -> {
            if (state.busy) update(new State(true, "正在停止：等待当前安全边界", state.text, "",
                    state.suiteVersion, state.stageIndex, state.completedStages, state.round, state.repetitions, state.savedRounds));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) feedback.run(); else main.post(feedback);
        MediaClient.cancel();
        GpuClient.cancel();
        WebClient.cancel();
        if (nativeReady) {
            NativeBridge.cancelStorage();
            NativeBridge.cancelCpu();
            NativeBridge.cancelZstd();
            NativeBridge.cancelSqlite();
            NativeBridge.cancelCoremark();
            NativeBridge.cancelScimark();
            NativeBridge.cancelMemory();
            if (MediaNative.loaded()) NativeBridge.cancelMedia();
        }
    }

    void importGpu(android.net.Uri uri) {
        if (state.busy) return;
        cancelRequested = false;
        update(new State(true, "正在校验并导入 GPU 数据", "可取消；原有数据包保留到新包校验完成。", ""));
        worker.execute(() -> {
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取所选文件");
                File installed = GpuDataPack.install(new File(context.getFilesDir(), "gpu-packs"), input, () -> cancelRequested);
                publish(false, "GPU 数据导入完成", GpuDataPack.manifest(installed).getJSONArray("tests").toString(), "");
            } catch (Exception error) { publish(false, cancelRequested ? "导入已取消" : "导入失败", error.toString(), ""); }
        });
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
                JSONObject device = CpuTopology.enrich(DeviceSnapshot.collect(context));
                nativeReady = NativeLoader.load() != null;
                String text = device.toString(2) + "\n\n";
                text += nativeReady ? NativeBridge.probeReport() : String.valueOf(NativeLoader.error());
                publish(false, "设备探针完成", text, device.toString(2));
                Log.i("RMB", "PROBE_DONE");
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                publish(false, "设备探针失败", e.toString(), "");
            }
        });
    }

    private JSONObject run(BenchmarkSuite suite, int repetitions, TemperatureTrace sessionTrace) {
        boolean batch = sessionTrace != null;
        TemperatureTrace trace = batch ? sessionTrace.beginStage() : new TemperatureTrace(context);
        JSONObject report = new JSONObject();
        int savedRounds = 0;
        long id = -1;
        String persistenceProblem = "";
        if (suite == BenchmarkSuite.WEB) WebClient.beginStage();
        try {
            if (WebClient.cleanupFailed()) throw new IllegalStateException("Web 进程回收未确认，禁止后续负载；请重启应用");
            if (!batch || suite.isGpu()) report.put("memory_preparation", MemoryPreparation.collect(context));
            report.put("schema_version", 1);
            report.put("suite_version", suite.version);
            report.put("status", "running");
            report.put("started_ms", System.currentTimeMillis());
            report.put("repetitions", repetitions);
            report.put("repeat_pause_ms", repetitions > 1 ? 1000 : 0);
            JSONArray samples = new JSONArray();
            report.put("samples", samples);
            if (batch) { currentSession.put("active_report", report); checkpointSession(); }
            JSONObject device = conditions(suite);
            report.put("device", device);
            if (!batch) {
                try { id = store.begin(report); report.put("id", id); }
                catch (Exception e) { persistenceProblem = "创建记录失败，结果保留在内存：" + e; }
            }
            if (suite != BenchmarkSuite.WEB) {
                nativeReady = NativeLoader.load() != null;
                if (!nativeReady) throw new IllegalStateException("原生库加载失败：" + NativeLoader.error());
            }
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
                    JSONObject before = conditions(suite);
                    reason = suite.preflight(before, parameter);
                    if (reason != null) {
                        report.put("status", "skipped");
                        report.put("message", reason);
                        break;
                    }
                    publishProgress(suite, round + 1, repetitions, savedRounds,
                            "正在运行" + suite.label + "，第 " + (round + 1) + " / " + repetitions + " 轮");
                    JSONObject sample = new JSONObject(suite.run(context, parameter));
                    if (suite != BenchmarkSuite.WEB) {
                        sample.put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
                        sample.put("native_library", NativeLoader.load());
                        sample.put("native_abi", before.getString("abi"));
                    }
                    sample.put("conditions_start", before);
                    sample.put("conditions_end", conditions(suite));
                    samples.put(sample);
                    if (sample.optBoolean("unsafe_cleanup")) cancel();
                    String status = sample.getString("status");
                    if (!"completed".equals(status)) {
                        report.put("status", status);
                        report.put("message", "本轮未完成，仅保留已完成的分项");
                    }
                    if (parameter == 1 && (suite.isCompression() || suite == BenchmarkSuite.COREMARK || suite == BenchmarkSuite.CRYPTO || suite == BenchmarkSuite.MEMORY)) {
                        for (String metric : suite.metrics) if (metric.startsWith("parallel_")) sample.remove(metric);
                        sample.put("parallel_omitted", "single_core");
                    }
                    if (interrupted) report.put("status", "interrupted");
                    if (batch) {
                        if (checkpointSession()) savedRounds = samples.length();
                    } else if (id >= 0) try { store.save(id, report); savedRounds = samples.length(); }
                    catch (Exception e) { persistenceProblem = "保存失败，结果保留在内存：" + e; }
                    publishProgress(suite, round + 1, repetitions, savedRounds,
                            round + 1 < repetitions && "completed".equals(status) ? "轮次间间隔 1 秒" : "本轮结束");
                    if (!"completed".equals(status)) break;
                    if (round + 1 < repetitions) {
                        long until = SystemClock.elapsedRealtime() + 1000;
                        while (!cancelRequested && SystemClock.elapsedRealtime() < until)
                            SystemClock.sleep(100);
                    }
                }
                if (cancelRequested) report.put("status", WebClient.cleanupFailed() ? "failed" : interrupted ? "interrupted" : "cancelled");
                else if ("running".equals(report.optString("status")))
                    report.put("status", samples.length() == repetitions ? "completed" : "failed");
            }
            report.put("ended_ms", System.currentTimeMillis());
            BenchmarkReport.summarize(report);
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            Log.e("RMB", suite.version + " run failed", e);
            try {
                report.put("status", "failed");
                report.put("ended_ms", System.currentTimeMillis());
                report.put("message", e.toString());
            } catch (JSONException ignored) { }
        }
        if (suite == BenchmarkSuite.WEB) WebClient.endStage();
        try {
            report.put("temperature", trace.finish());
            if (!batch && id >= 0) store.save(id, report);
            else if (!batch && persistenceProblem.length() == 0) persistenceProblem = "本次结果未写入数据库。";
            if (persistenceProblem.length() > 0) report.put("persistence_warning", persistenceProblem);
        } catch (Exception e) {
            persistenceProblem = "保存失败，可先复制 JSON：" + e;
            try { report.put("persistence_warning", persistenceProblem); } catch (JSONException ignored) { }
        } finally { trace.close(); }
        try {
            String message = persistenceProblem.length() == 0
                    ? "结果已保存 · " + BenchmarkReport.statusLabel(report.optString("status")) : persistenceProblem;
            if (!batch) publish(false, message, BenchmarkReport.format(report), report.toString(2));
            Log.i("RMB", suite.name() + "_DONE status=" + report.optString("status") + " id=" + id);
        } catch (JSONException e) {
            if (!batch) publish(false, "结果格式错误", e.toString(), report.toString());
        }
        return report;
    }

    private JSONObject conditions(BenchmarkSuite suite) throws Exception {
        return suite == BenchmarkSuite.WEB ? DeviceSnapshot.collect(context, false) : CpuTopology.enrich(DeviceSnapshot.collect(context));
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

    static String gpuPreflight(JSONObject device, int requiredMiB) {
        if (device.optBoolean("low_memory") || device.optLong("available_memory_bytes") < requiredMiB * MIB)
            return "GPU 测试需要至少 " + requiredMiB + " MiB 可用内存，请关闭其他应用后重试。";
        return batteryPreflight(device);
    }

    static String memoryPreflight(JSONObject device) {
        if (device.optBoolean("low_memory")
                || (device.has("available_memory_bytes") && device.optLong("available_memory_bytes") < 64 * MIB))
            return "内存测试需要至少 64 MiB 可用内存；请关闭其他应用后重试。";
        return batteryPreflight(device);
    }

    static String sqlitePreflight(JSONObject device) {
        if (device.optLong("available_storage_bytes") < 20 * MIB)
            return "SQLite 测试需要至少 20 MiB 可用空间（包含 16 MiB 预留）。";
        return cpuPreflight(device);
    }

    /** Only our fixed benchmark files; never the user's history database. Worker only. */
    static void cleanupSqliteFiles(File directory) throws IOException {
        for (String suffix : new String[]{"-journal", "-wal", "-shm", ""}) {
            File file = new File(directory, "rmb-sqlite-bench.db" + suffix);
            if (file.exists() && !file.delete()) throw new IOException("无法清理 SQLite 测试文件：" + file);
        }
    }

    private static String batteryPreflight(JSONObject device) {
        if (device.optInt("battery_percent", 100) < 30 && !device.optBoolean("plugged"))
            return "电量低于 30%，请充电后再运行。";
        return null;
    }

    private void showHistory(int limit) throws JSONException {
        JSONArray reports = store.latest(limit);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < reports.length(); i++) {
            if (i > 0) text.append("\n────────────────\n\n");
            text.append(BenchmarkReport.format(reports.getJSONObject(i)));
        }
        if (reports.length() == 0) text.append("尚无本地成绩。点击开始，自动执行固定套件；需多轮的项目自动采样三轮。\n"
                + "测试期间请保持应用在前台，屏幕会保持亮起。\n"
                + "切到后台会取消测试；旋转屏幕会保留进度。\n");
        String json = reports.length() == 1 ? reports.getJSONObject(0).toString(2)
                : reports.length() > 0 ? reports.toString(2) : "";
        publish(false, reports.length() == 0 ? "就绪" : "本地记录（最近 " + reports.length() + " 次）", text.toString(), json);
    }

    private void publishProgress(BenchmarkSuite suite, int round, int repetitions, int savedRounds, String message) {
        JSONArray completed = currentSession == null ? null : currentSession.optJSONArray("results");
        int count = completed == null ? 0 : completed.length();
        String text = (completed == null ? "" : "已完成 " + count + " / " + BenchmarkSuite.DEFAULT.length + " 个阶段\n")
                + "当前阶段已保存 " + savedRounds + " / " + repetitions + " 轮";
        State next = new State(true, message, text, "", suite.version, completed == null ? -1 : count,
                count, round, repetitions, savedRounds);
        main.post(() -> update(cancelRequested ? new State(true, "正在停止：等待当前安全边界", next.text, "",
                next.suiteVersion, next.stageIndex, next.completedStages, next.round, next.repetitions, next.savedRounds) : next));
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
