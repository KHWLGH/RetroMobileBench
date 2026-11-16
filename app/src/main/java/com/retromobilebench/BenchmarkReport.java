package com.retromobilebench;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Versioned raw measurements. No synthetic score until a reference device is chosen. */
final class BenchmarkReport {
    static final String[] METRICS = BenchmarkSuite.STORAGE.metrics;

    private BenchmarkReport() { }

    static void summarize(JSONObject report) throws JSONException {
        report.remove("median");
        BenchmarkSuite suite = BenchmarkSuite.fromReport(report);
        if (suite == null) return;
        JSONArray samples = report.getJSONArray("samples");
        int count = report.getInt("repetitions");
        if (count != 1 && count != 3) throw new JSONException("Expected one or three repetitions");
        if (!"completed".equals(report.optString("status")) || samples.length() != count) return;
        JSONObject first = samples.getJSONObject(0);
        for (int i = 0; i < count; i++) {
            JSONObject sample = samples.getJSONObject(i);
            boolean matches = "completed".equals(sample.optString("status"))
                    && sample.optInt("completed_metrics") == metricCount(suite, sample)
                    && report.optString("suite_version", suite.version).equals(sample.optString("test_version"))
                    && first.optString("native_build_id", "legacy").equals(sample.optString("native_build_id", "legacy"))
                    && first.optString("native_library", "legacy").equals(sample.optString("native_library", "legacy"))
                    && first.optString("native_abi", "legacy").equals(sample.optString("native_abi", "legacy"));
            if (suite == BenchmarkSuite.WEB && (sample.has("diagnostic_fault") || !sample.optBoolean("cleanup_confirmed"))) matches = false;
            JSONObject device = report.optJSONObject("device");
            if (suite != BenchmarkSuite.WEB && device != null && device.has("native_build_id"))
                matches &= device.getString("native_build_id").equals(sample.optString("native_build_id"));
            for (String key : parameterKeys(suite)) {
                if (!sample.has(key) || !first.has(key)
                        || !sample.get(key).toString().equals(first.get(key).toString())) matches = false;
            }
            if (!matches) {
                report.put("message", I18n.t(
                        "各轮参数、测试版本或完成状态不同，仅保留原始结果，不合并中位数",
                        "Rounds differ in parameters, suite version, or completion status; retaining raw results without computing a median"));
                return;
            }
        }
        if (suite == BenchmarkSuite.WEB && (count != 3 || first.has("diagnostic_fault"))) return;
        JSONObject median = new JSONObject();
        for (String key : metricKeys(suite, first)) {
            double[] values = new double[count];
            for (int i = 0; i < count; i++) {
                values[i] = samples.getJSONObject(i).getDouble(key);
                if (values[i] <= 0 || Double.isNaN(values[i]) || Double.isInfinite(values[i]))
                    throw new JSONException("Invalid measurement: " + key);
            }
            Arrays.sort(values);
            median.put(key, values[count / 2]);
        }
        report.put("median", median);
    }

    static String[] metricKeys(BenchmarkSuite suite, JSONObject sample) {
        boolean omitParallel = sample.optInt("parallel_threads") == 1;
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (String key : suite.metrics) if (!omitParallel || !key.startsWith("parallel_")) keys.add(key);
        return keys.toArray(new String[0]);
    }

    static String[] parameterKeys(BenchmarkSuite suite) {
        return suite.parameters;
    }

    static int metricCount(BenchmarkSuite suite, JSONObject sample) { return metricKeys(suite, sample).length; }

