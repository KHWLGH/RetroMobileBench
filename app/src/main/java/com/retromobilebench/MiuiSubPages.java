package com.retromobilebench;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builder for MIUI V5 secondary sub-pages and structured report/hardware cards.
 * Keeps each screen focused and uncluttered on small retro displays.
 */
@SuppressWarnings("deprecation")
final class MiuiSubPages {
    static final String GITHUB_REPO_URL = "https://github.com/KHWLGH/RetroMobileBench";

    interface Navigator {
        void openSubPage(String title, View content);
        void copyText(String label, String content);
        void exportReport(JSONObject report);
        void deleteRun(JSONObject report);
    }

    private static final ExecutorService TEXT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final HashMap<String, String> ASSET_TEXT_CACHE = new HashMap<>();
    private static final HashMap<String, List<String>> ASSET_CHUNK_CACHE = new HashMap<>();
    private static final SimpleDateFormat DATE_TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private MiuiSubPages() { }

    private static synchronized String formatDateTime(long ms) {
        return DATE_TIME_FMT.format(new Date(ms));
    }

    static ScrollView wrapScroll(Context context, View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static LinearLayout pageColumn(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, MiuiUi.dp(context, 6), 0, MiuiUi.dp(context, 20));
        return col;
    }

    static String formatTemperatureShort(JSONObject trace) {
        if (trace == null || !trace.optBoolean("available")) return I18n.t("不可用", "N/A");
        return String.format(Locale.US,
                I18n.t("%.1f → %.1f\u00A0°C\n峰值 %.1f\u00A0°C (%+.1f\u00A0°C)",
                        "%.1f → %.1f\u00A0°C\nPeak %.1f\u00A0°C (%+.1f\u00A0°C)"),
                trace.optInt("start_tenths_c") / 10.0,
                trace.optInt("end_tenths_c") / 10.0,
                trace.optInt("sampled_max_tenths_c") / 10.0,
                trace.optInt("delta_tenths_c") / 10.0);
    }

    static String formatBatteryShort(JSONObject trace) {
        if (trace == null || !trace.optBoolean("battery_available")) return I18n.t("不可用", "N/A");
        double start = trace.optDouble("battery_start_percent", -1);
        double end = trace.optDouble("battery_end_percent", -1);
        double delta = trace.optDouble("battery_delta_percent", 0);
        boolean pluggedAny = trace.optBoolean("plugged_any", false);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%.0f%% → %.0f%% (%+.1f%%)", start, end, delta));
        if (trace.has("voltage_start_mv") && trace.has("voltage_end_mv")) {
            sb.append(String.format(Locale.US, "\n%.2f → %.2f\u00A0V%s",
                    trace.optInt("voltage_start_mv") / 1000.0,
                    trace.optInt("voltage_end_mv") / 1000.0,
                    pluggedAny ? I18n.t(" · 含供电", " · Plugged") : ""));
        } else if (pluggedAny) {
            sb.append(I18n.t("\n含外接供电", "\nExternal Power"));
        }
        return sb.toString();
    }

