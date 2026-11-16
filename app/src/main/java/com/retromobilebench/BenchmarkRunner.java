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
        final JSONObject latestReport;
        final String suiteVersion;
        final int stageIndex, completedStages, round, repetitions, savedRounds;

        State(boolean busy, String message, String text, String exportJson) {
            this(busy, message, text, exportJson, null, "", -1, 0, 0, 0, 0);
        }
        State(boolean busy, String message, String text, String exportJson, String suiteVersion,
              int stageIndex, int completedStages, int round, int repetitions, int savedRounds) {
            this(busy, message, text, exportJson, null, suiteVersion, stageIndex, completedStages, round, repetitions, savedRounds);
        }
        State(boolean busy, String message, String text, String exportJson, JSONObject latestReport, String suiteVersion,
              int stageIndex, int completedStages, int round, int repetitions, int savedRounds) {
            this.busy = busy;
            this.message = message;
            this.text = text;
            this.exportJson = exportJson;
            this.latestReport = latestReport;
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
    private State state;

    static synchronized BenchmarkRunner get(Context context) {
        if (instance == null) instance = new BenchmarkRunner((Application) context.getApplicationContext());
        return instance;
    }

    private BenchmarkRunner(Application context) { this(context, new ResultStore(context)); }

    // Package-private store injection exercises real measurement when history writes fail.
    BenchmarkRunner(Application context, ResultStore resultStore) {
        this.context = context;
        I18n.init(context);
        this.state = new State(true, I18n.t("正在读取本地记录", "Loading local history"), "", "");
        store = resultStore;
        worker.execute(() -> {
            try {
                cleanupSqliteFiles(context.getCacheDir());
                GpuDataPack.cleanup(context);
                store.recoverInterrupted();
                showHistory();
            } catch (Exception e) {
                publish(false, I18n.t("读取记录失败", "Failed to load history"), e.toString(), "");
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

    void start(BenchmarkSuite suite, boolean full) {
        if (state.busy || refuseUnsafeRestart()) return;
        cancelRequested = interrupted = false; cancelRequestedElapsed = -1; currentSession = null;
        update(new State(true, I18n.t("准备" + suite.label(), "Preparing " + suite.label()), "", ""));
        worker.execute(() -> run(suite, full ? 3 : 1, null));
    }

    void startAll() {
        if (state.busy || refuseUnsafeRestart()) return;
        cancelRequested = interrupted = false; cancelRequestedElapsed = -1; currentSession = null;
        sessionStartedElapsed = SystemClock.elapsedRealtime();
        update(new State(true,
                I18n.t("正在准备固定套件", "Preparing Fixed Suite"),
                I18n.t("自动完成 12 项固定套件；CPU、内存、存储、SQLite 与 Web 三轮采样。请保持前台；GPU 配置时长 6 分 38 秒。",
                        "Running 12 fixed stages automatically; 3 rounds for CPU, Memory, Storage, SQLite & Web. Keep foreground; configured GPU time is 6m 38s."),
                ""));
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
                    trace.setStage(suite.label());
                    results.put(run(suite, suite.repetitions(), trace));
                    trace.setStage(I18n.t("阶段间隔", "Stage pause"));
                    currentSession.remove("active_report"); checkpointSession();
                    if (i + 1 < BenchmarkSuite.DEFAULT.length && !cancelRequested) {
                        long until = SystemClock.elapsedRealtime() + 1000;
                        while (!cancelRequested && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                    }
                }
                trace.setStage(null);
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
                        publish(false,
                                I18n.t("本次测试 · ", "Run · ") + BenchmarkReport.statusLabel(currentSession.optString("status")),
                                "", currentSession.toString(), currentSession);
                    }
                } catch (Exception e) {
                    publish(false, I18n.t("结果处理失败", "Result processing failed"), e.toString(),
                            currentSession == null ? "" : currentSession.toString(), currentSession);
                }
                trace.close();
                DisplaySurface.hide();
            }
        });
    }

    private boolean refuseUnsafeRestart() {
        if (!WebClient.cleanupFailed()) return false;
        publish(false, I18n.t("工作进程回收未确认，请重启应用", "Worker cleanup unconfirmed; please restart the app"),
                state.text, state.exportJson, state.latestReport);
        return true;
    }

    private boolean checkpointSession() {
        try {
            if (sessionRow < 0) sessionRow = store.begin(currentSession);
            else store.save(sessionRow, currentSession);
            return true;
        } catch (Exception e) {
            try {
                currentSession.put("persistence_warning",
                        I18n.t("保存失败，内存结果仍可导出：", "Save failed; in-memory results can still be exported: ") + e);
            } catch (JSONException ignored) { }
            return false;
        }
    }

    static void surfaceInterrupted() { if (instance != null && instance.isBusy()) instance.interrupt(); }
    void interrupt() { interrupted = true; cancel(); }

    void cancel() {
        if (!cancelRequested) cancelRequestedElapsed = SystemClock.elapsedRealtime();
        cancelRequested = true;
        Runnable feedback = () -> {
            if (state.busy) update(new State(true,
                    I18n.t("正在停止：等待当前安全边界", "Stopping: waiting for safe boundary"), state.text, "",
                    state.suiteVersion, state.stageIndex, state.completedStages, state.round, state.repetitions, state.savedRounds));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) feedback.run(); else main.post(feedback);
        MediaClient.cancel();
        GpuClient.cancel();
        WebClient.cancel();
        if (nativeReady) {
            NativeBridge.cancelStorage();
            NativeBridge.cancelSqlite();
            NativeBridge.cancelCoremark();
            NativeBridge.cancelScimark();
            NativeBridge.cancelMemory();
            if (MediaNative.loaded()) NativeBridge.cancelMedia();
        }
    }

    interface DeviceCallback { void onResult(JSONObject device, String probeText, String error); }
    interface HistoryCallback { void onResult(JSONArray history, String error); }

    void loadDeviceSnapshot(boolean includeNative, DeviceCallback callback) {
        if (state.busy) return;
        worker.execute(() -> {
            try {
                JSONObject device = CpuTopology.enrich(DeviceSnapshot.collect(context, includeNative), includeNative);
                device.put("ui_extra", DeviceHardwareExtra.collect(context));
                String text = "";
                if (includeNative) {
                    nativeReady = NativeLoader.load() != null;
                    text = nativeReady ? NativeBridge.probeReport() : String.valueOf(NativeLoader.error());
                }
                final String probeText = text;
                main.post(() -> callback.onResult(device, probeText, null));
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                main.post(() -> callback.onResult(null, null, e.toString()));
            }
        });
    }

    void loadAllHistory(HistoryCallback callback) {
        if (state.busy) return;
        worker.execute(() -> {
            try {
                JSONArray list = store.allRuns();
                main.post(() -> callback.onResult(list, null));
            } catch (Exception e) {
                main.post(() -> callback.onResult(null, e.toString()));
            }
        });
    }

    void deleteHistoryRun(long id, HistoryCallback callback) {
        if (state.busy) return;
        worker.execute(() -> {
            try {
                store.deleteById(id);
                JSONArray list = store.allRuns();
                showHistory();
                main.post(() -> callback.onResult(list, null));
            } catch (Exception e) {
                main.post(() -> callback.onResult(null, e.toString()));
            }
        });
    }

    void clearAllHistory(HistoryCallback callback) {
        if (state.busy) return;
        worker.execute(() -> {
            try {
                store.deleteAll();
                JSONArray list = store.allRuns();
                showHistory();
                main.post(() -> callback.onResult(list, null));
            } catch (Exception e) {
                main.post(() -> callback.onResult(null, e.toString()));
            }
        });
    }

    private JSONObject run(BenchmarkSuite suite, int repetitions, TemperatureTrace sessionTrace) {
        boolean batch = sessionTrace != null;
        TemperatureTrace trace = batch ? sessionTrace.beginStage() : new TemperatureTrace(context);
        if (!batch) trace.setStage(suite.label());
        JSONObject report = new JSONObject();
        int savedRounds = 0;
        long id = -1;
        String persistenceProblem = "";
        if (suite == BenchmarkSuite.WEB) WebClient.beginStage();
        try {
            if (WebClient.cleanupFailed()) {
                throw new IllegalStateException(I18n.t(
                        "Web 进程回收未确认，禁止后续负载；请重启应用",
                        "Web worker cleanup unconfirmed; blocking further runs—please restart the app"));
            }
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
                catch (Exception e) {
                    persistenceProblem = I18n.t("创建记录失败，结果保留在内存：", "Create record failed; keeping results in memory: ") + e;
                }
            }
            if (suite != BenchmarkSuite.WEB) {
                nativeReady = NativeLoader.load() != null;
                if (!nativeReady) {
                    throw new IllegalStateException(I18n.t("原生库加载失败：", "Native library load failed: ") + NativeLoader.error());
                }
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
                            I18n.t("正在运行" + suite.label() + "，第 " + (round + 1) + " / " + repetitions + " 轮",
                                    "Running " + suite.label() + " · Round " + (round + 1) + " / " + repetitions));
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
                        report.put("message", I18n.t("本轮未完成，仅保留已完成的分项", "Round incomplete; keeping completed sub-metrics only"));
                    }
                    if (parameter == 1 && (suite == BenchmarkSuite.COREMARK || suite == BenchmarkSuite.SCIMARK || suite == BenchmarkSuite.MEMORY)) {
                        for (String metric : suite.metrics) if (metric.startsWith("parallel_")) sample.remove(metric);
                        sample.put("parallel_omitted", "single_core");
                    }
                    if (interrupted) report.put("status", "interrupted");
                    if (batch) {
                        if (checkpointSession()) savedRounds = samples.length();
                    } else if (id >= 0) try { store.save(id, report); savedRounds = samples.length(); }
                    catch (Exception e) {
                        persistenceProblem = I18n.t("保存失败，结果保留在内存：", "Save failed; keeping results in memory: ") + e;
                    }
                    publishProgress(suite, round + 1, repetitions, savedRounds,
                            round + 1 < repetitions && "completed".equals(status)
                                    ? I18n.t("轮次间间隔 1 秒", "1s pause between rounds")
                                    : I18n.t("本轮结束", "Round finished"));
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
            else if (!batch && persistenceProblem.length() == 0) {
                persistenceProblem = I18n.t("本次结果未写入数据库。", "Result was not written to the database.");
            }
            if (persistenceProblem.length() > 0) report.put("persistence_warning", persistenceProblem);
        } catch (Exception e) {
            persistenceProblem = I18n.t("保存失败，可先导出数据：", "Save failed; export data if needed: ") + e;
            try { report.put("persistence_warning", persistenceProblem); } catch (JSONException ignored) { }
        } finally { trace.close(); }
        String message = persistenceProblem.length() == 0
                ? I18n.t("结果已保存 · ", "Saved · ") + BenchmarkReport.statusLabel(report.optString("status"))
                : persistenceProblem;
        if (!batch) publish(false, message, "", report.toString(), report);
        Log.i("RMB", suite.name() + "_DONE status=" + report.optString("status") + " id=" + id);
        return report;
    }

    private JSONObject conditions(BenchmarkSuite suite) throws Exception {
        return suite == BenchmarkSuite.WEB ? DeviceSnapshot.collect(context, false) : CpuTopology.enrich(DeviceSnapshot.collect(context));
    }

    static String preflight(JSONObject device, int fileMiB) {
        if (fileMiB < 4 || device.optLong("available_storage_bytes") < fileMiB * MIB + RESERVE_BYTES)
            return I18n.t("剩余空间不足；至少保留 16 MiB 空间，测试文件至少需要 4 MiB。",
                    "Insufficient storage; at least 16 MiB reserve and 4 MiB test file space required.");
        return batteryPreflight(device);
    }

    static String cpuPreflight(JSONObject device) {
        if (device.optBoolean("low_memory")
                || (device.has("available_memory_bytes") && device.optLong("available_memory_bytes") < 32 * MIB))
            return I18n.t("可用内存不足，请关闭其他应用后再运行 CPU 测试。",
                    "Low available memory; close other apps before running CPU tests.");
        return batteryPreflight(device);
    }

    static String gpuPreflight(JSONObject device, int requiredMiB) {
        if (device.optBoolean("low_memory") || device.optLong("available_memory_bytes") < requiredMiB * MIB)
            return I18n.t("GPU 测试需要至少 " + requiredMiB + " MiB 可用内存，请关闭其他应用后重试。",
                    "GPU test requires at least " + requiredMiB + " MiB available memory; close other apps and retry.");
        return batteryPreflight(device);
    }

    static String memoryPreflight(JSONObject device) {
        if (device.optBoolean("low_memory")
                || (device.has("available_memory_bytes") && device.optLong("available_memory_bytes") < 64 * MIB))
            return I18n.t("内存测试需要至少 64 MiB 可用内存；请关闭其他应用后重试。",
                    "Memory test requires at least 64 MiB available memory; close other apps and retry.");
        return batteryPreflight(device);
    }

    static String sqlitePreflight(JSONObject device) {
        if (device.optLong("available_storage_bytes") < 20 * MIB)
            return I18n.t("SQLite 测试需要至少 20 MiB 可用空间（包含 16 MiB 预留）。",
                    "SQLite test requires at least 20 MiB available storage (including 16 MiB reserve).");
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
            return I18n.t("电量低于 30%，请充电后再运行。", "Battery below 30%; please connect charger before running.");
        return null;
    }

    private void showHistory() throws JSONException {
        JSONArray reports = store.latest(1);
        JSONObject latest = reports.length() > 0 ? reports.optJSONObject(0) : null;
        publish(false,
                reports.length() == 0
                        ? I18n.t("就绪", "Ready")
                        : I18n.t("本地记录（最近 " + reports.length() + " 次）", "Local history (latest " + reports.length() + ")"),
                "", "", latest);
    }

    private void publishProgress(BenchmarkSuite suite, int round, int repetitions, int savedRounds, String message) {
        JSONArray completed = currentSession == null ? null : currentSession.optJSONArray("results");
        int count = completed == null ? 0 : completed.length();
        String text = (completed == null ? "" : I18n.t("已完成 " + count + " / " + BenchmarkSuite.DEFAULT.length + " 个阶段\n",
                "Completed " + count + " / " + BenchmarkSuite.DEFAULT.length + " stages\n"))
                + I18n.t("当前阶段已保存 " + savedRounds + " / " + repetitions + " 轮",
                "Saved " + savedRounds + " / " + repetitions + " rounds in current stage");
        State next = new State(true, message, text, "", suite.version, completed == null ? -1 : count,
                count, round, repetitions, savedRounds);
        main.post(() -> update(cancelRequested
                ? new State(true, I18n.t("正在停止：等待当前安全边界", "Stopping: waiting for safe boundary"),
                next.text, "", next.suiteVersion, next.stageIndex, next.completedStages, next.round, next.repetitions, next.savedRounds)
                : next));
    }

    private void publish(boolean busy, String message, String text, String exportJson) {
        publish(busy, message, text, exportJson, null);
    }

    private void publish(boolean busy, String message, String text, String exportJson, JSONObject latestReport) {
        State next = new State(busy, message, text, exportJson, latestReport, "", -1, 0, 0, 0, 0);
        main.post(() -> update(next));
    }

    private void update(State next) {
        state = next;
        if (listener != null) listener.onState(next);
    }
}
