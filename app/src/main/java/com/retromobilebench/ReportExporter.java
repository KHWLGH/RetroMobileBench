package com.retromobilebench;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Formats 100% complete benchmark results and telemetry curves into structured JSON,
 * Excel-friendly UTF-8 BOM CSV, or readable plain text without discarding any raw samples.
 */
final class ReportExporter {
    enum Format {
        JSON(".json", "application/json", "完整 JSON (.json)", "Full JSON (.json)"),
        CSV(".csv", "text/csv", "完整表格 CSV (.csv)", "Full Table CSV (.csv)"),
        TXT(".txt", "text/plain", "完整文本报告 (.txt)", "Full Text Report (.txt)");

        final String extension;
        final String mimeType;
        private final String labelZh;
        private final String labelEn;

        Format(String extension, String mimeType, String labelZh, String labelEn) {
            this.extension = extension;
            this.mimeType = mimeType;
            this.labelZh = labelZh;
            this.labelEn = labelEn;
        }

        String label() {
            return I18n.t(labelZh, labelEn);
        }
    }

    private static final SimpleDateFormat FILE_TIME_FMT = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat ISO_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
    private static final SimpleDateFormat HUMAN_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private ReportExporter() { }

    static String defaultFileName(JSONObject report, Format format) {
        String rawModel = Build.MODEL;
        JSONObject dev = extractDevice(report);
        if (dev != null && dev.optString("model").length() > 0) {
            rawModel = dev.optString("model");
        }
        String safeModel = sanitizeFilePart(rawModel);
        long ts = report != null && report.has("started_ms") ? report.optLong("started_ms") : System.currentTimeMillis();
        String timePart;
        synchronized (FILE_TIME_FMT) {
            timePart = FILE_TIME_FMT.format(new Date(ts));
        }
        boolean isSession = report != null && BenchmarkSuite.isSession(report.optString("suite_version"));
        String scope = isSession ? "" : "_" + sanitizeFilePart(report != null ? report.optString("suite_version", "stage") : "stage");
        return "RMB_" + safeModel + scope + "_" + timePart + format.extension;
    }

