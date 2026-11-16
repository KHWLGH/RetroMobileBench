package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real offline page, IPC and process cleanup. No substituted timing samples. */
final class WebRegressionChecks {
    interface Progress { void stage(String message); }
    static String run(Instrumentation test, Progress progress, boolean faults) throws Exception {
        Context context = test.getTargetContext(); foreground(test);
        JSONObject report = new JSONObject().put("suite_version", WebAssets.VERSION).put("started_ms", System.currentTimeMillis())
                .put("status", "completed").put("repetitions", 3).put("samples", new JSONArray()).put("device", DeviceSnapshot.collect(context, false));
        HashSet<Integer> pids = new HashSet<>();
        WebClient.resetCancellation(); WebClient.beginStage();
        try {
            for (int round = 0; round < 3; round++) {
                progress.stage("Web fixed round " + (round + 1) + "/3");
                JSONObject value = WebClient.run(context, "", 0); report.getJSONArray("samples").put(value);
                save(context, report);
                completed(value); check(pids.add(value.getInt("worker_pid")), "new Web process each round");
                if (round < 2) SystemClock.sleep(1000);
            }
        } finally { WebClient.endStage(); }
        check(!nativeMapped(), "Web-only never loads a benchmark JNI library");
        BenchmarkReport.summarize(report); check(report.has("median"), "three compatible rounds produce median: " + report);
        check(!report.getJSONObject("device").has("native_build_id"), "Web device collection has no native identity");
        summaryChecks(report);
        ResultStore store = new ResultStore(context, "web-regression.db");
        try {
            long id = store.begin(report); store.save(id, report); store.close(); store = new ResultStore(context, "web-regression.db");
            check(store.latest(1).getJSONObject(0).getJSONObject("median").length() == 6, "Web persisted median survives reopen");
            JSONObject partial = new JSONObject(report.toString()).put("status", "running"); partial.getJSONArray("samples").put(WebClient.failure("timeout", "fixture"));
            store.begin(partial); store.recoverInterrupted(); JSONObject recovered = store.latest(1).getJSONObject(0);
            check("interrupted".equals(recovered.getString("status")) && recovered.getJSONArray("samples").length() == 4 && !recovered.has("median"), "recovery retains all raw partial rounds");
        } finally { store.close(); context.deleteDatabase("web-regression.db"); }
        if (faults) {
            progress.stage("Web pre-entry cancellation"); WebClient.resetCancellation(); WebClient.cancel();
            check("cancelled".equals(WebClient.run(context, "", 0).optString("status")), "pre-entry cancel cannot launch worker");
            foreground(test); WebClient.resetCancellation(); progress.stage("Web stale request exclusion"); completed(WebClient.run(context, "stale_callback", 0));
            for (String fault : new String[]{"bad_page", "bad_todo", "bad_text", "bad_image", "clock_zero", "clock_backward", "late_text", "crash", "hang_prepare", "hang_measure"}) {
                foreground(test); progress.stage("Web negative path " + fault); WebClient.resetCancellation();
                JSONObject value = WebClient.run(context, fault, "hang_prepare".equals(fault) ? 1500 : 0);
                check(!"completed".equals(value.optString("status")) && value.optInt("completed_metrics") < 6 && value.optBoolean("cleanup_confirmed"), "negative rejected and process reaped: " + value);
                if ("late_text".equals(fault)) check(value.getInt("completed_metrics") == 2, "two completed subtests survive later failure");
                if (fault.startsWith("hang_")) check("timeout".equals(value.optString("status")), "parent timeout survives blocked JS: " + value);
                SystemClock.sleep(250);
            }
            foreground(test); progress.stage("Web parent cancellation during blocked JavaScript"); WebClient.resetCancellation();
            WebClient.diagnosticPhase = "idle";
            AtomicReference<JSONObject> cancelled = new AtomicReference<>();
            Thread running = new Thread(() -> cancelled.set(WebClient.run(context, "hang_measure", 0)), "web-cancel-check"); running.start();
            waitPhase("begin"); long cancelAt = SystemClock.elapsedRealtime(); WebClient.cancel(); running.join(12000);
            check(!running.isAlive() && "cancelled".equals(cancelled.get().optString("status")) && cancelled.get().optBoolean("cleanup_confirmed") && SystemClock.elapsedRealtime() - cancelAt < 12000, "blocked renderer cancelled and killed; alive=" + running.isAlive() + "; result=" + cancelled.get());
            foreground(test); progress.stage("Web Home interruption"); WebClient.resetCancellation();
            WebClient.diagnosticPhase = "idle";
            AtomicReference<JSONObject> background = new AtomicReference<>();
            Thread visible = new Thread(() -> background.set(WebClient.run(context, "pause_before_formal", 0)), "web-home-check"); visible.start();
            waitPhase("prepared");
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            visible.join(15000); check(!visible.isAlive() && "interrupted".equals(background.get().optString("status")) && background.get().optBoolean("cleanup_confirmed"), "Home interrupts real worker window: " + background.get());
            foreground(test); WebClient.resetCancellation(); progress.stage("Web restart after all failures"); completed(WebClient.run(context, "", 0));
        }
        save(context, report);
        StringBuilder summary = new StringBuilder(WebAssets.VERSION + ": portrait full-screen, 4 tasks x 20 units x 3 rounds; independent PIDs " + pids + "; validated medians ");
        summary.append(report.getJSONObject("median")); return summary.toString();
    }
    static void completed(JSONObject value) throws Exception {
        check("completed".equals(value.optString("status")), "Web completes all four real tasks: " + value);
        check(value.getInt("completed_metrics") == 6 && value.getJSONArray("results").length() == 4 && value.getInt("units_per_subtest") == 20, "all fixed work completed");
        check(value.getBoolean("cleanup_confirmed") && !value.has("native_build_id"), "reaped process and independent host identity");
        for (int i = 0; i < 4; i++) {
            JSONObject metric = value.getJSONArray("results").getJSONObject(i);
            check(metric.getInt("units") == 20 && metric.getJSONObject("proof").getInt("validated_units") == 20 && WebClient.IDS[i].equals(metric.getString("id")), "validated units, stable order");
        }
        for (String key : WebClient.METRICS) check(value.getDouble(key) > 0, "positive raw task time");
        check(value.getInt("viewport_height") >= value.getInt("viewport_width") && value.getInt("text_zoom_percent") == 100, "portrait CSS viewport");
        check("fullscreen".equals(value.getString("window_mode")) && value.getInt("physical_width") == value.getInt("window_width")
                && value.getInt("physical_height") == value.getInt("window_height"), "WebView fills the whole window");
    }
    private static void summaryChecks(JSONObject original) throws Exception {
        for (String key : BenchmarkSuite.WEB.parameters) {
            JSONObject changed = new JSONObject(original.toString()); changed.getJSONArray("samples").getJSONObject(2).put(key, "changed");
            BenchmarkReport.summarize(changed); check(!changed.has("median"), "mixed Web identity excluded: " + key);
        }
        JSONObject changed = new JSONObject(original.toString()); changed.getJSONArray("samples").getJSONObject(1).put("status", "timeout");
        BenchmarkReport.summarize(changed); check(!changed.has("median"), "failed round cannot be zero");
        JSONObject session = new JSONObject().put("suite_version", BenchmarkSuite.SESSION_VERSION).put("status", "partial").put("results", new JSONArray().put(original));
        check(BenchmarkReport.format(session).contains("Web 竖屏信息流与大图"), "session readable");
    }
    private static boolean nativeMapped() throws Exception {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line; while ((line = reader.readLine()) != null) if (line.contains("librmb.so") || line.contains("librmb_noneon.so") || line.contains("librmbmedia.so")) return true;
        }
        return false;
    }
    private static void waitPhase(String phase) {
        long deadline = SystemClock.elapsedRealtime() + 18000;
        while (!phase.equals(WebClient.diagnosticPhase) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        check(phase.equals(WebClient.diagnosticPhase), "real Web phase " + phase + ", actual " + WebClient.diagnosticPhase);
    }
    private static void foreground(Instrumentation test) {
        // NEW_TASK may deliver to an existing root Activity; startActivitySync waits forever then.
        test.getTargetContext().startActivity(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync(); SystemClock.sleep(500);
    }
    private static void save(Context context, JSONObject report) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "web-validation.json"))) { output.write(report.toString(2).getBytes("UTF-8")); }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