    static String format(JSONObject report) throws JSONException {
        boolean zh = I18n.isZh();
        if (BenchmarkSuite.isSession(report.optString("suite_version"))) {
            StringBuilder text = new StringBuilder(zh ? "本次固定套件 · " : "Fixed Suite Run · ")
                    .append(statusLabel(report.optString("status"))).append('\n');
            text.append(zh ? "会话：" : "Session: ").append(report.optString("session_id")).append('\n');
            if (report.has("persistence_warning")) text.append(report.optString("persistence_warning")).append('\n');
            if (report.has("preparation_warning")) text.append(zh ? "准备：" : "Preparation: ").append(report.optString("preparation_warning")).append('\n');
            if (report.has("media_preparation_warning")) text.append(zh ? "媒体准备：" : "Media preparation: ").append(report.optString("media_preparation_warning")).append('\n');
            if (report.has("cancel_stop_latency_ms")) text.append(zh ? "请求停止后用时：" : "Stop latency after cancel: ").append(report.optLong("cancel_stop_latency_ms")).append(" ms\n");
            appendTemperature(text, report.optJSONObject("temperature"));
            JSONArray stages = report.optJSONArray("results");
            if (stages != null) for (int i = 0; i < stages.length(); i++) text.append("\n────────────────\n").append(format(stages.getJSONObject(i)));
            JSONObject active = report.optJSONObject("active_report");
            if (active != null) text.append(zh ? "\n──────── 当前阶段已保存轮次 ────────\n" : "\n──────── Saved Rounds in Active Stage ────────\n").append(format(active));
            return text.toString();
        }
        BenchmarkSuite suite = BenchmarkSuite.fromReport(report);
        if (suite == null) return (zh ? "未知测试版本，保留原始记录：\n" : "Unknown suite version; preserving raw record:\n") + report.toString(2);
        StringBuilder text = new StringBuilder();
        JSONObject device = report.optJSONObject("device");
        text.append(suite.label()).append(" · ").append(statusLabel(report.optString("status"))).append('\n');
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, zh ? Locale.SIMPLIFIED_CHINESE : Locale.US);
        text.append(df.format(new Date(report.getLong("started_ms")))).append('\n');
        if (device != null) {
            text.append(device.optString("manufacturer")).append(' ').append(device.optString("model"))
                    .append(" / Android ").append(device.optString("android_release"))
                    .append(" (API ").append(device.optInt("sdk")).append(")\n");
            if (device.optBoolean("emulator")) {
                text.append(zh ? "运行环境：模拟器（仅供功能验证参考）\n" : "Environment: Emulator (functional verification only)\n");
            }
            if (suite != BenchmarkSuite.WEB) {
                text.append(zh ? "原生库：" : "Native library: ")
                        .append(device.optString("native_library", zh ? "加载失败" : "Load failed")).append('\n');
                text.append(zh ? "构建标识：" : "Build ID: ")
                        .append(device.optString("native_build_id", zh ? "未记录" : "Unrecorded")).append('\n');
            }
        }
        String message = report.optString("message");
        if (message.length() != 0) text.append(message).append('\n');
        appendTemperature(text, report.optJSONObject("temperature"));
        if (suite == BenchmarkSuite.WEB) {
            if (zh) {
                text.append("离线合成负载；每项 20 单元 × 3 轮；独立竖屏全屏视口（信息流+480p/720p/1080p大图解码）；12/18 px 字体。\n")
                        .append("测量任务完成与布局/Canvas像素检查点耗时（含单元间调度，越低越好）。\n");
            } else {
                text.append("Offline synthetic workload; 20 units x 3 rounds per subtest; isolated portrait fullscreen viewport (feed + 480p/720p/1080p HD images); 12/18 px font.\n")
                        .append("Measures task completion with layout and Canvas pixel checkpoints (lower is better).\n");
            }
            JSONArray rounds = report.optJSONArray("samples");
            if (rounds != null && rounds.length() > 0) {
                JSONObject environment = rounds.getJSONObject(0);
                text.append(zh ? "Web 宿主：" : "Web host: ").append(environment.optString("host_build_id")).append('\n')
                        .append(zh ? "资源摘要：" : "Resource SHA-256: ").append(environment.optString("resource_sha256")).append('\n')
                        .append(zh ? "引擎：" : "Provider: ").append(environment.optString("provider")).append('\n')
                        .append("UA: ").append(environment.optString("user_agent")).append('\n')
                        .append(zh ? "CSS 视口：" : "CSS viewport: ").append(environment.optInt("viewport_width")).append('×').append(environment.optInt("viewport_height")).append('\n')
                        .append(zh ? "物理视口：" : "Physical viewport: ").append(environment.optInt("physical_width")).append('×').append(environment.optInt("physical_height"))
                        .append("; DPR ").append(environment.optDouble("dpr"))
                        .append(zh ? "；硬件加速 " : "; Hardware accelerated ").append(environment.optBoolean("hardware_accelerated")).append('\n');
            }
        }
        JSONObject median = report.optJSONObject("median");
        boolean summaryOnly = median != null && report.optInt("repetitions") == 3;
        if (summaryOnly) {
            text.append(zh ? "\n三轮中位数（各轮原始值见 JSON）\n" : "\n3-Round Median (see JSON for raw per-round samples)\n");
            for (int i = 0; i < suite.metrics.length; i++) appendMetric(text, suite, median, i);
            if (suite == BenchmarkSuite.MEMORY && median.has("single_triad_mbps")) {
                text.append(zh
                        ? "工作量：8 MiB 复制/填充 · 4 MiB 随机指针链延迟 · Triad 共享固定 24 MiB 工作集（3×8 MiB）。\n"
                        : "Workload: 8 MiB copy/fill · 4 MiB random pointer-chase latency · Triad shared fixed 24 MiB working set (3×8 MiB).\n");
            }
        }
        JSONArray samples = report.optJSONArray("samples");
        if (samples != null && !summaryOnly) {
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                text.append(zh ? "\n第 " + (i + 1) + " 轮 · " : "\nRound " + (i + 1) + " · ")
                        .append(statusLabel(sample.optString("status"))).append('\n');
                if (suite == BenchmarkSuite.VIDEO) {
                    JSONArray details = sample.optJSONArray("results");
                    if (details != null) for (int n = 0; n < details.length(); n++) {
                        JSONObject item = details.getJSONObject(n); text.append(item.optString("id")).append('\n');
                        for (String path : new String[]{"software", "system", "playback"}) {
                            JSONObject value = item.optJSONObject(path); if (value == null) continue;
                            String pathName = "software".equals(path) ? (zh ? "软件解码" : "Software decode")
                                    : "system".equals(path) ? (zh ? "系统解码" : "System decode")
                                    : (zh ? "实时播放" : "Real-time playback");
                            text.append("  ").append(pathName).append(zh ? "：" : ": ")
                                    .append(statusLabel(value.optString("status")));
                            if (value.has("fps") && !value.isNull("fps")) text.append(String.format(Locale.US, " · %.2f fps", value.optDouble("fps")));
                            if (value.has("realtime")) text.append(value.optBoolean("realtime") ? (zh ? " · 达到实时" : " · Real-time") : (zh ? " · 低于实时" : " · Below real-time"));
                            if (value.has("error_stage")) text.append(" · ").append(value.optString("error_stage"));
                            if (value.has("decoder")) text.append(" · ").append(value.optString("decoder"));
                            if (!"software".equals(path)) appendDecoderEvidence(text, value);
                            text.append('\n');
                        }
                    }
                } else if (suite == BenchmarkSuite.WEB) {
                    text.append(zh ? "独立工作进程 " : "Worker PID ").append(sample.optInt("worker_pid"))
                            .append(zh ? "；进程已回收 " : "; Cleanup confirmed ").append(sample.optBoolean("cleanup_confirmed"))
                            .append(zh ? "；计时源 " : "; Clock ").append(sample.optString("clock_source")).append(" / ").append(sample.optDouble("clock_resolution_ms")).append(" ms\n");
                } else if (suite.isGpu()) {
                    text.append(sample.optString("vendor")).append(" / ").append(sample.optString("renderer")).append('\n')
                            .append(sample.optInt("render_width", 1920)).append(" × ").append(sample.optInt("render_height", 1080))
                            .append(suite.gpuTest().endsWith("_off")
                                    ? (zh ? " 离屏渲染" : " Offscreen")
                                    : (zh ? " 屏上渲染（受刷新率与 VSync 影响）" : " Onscreen (bounded by display refresh & VSync)"))
                            .append(zh ? " / ETC1；数据摘要 " : " / ETC1; Data SHA-256 ").append(sample.optString("data_sha256")).append('\n');
                    if (sample.optInt("diagnostic_duration_ms") > 0) {
                        text.append(zh ? "短时诊断运行（非正式时长）。\n" : "Short diagnostic run (non-official duration).\n");
                    }
                } else if (suite == BenchmarkSuite.MEMORY) {
                    if (zh) {
                        text.append("tinymembench C 内核；8 MiB 缓冲区复制/填充（共 16 MiB），4 MiB 随机指针链延迟（100 万节点）\n")
                                .append("内存复制按读＋写总字节计算；延迟越低越好。\n");
                        if (sample.has("triad_protocol")) text.append("Triad：1 + ").append(sample.optInt("parallel_threads"))
                                .append(" 线程；共享固定 24 MiB 工作集（3 × 8 MiB，STREAM 变体）。\n");
                    } else {
                        text.append("tinymembench C kernels; 8 MiB copy/fill (16 MiB buffers), 4 MiB random pointer-chase latency (1M nodes)\n")
                                .append("Memory copy counts read + write bytes; lower latency is better.\n");
                        if (sample.has("triad_protocol")) text.append("Triad: 1 + ").append(sample.optInt("parallel_threads"))
                                .append(" threads; shared fixed 24 MiB working set (3 × 8 MiB, STREAM variant).\n");
                    }
                } else if (suite == BenchmarkSuite.SCIMARK) {
                    if (zh) {
                        text.append("NIST SciMark 2.0 内核 / 单核及 ").append(sample.optInt("parallel_threads", 1)).append(" 线程 / 小数据集\n")
                                .append("各内核独立计时；每项至少 ").append(sample.optInt("duration_ms")).append(" ms\n");
                    } else {
                        text.append("NIST SciMark 2.0 kernels / Single-core & ").append(sample.optInt("parallel_threads", 1)).append(" threads / Small dataset\n")
                                .append("Timed per kernel; >= ").append(sample.optInt("duration_ms")).append(" ms each\n");
                    }
                } else if (suite == BenchmarkSuite.COREMARK) {
                    text.append(zh ? "2000 字节栈工作区 / 双种子校验 / 每段至少 10 秒\n" : "2000 B stack workspace / Two-seed validation / >=10 s per segment\n")
                            .append(sample.optString("compiler_version")).append('\n')
                            .append(sample.optString("compiler_flags")).append('\n')
                            .append(zh ? "栈内存；PThreads / 并行线程数：" : "Stack memory; PThreads / Parallel threads: ").append(sample.optInt("parallel_threads")).append('\n')
                            .append(zh ? "CoreMark® 是 EEMBC 的注册商标；完整校验数据见 JSON。\n" : "CoreMark® is a registered trademark of EEMBC; full validation details in JSON.\n");
                } else if (suite == BenchmarkSuite.SQLITE) {
                    if (zh) {
                        text.append("SQLite ").append(sample.optString("sqlite_version"))
                                .append(" / INSERT、UPDATE、DELETE 各 ").append(sample.optInt("transactions")).append(" 个独立事务（共 ")
                                .append(sample.optInt("transactions") * 3).append(" 次 COMMIT）\n")
                                .append("1 KiB / 行；4 KiB 页；DELETE 日志；FULL 同步提交\n");
                    } else {
                        text.append("SQLite ").append(sample.optString("sqlite_version"))
                                .append(" / ").append(sample.optInt("transactions")).append(" transactions each for INSERT, UPDATE, DELETE (")
                                .append(sample.optInt("transactions") * 3).append(" COMMITs total)\n")
                                .append("1 KiB/row; 4 KiB pages; DELETE journal; synchronous=FULL\n");
                    }
                } else {
                    long fileMib = sample.optLong("file_bytes") / (1024 * 1024);
                    if (zh) {
                        text.append("文件 ").append(fileMib)
                                .append(" MiB（256 KiB 顺序读写 × ").append(fileMib * 4)
                                .append(" 块） / 4 KiB 随机读写 ").append(sample.optInt("random_operations")).append(" 次\n");
                        if ("cached".equals(sample.optString("read_mode")))
                            text.append("读取模式：系统缓存（cached） / 写入 O_SYNC\n");
                        else text.append("读取模式：O_DIRECT（绕过页缓存） / 写入 O_SYNC\n");
                    } else {
                        text.append("File ").append(fileMib)
                                .append(" MiB (256 KiB seq R/W × ").append(fileMib * 4)
                                .append(" blocks) / 4 KiB random R/W ").append(sample.optInt("random_operations")).append(" ops\n");
                        if ("cached".equals(sample.optString("read_mode")))
                            text.append("Read mode: system page cache (cached) / Write O_SYNC\n");
                        else text.append("Read mode: O_DIRECT (bypass page cache) / Write O_SYNC\n");
                    }
                }
                for (int j = 0; j < suite.metrics.length; j++) appendMetric(text, suite, sample, j);
                if (!"completed".equals(sample.optString("status"))) {
                    String errorKey = suite == BenchmarkSuite.STORAGE ? "errno" : "error_code";
                    text.append(zh ? "停止位置：" : "Stopped at: ").append(sample.optString("error_stage"))
                            .append(" / ").append(errorKey).append(' ').append(sample.optInt(errorKey)).append('\n');
                }
            }
        }
        text.append(zh ? "\n报告展示各分项真实测量值与有效中位数。\n" : "\nReport shows raw per-stage measurements and validated medians.\n");
        return text.toString();
    }

    private static void appendMetric(StringBuilder text, BenchmarkSuite suite, JSONObject sample, int index) throws JSONException {
        if (!sample.has(suite.metrics[index])) return;
        boolean zh = I18n.isZh();
        text.append(String.format(Locale.US, "%s%s%.2f %s%n", suite.metricLabel(index), zh ? "：" : ": ",
                sample.getDouble(suite.metrics[index]), suite.units[index]));
        if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL && sample.has("fps")) {
            text.append(String.format(Locale.US, zh ? "平均：%.2f FPS（%s）%n" : "Average: %.2f FPS (%s)%n",
                    sample.getDouble("fps"),
                    suite.gpuTest().endsWith("_off") ? (zh ? "离屏渲染" : "Offscreen") : (zh ? "窗口提交" : "Onscreen")));
        }
    }

    private static void appendDecoderEvidence(StringBuilder text, JSONObject value) {
        boolean zh = I18n.isZh();
        JSONObject attributes = value.optJSONObject("system_attributes");
        if (attributes != null && attributes.has("hardware_accelerated")) {
            text.append(attributes.optBoolean("hardware_accelerated")
                    ? (zh ? " · 系统声明硬件加速" : " · System HW accelerated")
                    : attributes.optBoolean("software_only")
                    ? (zh ? " · 系统声明软件解码" : " · System SW-only")
                    : (zh ? " · 系统声明未分类" : " · System unclassified"));
        } else if ("known_software_name".equals(value.optString("type_evidence"))) {
            text.append(zh ? " · 已知软件解码器名称" : " · Known SW decoder name");
        } else {
            text.append(zh ? " · 硬件类型未确认" : " · HW type unconfirmed");
        }
    }

    static void appendTemperature(StringBuilder text, JSONObject trace) {
        boolean zh = I18n.isZh();
        if (trace == null || !trace.optBoolean("available")) {
            text.append(zh ? "电池温度：不可获得\n" : "Battery temperature: Unavailable\n");
        } else {
            text.append(String.format(Locale.US,
                    zh ? "电池温度 %.1f → %.1f°C；采样峰值 %.1f°C；温升 %+.1f°C%n"
                            : "Battery temp %.1f -> %.1f°C; Peak %.1f°C; Delta %+.1f°C%n",
                    trace.optInt("start_tenths_c") / 10.0, trace.optInt("end_tenths_c") / 10.0,
                    trace.optInt("sampled_max_tenths_c") / 10.0, trace.optInt("delta_tenths_c") / 10.0));
        }
        if (trace != null && trace.optBoolean("battery_available")) {
            double startPct = trace.optDouble("battery_start_percent", -1);
            double endPct = trace.optDouble("battery_end_percent", -1);
            double deltaPct = trace.optDouble("battery_delta_percent", 0);
            boolean pluggedAny = trace.optBoolean("plugged_any", false);
            text.append(String.format(Locale.US,
                    zh ? "电池电量 %.1f%% → %.1f%%（变化 %+.1f%%%s）"
                            : "Battery level %.1f%% -> %.1f%% (delta %+.1f%%%s)",
                    startPct, endPct, deltaPct,
                    pluggedAny ? (zh ? "，期间曾接入电源" : ", plugged during run")
                            : (zh ? "，纯电池放电" : ", battery only")));
            if (trace.has("voltage_start_mv") && trace.has("voltage_end_mv")) {
                text.append(String.format(Locale.US, zh ? "；电压 %.3f → %.3f V" : "; Voltage %.3f -> %.3f V",
                        trace.optInt("voltage_start_mv") / 1000.0,
                        trace.optInt("voltage_end_mv") / 1000.0));
            }
            text.append('\n');
        }
    }

    static String statusLabel(String status) {
        boolean zh = I18n.isZh();
        if ("partial".equals(status)) return zh ? "部分完成" : "Partial";
        if ("unsupported".equals(status)) return zh ? "不支持" : "Unsupported";
        if ("timeout".equals(status)) return zh ? "超时" : "Timeout";
        if ("completed".equals(status)) return zh ? "完成" : "Completed";
        if ("cancelled".equals(status)) return zh ? "已取消" : "Cancelled";
        if ("interrupted".equals(status)) return zh ? "已中断" : "Interrupted";
        if ("skipped".equals(status)) return zh ? "已跳过" : "Skipped";
        if ("running".equals(status)) return zh ? "运行中" : "Running";
        return zh ? "失败" : "Failed";
    }
}