    static byte[] exportBytes(JSONObject report, Format format) throws JSONException {
        String text;
        if (format == Format.JSON) {
            text = buildCompleteJson(report);
        } else if (format == Format.CSV) {
            text = "\uFEFF" + buildCompleteCsv(report);
        } else {
            text = buildCompleteText(report);
        }
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return text.getBytes();
        }
    }

    /** Complete JSON export: structured top-level summary + 100% unaltered raw_report. */
    static String buildCompleteJson(JSONObject report) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("export_format", "retromobilebench-full-v1");
        root.put("exported_at", formatIso(System.currentTimeMillis()));

        boolean isSession = BenchmarkSuite.isSession(report.optString("suite_version"));
        JSONObject overview = new JSONObject();
        overview.put("kind", isSession ? "full_session" : "single_stage");
        overview.put("suite_version", report.optString("suite_version"));
        if (report.has("session_id")) overview.put("session_id", report.optString("session_id"));
        overview.put("status", report.optString("status"));
        if (report.has("started_ms")) overview.put("started_at", formatIso(report.optLong("started_ms")));
        if (report.has("ended_ms")) overview.put("ended_at", formatIso(report.optLong("ended_ms")));
        long wallMs = report.optLong("wall_ms", report.optLong("ended_ms", 0) - report.optLong("started_ms", 0));
        if (wallMs > 0) overview.put("wall_ms", wallMs);
        overview.put("app_version", BuildConfig.VERSION_NAME);
        overview.put("app_version_code", BuildConfig.VERSION_CODE);
        overview.put("native_build_id", report.optString("native_build_id", BuildConfig.NATIVE_BUILD_ID));
        overview.put("web_build_id", BuildConfig.WEB_BUILD_ID);
        root.put("overview", overview);

        JSONObject dev = extractDevice(report);
        if (dev != null) {
            root.put("device_summary", buildDeviceSummary(dev));
        }

        root.put("metrics_summary", buildMetricsSummary(report));
        root.put("telemetry_summary", buildTelemetrySummary(report.optJSONObject("temperature")));
        root.put("raw_report", report);
        return root.toString(2);
    }

    /** Complete CSV export: overview, core summary, every stage/round/video path, and full telemetry curve. */
    static String buildCompleteCsv(JSONObject report) throws JSONException {
        boolean zh = I18n.isZh();
        StringBuilder sb = new StringBuilder(16384);
        boolean isSession = BenchmarkSuite.isSession(report.optString("suite_version"));
        JSONObject dev = extractDevice(report);

        // 1. Overview & Device
        appendCsvRow(sb, zh ? "[概览与设备信息]" : "[Overview & Device]");
        appendCsvRow(sb, zh ? "字段" : "Field", zh ? "值" : "Value");
        appendCsvRow(sb, zh ? "导出格式" : "Export Format", "retromobilebench-full-csv-v1");
        appendCsvRow(sb, zh ? "导出时间" : "Exported At", formatHuman(System.currentTimeMillis()));
        appendCsvRow(sb, zh ? "测试套件" : "Suite Version", report.optString("suite_version"));
        if (report.has("session_id")) appendCsvRow(sb, zh ? "会话 ID" : "Session ID", report.optString("session_id"));
        appendCsvRow(sb, zh ? "状态" : "Status", BenchmarkReport.statusLabel(report.optString("status")) + " (" + report.optString("status") + ")");
        if (report.has("started_ms")) appendCsvRow(sb, zh ? "开始时间" : "Started At", formatHuman(report.optLong("started_ms")));
        if (report.has("ended_ms")) appendCsvRow(sb, zh ? "结束时间" : "Ended At", formatHuman(report.optLong("ended_ms")));
        long wallMs = report.optLong("wall_ms", report.optLong("ended_ms", 0) - report.optLong("started_ms", 0));
        if (wallMs > 0) appendCsvRow(sb, zh ? "总耗时(ms)" : "Wall Time (ms)", String.valueOf(wallMs));
        appendCsvRow(sb, zh ? "软件版本" : "App Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        appendCsvRow(sb, "NATIVE_BUILD_ID", report.optString("native_build_id", BuildConfig.NATIVE_BUILD_ID));
        appendCsvRow(sb, "WEB_BUILD_ID", BuildConfig.WEB_BUILD_ID);
        if (dev != null) {
            CpuSocResolver.Result soc = CpuSocResolver.resolve(dev);
            appendCsvRow(sb, zh ? "设备型号" : "Device Model", dev.optString("manufacturer") + " " + dev.optString("model") + " (" + dev.optString("device") + ")");
            appendCsvRow(sb, zh ? "系统版本" : "OS Version", "Android " + dev.optString("android_release") + " (API " + dev.optInt("sdk") + ")");
            appendCsvRow(sb, zh ? "指令集 ABI" : "ABI", dev.optString("abi") + (dev.optString("abi2").length() > 0 ? "/" + dev.optString("abi2") : ""));
            appendCsvRow(sb, zh ? "处理器(SoC)" : "Processor (SoC)", soc.socName);
            appendCsvRow(sb, zh ? "核心微架构" : "CPU Microarchitecture", soc.cpuArch);
            appendCsvRow(sb, zh ? "逻辑核心数" : "CPU Workers", String.valueOf(CpuTopology.workers(dev)));
            if (dev.has("total_memory_bytes")) appendCsvRow(sb, zh ? "总内存(bytes)" : "Total Memory (bytes)", String.valueOf(dev.optLong("total_memory_bytes")));
            appendCsvRow(sb, zh ? "运行环境" : "Environment", dev.optBoolean("emulator") ? (zh ? "模拟器" : "Emulator") : (zh ? "物理设备" : "Physical Device"));
            if (dev.has("native_library")) appendCsvRow(sb, zh ? "原生库" : "Native Library", dev.optString("native_library"));
        }
        sb.append('\n');

        // 2. Stage summary table
        appendCsvRow(sb, zh ? "[核心指标汇总]" : "[Core Metrics Summary]");
        appendCsvRow(sb,
                zh ? "阶段序号" : "Stage #",
                zh ? "阶段标识" : "Stage ID",
                zh ? "阶段名称" : "Stage Name",
                zh ? "状态" : "Status",
                zh ? "统计来源" : "Source",
                zh ? "指标键" : "Metric Key",
                zh ? "指标名称" : "Metric Label",
                zh ? "数值" : "Value",
                zh ? "单位" : "Unit");
        JSONArray stageList = collectStages(report, isSession);
        for (int sIdx = 0; sIdx < stageList.length(); sIdx++) {
            JSONObject st = stageList.optJSONObject(sIdx);
            if (st == null) continue;
            BenchmarkSuite suite = BenchmarkSuite.fromReport(st);
            String stId = st.optString("suite_version");
            String stLabel = suite != null ? suite.label() : stId;
            String stStatus = BenchmarkReport.statusLabel(st.optString("status"));
            if (suite == BenchmarkSuite.VIDEO) {
                JSONArray samples = st.optJSONArray("samples");
                JSONArray clips = samples != null && samples.length() > 0 ? samples.optJSONObject(0).optJSONArray("results") : null;
                if (clips != null) {
                    int sw = 0, sys = 0, play = 0;
                    for (int c = 0; c < clips.length(); c++) {
                        JSONObject item = clips.optJSONObject(c);
                        if (item == null) continue;
                        if (isPathCompleted(item, "software")) sw++;
                        if (isPathCompleted(item, "system")) sys++;
                        if (isPathCompleted(item, "playback")) play++;
                    }
                    String singleRun = zh ? "单轮实测" : "Single Run";
                    appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, singleRun, "software_passed", zh ? "软件解码通过数" : "Software Decode Passed", String.valueOf(sw), "/ " + clips.length());
                    appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, singleRun, "system_passed", zh ? "系统解码通过数" : "System Decode Passed", String.valueOf(sys), "/ " + clips.length());
                    appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, singleRun, "playback_passed", zh ? "实时播放通过数" : "Real-time Playback Passed", String.valueOf(play), "/ " + clips.length());
                }
                continue;
            }
            JSONObject median = st.optJSONObject("median");
            JSONArray samples = st.optJSONArray("samples");
            JSONObject src = median != null ? median : (samples != null && samples.length() > 0 ? samples.optJSONObject(0) : null);
            String srcType = median != null ? (zh ? "三轮中位数" : "3-Round Median") : (zh ? "单轮/原始" : "Single / Raw");
            if (suite != null && src != null) {
                for (int m = 0; m < suite.metrics.length; m++) {
                    String key = suite.metrics[m];
                    if (!src.has(key)) continue;
                    appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, srcType, key, suite.metricLabel(m),
                            formatNum(src.optDouble(key)), suite.units[m]);
                }
                if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL && src.has("fps")) {
                    String mode = suite.gpuTest().endsWith("_off") ? (zh ? "离屏" : "Offscreen") : (zh ? "屏上" : "Onscreen");
                    appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, srcType, "fps",
                            zh ? "平均帧率(" + mode + ")" : "Average FPS (" + mode + ")",
                            formatNum(src.optDouble("fps")), "FPS");
                }
            } else {
                appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, stStatus, "-", "-", "-", "", "");
            }
        }
        sb.append('\n');

        // 3. Complete per-stage, per-round & parameter details
        appendCsvRow(sb, zh ? "[各阶段各轮次完整明细]" : "[Per-Stage & Per-Round Details]");
        appendCsvRow(sb,
                zh ? "阶段序号" : "Stage #",
                zh ? "阶段标识" : "Stage ID",
                zh ? "阶段名称" : "Stage Name",
                zh ? "轮次" : "Round",
                zh ? "状态" : "Status",
                zh ? "项目/指标键" : "Item / Metric Key",
                zh ? "项目/指标名称" : "Item / Metric Name",
                zh ? "值" : "Value",
                zh ? "单位" : "Unit",
                zh ? "附加参数与环境" : "Parameters & Environment");
        for (int sIdx = 0; sIdx < stageList.length(); sIdx++) {
            JSONObject st = stageList.optJSONObject(sIdx);
            if (st == null) continue;
            BenchmarkSuite suite = BenchmarkSuite.fromReport(st);
            String stId = st.optString("suite_version");
            String stLabel = suite != null ? suite.label() : stId;
            JSONArray samples = st.optJSONArray("samples");
            if (samples == null || samples.length() == 0) {
                appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, "0",
                        st.optString("status"), "message", zh ? "说明" : "Note", st.optString("message"), "", "");
                continue;
            }
            for (int r = 0; r < samples.length(); r++) {
                JSONObject sample = samples.optJSONObject(r);
                if (sample == null) continue;
                String roundStr = String.valueOf(r + 1);
                String roundStatus = sample.optString("status");
                String paramsNote = collectSampleParams(suite, sample);
                if (suite == BenchmarkSuite.VIDEO) {
                    JSONArray clips = sample.optJSONArray("results");
                    if (clips != null) {
                        for (int c = 0; c < clips.length(); c++) {
                            JSONObject clip = clips.optJSONObject(c);
                            if (clip == null) continue;
                            String clipId = clip.optString("id");
                            for (String path : new String[]{"software", "system", "playback"}) {
                                JSONObject p = clip.optJSONObject(path);
                                if (p == null) continue;
                                String pStatus = p.optString("status");
                                String fps = p.has("fps") && !p.isNull("fps") ? formatNum(p.optDouble("fps")) : "";
                                String detail = "decoder=" + p.optString("decoder")
                                        + (p.has("realtime") ? "; realtime=" + p.optBoolean("realtime") : "")
                                        + (p.has("frames") ? "; frames=" + p.optInt("frames") : "")
                                        + (p.has("error_stage") ? "; error=" + p.optString("error_stage") : "");
                                appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, roundStr, pStatus,
                                        clipId + "." + path, clipId + " · " + path, fps, fps.length() > 0 ? "FPS" : "", detail);
                            }
                        }
                    }
                } else if (suite != null) {
                    boolean wroteMetric = false;
                    for (int m = 0; m < suite.metrics.length; m++) {
                        String key = suite.metrics[m];
                        if (!sample.has(key)) continue;
                        appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, roundStr, roundStatus,
                                key, suite.metricLabel(m), formatNum(sample.optDouble(key)), suite.units[m],
                                wroteMetric ? "" : paramsNote);
                        wroteMetric = true;
                    }
                    if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL && sample.has("fps")) {
                        appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, roundStr, roundStatus,
                                "fps", zh ? "平均帧率" : "Average FPS", formatNum(sample.optDouble("fps")), "FPS", "");
                    }
                    if (!wroteMetric) {
                        appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, roundStr, roundStatus,
                                "error_stage", zh ? "停止位置" : "Stopped At", sample.optString("error_stage"), "", paramsNote);
                    }
                }
            }
            JSONObject stTrace = st.optJSONObject("temperature");
            if (stTrace != null && (stTrace.optBoolean("available") || stTrace.optBoolean("battery_available"))) {
                String tSummary = String.format(Locale.US, "temp: %.1f->%.1f°C (peak %.1f°C, delta %+.1f°C)",
                        stTrace.optInt("start_tenths_c") / 10.0, stTrace.optInt("end_tenths_c") / 10.0,
                        stTrace.optInt("sampled_max_tenths_c") / 10.0, stTrace.optInt("delta_tenths_c") / 10.0);
                String bSummary = stTrace.optBoolean("battery_available")
                        ? String.format(Locale.US, "battery: %.1f%%->%.1f%% (delta %+.1f%%)",
                        stTrace.optDouble("battery_start_percent"), stTrace.optDouble("battery_end_percent"),
                        stTrace.optDouble("battery_delta_percent"))
                        : "";
                appendCsvRow(sb, String.valueOf(sIdx + 1), stId, stLabel, "-", st.optString("status"),
                        "stage_telemetry", zh ? "阶段温升与电量" : "Stage Thermal & Battery", tSummary, "", bSummary);
            }
        }
        sb.append('\n');

        // 4. Telemetry summary & complete curve
        JSONObject trace = report.optJSONObject("temperature");
        appendCsvRow(sb, zh ? "[温升与电量消耗汇总]" : "[Thermal & Battery Summary]");
        appendCsvRow(sb, zh ? "项目" : "Item", zh ? "值" : "Value");
        if (trace != null && trace.optBoolean("available")) {
            appendCsvRow(sb, zh ? "起始电池温度(°C)" : "Start Battery Temp (°C)", String.format(Locale.US, "%.1f", trace.optInt("start_tenths_c") / 10.0));
            appendCsvRow(sb, zh ? "结束电池温度(°C)" : "End Battery Temp (°C)", String.format(Locale.US, "%.1f", trace.optInt("end_tenths_c") / 10.0));
            appendCsvRow(sb, zh ? "峰值电池温度(°C)" : "Peak Battery Temp (°C)", String.format(Locale.US, "%.1f", trace.optInt("sampled_max_tenths_c") / 10.0));
            if (trace.has("sampled_min_tenths_c")) {
                appendCsvRow(sb, zh ? "最低电池温度(°C)" : "Min Battery Temp (°C)", String.format(Locale.US, "%.1f", trace.optInt("sampled_min_tenths_c") / 10.0));
            }
            appendCsvRow(sb, zh ? "总温升(°C)" : "Total Temp Rise (°C)", String.format(Locale.US, "%+.1f", trace.optInt("delta_tenths_c") / 10.0));
        } else {
            appendCsvRow(sb, zh ? "电池温度" : "Battery Temp", zh ? "不可用" : "Unavailable");
        }
        if (trace != null && trace.optBoolean("battery_available")) {
            appendCsvRow(sb, zh ? "起始电量(%)" : "Start Battery (%)", String.format(Locale.US, "%.1f", trace.optDouble("battery_start_percent")));
            appendCsvRow(sb, zh ? "结束电量(%)" : "End Battery (%)", String.format(Locale.US, "%.1f", trace.optDouble("battery_end_percent")));
            appendCsvRow(sb, zh ? "电量净变化(%)" : "Battery Delta (%)", String.format(Locale.US, "%+.1f", trace.optDouble("battery_delta_percent")));
            appendCsvRow(sb, zh ? "电量消耗(%)" : "Battery Consumed (%)", String.format(Locale.US, "%.1f", trace.optDouble("battery_consumed_percent")));
            appendCsvRow(sb, zh ? "测试期间供电状态" : "Power State",
                    trace.optBoolean("plugged_any") ? (zh ? "曾接入外部电源" : "External power connected") : (zh ? "全程电池供电" : "Battery only"));
            if (trace.has("voltage_start_mv")) {
                appendCsvRow(sb, zh ? "起始电压(mV)" : "Start Voltage (mV)", String.valueOf(trace.optInt("voltage_start_mv")));
                appendCsvRow(sb, zh ? "结束电压(mV)" : "End Voltage (mV)", String.valueOf(trace.optInt("voltage_end_mv")));
                appendCsvRow(sb, zh ? "最低/最高电压(mV)" : "Min / Max Voltage (mV)", trace.optInt("voltage_min_mv") + " / " + trace.optInt("voltage_max_mv"));
            }
        } else {
            appendCsvRow(sb, zh ? "电池电量" : "Battery Level", zh ? "无电量采样数据" : "No battery level samples");
        }
        sb.append('\n');

        appendCsvRow(sb, zh ? "[温升与电量时间序列曲线]" : "[Thermal & Battery Time-Series]");
        appendCsvRow(sb,
                zh ? "序号" : "#",
                zh ? "经过时间(s)" : "Elapsed (s)",
                zh ? "时间戳(ms)" : "Timestamp (ms)",
                zh ? "日期时间" : "Date Time",
                zh ? "当前阶段" : "Stage",
                zh ? "电池温度(°C)" : "Battery Temp (°C)",
                zh ? "电池电量(%)" : "Battery (%)",
                zh ? "电池电压(mV)" : "Voltage (mV)",
                zh ? "供电状态" : "Power");
        JSONArray samples = trace != null ? trace.optJSONArray("samples") : null;
        if (samples != null && samples.length() > 0) {
            long firstTs = samples.optJSONObject(0).optLong("timestamp_ms", 0);
            for (int i = 0; i < samples.length(); i++) {
                JSONObject p = samples.optJSONObject(i);
                if (p == null) continue;
                long ts = p.optLong("timestamp_ms", firstTs);
                double elapsedS = Math.max(0L, ts - firstTs) / 1000.0;
                String tempStr = p.has("tenths_c") ? String.format(Locale.US, "%.1f", p.optInt("tenths_c") / 10.0) : "";
                String pctStr = p.has("battery_percent") ? String.format(Locale.US, "%.1f", p.optDouble("battery_percent")) : "";
                String mvStr = p.has("voltage_mv") ? String.valueOf(p.optInt("voltage_mv")) : "";
                appendCsvRow(sb, String.valueOf(i + 1),
                        String.format(Locale.US, "%.1f", elapsedS),
                        String.valueOf(ts),
                        formatHuman(ts),
                        p.optString("stage"),
                        tempStr, pctStr, mvStr,
                        p.optBoolean("plugged") ? (zh ? "充电/供电中" : "Plugged") : (zh ? "电池放电" : "Discharging"));
            }
        }
        return sb.toString();
    }

    /** Complete text report: formatted human report + complete sampled temperature/battery timeline. */
    static String buildCompleteText(JSONObject report) throws JSONException {
        boolean zh = I18n.isZh();
        StringBuilder sb = new StringBuilder();
        JSONObject dev = extractDevice(report);
        if (dev != null) {
            CpuSocResolver.Result soc = CpuSocResolver.resolve(dev);
            sb.append(zh ? "设备：" : "Device: ").append(dev.optString("manufacturer")).append(' ').append(dev.optString("model"))
                    .append(" (").append(dev.optString("device")).append(")\n");
            sb.append(zh ? "系统：" : "OS: ").append("Android ").append(dev.optString("android_release"))
                    .append(" (API ").append(dev.optInt("sdk")).append(") · ABI ").append(dev.optString("abi")).append('\n');
            if (soc.matched) {
                sb.append(zh ? "处理器：" : "SoC: ").append(soc.socName);
                if (CpuSocResolver.isKnownArch(soc.cpuArch)) sb.append(" · ").append(soc.cpuArch);
                sb.append('\n');
            }
            sb.append("────────────────\n");
        }
        sb.append(BenchmarkReport.format(report));

        JSONObject trace = report.optJSONObject("temperature");
        JSONArray samples = trace != null ? trace.optJSONArray("samples") : null;
        if (samples != null && samples.length() > 0) {
            sb.append(zh
                    ? "\n────────────────\n[温升与电量采样时间线（共 " + samples.length() + " 点）]\n"
                    : "\n────────────────\n[Thermal & Battery Timeline (" + samples.length() + " samples)]\n");
            long firstTs = samples.optJSONObject(0).optLong("timestamp_ms", 0);
            for (int i = 0; i < samples.length(); i++) {
                JSONObject p = samples.optJSONObject(i);
                if (p == null) continue;
                long ts = p.optLong("timestamp_ms", firstTs);
                double sec = Math.max(0L, ts - firstTs) / 1000.0;
                sb.append(String.format(Locale.US, "#%03d +%6.1fs", i + 1, sec));
                if (p.has("tenths_c")) sb.append(String.format(Locale.US, " | %.1f°C", p.optInt("tenths_c") / 10.0));
                if (p.has("battery_percent")) sb.append(String.format(Locale.US, " | %.1f%%", p.optDouble("battery_percent")));
                if (p.has("voltage_mv")) sb.append(" | ").append(p.optInt("voltage_mv")).append("mV");
                sb.append(p.optBoolean("plugged") ? (zh ? " | 供电" : " | Plugged") : (zh ? " | 放电" : " | Discharging"));
                String st = p.optString("stage");
                if (st.length() > 0) sb.append(" | ").append(st);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static JSONObject extractDevice(JSONObject report) {
        if (report == null) return null;
        if (report.has("device")) return report.optJSONObject("device");
        JSONArray results = report.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject st = results.optJSONObject(i);
                if (st != null && st.has("device")) return st.optJSONObject("device");
            }
        }
        return null;
    }

    private static JSONObject buildDeviceSummary(JSONObject dev) throws JSONException {
        JSONObject out = new JSONObject();
        CpuSocResolver.Result soc = CpuSocResolver.resolve(dev);
        out.put("manufacturer", dev.optString("manufacturer"));
        out.put("model", dev.optString("model"));
        out.put("device", dev.optString("device"));
        out.put("android_release", dev.optString("android_release"));
        out.put("sdk", dev.optInt("sdk"));
        out.put("abi", dev.optString("abi"));
        out.put("soc_name", soc.socName);
        out.put("cpu_arch", soc.cpuArch);
        out.put("cpu_workers", CpuTopology.workers(dev));
        out.put("emulator", dev.optBoolean("emulator"));
        if (dev.has("total_memory_bytes")) out.put("total_memory_bytes", dev.optLong("total_memory_bytes"));
        if (dev.has("native_library")) out.put("native_library", dev.optString("native_library"));
        return out;
    }

    private static JSONObject buildMetricsSummary(JSONObject report) throws JSONException {
        JSONObject out = new JSONObject();
        boolean isSession = BenchmarkSuite.isSession(report.optString("suite_version"));
        JSONArray stages = collectStages(report, isSession);
        for (int i = 0; i < stages.length(); i++) {
            JSONObject st = stages.optJSONObject(i);
            if (st == null) continue;
            BenchmarkSuite suite = BenchmarkSuite.fromReport(st);
            String key = suite != null ? suite.version : st.optString("suite_version");
            JSONObject item = new JSONObject();
            item.put("label", suite != null ? suite.label() : key);
            item.put("status", st.optString("status"));
            if (suite == BenchmarkSuite.VIDEO) {
                JSONArray samples = st.optJSONArray("samples");
                JSONArray clips = samples != null && samples.length() > 0 ? samples.optJSONObject(0).optJSONArray("results") : null;
                if (clips != null) {
                    int sw = 0, sys = 0, play = 0;
                    for (int c = 0; c < clips.length(); c++) {
                        JSONObject clip = clips.optJSONObject(c);
                        if (clip == null) continue;
                        if (isPathCompleted(clip, "software")) sw++;
                        if (isPathCompleted(clip, "system")) sys++;
                        if (isPathCompleted(clip, "playback")) play++;
                    }
                    item.put("total_clips", clips.length());
                    item.put("software_completed", sw);
                    item.put("system_completed", sys);
                    item.put("playback_completed", play);
                }
            } else {
                JSONObject median = st.optJSONObject("median");
                JSONArray samples = st.optJSONArray("samples");
                JSONObject src = median != null ? median : (samples != null && samples.length() > 0 ? samples.optJSONObject(0) : null);
                if (src != null && suite != null) {
                    JSONObject vals = new JSONObject();
                    for (String metric : suite.metrics) {
                        if (src.has(metric)) vals.put(metric, src.optDouble(metric));
                    }
                    if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL && src.has("fps")) {
                        vals.put("fps", src.optDouble("fps"));
                    }
                    item.put("source", median != null ? "median" : "single_sample");
                    item.put("values", vals);
                }
            }
            out.put(key, item);
        }
        return out;
    }

    private static JSONObject buildTelemetrySummary(JSONObject trace) throws JSONException {
        JSONObject out = new JSONObject();
        if (trace == null) {
            out.put("temperature_available", false);
            out.put("battery_available", false);
            return out;
        }
        out.put("temperature_available", trace.optBoolean("available"));
        if (trace.optBoolean("available")) {
            out.put("temperature_start_c", trace.optInt("start_tenths_c") / 10.0);
            out.put("temperature_end_c", trace.optInt("end_tenths_c") / 10.0);
            out.put("temperature_max_c", trace.optInt("sampled_max_tenths_c") / 10.0);
            if (trace.has("sampled_min_tenths_c")) out.put("temperature_min_c", trace.optInt("sampled_min_tenths_c") / 10.0);
            out.put("temperature_delta_c", trace.optInt("delta_tenths_c") / 10.0);
        }
        out.put("battery_available", trace.optBoolean("battery_available"));
        out.put("plugged_any", trace.optBoolean("plugged_any"));
        if (trace.optBoolean("battery_available")) {
            out.put("battery_start_percent", trace.optDouble("battery_start_percent"));
            out.put("battery_end_percent", trace.optDouble("battery_end_percent"));
            out.put("battery_min_percent", trace.optDouble("battery_min_percent"));
            out.put("battery_max_percent", trace.optDouble("battery_max_percent"));
            out.put("battery_delta_percent", trace.optDouble("battery_delta_percent"));
            out.put("battery_consumed_percent", trace.optDouble("battery_consumed_percent"));
        }
        if (trace.has("voltage_start_mv")) {
            out.put("voltage_start_mv", trace.optInt("voltage_start_mv"));
            out.put("voltage_end_mv", trace.optInt("voltage_end_mv"));
            out.put("voltage_min_mv", trace.optInt("voltage_min_mv"));
            out.put("voltage_max_mv", trace.optInt("voltage_max_mv"));
        }
        JSONArray samples = trace.optJSONArray("samples");
        out.put("sample_count", samples == null ? 0 : samples.length());
        return out;
    }

    private static JSONArray collectStages(JSONObject report, boolean isSession) {
        if (isSession) {
            JSONArray results = report.optJSONArray("results");
            return results != null ? results : new JSONArray();
        }
        JSONArray single = new JSONArray();
        single.put(report);
        return single;
    }

    private static boolean isPathCompleted(JSONObject clip, String path) {
        JSONObject obj = clip.optJSONObject(path);
        return obj != null && "completed".equals(obj.optString("status"));
    }

    private static String collectSampleParams(BenchmarkSuite suite, JSONObject sample) {
        if (suite == null || sample == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : suite.parameters) {
            if (!sample.has(key)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(key).append('=').append(sample.opt(key));
        }
        if (sample.has("native_library")) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("native_library=").append(sample.optString("native_library"));
        }
        if (sample.has("successful_window_swaps")) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("window_swaps=").append(sample.optLong("successful_window_swaps"));
        }
        if (sample.has("worker_pid")) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("pid=").append(sample.optInt("worker_pid"));
        }
        return sb.toString();
    }

    private static void appendCsvRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCsv(cells[i]));
        }
        sb.append("\r\n");
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String formatNum(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        if (Math.abs(value - Math.rint(value)) < 1e-6 && Math.abs(value) < 1e12) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String sanitizeFilePart(String raw) {
        if (raw == null || raw.length() == 0) return "Device";
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (cleaned.length() == 0) return "Device";
        return cleaned.length() > 28 ? cleaned.substring(0, 28) : cleaned;
    }

    private static String formatIso(long ms) {
        synchronized (ISO_FMT) {
            return ISO_FMT.format(new Date(ms));
        }
    }

    private static String formatHuman(long ms) {
        synchronized (HUMAN_FMT) {
            return HUMAN_FMT.format(new Date(ms));
        }
    }
}