    private static void appendTelemetryRowsAndCurve(Context context, LinearLayout card, JSONObject trace) {
        JSONArray samples = trace != null ? trace.optJSONArray("samples") : null;
        MiuiUi.TelemetryCurveView curveView = (samples != null && samples.length() >= 2)
                ? new MiuiUi.TelemetryCurveView(context, trace) : null;
        boolean hasTempCurve = curveView != null && curveView.hasTempCurve;
        boolean hasBatCurve = curveView != null && curveView.hasBatCurve;

        if (!hasTempCurve) {
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, I18n.t(context, "电池温度", "Battery Temp"), null,
                    formatTemperatureShort(trace), null, 0));
        }
        if (!hasBatCurve && trace != null && trace.optBoolean("battery_available")) {
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, I18n.t(context, "电池电量", "Battery Level"), null,
                    formatBatteryShort(trace), null, 0));
        }
        if (hasTempCurve || hasBatCurve) {
            MiuiUi.addDivider(context, card);
            card.addView(curveView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private static JSONObject[] collectSessionStages(JSONArray results) {
        JSONObject[] byStage = new JSONObject[BenchmarkSuite.DEFAULT.length];
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                BenchmarkSuite s = BenchmarkSuite.fromReport(item);
                for (int k = 0; k < BenchmarkSuite.DEFAULT.length; k++) {
                    if (BenchmarkSuite.DEFAULT[k] == s && byStage[k] == null) {
                        byStage[k] = item;
                        break;
                    }
                }
            }
        }
        return byStage;
    }

    private static JSONObject stageSource(JSONObject stageReport) {
        if (stageReport == null) return null;
        JSONObject median = stageReport.optJSONObject("median");
        if (median != null) return median;
        JSONArray samples = stageReport.optJSONArray("samples");
        return samples != null && samples.length() > 0 ? samples.optJSONObject(0) : null;
    }

    static String stageHeadlineSummary(BenchmarkSuite suite, JSONObject stageReport) {
        return formatStageHeadline(suite, stageReport, false);
    }

    private static String stageQuickLines(BenchmarkSuite suite, JSONObject stageReport) {
        return formatStageHeadline(suite, stageReport, true);
    }

    private static String formatStageHeadline(BenchmarkSuite suite, JSONObject stageReport, boolean multiline) {
        boolean zh = I18n.isZh();
        if (stageReport == null) return zh ? "未执行" : "Not run";
        String status = stageReport.optString("status", "failed");
        if ("skipped".equals(status) || "unsupported".equals(status)) {
            String msg = stageReport.optString("message");
            return msg.length() > 0 ? msg : BenchmarkReport.statusLabel(status);
        }
        JSONArray samples = stageReport.optJSONArray("samples");
        JSONObject source = stageSource(stageReport);
        if (source == null) return BenchmarkReport.statusLabel(status);

        String sep = multiline ? "\n" : " · ";

        if (suite == BenchmarkSuite.VIDEO) {
            if (samples != null && samples.length() > 0) {
                JSONArray list = samples.optJSONObject(0).optJSONArray("results");
                if (list != null) {
                    int swOk = 0, sysOk = 0, playOk = 0;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null) continue;
                        if ("completed".equals(optPathStatus(item, "software"))) swOk++;
                        if ("completed".equals(optPathStatus(item, "system"))) sysOk++;
                        if ("completed".equals(optPathStatus(item, "playback"))) playOk++;
                    }
                    return String.format(Locale.US,
                            zh ? "软解 %d/7 · 系统 %d/7 · 播放 %d/7" : "SW %d/7 · Sys %d/7 · Play %d/7",
                            swOk, sysOk, playOk);
                }
            }
            return zh ? "7 段视频三路径测试" : "7-clip 3-path suite";
        }
        if (suite.isGpu()) {
            if (suite == BenchmarkSuite.GPU_FILL && source.has("score")) {
                return String.format(Locale.US, "%.1f\u00A0MTexels/s", source.optDouble("score"));
            }
            double fps = source.optDouble("fps", -1);
            if (fps <= 0 && samples != null && samples.length() > 0) {
                fps = samples.optJSONObject(0).optDouble("fps", -1);
            }
            double frames = source.optDouble("score", -1);
            if (fps > 0 && frames > 0) {
                return String.format(Locale.US, "%.2f\u00A0FPS (%.0f\u00A0frames)", fps, frames);
            } else if (frames > 0) {
                return String.format(Locale.US, "%.0f\u00A0frames", frames);
            }
        }
        if (suite == BenchmarkSuite.COREMARK) {
            double s = source.optDouble("single_iterations_per_second", -1);
            double p = source.optDouble("parallel_iterations_per_second", -1);
            if (s > 0 && p > 0) {
                if (multiline) {
                    return String.format(Locale.US,
                            zh ? "单核 %.1f\u00A0iter/s\n多核 %.1f\u00A0iter/s" : "1T %.1f\u00A0iter/s\nMT %.1f\u00A0iter/s",
                            s, p);
                }
                return String.format(Locale.US,
                        zh ? "单核 %.1f · 多核 %.1f\u00A0iter/s" : "1T %.1f · MT %.1f\u00A0iter/s",
                        s, p);
            }
            if (s > 0) return String.format(Locale.US, zh ? "单核 %.1f\u00A0iter/s" : "1T %.1f\u00A0iter/s", s);
        }
        if (suite == BenchmarkSuite.SCIMARK) {
            double s = source.optDouble("single_composite_mflops", -1);
            double p = source.optDouble("parallel_composite_mflops", -1);
            if (s > 0 && p > 0) {
                if (multiline) {
                    return String.format(Locale.US,
                            zh ? "单核 %.1f\u00A0MFLOPS\n多核 %.1f\u00A0MFLOPS" : "1T %.1f\u00A0MFLOPS\nMT %.1f\u00A0MFLOPS",
                            s, p);
                }
                return String.format(Locale.US,
                        zh ? "单核 %.1f · 多核 %.1f\u00A0MFLOPS" : "1T %.1f · MT %.1f\u00A0MFLOPS",
                        s, p);
            }
            if (s > 0) return String.format(Locale.US, zh ? "单核综合 %.1f\u00A0MFLOPS" : "1T %.1f\u00A0MFLOPS", s);
        }
        if (suite == BenchmarkSuite.MEMORY) {
            double copy = source.optDouble("copy_mbps", -1);
            double triad = source.optDouble("parallel_triad_mbps", source.optDouble("single_triad_mbps", -1));
            double lat = source.optDouble("latency_ns", -1);
            if (copy > 0 && triad > 0 && lat > 0) {
                if (multiline) {
                    return String.format(Locale.US,
                            zh ? "复制 %.0f\u00A0MB/s · Triad %.0f\u00A0MB/s\n延迟 %.1f\u00A0ns"
                                    : "Copy %.0f\u00A0MB/s · Triad %.0f\u00A0MB/s\nLatency %.1f\u00A0ns",
                            copy, triad, lat);
                }
                return String.format(Locale.US,
                        zh ? "复制 %.0f · Triad %.0f\u00A0MB/s · 延迟 %.1f\u00A0ns"
                                : "Copy %.0f · Triad %.0f\u00A0MB/s · Latency %.1f\u00A0ns",
                        copy, triad, lat);
            }
        }
        if (suite == BenchmarkSuite.STORAGE) {
            double sr = source.optDouble("sequential_read_mbps", -1);
            double sw = source.optDouble("sequential_write_mbps", -1);
            double rr = source.optDouble("random_read_iops", -1);
            double rw = source.optDouble("random_write_iops", -1);
            if (sr > 0 && sw > 0 && rr > 0 && rw > 0) {
                return String.format(Locale.US,
                        zh ? "顺读 %.1f / 顺写 %.1f\u00A0MB/s%s随读 %.0f / 随写 %.0f\u00A0IOPS"
                                : "Seq R %.1f / W %.1f\u00A0MB/s%sRand R %.0f / W %.0f\u00A0IOPS",
                        sr, sw, sep, rr, rw);
            }
        }
        if (suite == BenchmarkSuite.SQLITE) {
            double comp = source.optDouble("composite_tps", -1);
            double ins = source.optDouble("insert_tps", -1);
            double upd = source.optDouble("update_tps", -1);
            double del = source.optDouble("delete_tps", -1);
            if (comp > 0) {
                return String.format(Locale.US,
                        zh ? "综合 %.1f\u00A0TPS%s插 %.0f / 更 %.0f / 删 %.0f\u00A0TPS"
                                : "Avg %.1f\u00A0TPS%sIns %.0f / Upd %.0f / Del %.0f\u00A0TPS",
                        comp, sep, ins, upd, del);
            }
            if (ins > 0) {
                return String.format(Locale.US,
                        zh ? "插入 %.1f · 更新 %.1f · 删除 %.1f\u00A0TPS" : "Ins %.1f · Upd %.1f · Del %.1f\u00A0TPS",
                        ins, upd, del);
            }
        }
        if (suite == BenchmarkSuite.WEB) {
            double total = source.optDouble("total_ms", -1);
            double feed = source.optDouble("feed_ms", -1);
            double img = source.optDouble("image_ms", -1);
            if (total > 0 && feed > 0 && img > 0) {
                return String.format(Locale.US,
                        zh ? "总计 %.0f\u00A0ms%s信息流 %.0f\u00A0ms + 大图 %.0f\u00A0ms"
                                : "Total %.0f\u00A0ms%sFeed %.0f\u00A0ms + Image %.0f\u00A0ms",
                        total, sep, feed, img);
            }
        }
        if (suite.metrics.length > 0 && source.has(suite.metrics[0])) {
            double v0 = source.optDouble(suite.metrics[0], -1);
            if (v0 > 0) {
                if (suite.metrics.length > 1 && source.has(suite.metrics[1])) {
                    double v1 = source.optDouble(suite.metrics[1], -1);
                    return String.format(Locale.US, "%s %.1f\u00A0%s · %s %.1f\u00A0%s",
                            suite.metricLabel(0), v0, suite.units[0], suite.metricLabel(1), v1, suite.units[1]);
                }
                return String.format(Locale.US, "%s: %.2f\u00A0%s", suite.metricLabel(0), v0, suite.units[0]);
            }
        }
        return BenchmarkReport.statusLabel(status);
    }

    private static String optPathStatus(JSONObject clipItem, String path) {
        JSONObject p = clipItem.optJSONObject(path);
        return p == null ? "" : p.optString("status");
    }

    private static double extractGpuFps(JSONObject stageRep) {
        if (stageRep == null) return -1;
        JSONObject src = stageSource(stageRep);
        if (src != null && src.optDouble("fps", -1) > 0) return src.optDouble("fps", -1);
        JSONArray samples = stageRep.optJSONArray("samples");
        if (samples != null && samples.length() > 0) return samples.optJSONObject(0).optDouble("fps", -1);
        return -1;
    }

    private static String formatPairedGpuFps(JSONObject offRep, JSONObject onRep) {
        boolean zh = I18n.isZh();
        String notRun = zh ? "未测" : "N/A";
        String offStr = extractGpuFps(offRep) > 0 ? String.format(Locale.US, "%.1f\u00A0FPS", extractGpuFps(offRep))
                : (offRep != null ? BenchmarkReport.statusLabel(offRep.optString("status")) : notRun);
        String onStr = extractGpuFps(onRep) > 0 ? String.format(Locale.US, "%.1f\u00A0FPS", extractGpuFps(onRep))
                : (onRep != null ? BenchmarkReport.statusLabel(onRep.optString("status")) : notRun);
        return (zh ? "离屏 " : "Offscreen ") + offStr + (zh ? " · 屏上 " : " · Onscreen ") + onStr;
    }

    static String buildQuickComparisonText(JSONObject report) {
        JSONArray results = report != null ? report.optJSONArray("results") : null;
        return buildQuickComparisonText(report, collectSessionStages(results));
    }

    private static String buildQuickComparisonText(JSONObject report, JSONObject[] byStage) {
        boolean zh = I18n.isZh();
        StringBuilder sb = new StringBuilder();
        JSONObject dev = null;
        for (JSONObject st : byStage) {
            if (st != null && st.has("device")) { dev = st.optJSONObject("device"); break; }
        }
        sb.append(zh ? "【RetroMobileBench 核心指标速览】\n" : "[RetroMobileBench Key Metrics Summary]\n");
        if (dev != null) {
            CpuSocResolver.Result soc = CpuSocResolver.resolve(dev);
            sb.append(zh ? "设备：" : "Device: ").append(dev.optString("manufacturer")).append(' ').append(dev.optString("model"))
                    .append(" (Android ").append(dev.optString("android_release")).append(", API ").append(dev.optInt("sdk")).append(")\n");
            if (soc.matched) {
                sb.append(zh ? "处理器：" : "Processor: ").append(soc.socName);
                if (CpuSocResolver.isKnownArch(soc.cpuArch)) {
                    sb.append(" · ").append(soc.cpuArch);
                }
                sb.append('\n');
            }
        }
        sb.append(zh ? "1. CPU 整数 (CoreMark)：" : "1. CPU Integer (CoreMark): ").append(stageHeadlineSummary(BenchmarkSuite.COREMARK, byStage[0])).append('\n');
        sb.append(zh ? "2. CPU 浮点 (SciMark 2.0)：" : "2. CPU Float (SciMark 2.0): ").append(stageHeadlineSummary(BenchmarkSuite.SCIMARK, byStage[1])).append('\n');
        sb.append(zh ? "3. 内存 (8MiB复制/4MiB延迟/24MiB Triad)：" : "3. Memory (8MiB Copy/4MiB Latency/24MiB Triad): ").append(stageHeadlineSummary(BenchmarkSuite.MEMORY, byStage[2])).append('\n');
        sb.append(zh ? "4. 存储 (256KiB×128顺序/4KiB×256随机)：" : "4. Storage (256KiB×128 Seq/4KiB×256 Rand): ").append(stageHeadlineSummary(BenchmarkSuite.STORAGE, byStage[3])).append('\n');
        sb.append(zh ? "5. SQLite (900独立事务/1KiB行)：" : "5. SQLite (900 COMMITs/1KiB row): ").append(stageHeadlineSummary(BenchmarkSuite.SQLITE, byStage[4])).append('\n');
        sb.append(zh ? "6. Web 竖屏信息流与大图：" : "6. Web Portrait Feed & Images: ").append(stageHeadlineSummary(BenchmarkSuite.WEB, byStage[5])).append('\n');
        sb.append(zh ? "7. 视频解码与播放 (7段)：" : "7. Video Decode & Playback (7 clips): ").append(stageHeadlineSummary(BenchmarkSuite.VIDEO, byStage[6])).append('\n');
        sb.append("8. GPU Egypt HD: ").append(formatPairedGpuFps(byStage[7], byStage[8])).append('\n');
        sb.append("9. GPU T-Rex: ").append(formatPairedGpuFps(byStage[9], byStage[10])).append('\n');
        sb.append(zh ? "10. GPU 纹理填充率：" : "10. GPU Texture Fill Rate: ").append(stageHeadlineSummary(BenchmarkSuite.GPU_FILL, byStage[11])).append('\n');
        JSONObject trace = report.optJSONObject("temperature");
        sb.append(zh ? "电池温度：" : "Battery Temp: ").append(formatTemperatureShort(trace).replace('\n', ' '));
        if (trace != null && trace.optBoolean("battery_available")) {
            sb.append(zh ? "\n电池电量：" : "\nBattery Level: ").append(formatBatteryShort(trace).replace('\n', ' '));
        }
        return sb.toString().replace('\u00A0', ' ');
    }

    /** Sub-page A0: Full report detail (session opens overview + 12-stage list; single stage opens stage detail directly). */
    static View buildReportDetailPage(Context context, final JSONObject report, boolean isBusy, final Navigator nav) {
        boolean zh = I18n.isZh(context);
        String suiteVer = report.optString("suite_version");
        boolean isSession = BenchmarkSuite.isSession(suiteVer);
        BenchmarkSuite singleSuite = isSession ? null : BenchmarkSuite.fromReport(report);
        if (!isSession && singleSuite != null) {
            return buildStageDetailPage(context, singleSuite, report, nav, true);
        }

        LinearLayout col = pageColumn(context);
        String status = report.optString("status", "failed");
        String sessionId = report.optString("session_id");

        col.addView(MiuiUi.sectionTitle(context, zh ? "报告总览" : "Report Overview"));
        LinearLayout summaryCard = MiuiUi.cardGroup(context);
        summaryCard.addView(MiuiUi.kvRow(context,
                isSession ? (zh ? "完整测试套件（12 阶段）" : "Full Benchmark Suite (12 Stages)") : suiteVer,
                sessionId.length() > 0 ? (zh ? "会话：" : "Session: ") + MiuiUi.ellipsizeMiddle(sessionId, 8, 6) : null,
                null, BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status)));

        if (report.has("started_ms")) {
            MiuiUi.addDivider(context, summaryCard);
            long started = report.optLong("started_ms");
            summaryCard.addView(MiuiUi.kvRow(context, zh ? "测试时间" : "Run Time", null,
                    formatDateTime(started), null, 0));
        }
        long wallMs = report.optLong("wall_ms",
                report.optLong("ended_ms", 0) - report.optLong("started_ms", 0));
        if (wallMs > 0) {
            MiuiUi.addDivider(context, summaryCard);
            summaryCard.addView(MiuiUi.kvRow(context, zh ? "总耗时" : "Duration", null, MiuiUi.formatDuration(wallMs), null, 0));
        }
        appendTelemetryRowsAndCurve(context, summaryCard, report.optJSONObject("temperature"));

        for (String warnKey : new String[]{"persistence_warning", "preparation_warning", "media_preparation_warning", "message"}) {
            String warn = report.optString(warnKey);
            if (warn.length() > 0) {
                MiuiUi.addDivider(context, summaryCard);
                summaryCard.addView(MiuiUi.kvRow(context, zh ? "提示" : "Notice", warn, null,
                        zh ? "注意" : "Note", MiuiUi.TONE_WARN));
            }
        }

        MiuiUi.addDivider(context, summaryCard);
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setBaselineAligned(false);
        int pad = MiuiUi.dp(context, 12);
        btnRow.setPadding(pad, MiuiUi.dp(context, 10), pad, pad);

        Button exportBtn = MiuiUi.styledButton(context, zh ? "导出完整数据" : "Export Report", true, false,
                () -> nav.exportReport(report));
        exportBtn.setEnabled(!isBusy);
        btnRow.addView(exportBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button rawBtn = MiuiUi.styledButton(context, zh ? "原始文本" : "Raw Text", false, false,
                () -> nav.openSubPage(zh ? "原始文本报告" : "Raw Text Report", buildRawReportPage(context, report, nav)));
        LinearLayout.LayoutParams rawLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rawLp.leftMargin = MiuiUi.dp(context, 8);
        btnRow.addView(rawBtn, rawLp);
        summaryCard.addView(btnRow);
        col.addView(summaryCard);

        if (isSession) {
            JSONArray results = report.optJSONArray("results");
            JSONObject[] byStage = collectSessionStages(results);

            col.addView(MiuiUi.sectionTitle(context, zh ? "核心指标速览" : "Key Metrics Summary"));
            LinearLayout quickCard = MiuiUi.cardGroup(context);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "CPU 整数 (CoreMark®)" : "CPU Integer (CoreMark®)",
                    stageQuickLines(BenchmarkSuite.COREMARK, byStage[0]),
                    zh ? "2000B 栈工作区 · 双种子校验 · 每段 ≥10s" : "2000B stack · two-seed check · >=10s per segment"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "CPU 浮点 (SciMark 2.0)" : "CPU Float (SciMark 2.0)",
                    stageQuickLines(BenchmarkSuite.SCIMARK, byStage[1]),
                    zh ? "5 项浮点内核均值 (FFT/SOR/MC/稀疏/LU)" : "5-kernel FP composite (FFT/SOR/MC/Sparse/LU)"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "内存带宽与延迟" : "Memory Bandwidth & Latency",
                    stageQuickLines(BenchmarkSuite.MEMORY, byStage[2]),
                    zh ? "8MiB 复制/填充 · 4MiB 随机指针链(100万节点)\n24MiB 共享 Triad (3×8MiB)"
                            : "8MiB Copy/Fill · 4MiB Ptr Latency (1M nodes)\n24MiB Shared Triad (3×8MiB)"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "闪存读写 (Storage)" : "Storage I/O",
                    stageQuickLines(BenchmarkSuite.STORAGE, byStage[3]),
                    zh ? "32MiB 文件(4–32MiB 自适应) · O_SYNC 写入\n256KiB×128 顺序 · 4KiB×256 随机"
                            : "32MiB file (4-32MiB adaptive) · O_SYNC write\n256KiB×128 seq · 4KiB×256 random"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "SQLite 事务吞吐" : "SQLite Transactions",
                    stageQuickLines(BenchmarkSuite.SQLITE, byStage[4]),
                    zh ? "INSERT/UPDATE/DELETE 各 300 独立事务\n共 900 次 COMMIT · 1KiB/行 · FULL 同步"
                            : "300 INSERT/UPDATE/DELETE transactions each\n900 COMMITs total · 1KiB/row · FULL sync"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "Web 竖屏信息流与大图" : "Web Feed & Large Images",
                    stageQuickLines(BenchmarkSuite.WEB, byStage[5]),
                    zh ? "竖屏 640卡片+2000列表+480段落\n480p/720p/1080p 大图解码×20 (越低越好)"
                            : "Portrait 640 cards+2000 list+480 text blocks\n480p/720p/1080p image decode ×20 (lower is better)"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "视频解码与播放" : "Video Decode & Playback",
                    stageQuickLines(BenchmarkSuite.VIDEO, byStage[6]),
                    zh ? "7\u00A0段 240p–1080p 素材 (H.263/MPEG-4/H.264/VP8/VP9)"
                            : "7\u00A0clips 240p–1080p (H.263/MPEG-4/H.264/VP8/VP9)"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "GPU Egypt HD (中压场景)" : "GPU Egypt HD (Medium Load)",
                    formatPairedGpuFps(byStage[7], byStage[8]),
                    zh ? "1080p 离屏 vs 实际屏幕分辨率屏上" : "1080p offscreen vs native window onscreen"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "GPU T-Rex (高压场景)" : "GPU T-Rex (Heavy Load)",
                    formatPairedGpuFps(byStage[9], byStage[10]),
                    zh ? "1080p 离屏 vs 实际屏幕分辨率屏上" : "1080p offscreen vs native window onscreen"));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.summaryRow(context, zh ? "GPU 纹理填充率" : "GPU Texture Fill Rate",
                    stageQuickLines(BenchmarkSuite.GPU_FILL, byStage[11]),
                    zh ? "1080p 离屏四层多纹理混合" : "1080p offscreen quad-layer multi-texture blend"));
            MiuiUi.addDivider(context, quickCard);
            final JSONObject[] finalByStage = byStage;
            quickCard.addView(MiuiUi.navRow(context,
                    zh ? "复制核心指标摘要" : "Copy Key Metrics Summary",
                    zh ? "一键复制以上 10 项核心指标与工作量说明" : "Copy all 10 key metrics and workloads to clipboard",
                    zh ? "复制" : "Copy", null, 0,
                    () -> nav.copyText(zh ? "核心指标摘要" : "Key Metrics", buildQuickComparisonText(report, finalByStage))));
            col.addView(quickCard);

            String[] groupTitles = zh
                    ? new String[]{"处理器算力 (CPU)", "内存与存储 (Memory & I/O)", "Web 与视频 (Daily & Media)", "OpenGL ES 2.0 (GPU)"}
                    : new String[]{"Processor (CPU)", "Memory & Storage (I/O)", "Web & Media", "OpenGL ES 2.0 (GPU)"};
            int[][] groupIndices = {{0, 1}, {2, 3, 4}, {5, 6}, {7, 8, 9, 10, 11}};
            for (int g = 0; g < groupTitles.length; g++) {
                col.addView(MiuiUi.sectionTitle(context, groupTitles[g]));
                LinearLayout card = MiuiUi.cardGroup(context);
                int[] indices = groupIndices[g];
                for (int j = 0; j < indices.length; j++) {
                    if (j > 0) MiuiUi.addDivider(context, card);
                    final BenchmarkSuite suite = BenchmarkSuite.DEFAULT[indices[j]];
                    final JSONObject stageRep = byStage[indices[j]];
                    final String stageLabel = suite.label();
                    if (stageRep != null) {
                        String st = stageRep.optString("status", "failed");
                        card.addView(MiuiUi.navRow(context, stageLabel, stageHeadlineSummary(suite, stageRep), null,
                                BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st),
                                () -> nav.openSubPage(stageLabel, buildStageDetailPage(context, suite, stageRep, nav, false))));
                    } else {
                        card.addView(MiuiUi.kvRow(context, stageLabel,
                                zh ? "未执行到该阶段" : "Stage not reached", null,
                                zh ? "未运行" : "Not Run", MiuiUi.TONE_NEUTRAL));
                    }
                }
                col.addView(card);
            }
        }

        if (report.has("id") && !isBusy) {
            LinearLayout delBox = new LinearLayout(context);
            delBox.setOrientation(LinearLayout.VERTICAL);
            int m = MiuiUi.dp(context, 12);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(m, MiuiUi.dp(context, 14), m, 0);
            delBox.setLayoutParams(dlp);
            delBox.addView(MiuiUi.styledButton(context, zh ? "删除此条记录" : "Delete Record",
                    false, true, () -> nav.deleteRun(report)));
            col.addView(delBox);
        }
        return wrapScroll(context, col);
    }

    /** Sub-page A: Detailed single-stage report view. */
    static View buildStageDetailPage(Context context, BenchmarkSuite suite, JSONObject report, Navigator nav, boolean allowDelete) {
        boolean zh = I18n.isZh(context);
        LinearLayout col = pageColumn(context);
        String status = report.optString("status", "failed");

        JSONArray samples = report.optJSONArray("samples");
        JSONObject firstSample = samples != null && samples.length() > 0 ? samples.optJSONObject(0) : null;
        String stageSubtitle = null;
        if (suite != null) {
            int param = 4;
            if (suite == BenchmarkSuite.STORAGE) {
                long fb = firstSample != null ? firstSample.optLong("file_bytes", 0) : 0;
                param = fb > 0 ? (int) (fb / (1024L * 1024L)) : 32;
            } else if (suite == BenchmarkSuite.SQLITE) {
                param = firstSample != null ? firstSample.optInt("transactions", 300) : 300;
            } else if (firstSample != null && firstSample.has("parallel_threads")) {
                param = firstSample.optInt("parallel_threads", 4);
            } else if (report.has("device")) {
                param = suite.parameter(report.optJSONObject("device"));
            }
            stageSubtitle = suite.progress(param);
        }
        col.addView(MiuiUi.sectionTitle(context, zh ? "阶段概览" : "Stage Overview"));
        LinearLayout overview = MiuiUi.cardGroup(context);
        final String suiteTitle = suite != null ? suite.label() : report.optString("suite_version");
        overview.addView(MiuiUi.kvRow(context, suiteTitle,
                stageSubtitle,
                null, BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status)));
        if (report.has("started_ms")) {
            MiuiUi.addDivider(context, overview);
            long started = report.optLong("started_ms");
            long ended = report.optLong("ended_ms", started);
            overview.addView(MiuiUi.kvRow(context, zh ? "开始时间" : "Start Time", null,
                    formatDateTime(started), null, 0));
            if (ended > started) {
                MiuiUi.addDivider(context, overview);
                overview.addView(MiuiUi.kvRow(context, zh ? "耗时" : "Duration", null,
                        MiuiUi.formatDuration(ended - started), null, 0));
            }
        }
        MiuiUi.addDivider(context, overview);
        int reps = report.optInt("repetitions", 1);
        int doneRounds = samples == null ? 0 : samples.length();
        String roundStr = zh
                ? (doneRounds + " / " + reps + " 轮" + (report.has("median") ? "（中位数）" : ""))
                : (doneRounds + " / " + reps + " rounds" + (report.has("median") ? " (Median)" : ""));
        overview.addView(MiuiUi.kvRow(context, zh ? "采样轮次" : "Rounds", null, roundStr, null, 0));
        appendTelemetryRowsAndCurve(context, overview, report.optJSONObject("temperature"));

        String msg = report.optString("message");
        if (msg.length() > 0) {
            MiuiUi.addDivider(context, overview);
            overview.addView(MiuiUi.kvRow(context, zh ? "说明" : "Note", msg, null, null, 0));
        }
        col.addView(overview);

        if (suite == BenchmarkSuite.VIDEO) {
            appendVideoStageDetails(context, col, report);
        } else if (suite != null) {
            appendStandardStageMetrics(context, col, suite, report);
        }

        if (suite != null && samples != null && samples.length() > 0) {
            appendStageEnvironmentCard(context, col, suite, samples.optJSONObject(0));
            if (samples.length() > 1 && suite != BenchmarkSuite.VIDEO) {
                appendPerRoundSamplesCard(context, col, suite, samples);
            }
        }

        col.addView(MiuiUi.sectionTitle(context, zh ? "数据与操作" : "Data & Actions"));
        LinearLayout actions = MiuiUi.cardGroup(context);
        actions.addView(MiuiUi.navRow(context, zh ? "查看原始文本" : "View Raw Text", null, null, null, 0, () ->
                nav.openSubPage((suite != null ? suite.label() : (zh ? "阶段" : "Stage")) + (zh ? " · 文本" : " · Text"),
                        buildRawReportPage(context, report, nav))));
        MiuiUi.addDivider(context, actions);
        actions.addView(MiuiUi.navRow(context, zh ? "导出完整数据" : "Export Complete Data",
                zh ? "导出为 JSON / CSV / TXT 文件" : "Export as JSON / CSV / TXT file", null, null, 0,
                () -> nav.exportReport(report)));
        col.addView(actions);

        if (allowDelete && report.has("id")) {
            LinearLayout delBox = new LinearLayout(context);
            delBox.setOrientation(LinearLayout.VERTICAL);
            int m = MiuiUi.dp(context, 12);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(m, MiuiUi.dp(context, 14), m, 0);
            delBox.setLayoutParams(dlp);
            delBox.addView(MiuiUi.styledButton(context, zh ? "删除此条记录" : "Delete Record",
                    false, true, () -> nav.deleteRun(report)));
            col.addView(delBox);
        }

        return wrapScroll(context, col);
    }

    private static void appendStandardStageMetrics(Context context, LinearLayout col, BenchmarkSuite suite, JSONObject report) {
        boolean zh = I18n.isZh(context);
        JSONObject median = report.optJSONObject("median");
        JSONArray samples = report.optJSONArray("samples");
        JSONObject source = stageSource(report);
        if (source == null) return;

        LinearLayout card = MiuiUi.cardGroup(context);

        java.util.HashMap<String, Double> maxByUnit = new java.util.HashMap<>();
        for (int i = 0; i < suite.metrics.length; i++) {
            String key = suite.metrics[i];
            if (source.has(key)) {
                double v = source.optDouble(key, 0);
                String unit = suite.units[i];
                Double prev = maxByUnit.get(unit);
                if (prev == null || v > prev) maxByUnit.put(unit, v);
            }
        }

        boolean added = false;
        for (int i = 0; i < suite.metrics.length; i++) {
            String key = suite.metrics[i];
            if (!source.has(key)) continue;
            if (added) MiuiUi.addDivider(context, card);
            double val = source.optDouble(key, 0);
            String valStr = String.format(Locale.US, "%.2f\u00A0%s", val, suite.units[i]);
            Double unitMaxObj = maxByUnit.get(suite.units[i]);
            double unitMax = unitMaxObj != null ? unitMaxObj : 0;
            float ratio = unitMax > 0 ? (float) (val / unitMax) : 0.5f;
            int barColor = suite == BenchmarkSuite.WEB ? 0xFF188AE2 : MiuiUi.ORANGE;
            card.addView(MiuiUi.metricBarRow(context, suite.metricLabel(i), valStr, ratio, barColor));
            added = true;

            if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL) {
                double fps = source.optDouble("fps", -1);
                if (fps <= 0 && samples != null && samples.length() > 0) {
                    fps = samples.optJSONObject(0).optDouble("fps", -1);
                }
                if (fps > 0) {
                    MiuiUi.addDivider(context, card);
                    String mode = suite.gpuTest().endsWith("_off")
                            ? (zh ? "离屏渲染" : "Offscreen")
                            : (zh ? "窗口提交" : "Onscreen");
                    card.addView(MiuiUi.metricBarRow(context,
                            zh ? ("平均帧率（" + mode + "）") : ("Avg Frame Rate (" + mode + ")"),
                            String.format(Locale.US, "%.2f\u00A0FPS", fps), Math.min(1f, (float) (fps / 60.0)), MiuiUi.GREEN));
                }
            }
        }
        if (added) {
            col.addView(MiuiUi.sectionTitle(context, median != null
                    ? (zh ? "核心测量结果（三轮有效中位数）" : "Measured Results (3-Round Median)")
                    : (zh ? "测量结果（单轮 / 原始值）" : "Measured Results (Single / Raw)")));
            col.addView(card);
        }
    }

    private static void appendVideoStageDetails(Context context, LinearLayout col, JSONObject report) {
        boolean zh = I18n.isZh(context);
        JSONArray samples = report.optJSONArray("samples");
        if (samples == null || samples.length() == 0) return;
        JSONArray clips = samples.optJSONObject(0).optJSONArray("results");
        if (clips == null) return;

        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.optJSONObject(i);
            if (clip == null) continue;
            col.addView(MiuiUi.sectionTitle(context,
                    (zh ? "视频素材 " : "Video Clip ") + (i + 1) + ": " + clip.optString("id")));
            LinearLayout card = MiuiUi.cardGroup(context);
            String[] paths = {"software", "system", "playback"};
            String[] names = zh
                    ? new String[]{"固定软解 (FFmpeg)", "系统解码 (MediaCodec)", "实时窗口播放"}
                    : new String[]{"FFmpeg SW Decode", "System MediaCodec", "Real-Time Playback"};
            boolean added = false;
            for (int p = 0; p < paths.length; p++) {
                JSONObject pathObj = clip.optJSONObject(paths[p]);
                if (pathObj == null) continue;
                if (added) MiuiUi.addDivider(context, card);
                String st = pathObj.optString("status");
                String right = "";
                if (pathObj.has("fps") && !pathObj.isNull("fps")) {
                    right = String.format(Locale.US, "%.2f\u00A0FPS", pathObj.optDouble("fps"));
                }
                StringBuilder sub = new StringBuilder();
                if (pathObj.has("decoder")) sub.append(pathObj.optString("decoder"));
                if (pathObj.has("realtime")) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(pathObj.optBoolean("realtime")
                            ? (zh ? "达到实时" : "Real-time OK")
                            : (zh ? "低于实时" : "Below real-time"));
                }
                if (pathObj.has("error_stage")) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(pathObj.optString("error_stage"));
                }
                card.addView(MiuiUi.kvRow(context, names[p], sub.toString(),
                        right, BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st)));
                added = true;
            }
            col.addView(card);
        }
    }

    private static void appendStageEnvironmentCard(Context context, LinearLayout col, BenchmarkSuite suite, JSONObject sample) {
        if (sample == null) return;
        boolean zh = I18n.isZh(context);
        LinearLayout card = MiuiUi.cardGroup(context);
        boolean hasRow = false;
        if (suite.isGpu()) {
            String vendor = sample.optString("vendor");
            String renderer = sample.optString("renderer");
            if (vendor.length() > 0 || renderer.length() > 0) {
                String gpuModel = DeviceHardwareExtra.formatGpuModel(renderer);
                String vendorDisplay = DeviceHardwareExtra.formatGpuVendor(vendor);
                boolean isTranslated = DeviceHardwareExtra.isEmulatorTranslatedGpu(renderer);
                boolean isSoftware = DeviceHardwareExtra.isSoftwareGpu(renderer);
                String tag = isSoftware
                        ? (zh ? "软件渲染" : "Software")
                        : (isTranslated ? (zh ? "宿主转译" : "Translated") : null);
                int tone = isSoftware ? MiuiUi.TONE_WARN : MiuiUi.TONE_NEUTRAL;
                card.addView(MiuiUi.kvRow(context, zh ? "GPU 型号" : "GPU Model",
                        gpuModel.length() > 0 ? gpuModel : (zh ? "未知" : "Unknown"),
                        vendorDisplay.length() > 0 ? vendorDisplay : null, tag, tone));
                if (renderer.length() > 0 && !renderer.equals(gpuModel)) {
                    MiuiUi.addDivider(context, card);
                    card.addView(MiuiUi.kvRow(context, zh ? "GL 渲染器" : "GL Renderer", renderer, null, null, 0));
                }
                hasRow = true;
            }
            if (sample.has("render_width") || sample.has("render_height")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, zh ? "渲染分辨率与模式" : "Render Size & Mode", null,
                        sample.optInt("render_width", 1920) + "×" + sample.optInt("render_height", 1080) + "\u00A0px\n"
                                + (suite.gpuTest().endsWith("_off")
                                ? (zh ? "离屏渲染" : "Offscreen")
                                : (zh ? "屏上提交" : "Onscreen")), null, 0));
                hasRow = true;
            }
            if (sample.has("successful_window_swaps")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, zh ? "窗口成功交换次数" : "Window Buffer Swaps", null,
                        sample.optLong("successful_window_swaps") + (zh ? "\u00A0次" : "\u00A0swaps"), null, 0));
                hasRow = true;
            }
        } else if (suite == BenchmarkSuite.WEB) {
            card.addView(MiuiUi.kvRow(context, zh ? "CSS / 物理竖屏视口" : "CSS / Physical Viewport", null,
                    "CSS " + sample.optInt("viewport_width") + "×" + sample.optInt("viewport_height")
                            + (zh ? "\n物理 " : "\nPhys ") + sample.optInt("physical_width") + "×" + sample.optInt("physical_height")
                            + " (DPR\u00A0" + sample.optDouble("dpr") + ")", null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, zh ? "测试负载构成" : "Workload Composition",
                    zh ? "信息流卡片 640 张 + 列表项 2000 条 + 段落 480 段 + 480p/720p/1080p 大图解码 20 次"
                            : "640 feed cards + 2000 list items + 480 text blocks + 20× 480p/720p/1080p HD image decodes",
                    null, null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, zh ? "计时器与精度" : "Clock & Resolution", null,
                    sample.optString("clock_source") + "\n" + sample.optDouble("clock_resolution_ms") + "\u00A0ms", null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, zh ? "Web 宿主身份" : "Web Host ID", sample.optString("host_build_id"), null, null, 0));
            hasRow = true;
        } else {
            if (sample.has("parallel_threads")) {
                card.addView(MiuiUi.kvRow(context, zh ? "并行工作线程数" : "Worker Threads", null,
                        sample.optInt("parallel_threads") + (zh ? "\u00A0线程" : "\u00A0threads"), null, 0));
                hasRow = true;
            }
            if (sample.has("native_library")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, zh ? "原生计算库" : "Native Library", sample.optString("native_build_id"),
                        sample.optString("native_library") + "\n(" + sample.optString("native_abi") + ")", null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.MEMORY && sample.has("buffer_bytes")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, zh ? "内存测试量" : "Memory Footprint",
                        zh ? "8\u00A0MiB 复制/填充(共 16\u00A0MiB) · 4\u00A0MiB 随机指针链(100 万节点) · 24\u00A0MiB 共享 Triad(3×8\u00A0MiB)"
                                : "8\u00A0MiB copy/fill (16\u00A0MiB total) · 4\u00A0MiB pointer chase (1M nodes) · 24\u00A0MiB shared Triad (3×8\u00A0MiB)",
                        null, null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.STORAGE && sample.has("file_bytes")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                long fileMib = sample.optLong("file_bytes") / (1024 * 1024);
                card.addView(MiuiUi.kvRow(context, zh ? "存储测试量与模式" : "Storage Footprint & Mode",
                        zh ? ("256\u00A0KiB 顺序读写 × " + (fileMib * 4) + "\u00A0块 · 4\u00A0KiB 随机读写 × " + sample.optInt("random_operations", 256) + "\u00A0次")
                                : ("256\u00A0KiB seq R/W × " + (fileMib * 4) + "\u00A0blocks · 4\u00A0KiB random R/W × " + sample.optInt("random_operations", 256) + "\u00A0ops"),
                        fileMib + "\u00A0MiB · " + sample.optString("read_mode"), null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.SQLITE && sample.has("sqlite_version")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                int tx = sample.optInt("transactions", 300);
                card.addView(MiuiUi.kvRow(context, zh ? "SQLite 引擎与测试量" : "SQLite Engine & Workload",
                        zh ? ("INSERT / UPDATE / DELETE 各 " + tx + "\u00A0独立事务（共 " + (tx * 3) + "\u00A0次 COMMIT）· 1\u00A0KiB/行 · DELETE 日志 + FULL 同步")
                                : ("INSERT / UPDATE / DELETE " + tx + "\u00A0tx each (" + (tx * 3) + "\u00A0COMMITs total) · 1\u00A0KiB/row · DELETE journal + FULL sync"),
                        "v" + sample.optString("sqlite_version"), null, 0));
                hasRow = true;
            }
        }
        if (hasRow) {
            col.addView(MiuiUi.sectionTitle(context, zh ? "测试参数与运行环境" : "Parameters & Environment"));
            col.addView(card);
        }
    }

    private static void appendPerRoundSamplesCard(Context context, LinearLayout col, BenchmarkSuite suite, JSONArray samples) {
        boolean zh = I18n.isZh(context);
        col.addView(MiuiUi.sectionTitle(context, zh ? "各轮原始采样明细" : "Per-Round Raw Samples"));
        LinearLayout card = MiuiUi.cardGroup(context);
        for (int r = 0; r < samples.length(); r++) {
            JSONObject sample = samples.optJSONObject(r);
            if (sample == null) continue;
            if (r > 0) MiuiUi.addDivider(context, card);
            String st = sample.optString("status");
            card.addView(MiuiUi.kvRow(context,
                    zh ? ("第 " + (r + 1) + " 轮") : ("Round " + (r + 1)),
                    null, null, BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st)));
            for (int m = 0; m < suite.metrics.length; m++) {
                String k = suite.metrics[m];
                if (!sample.has(k)) continue;
                String valStr = String.format(Locale.US, "%.2f\u00A0%s", sample.optDouble(k), suite.units[m]);
                card.addView(MiuiUi.compactMetricRow(context, suite.metricLabel(m), valStr));
            }
            View spacer = new View(context);
            card.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, MiuiUi.dp(context, 6)));
        }
        col.addView(card);
    }

    /** Sub-page C: CPU per-core topology, GPU GL extensions, /proc/cpuinfo, and JNI probe report. */
    static View buildCpuProbePage(Context context, JSONObject device, String nativeProbeText, Navigator nav) {
        boolean zh = I18n.isZh(context);
        LinearLayout col = pageColumn(context);

        if (device != null) {
            JSONArray cores = device.optJSONArray("cpu_cores");
            if (cores != null && cores.length() > 0) {
                col.addView(MiuiUi.sectionTitle(context,
                        zh ? ("CPU 各核心状态 (" + cores.length() + " 核)")
                                : ("CPU Per-Core Status (" + cores.length() + " cores)")));
                LinearLayout coreCard = MiuiUi.cardGroup(context);
                for (int i = 0; i < cores.length(); i++) {
                    JSONObject c = cores.optJSONObject(i);
                    if (c == null) continue;
                    if (i > 0) MiuiUi.addDivider(context, coreCard);
                    int idx = c.optInt("index", i);
                    boolean online = "1".equals(c.optString("online", "1"));
                    String cur = MiuiUi.formatKhz(c.optString("frequency_khz"));
                    String min = MiuiUi.formatKhz(c.optString("min_frequency_khz"));
                    String max = MiuiUi.formatKhz(c.optString("max_frequency_khz"));
                    String gov = c.optString("governor", "unknown");
                    coreCard.addView(MiuiUi.kvRow(context,
                            (zh ? "核心 #" : "Core #") + idx,
                            min + " ~ " + max + " · " + gov,
                            cur, online ? (zh ? "在线" : "Online") : (zh ? "离线" : "Offline"),
                            online ? MiuiUi.TONE_OK : MiuiUi.TONE_NEUTRAL));
                }
                col.addView(coreCard);
            }

            CpuSocResolver.Result soc = CpuSocResolver.resolve(device);
            col.addView(MiuiUi.sectionTitle(context, zh ? "SoC 与处理器识别" : "SoC & Processor Identity"));
            LinearLayout socCard = MiuiUi.cardGroup(context);
            socCard.addView(MiuiUi.kvRow(context, zh ? "识别型号" : "Identified SoC",
                    soc.sourceNote.length() > 0 ? soc.sourceNote : null,
                    soc.socName, soc.matched ? (zh ? "已匹配" : "Matched") : (zh ? "原始值" : "Raw"),
                    soc.matched ? MiuiUi.TONE_OK : MiuiUi.TONE_NEUTRAL));
            if (CpuSocResolver.isKnownArch(soc.cpuArch)) {
                MiuiUi.addDivider(context, socCard);
                socCard.addView(MiuiUi.kvRow(context, zh ? "核心微架构" : "Microarchitecture",
                        soc.cpuArchDetail.length() > 0 ? soc.cpuArchDetail : null,
                        soc.cpuArch, null, 0));
            }
            JSONObject extraObj = device.optJSONObject("ui_extra");
            if (extraObj != null) {
                String[][] propKeys = {
                        {"sys_board_platform", "ro.board.platform"},
                        {"sys_product_board", "ro.product.board"},
                        {"sys_chipname", "ro.chipname"},
                        {"sys_hardware_chipname", "ro.hardware.chipname"},
                        {"sys_mediatek_platform", "ro.mediatek.platform"},
                        {"sys_arch", "ro.arch"},
                        {"sys_soc_model", "ro.soc.model"},
                        {"sysfs_soc_machine", "sysfs soc0/machine"},
                        {"sysfs_soc_id", "sysfs soc0/soc_id"}
                };
                for (String[] pair : propKeys) {
                    String val = extraObj.optString(pair[0]);
                    if (val.length() > 0) {
                        MiuiUi.addDivider(context, socCard);
                        socCard.addView(MiuiUi.kvRow(context, pair[1], null, val, null, 0));
                    }
                }
            }
            col.addView(socCard);

            JSONObject id = device.optJSONObject("cpu_identity");
            if (id != null && id.length() > 0) {
                col.addView(MiuiUi.sectionTitle(context, "/proc/cpuinfo"));
                LinearLayout idCard = MiuiUi.cardGroup(context);
                Iterator<String> keys = id.keys();
                boolean first = true;
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!first) MiuiUi.addDivider(context, idCard);
                    idCard.addView(MiuiUi.kvRow(context, k, id.optString(k), null, null, 0));
                    first = false;
                }
                col.addView(idCard);
            }

            JSONObject extra = device.optJSONObject("ui_extra");
            if (extra != null && extra.has("gpu_extensions")) {
                final String glExt = extra.optString("gpu_extensions");
                col.addView(MiuiUi.sectionTitle(context, zh ? "OpenGL ES 扩展列表" : "OpenGL ES Extensions"));
                LinearLayout glCard = MiuiUi.cardGroup(context);
                TextView extTv = new TextView(context);
                extTv.setText(glExt);
                extTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                extTv.setTypeface(Typeface.MONOSPACE);
                extTv.setTextColor(MiuiUi.TEXT_SECONDARY);
                int pad = MiuiUi.dp(context, 14);
                extTv.setPadding(pad, pad, pad, pad);
                glCard.addView(extTv);
                MiuiUi.addDivider(context, glCard);
                glCard.addView(MiuiUi.navRow(context, zh ? "复制 GL 扩展列表" : "Copy GL Extensions", null,
                        zh ? "复制" : "Copy", null, 0,
                        () -> nav.copyText(zh ? "GL 扩展列表" : "GL Extensions", glExt)));
                col.addView(glCard);
            }
        }

        col.addView(MiuiUi.sectionTitle(context, zh ? "JNI 原生探针" : "JNI Native Probe"));
        LinearLayout probeCard = MiuiUi.cardGroup(context);
        final String safeProbe = nativeProbeText != null && nativeProbeText.length() > 0
                ? nativeProbeText
                : (zh ? "暂无探针数据" : "No probe data");
        TextView mono = new TextView(context);
        mono.setText(safeProbe);
        mono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mono.setTypeface(Typeface.MONOSPACE);
        mono.setTextColor(MiuiUi.TEXT_PRIMARY);
        int pad = MiuiUi.dp(context, 14);
        mono.setPadding(pad, pad, pad, pad);
        probeCard.addView(mono);
        if (nativeProbeText != null && nativeProbeText.length() > 0) {
            MiuiUi.addDivider(context, probeCard);
            probeCard.addView(MiuiUi.navRow(context, zh ? "复制 JNI 探针输出" : "Copy JNI Probe Output", null,
                    zh ? "复制" : "Copy", null, 0,
                    () -> nav.copyText(zh ? "JNI 探针" : "JNI Probe", safeProbe)));
        }
        if (device != null) {
            MiuiUi.addDivider(context, probeCard);
            probeCard.addView(MiuiUi.navRow(context, zh ? "复制完整设备 JSON" : "Copy Full Device JSON", null,
                    zh ? "复制" : "Copy", null, 0,
                    () -> copyJsonAsync(zh ? "设备 JSON" : "Device JSON", device, nav)));
        }
        col.addView(probeCard);

        return wrapScroll(context, col);
    }

    /** Sub-page D: About RetroMobileBench (description, version, repo links, build identity). */
    static View buildAboutPage(Context context, Navigator nav) {
        boolean zh = I18n.isZh(context);
        LinearLayout col = pageColumn(context);

        col.addView(MiuiUi.sectionTitle(context, zh ? "关于 RetroMobileBench" : "About RetroMobileBench"));
        LinearLayout descCard = MiuiUi.cardGroup(context);
        descCard.addView(MiuiUi.kvRow(context,
                zh ? "面向老旧 Android 设备的轻量级离线基准测试工具" : "Offline Benchmark Suite for Legacy Android Devices",
                zh ? "RetroMobileBench 是一款零联网依赖、面向 Android 2.2+ (API 8) 及以上设备的纯离线基准测试套件。通过固定 12 阶段测试（CPU 整数/浮点、内存带宽与延迟、闪存读写、SQLite 事务、竖屏 WebView、7 段视频三路径解码/播放、OpenGL ES 2.0 离屏与屏上渲染）与电池温升/电量曲线采样，如实反映经典机型在不同架构与系统环境下的真实性能表现。软件基于 Apache-2.0 协议开源。"
                        : "RetroMobileBench is a zero-network, deterministic benchmark suite built for Android 2.2+ (API 8) and newer devices. It executes a fixed 12-stage suite covering CPU integer/float, memory bandwidth & latency, storage I/O, SQLite transactions, portrait WebView, 7-clip 3-path video decode/playback, and OpenGL ES 2.0 offscreen/onscreen rendering alongside thermal & battery telemetry. Open-sourced under Apache-2.0.",
                null, null, 0));
        col.addView(descCard);

        col.addView(MiuiUi.sectionTitle(context, zh ? "应用与版本信息" : "Application & Version"));
        LinearLayout appCard = MiuiUi.cardGroup(context);
        appCard.addView(MiuiUi.kvRow(context, zh ? "应用名称" : "App Name", null, "RetroMobileBench", null, 0));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, zh ? "应用包名" : "Package ID", BuildConfig.APPLICATION_ID, null, null, 0));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, zh ? "软件版本" : "Version", null,
                BuildConfig.VERSION_NAME, "Code " + BuildConfig.VERSION_CODE, MiuiUi.TONE_ACTIVE));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, zh ? "开源协议" : "License", null, "Apache-2.0", null, 0));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, zh ? "兼容目标" : "Compatibility",
                zh ? "ARMv7 (NEON / 无 NEON) · x86" : "ARMv7 (NEON / No-NEON) · x86",
                "API 8 (Android 2.2+)", null, 0));
        col.addView(appCard);

        col.addView(MiuiUi.sectionTitle(context, zh ? "项目链接" : "Project Links"));
        LinearLayout linksCard = MiuiUi.cardGroup(context);
        linksCard.addView(MiuiUi.navRow(context,
                zh ? "GitHub 项目主页" : "GitHub Repository",
                GITHUB_REPO_URL + (zh ? "\n点击在浏览器中打开仓库" : "\nTap to open repository in browser"),
                zh ? "打开" : "Open", null, 0,
                () -> openExternalUrl(context, GITHUB_REPO_URL, nav)));
        MiuiUi.addDivider(context, linksCard);
        linksCard.addView(MiuiUi.navRow(context,
                zh ? "复制项目地址" : "Copy Repository URL",
                GITHUB_REPO_URL,
                zh ? "复制" : "Copy", null, 0,
                () -> nav.copyText("GitHub URL", GITHUB_REPO_URL)));
        col.addView(linksCard);

        col.addView(MiuiUi.sectionTitle(context, zh ? "构建标识（点击复制）" : "Build Identity (Tap to Copy)"));
        LinearLayout buildCard = MiuiUi.cardGroup(context);
        String lib = NativeLoader.load();
        buildCard.addView(MiuiUi.navRow(context, "NATIVE_BUILD_ID",
                BuildConfig.NATIVE_BUILD_ID, zh ? "复制" : "Copy", null, 0,
                () -> nav.copyText("NATIVE_BUILD_ID", BuildConfig.NATIVE_BUILD_ID)));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.navRow(context, "WEB_BUILD_ID",
                BuildConfig.WEB_BUILD_ID, zh ? "复制" : "Copy", null, 0,
                () -> nav.copyText("WEB_BUILD_ID", BuildConfig.WEB_BUILD_ID)));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, zh ? "编译工具链" : "Toolchain",
                "NDK r10e · GCC 4.9 · gnustl_static (-O2)", null, null, 0));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, zh ? "原生计算库" : "Native Library",
                lib != null ? lib : (zh ? "未加载" : "Not loaded"),
                lib != null && lib.contains("noneon")
                        ? (zh ? "标量 / VFPv3-D16" : "Scalar / VFPv3-D16")
                        : "NEON / x86", null, 0));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, zh ? "GPU 屏上浮层" : "GPU Onscreen HUD",
                zh ? "窗口提交帧率 + 电池温度" : "Window swap FPS + battery temp",
                GpuHud.INTERVAL_MS + "\u00A0ms", null, 0));
        col.addView(buildCard);

        return wrapScroll(context, col);
    }

    /** Sub-page E1: Fixed Suite (12 stages) protocol & statistics policy overview. */
    static View buildSuiteProtocolPage(Context context) {
        boolean zh = I18n.isZh(context);
        LinearLayout col = pageColumn(context);

        col.addView(MiuiUi.sectionTitle(context, zh ? "结果统计说明" : "Result & Statistics Policy"));
        LinearLayout ruleCard = MiuiUi.cardGroup(context);
        ruleCard.addView(MiuiUi.kvRow(context,
                zh ? "分项实测与中位数" : "Per-Stage Raw & Medians",
                zh ? "展示各阶段真实测量值与 3 轮有效中位数，不合成单一总分。"
                        : "Displays raw per-stage measurements and 3-round valid medians without combining into an arbitrary total score.",
                null, null, 0));
        MiuiUi.addDivider(context, ruleCard);
        ruleCard.addView(MiuiUi.kvRow(context,
                zh ? "一致性校验" : "Consistency Validation",
                zh ? "仅在构建标识、测试参数与运行状态一致时计算多轮中位数。"
                        : "Multi-round medians are computed only when build IDs, workload parameters, and completion status match.",
                null, null, 0));
        col.addView(ruleCard);

        col.addView(MiuiUi.sectionTitle(context,
                zh ? "12 阶段执行顺序与明确测试量说明" : "12-Stage Execution Order & Workloads"));

        LinearLayout listCard = MiuiUi.cardGroup(context);
        for (int i = 0; i < BenchmarkSuite.DEFAULT.length; i++) {
            BenchmarkSuite s = BenchmarkSuite.DEFAULT[i];
            if (i > 0) MiuiUi.addDivider(context, listCard);
            String rounds = zh
                    ? (s.repetitions() + " 轮" + (s.repetitions() == 3 ? "中位数" : "正式测量"))
                    : (s.repetitions() == 3 ? "3-Round Median" : "1 Formal Run");
            String desc = s.progress(s == BenchmarkSuite.STORAGE ? 32 : (s == BenchmarkSuite.SQLITE ? 300 : 4));
            listCard.addView(MiuiUi.kvRow(context, (i + 1) + ". " + s.label(), desc,
                    null, rounds, s.repetitions() == 3 ? MiuiUi.TONE_OK : MiuiUi.TONE_ACTIVE));
        }
        col.addView(listCard);
        return wrapScroll(context, col);
    }

    /** Sub-page E2: Bundled Open-Source Projects & Benchmark Kernels list. */
    static View buildOpenSourceProjectsPage(Context context, Navigator nav) {
        boolean zh = I18n.isZh(context);
        LinearLayout col = pageColumn(context);
        col.addView(MiuiUi.sectionTitle(context,
                zh ? "内置开源基准测试与依赖项目（共 7 项）" : "Bundled Open-Source Projects (7 Total)"));

        String[][] projects = zh
                ? new String[][]{
                        {"CoreMark® 1.0", "EEMBC · COREMARK 许可证", "1f483d5b8316 · CPU 单核与多核整数核心计算（2000B 栈 / 双种子）"},
                        {"SciMark 2.0 C", "NIST · 公有领域 / 学术声明", "FFT、SOR、Monte Carlo、稀疏矩阵、LU 五项单核/多核浮点内核"},
                        {"FFmpeg 4.4.6", "FFmpeg 团队 · LGPL v2.1+", "44b04492bfc8 · avutil/avcodec/avformat 独立动态库视频标量软解与 SHA-256 校验"},
                        {"tinymembench", "Siarhei Siamashka · MIT 许可证", "a2cf6d7e382e · 8 MiB 内存复制/填充带宽与 4 MiB 随机指针链延迟"},
                        {"STREAM Triad 变体", "John D. McCalpin / Jeff Hammond · STREAM 许可", "6703f7504a38 · 共享固定 24 MiB 工作集（3×8 MiB）单核/多核 Triad 内存带宽"},
                        {"SQLite 3.53.1", "Hipp, Wyrick & Company · 公有领域", "sqlite-amalgamation-3530100 · INSERT/UPDATE/DELETE 各 300 独立同步事务 TPS 基准"},
                        {"GFXBench 4.0 (ES2 子集)", "Kishonti Ltd · BSD / CC BY 4.0 素材", "89aa956f4383 · OpenGL ES 2.0 Egypt HD / T-Rex / Fill 离屏与屏上渲染"}
                }
                : new String[][]{
                        {"CoreMark® 1.0", "EEMBC · COREMARK License", "1f483d5b8316 · Single & multi-core CPU integer benchmark (2000B stack / dual-seed)"},
                        {"SciMark 2.0 C", "NIST · Public Domain / Attribution", "FFT, SOR, Monte Carlo, Sparse Matrix, and LU 5-kernel single/multi-core FP suite"},
                        {"FFmpeg 4.4.6", "FFmpeg Team · LGPL v2.1+", "44b04492bfc8 · Standalone shared avutil/avcodec/avformat scalar video decode with SHA-256 verification"},
                        {"tinymembench", "Siarhei Siamashka · MIT License", "a2cf6d7e382e · 8 MiB memory copy/fill bandwidth and 4 MiB random pointer-chase latency"},
                        {"STREAM Triad Variant", "John D. McCalpin / Jeff Hammond · STREAM License", "6703f7504a38 · Shared fixed 24 MiB working set (3×8 MiB) single & multi-core Triad bandwidth"},
                        {"SQLite 3.53.1", "Hipp, Wyrick & Company · Public Domain", "sqlite-amalgamation-3530100 · 300 individual synced INSERT/UPDATE/DELETE transactions TPS"},
                        {"GFXBench 4.0 (ES2 Subset)", "Kishonti Ltd · BSD / CC BY 4.0 Assets", "89aa956f4383 · OpenGL ES 2.0 Egypt HD / T-Rex / Fill offscreen & onscreen rendering"}
                };

        LinearLayout card = MiuiUi.cardGroup(context);
        for (int i = 0; i < projects.length; i++) {
            if (i > 0) MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, projects[i][0],
                    projects[i][2] + (zh ? "\n来源与协议：" : "\nSource & License: ") + projects[i][1], null, null, 0));
        }
        col.addView(card);

        col.addView(MiuiUi.sectionTitle(context, zh ? "许可证原文" : "Full License Texts"));
        LinearLayout linkCard = MiuiUi.cardGroup(context);
        linkCard.addView(MiuiUi.navRow(context,
                zh ? "阅读开源许可证与署名全文" : "Read Open-Source Licenses & Notices",
                zh ? "查看 licenses.txt / benchmark-notices.txt / xiph-notices.txt"
                        : "Browse licenses_en.txt / benchmark-notices.txt / xiph-notices.txt",
                null, null, 0, () -> nav.openSubPage(
                        zh ? "开源许可协议全文" : "Open-Source Licenses",
                        buildLicenseReaderPage(context, nav))));
        col.addView(linkCard);

        return wrapScroll(context, col);
    }

    static View buildLicenseReaderPage(final Context context) {
        return buildLicenseReaderPage(context, null);
    }

    /** Sub-page F: Virtualized segmented reader for the 3 bundled license/notice asset files. */
    static View buildLicenseReaderPage(final Context context, final Navigator nav) {
        final boolean zh = I18n.isZh(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, MiuiUi.dp(context, 6), 0, MiuiUi.dp(context, 10));

        root.addView(MiuiUi.sectionTitle(context, zh ? "选择要查看的协议文档" : "Select License Document"));

        LinearLayout segRow = new LinearLayout(context);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setBaselineAligned(false);
        int m = MiuiUi.dp(context, 12);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segLp.setMargins(m, 0, m, MiuiUi.dp(context, 6));
        segRow.setLayoutParams(segLp);

        final String[] files = {zh ? "licenses.txt" : "licenses_en.txt", "benchmark-notices.txt", "xiph-notices.txt"};
        final String[] titles = zh
                ? new String[]{"第三方库许可", "基准组件声明", "媒体素材署名"}
                : new String[]{"3rd-Party", "Benchmarks", "Media"};
        final Button[] tabs = new Button[3];
        final String[] currentFullText = {""};
        final int[] loadToken = {0};

        // Compact hint + copy full document bar
        LinearLayout metaRow = new LinearLayout(context);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setBaselineAligned(false);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(MiuiUi.dp(context, 14), 0, MiuiUi.dp(context, 14), MiuiUi.dp(context, 6));

        final TextView hintTv = new TextView(context);
        hintTv.setText(zh ? "长按任意段落可复制该段" : "Long-press any section to copy");
        hintTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hintTv.setTextColor(MiuiUi.TEXT_MUTED);
        metaRow.addView(hintTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyAllChip = MiuiUi.actionChip(context, zh ? "复制全文" : "Copy All", false, () -> {
            if (currentFullText[0] != null && currentFullText[0].length() > 0) {
                copyTextFallback(context, nav, zh ? "协议全文" : "License Text", currentFullText[0]);
            }
        });
        metaRow.addView(copyAllChip);

        final ChunkedTextAdapter adapter = new ChunkedTextAdapter(context, 12f);
        ListView listView = createChunkedListView(context, adapter, nav, zh ? "协议片段" : "License Excerpt");

        FrameLayout listCard = new FrameLayout(context);
        MiuiUi.setBackground(listCard, MiuiUi.cardDrawable(context));
        int border = MiuiUi.dp(context, 1);
        listCard.setPadding(border, border, border, border);
        listCard.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.setMargins(m, 0, m, 0);

        final View.OnClickListener[] listeners = new View.OnClickListener[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button b = new Button(context);
            b.setText(titles[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            int vPad = MiuiUi.dp(context, 8);
            b.setPadding(MiuiUi.dp(context, 6), vPad, MiuiUi.dp(context, 6), vPad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = MiuiUi.dp(context, 6);
            listeners[i] = v -> {
                for (int j = 0; j < 3; j++) {
                    boolean sel = (j == index);
                    MiuiUi.setBackground(tabs[j], MiuiUi.segmentDrawable(context, sel));
                    tabs[j].setTextColor(sel ? Color.WHITE : MiuiUi.TEXT_PRIMARY);
                }
                final int token = ++loadToken[0];
                final String asset = files[index];
                List<String> cachedChunks;
                String cachedText;
                synchronized (ASSET_CHUNK_CACHE) {
                    cachedChunks = ASSET_CHUNK_CACHE.get(asset);
                    cachedText = ASSET_TEXT_CACHE.get(asset);
                }
                if (cachedChunks != null && cachedText != null) {
                    currentFullText[0] = cachedText;
                    adapter.setChunks(cachedChunks);
                    listView.setSelection(0);
                    hintTv.setText(zh
                            ? ("共 " + cachedChunks.size() + " 段 · 长按段落可复制")
                            : (cachedChunks.size() + " sections · Long-press to copy"));
                    return;
                }
                currentFullText[0] = "";
                adapter.setChunks(Collections.singletonList(zh
                        ? ("正在加载 " + titles[index] + "...")
                        : ("Loading " + titles[index] + "...")));
                hintTv.setText(zh ? "正在读取文档..." : "Loading document...");
                TEXT_EXECUTOR.execute(() -> {
                    String text = readAssetText(context, asset);
                    List<String> chunks = chunkText(text);
                    synchronized (ASSET_CHUNK_CACHE) {
                        ASSET_TEXT_CACHE.put(asset, text);
                        ASSET_CHUNK_CACHE.put(asset, chunks);
                    }
                    MAIN_HANDLER.post(() -> {
                        if (loadToken[0] != token) return;
                        currentFullText[0] = text;
                        adapter.setChunks(chunks);
                        listView.setSelection(0);
                        hintTv.setText(zh
                                ? ("共 " + chunks.size() + " 段 · 长按段落可复制")
                                : (chunks.size() + " sections · Long-press to copy"));
                    });
                });
            };
            b.setOnClickListener(listeners[i]);
            tabs[i] = b;
            segRow.addView(b, lp);
        }

        root.addView(segRow);
        root.addView(metaRow);
        root.addView(listCard, cardLp);

        listeners[0].onClick(tabs[0]);
        return root;
    }

    static void prewarmLicenseAssets(final Context context) {
        final Context app = context.getApplicationContext();
        final String[] files = {"licenses.txt", "licenses_en.txt", "benchmark-notices.txt", "xiph-notices.txt"};
        TEXT_EXECUTOR.execute(() -> {
            for (String asset : files) {
                synchronized (ASSET_CHUNK_CACHE) {
                    if (ASSET_CHUNK_CACHE.containsKey(asset)) continue;
                }
                String text = readAssetText(app, asset);
                List<String> chunks = chunkText(text);
                synchronized (ASSET_CHUNK_CACHE) {
                    if (!ASSET_CHUNK_CACHE.containsKey(asset)) {
                        ASSET_TEXT_CACHE.put(asset, text);
                        ASSET_CHUNK_CACHE.put(asset, chunks);
                    }
                }
            }
        });
    }

    private static String readAssetText(Context context, String assetName) {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(context.getAssets().open(assetName), "UTF-8")) {
            int n;
            while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
            return out.toString();
        } catch (Exception e) {
            return I18n.t(context, "读取 ", "Failed to read ") + assetName
                    + I18n.t(context, " 失败：", ": ") + e;
        }
    }

    /** Sub-page G (lazy report): Virtualized reader that formats report text off the UI thread. */
    static View buildRawReportPage(Context context, final JSONObject report, final Navigator nav) {
        final boolean zh = I18n.isZh(context);
        final String[] cachedText = {null};
        final ChunkedTextAdapter adapter = new ChunkedTextAdapter(context, 12.5f);
        adapter.setChunks(Collections.singletonList(zh ? "正在生成原始文本报告..." : "Loading raw text report..."));
        View page = buildRawReportListContainer(context, adapter,
                () -> {
                    if (cachedText[0] != null) {
                        copyTextFallback(context, nav, zh ? "报告文本" : "Report Text", cachedText[0]);
                    } else {
                        TEXT_EXECUTOR.execute(() -> {
                            String txt = formatReportSafe(report);
                            cachedText[0] = txt;
                            MAIN_HANDLER.post(() -> copyTextFallback(context, nav, zh ? "报告文本" : "Report Text", txt));
                        });
                    }
                },
                () -> nav.exportReport(report),
                nav);

        TEXT_EXECUTOR.execute(() -> {
            String txt = formatReportSafe(report);
            List<String> chunks = chunkText(txt.length() > 0 ? txt : (zh ? "暂无文本内容" : "No text content"));
            MAIN_HANDLER.post(() -> {
                cachedText[0] = txt;
                adapter.setChunks(chunks);
            });
        });
        return page;
    }

    private static View buildRawReportListContainer(Context context, ChunkedTextAdapter adapter,
                                                    Runnable copyTextAction, Runnable copyJsonAction, Navigator nav) {
        boolean zh = I18n.isZh(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, MiuiUi.dp(context, 6), 0, MiuiUi.dp(context, 10));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBaselineAligned(false);
        int m = MiuiUi.dp(context, 12);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(m, 0, m, MiuiUi.dp(context, 8));
        bar.setLayoutParams(barLp);

        Button copyTextBtn = MiuiUi.styledButton(context, zh ? "复制报告文本" : "Copy Report Text",
                copyJsonAction == null, false, copyTextAction);
        bar.addView(copyTextBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (copyJsonAction != null) {
            Button copyJsonBtn = MiuiUi.styledButton(context, zh ? "导出文件" : "Export File", true, false, copyJsonAction);
            LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            jLp.leftMargin = MiuiUi.dp(context, 8);
            bar.addView(copyJsonBtn, jLp);
        }
        root.addView(bar);

        ListView listView = createChunkedListView(context, adapter, nav, zh ? "报告片段" : "Report Excerpt");
        FrameLayout card = new FrameLayout(context);
        MiuiUi.setBackground(card, MiuiUi.cardDrawable(context));
        int border = MiuiUi.dp(context, 1);
        card.setPadding(border, border, border, border);
        card.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.setMargins(m, 0, m, 0);
        root.addView(card, cardLp);
        return root;
    }

    private static ListView createChunkedListView(final Context context, final ChunkedTextAdapter adapter,
                                                  final Navigator nav, final String copyLabel) {
        ListView listView = new ListView(context);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setCacheColorHint(MiuiUi.BG_CARD);
        listView.setBackgroundColor(MiuiUi.BG_CARD);
        listView.setVerticalFadingEdgeEnabled(false);
        listView.setScrollingCacheEnabled(false);
        listView.setSelector(android.R.color.transparent);
        listView.setFastScrollEnabled(true);
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String chunk = adapter.getItem(position);
            if (chunk != null && chunk.length() > 0) {
                copyTextFallback(context, nav, copyLabel, chunk);
                return true;
            }
            return false;
        });
        return listView;
    }

    private static final class ChunkedTextAdapter extends BaseAdapter {
        private final Context context;
        private final float textSizeSp;
        private final int hPad, vPad, edgePad;
        private List<String> chunks = Collections.emptyList();

        ChunkedTextAdapter(Context context, float textSizeSp) {
            this.context = context;
            this.textSizeSp = textSizeSp;
            this.hPad = MiuiUi.dp(context, 14);
            this.vPad = MiuiUi.dp(context, 4);
            this.edgePad = MiuiUi.dp(context, 12);
        }

        void setChunks(List<String> items) {
            this.chunks = items != null ? items : Collections.<String>emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return chunks.size(); }
        @Override public String getItem(int position) { return chunks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(MiuiUi.TEXT_PRIMARY);
                tv.setLineSpacing(MiuiUi.dp(context, 1.5f), 1f);
            }
            int top = position == 0 ? edgePad : vPad;
            int bottom = position == chunks.size() - 1 ? edgePad : vPad;
            tv.setPadding(hPad, top, hPad, bottom);
            tv.setText(chunks.get(position));
            return tv;
        }
    }

    private static List<String> chunkText(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null || text.length() == 0) return out;
        StringBuilder block = new StringBuilder();
        int lineCount = 0;
        int len = text.length();
        int start = 0;
        while (start < len) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            String line = text.substring(start, end);
            start = nl < 0 ? len : nl + 1;

            if (line.length() > 700) {
                if (block.length() > 0) {
                    out.add(block.toString());
                    block.setLength(0);
                    lineCount = 0;
                }
                for (int off = 0; off < line.length(); off += 600) {
                    out.add(line.substring(off, Math.min(line.length(), off + 600)));
                }
                continue;
            }

            if (lineCount > 0) block.append('\n');
            block.append(line);
            lineCount++;

            boolean naturalBreak = line.trim().length() == 0 && lineCount >= 7;
            if (naturalBreak || lineCount >= 12 || block.length() >= 900) {
                out.add(block.toString());
                block.setLength(0);
                lineCount = 0;
            }
        }
        if (block.length() > 0) out.add(block.toString());
        return out;
    }

    private static String formatReportSafe(JSONObject report) {
        if (report == null) return "";
        try {
            return BenchmarkReport.format(report);
        } catch (JSONException e) {
            return e.toString();
        }
    }

    private static void copyJsonAsync(final String label, final JSONObject report, final Navigator nav) {
        if (report == null || nav == null) return;
        TEXT_EXECUTOR.execute(() -> {
            String json;
            try {
                json = report.toString(2);
            } catch (JSONException e) {
                json = report.toString();
            }
            final String finalJson = json;
            MAIN_HANDLER.post(() -> nav.copyText(label, finalJson));
        });
    }

    private static void openExternalUrl(Context context, String url, Navigator nav) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            copyTextFallback(context, nav, "GitHub URL", url);
        }
    }

    private static void copyTextFallback(Context context, Navigator nav, String label, String content) {
        if (content == null || content.length() == 0) return;
        if (nav != null) {
            nav.copyText(label, content);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setText(content);
    }
}
