package com.retromobilebench;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.Reader;
import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Builder for MIUI V5 secondary sub-pages and structured report/hardware cards.
 * Keeps each screen focused and uncluttered on small retro displays.
 */
final class MiuiSubPages {
    interface Navigator {
        void openSubPage(String title, View content);
        void copyText(String label, String content);
        void deleteRun(JSONObject report);
    }

    private MiuiSubPages() { }

    static ScrollView wrapScroll(Context context, View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(MiuiUi.BG_PAGE);
        scroll.setFillViewport(true);
        scroll.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static LinearLayout pageColumn(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(MiuiUi.BG_PAGE);
        col.setPadding(0, MiuiUi.dp(context, 6), 0, MiuiUi.dp(context, 20));
        return col;
    }

    static String formatTemperatureShort(JSONObject trace) {
        if (trace == null || !trace.optBoolean("available")) return "不可用";
        return String.format(Locale.US, "%.1f → %.1f °C (峰值 %.1f °C / %+.1f °C)",
                trace.optInt("start_tenths_c") / 10.0,
                trace.optInt("end_tenths_c") / 10.0,
                trace.optInt("sampled_max_tenths_c") / 10.0,
                trace.optInt("delta_tenths_c") / 10.0);
    }

    private static JSONObject stageSource(JSONObject stageReport) {
        if (stageReport == null) return null;
        JSONObject median = stageReport.optJSONObject("median");
        if (median != null) return median;
        JSONArray samples = stageReport.optJSONArray("samples");
        return samples != null && samples.length() > 0 ? samples.optJSONObject(0) : null;
    }

    static String stageHeadlineSummary(BenchmarkSuite suite, JSONObject stageReport) {
        if (stageReport == null) return "未执行";
        String status = stageReport.optString("status", "failed");
        if ("skipped".equals(status) || "unsupported".equals(status)) {
            String msg = stageReport.optString("message");
            return msg.length() > 0 ? msg : BenchmarkReport.statusLabel(status);
        }
        JSONArray samples = stageReport.optJSONArray("samples");
        JSONObject source = stageSource(stageReport);
        if (source == null) return BenchmarkReport.statusLabel(status);

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
                    return String.format(Locale.US, "软解 %d/7 · 系统 %d/7 · 播放 %d/7", swOk, sysOk, playOk);
                }
            }
            return "7 段视频三路径测试";
        }
        if (suite.isGpu()) {
            if (suite == BenchmarkSuite.GPU_FILL && source.has("score")) {
                return String.format(Locale.US, "%.1f MTexels/s", source.optDouble("score"));
            }
            double fps = source.optDouble("fps", -1);
            if (fps <= 0 && samples != null && samples.length() > 0) {
                fps = samples.optJSONObject(0).optDouble("fps", -1);
            }
            double frames = source.optDouble("score", -1);
            if (fps > 0 && frames > 0) {
                return String.format(Locale.US, "%.2f FPS (%.0f frames)", fps, frames);
            } else if (frames > 0) {
                return String.format(Locale.US, "%.0f frames", frames);
            }
        }
        if (suite == BenchmarkSuite.COREMARK) {
            double s = source.optDouble("single_iterations_per_second", -1);
            double p = source.optDouble("parallel_iterations_per_second", -1);
            if (s > 0 && p > 0) return String.format(Locale.US, "单核 %.1f · 多核 %.1f iter/s", s, p);
            if (s > 0) return String.format(Locale.US, "单核 %.1f iter/s", s);
        }
        if (suite == BenchmarkSuite.SCIMARK) {
            double s = source.optDouble("single_composite_mflops", -1);
            double p = source.optDouble("parallel_composite_mflops", -1);
            if (s > 0 && p > 0) return String.format(Locale.US, "单核 %.1f · 多核 %.1f MFLOPS", s, p);
            if (s > 0) return String.format(Locale.US, "单核综合 %.1f MFLOPS", s);
        }
        if (suite == BenchmarkSuite.MEMORY) {
            double copy = source.optDouble("copy_mbps", -1);
            double triad = source.optDouble("parallel_triad_mbps", source.optDouble("single_triad_mbps", -1));
            double lat = source.optDouble("latency_ns", -1);
            if (copy > 0 && triad > 0 && lat > 0) {
                return String.format(Locale.US, "复制 %.0f · Triad %.0f MB/s · %.1f ns", copy, triad, lat);
            }
        }
        if (suite == BenchmarkSuite.STORAGE) {
            double sr = source.optDouble("sequential_read_mbps", -1);
            double sw = source.optDouble("sequential_write_mbps", -1);
            double rr = source.optDouble("random_read_iops", -1);
            double rw = source.optDouble("random_write_iops", -1);
            if (sr > 0 && sw > 0 && rr > 0 && rw > 0) {
                return String.format(Locale.US, "顺读 %.1f/写 %.1f MB/s · 随读 %.0f/写 %.0f IOPS", sr, sw, rr, rw);
            }
        }
        if (suite == BenchmarkSuite.SQLITE) {
            double comp = source.optDouble("composite_tps", -1);
            double ins = source.optDouble("insert_tps", -1);
            double upd = source.optDouble("update_tps", -1);
            double del = source.optDouble("delete_tps", -1);
            if (comp > 0) return String.format(Locale.US, "综合 %.1f TPS (插%.0f/更%.0f/删%.0f)", comp, ins, upd, del);
            if (ins > 0) return String.format(Locale.US, "插入 %.1f · 更新 %.1f · 删除 %.1f TPS", ins, upd, del);
        }
        if (suite == BenchmarkSuite.WEB) {
            double total = source.optDouble("total_ms", -1);
            double feed = source.optDouble("feed_ms", -1);
            double img = source.optDouble("image_ms", -1);
            if (total > 0 && feed > 0 && img > 0) {
                return String.format(Locale.US, "总计 %.0f ms (信息流 %.0f + 大图 %.0f ms)", total, feed, img);
            }
        }
        if (suite.metrics.length > 0 && source.has(suite.metrics[0])) {
            double v0 = source.optDouble(suite.metrics[0], -1);
            if (v0 > 0) {
                if (suite.metrics.length > 1 && source.has(suite.metrics[1])) {
                    double v1 = source.optDouble(suite.metrics[1], -1);
                    return String.format(Locale.US, "%s %.1f · %s %.1f %s",
                            suite.labels[0], v0, suite.labels[1], v1, suite.units[0]);
                }
                return String.format(Locale.US, "%s：%.2f %s", suite.labels[0], v0, suite.units[0]);
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
        double offFps = extractGpuFps(offRep);
        double onFps = extractGpuFps(onRep);
        String offStr = offFps > 0 ? String.format(Locale.US, "%.1f FPS", offFps)
                : (offRep != null ? BenchmarkReport.statusLabel(offRep.optString("status")) : "未测");
        String onStr = onFps > 0 ? String.format(Locale.US, "%.1f FPS", onFps)
                : (onRep != null ? BenchmarkReport.statusLabel(onRep.optString("status")) : "未测");
        return "离屏 " + offStr + " · 屏上 " + onStr;
    }

    private static String buildQuickComparisonText(JSONObject report, JSONObject[] byStage) {
        StringBuilder sb = new StringBuilder();
        JSONObject dev = null;
        for (JSONObject st : byStage) {
            if (st != null && st.has("device")) { dev = st.optJSONObject("device"); break; }
        }
        sb.append("【RetroMobileBench 核心指标速览】\n");
        if (dev != null) {
            sb.append("设备：").append(dev.optString("manufacturer")).append(' ').append(dev.optString("model"))
                    .append(" (Android ").append(dev.optString("android_release")).append(", API ").append(dev.optInt("sdk")).append(")\n");
        }
        sb.append("1. CPU 整数 (CoreMark)：").append(stageHeadlineSummary(BenchmarkSuite.COREMARK, byStage[0])).append('\n');
        sb.append("2. CPU 浮点 (SciMark 2.0)：").append(stageHeadlineSummary(BenchmarkSuite.SCIMARK, byStage[1])).append('\n');
        sb.append("3. 内存 (8MiB复制/4MiB延迟/24MiB Triad)：").append(stageHeadlineSummary(BenchmarkSuite.MEMORY, byStage[2])).append('\n');
        sb.append("4. 存储 (256KiB×128顺序/4KiB×256随机)：").append(stageHeadlineSummary(BenchmarkSuite.STORAGE, byStage[3])).append('\n');
        sb.append("5. SQLite (900独立事务/1KiB行)：").append(stageHeadlineSummary(BenchmarkSuite.SQLITE, byStage[4])).append('\n');
        sb.append("6. Web 竖屏信息流与大图：").append(stageHeadlineSummary(BenchmarkSuite.WEB, byStage[5])).append('\n');
        sb.append("7. 视频解码与播放 (7段)：").append(stageHeadlineSummary(BenchmarkSuite.VIDEO, byStage[6])).append('\n');
        sb.append("8. GPU Egypt HD：").append(formatPairedGpuFps(byStage[7], byStage[8])).append('\n');
        sb.append("9. GPU T-Rex：").append(formatPairedGpuFps(byStage[9], byStage[10])).append('\n');
        sb.append("10. GPU 纹理填充率：").append(stageHeadlineSummary(BenchmarkSuite.GPU_FILL, byStage[11])).append('\n');
        sb.append("电池温度：").append(formatTemperatureShort(report.optJSONObject("temperature")));
        return sb.toString();
    }

    /** Sub-page A0: Full report detail (session opens overview + 12-stage list; single stage opens stage detail directly). */
    static View buildReportDetailPage(Context context, JSONObject report, boolean isBusy, Navigator nav) {
        String suiteVer = report.optString("suite_version");
        boolean isSession = BenchmarkSuite.isSession(suiteVer);
        BenchmarkSuite singleSuite = isSession ? null : BenchmarkSuite.fromReport(report);
        if (!isSession && singleSuite != null) {
            return buildStageDetailPage(context, singleSuite, report, nav, true);
        }

        LinearLayout col = pageColumn(context);
        String status = report.optString("status", "failed");
        String sessionId = report.optString("session_id");
        String rawText;
        String exportJson;
        try {
            exportJson = report.toString(2);
            rawText = BenchmarkReport.format(report);
        } catch (JSONException e) {
            exportJson = report.toString();
            rawText = e.toString();
        }
        final String finalRaw = rawText;
        final String finalJson = exportJson;

        col.addView(MiuiUi.sectionTitle(context, "报告总览"));
        LinearLayout summaryCard = MiuiUi.cardGroup(context);
        summaryCard.addView(MiuiUi.kvRow(context,
                isSession ? "完整测试套件（12 阶段）" : suiteVer,
                sessionId.length() > 0 ? "会话：" + MiuiUi.ellipsizeMiddle(sessionId, 8, 6) : null,
                null, BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status)));

        if (report.has("started_ms")) {
            MiuiUi.addDivider(context, summaryCard);
            long started = report.optLong("started_ms");
            summaryCard.addView(MiuiUi.kvRow(context, "测试时间", null,
                    DateFormat.getDateTimeInstance().format(new Date(started)), null, 0));
        }
        long wallMs = report.optLong("wall_ms",
                report.optLong("ended_ms", 0) - report.optLong("started_ms", 0));
        if (wallMs > 0) {
            MiuiUi.addDivider(context, summaryCard);
            summaryCard.addView(MiuiUi.kvRow(context, "总耗时", null, MiuiUi.formatDuration(wallMs), null, 0));
        }
        MiuiUi.addDivider(context, summaryCard);
        summaryCard.addView(MiuiUi.kvRow(context, "电池温度", null,
                formatTemperatureShort(report.optJSONObject("temperature")), null, 0));

        for (String warnKey : new String[]{"persistence_warning", "preparation_warning", "media_preparation_warning", "message"}) {
            String warn = report.optString(warnKey);
            if (warn.length() > 0) {
                MiuiUi.addDivider(context, summaryCard);
                summaryCard.addView(MiuiUi.kvRow(context, "提示", warn, null, "注意", MiuiUi.TONE_WARN));
            }
        }

        MiuiUi.addDivider(context, summaryCard);
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        int pad = MiuiUi.dp(context, 12);
        btnRow.setPadding(pad, MiuiUi.dp(context, 10), pad, pad);

        Button exportBtn = MiuiUi.styledButton(context, "导出 JSON", true, false,
                () -> nav.copyText("结果 JSON", finalJson));
        exportBtn.setEnabled(!isBusy && finalJson.length() > 0);
        btnRow.addView(exportBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button rawBtn = MiuiUi.styledButton(context, "原始文本", false, false,
                () -> nav.openSubPage("原始文本报告", buildRawReportPage(context, finalRaw, finalJson, nav)));
        LinearLayout.LayoutParams rawLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rawLp.leftMargin = MiuiUi.dp(context, 8);
        btnRow.addView(rawBtn, rawLp);
        summaryCard.addView(btnRow);
        col.addView(summaryCard);

        if (isSession) {
            JSONArray results = report.optJSONArray("results");
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

            // One-screen summary card for video creators & device comparisons
            col.addView(MiuiUi.sectionTitle(context, "核心指标速览（横评总结）"));
            LinearLayout quickCard = MiuiUi.cardGroup(context);
            quickCard.addView(MiuiUi.kvRow(context, "CPU 整数 (CoreMark®)",
                    "2000B 栈工作区 · 双种子校验 · 每段 ≥10s",
                    stageHeadlineSummary(BenchmarkSuite.COREMARK, byStage[0]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "CPU 浮点 (SciMark 2.0)",
                    "5 项浮点内核均值 (FFT/SOR/MC/稀疏/LU)",
                    stageHeadlineSummary(BenchmarkSuite.SCIMARK, byStage[1]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "内存带宽与延迟",
                    "8MiB 复制/填充 · 4MiB 随机指针链(100万节点) · 24MiB 共享 Triad",
                    stageHeadlineSummary(BenchmarkSuite.MEMORY, byStage[2]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "闪存读写 (Storage)",
                    "32MiB 文件(4–32MiB 自适应) · 256KiB×128 顺序 · 4KiB×256 随机 · O_SYNC",
                    stageHeadlineSummary(BenchmarkSuite.STORAGE, byStage[3]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "SQLite 事务吞吐",
                    "INSERT/UPDATE/DELETE 各 300 独立事务(共 900 次 COMMIT) · 1KiB/行 · FULL 同步",
                    stageHeadlineSummary(BenchmarkSuite.SQLITE, byStage[4]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "Web 竖屏信息流与大图",
                    "竖屏 640卡片+2000列表+480段落 + 480p/720p/1080p 大图解码×20 (越低越好)",
                    stageHeadlineSummary(BenchmarkSuite.WEB, byStage[5]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "视频解码与播放",
                    "7 段 240p–1080p 素材 (H.263/MPEG-4/H.264/VP8/VP9)",
                    stageHeadlineSummary(BenchmarkSuite.VIDEO, byStage[6]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "GPU Egypt HD (中压场景)",
                    "1080p 离屏 vs 实际屏幕分辨率屏上",
                    formatPairedGpuFps(byStage[7], byStage[8]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "GPU T-Rex (高压场景)",
                    "1080p 离屏 vs 实际屏幕分辨率屏上",
                    formatPairedGpuFps(byStage[9], byStage[10]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            quickCard.addView(MiuiUi.kvRow(context, "GPU 纹理填充率",
                    "1080p 离屏四层多纹理混合",
                    stageHeadlineSummary(BenchmarkSuite.GPU_FILL, byStage[11]), null, 0));
            MiuiUi.addDivider(context, quickCard);
            final String quickSummaryText = buildQuickComparisonText(report, byStage);
            quickCard.addView(MiuiUi.navRow(context, "复制横评总结文本", "一键复制以上 10 项核心指标与工作量说明",
                    "复制", null, 0, () -> nav.copyText("横评总结", quickSummaryText)));
            col.addView(quickCard);

            String[] groupTitles = {"处理器算力 (CPU)", "内存与存储 (Memory & I/O)", "Web 与视频 (Daily & Media)", "OpenGL ES 2.0 (GPU)"};
            int[][] groupIndices = {{0, 1}, {2, 3, 4}, {5, 6}, {7, 8, 9, 10, 11}};
            for (int g = 0; g < groupTitles.length; g++) {
                col.addView(MiuiUi.sectionTitle(context, groupTitles[g]));
                LinearLayout card = MiuiUi.cardGroup(context);
                int[] indices = groupIndices[g];
                for (int j = 0; j < indices.length; j++) {
                    if (j > 0) MiuiUi.addDivider(context, card);
                    final BenchmarkSuite suite = BenchmarkSuite.DEFAULT[indices[j]];
                    final JSONObject stageRep = byStage[indices[j]];
                    if (stageRep != null) {
                        String st = stageRep.optString("status", "failed");
                        card.addView(MiuiUi.navRow(context, suite.label, stageHeadlineSummary(suite, stageRep), null,
                                BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st),
                                () -> nav.openSubPage(suite.label, buildStageDetailPage(context, suite, stageRep, nav, false))));
                    } else {
                        card.addView(MiuiUi.kvRow(context, suite.label, "未执行到该阶段", null, "未运行", MiuiUi.TONE_NEUTRAL));
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
            delBox.addView(MiuiUi.styledButton(context, "删除此条记录", false, true, () -> nav.deleteRun(report)));
            col.addView(delBox);
        }
        return wrapScroll(context, col);
    }

    /** Sub-page A: Detailed single-stage report view. */
    static View buildStageDetailPage(Context context, BenchmarkSuite suite, JSONObject report, Navigator nav, boolean allowDelete) {
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
        col.addView(MiuiUi.sectionTitle(context, "阶段概览"));
        LinearLayout overview = MiuiUi.cardGroup(context);
        overview.addView(MiuiUi.kvRow(context, suite != null ? suite.label : report.optString("suite_version"),
                stageSubtitle,
                null, BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status)));
        if (report.has("started_ms")) {
            MiuiUi.addDivider(context, overview);
            long started = report.optLong("started_ms");
            long ended = report.optLong("ended_ms", started);
            overview.addView(MiuiUi.kvRow(context, "开始时间", null,
                    DateFormat.getDateTimeInstance().format(new Date(started)), null, 0));
            if (ended > started) {
                MiuiUi.addDivider(context, overview);
                overview.addView(MiuiUi.kvRow(context, "耗时", null,
                        MiuiUi.formatDuration(ended - started), null, 0));
            }
        }
        MiuiUi.addDivider(context, overview);
        int reps = report.optInt("repetitions", 1);
        int doneRounds = samples == null ? 0 : samples.length();
        overview.addView(MiuiUi.kvRow(context, "采样轮次", null,
                doneRounds + " / " + reps + " 轮" + (report.has("median") ? "（中位数）" : ""), null, 0));
        MiuiUi.addDivider(context, overview);
        overview.addView(MiuiUi.kvRow(context, "电池温度", null,
                formatTemperatureShort(report.optJSONObject("temperature")), null, 0));

        String msg = report.optString("message");
        if (msg.length() > 0) {
            MiuiUi.addDivider(context, overview);
            overview.addView(MiuiUi.kvRow(context, "说明", msg, null, null, 0));
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

        col.addView(MiuiUi.sectionTitle(context, "数据与操作"));
        LinearLayout actions = MiuiUi.cardGroup(context);
        actions.addView(MiuiUi.navRow(context, "查看原始文本", null, null, null, 0, () -> {
            try {
                nav.openSubPage((suite != null ? suite.label : "阶段") + " · 文本",
                        buildRawReportPage(context, BenchmarkReport.format(report), report.toString(2), nav));
            } catch (JSONException e) {
                nav.openSubPage("原始文本", buildRawReportPage(context, e.toString(), report.toString(), nav));
            }
        }));
        MiuiUi.addDivider(context, actions);
        actions.addView(MiuiUi.navRow(context, "复制 JSON", null, null, null, 0, () -> {
            try {
                nav.copyText("阶段 JSON", report.toString(2));
            } catch (JSONException e) {
                nav.copyText("阶段 JSON", report.toString());
            }
        }));
        col.addView(actions);

        if (allowDelete && report.has("id")) {
            LinearLayout delBox = new LinearLayout(context);
            delBox.setOrientation(LinearLayout.VERTICAL);
            int m = MiuiUi.dp(context, 12);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(m, MiuiUi.dp(context, 14), m, 0);
            delBox.setLayoutParams(dlp);
            delBox.addView(MiuiUi.styledButton(context, "删除此条记录", false, true, () -> nav.deleteRun(report)));
            col.addView(delBox);
        }

        return wrapScroll(context, col);
    }

    private static void appendStandardStageMetrics(Context context, LinearLayout col, BenchmarkSuite suite, JSONObject report) {
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
            String valStr = String.format(Locale.US, "%.2f %s", val, suite.units[i]);
            Double unitMaxObj = maxByUnit.get(suite.units[i]);
            double unitMax = unitMaxObj != null ? unitMaxObj : 0;
            float ratio = unitMax > 0 ? (float) (val / unitMax) : 0.5f;
            int barColor = suite == BenchmarkSuite.WEB ? 0xFF188AE2 : MiuiUi.ORANGE;
            card.addView(MiuiUi.metricBarRow(context, suite.labels[i], valStr, ratio, barColor));
            added = true;

            if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL) {
                double fps = source.optDouble("fps", -1);
                if (fps <= 0 && samples != null && samples.length() > 0) {
                    fps = samples.optJSONObject(0).optDouble("fps", -1);
                }
                if (fps > 0) {
                    MiuiUi.addDivider(context, card);
                    String mode = suite.gpuTest().endsWith("_off") ? "离屏渲染" : "窗口提交";
                    card.addView(MiuiUi.metricBarRow(context, "平均帧率（" + mode + "）",
                            String.format(Locale.US, "%.2f FPS", fps), Math.min(1f, (float) (fps / 60.0)), MiuiUi.GREEN));
                }
            }
        }
        if (added) {
            col.addView(MiuiUi.sectionTitle(context, median != null ? "核心测量结果（三轮有效中位数）" : "测量结果（单轮 / 原始值）"));
            col.addView(card);
        }
    }

    private static void appendVideoStageDetails(Context context, LinearLayout col, JSONObject report) {
        JSONArray samples = report.optJSONArray("samples");
        if (samples == null || samples.length() == 0) return;
        JSONArray clips = samples.optJSONObject(0).optJSONArray("results");
        if (clips == null) return;

        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.optJSONObject(i);
            if (clip == null) continue;
            col.addView(MiuiUi.sectionTitle(context, "视频素材 " + (i + 1) + "：" + clip.optString("id")));
            LinearLayout card = MiuiUi.cardGroup(context);
            String[] paths = {"software", "system", "playback"};
            String[] names = {"固定软解 (FFmpeg)", "系统解码 (MediaCodec)", "实时窗口播放"};
            boolean added = false;
            for (int p = 0; p < paths.length; p++) {
                JSONObject pathObj = clip.optJSONObject(paths[p]);
                if (pathObj == null) continue;
                if (added) MiuiUi.addDivider(context, card);
                String st = pathObj.optString("status");
                StringBuilder right = new StringBuilder();
                if (pathObj.has("fps") && !pathObj.isNull("fps")) {
                    right.append(String.format(Locale.US, "%.2f FPS", pathObj.optDouble("fps")));
                }
                if (pathObj.has("realtime")) {
                    if (right.length() > 0) right.append(" · ");
                    right.append(pathObj.optBoolean("realtime") ? "达到实时" : "低于实时");
                }
                StringBuilder sub = new StringBuilder();
                if (pathObj.has("decoder")) sub.append(pathObj.optString("decoder"));
                if (pathObj.has("error_stage")) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(pathObj.optString("error_stage"));
                }
                card.addView(MiuiUi.kvRow(context, names[p], sub.toString(),
                        right.toString(), BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st)));
                added = true;
            }
            col.addView(card);
        }
    }

    private static void appendStageEnvironmentCard(Context context, LinearLayout col, BenchmarkSuite suite, JSONObject sample) {
        if (sample == null) return;
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
                String tag = isSoftware ? "软件渲染" : (isTranslated ? "宿主转译" : null);
                int tone = isSoftware ? MiuiUi.TONE_WARN : MiuiUi.TONE_NEUTRAL;
                card.addView(MiuiUi.kvRow(context, "GPU 型号",
                        gpuModel.length() > 0 ? gpuModel : "未知",
                        vendorDisplay.length() > 0 ? vendorDisplay : null, tag, tone));
                if (renderer.length() > 0 && !renderer.equals(gpuModel)) {
                    MiuiUi.addDivider(context, card);
                    card.addView(MiuiUi.kvRow(context, "GL 渲染器", renderer, null, null, 0));
                }
                hasRow = true;
            }
            if (sample.has("render_width") || sample.has("render_height")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, "渲染分辨率与模式", null,
                        sample.optInt("render_width", 1920) + " × " + sample.optInt("render_height", 1080)
                                + (suite.gpuTest().endsWith("_off") ? " (离屏)" : " (屏上提交)"), null, 0));
                hasRow = true;
            }
            if (sample.has("successful_window_swaps")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, "窗口成功交换次数", null,
                        sample.optLong("successful_window_swaps") + " 次", null, 0));
                hasRow = true;
            }
        } else if (suite == BenchmarkSuite.WEB) {
            card.addView(MiuiUi.kvRow(context, "CSS / 物理竖屏视口", null,
                    sample.optInt("viewport_width") + "×" + sample.optInt("viewport_height")
                            + " / " + sample.optInt("physical_width") + "×" + sample.optInt("physical_height")
                            + " (DPR " + sample.optDouble("dpr") + ")", null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, "测试负载构成",
                    "信息流卡片 640 张 + 列表项 2000 条 + 段落 480 段 + 480p/720p/1080p 大图解码 20 次", null, null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, "计时器与精度", null,
                    sample.optString("clock_source") + " / " + sample.optDouble("clock_resolution_ms") + " ms", null, 0));
            MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, "Web 宿主身份", sample.optString("host_build_id"), null, null, 0));
            hasRow = true;
        } else {
            if (sample.has("parallel_threads")) {
                card.addView(MiuiUi.kvRow(context, "并行工作线程数", null, sample.optInt("parallel_threads") + " 线程", null, 0));
                hasRow = true;
            }
            if (sample.has("native_library")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, "原生计算库", sample.optString("native_build_id"),
                        sample.optString("native_library") + " (" + sample.optString("native_abi") + ")", null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.MEMORY && sample.has("buffer_bytes")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                card.addView(MiuiUi.kvRow(context, "内存测试量",
                        "8 MiB 复制/填充(共 16 MiB) · 4 MiB 随机指针链(100 万节点) · 24 MiB 共享 Triad(3×8 MiB)",
                        null, null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.STORAGE && sample.has("file_bytes")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                long fileMib = sample.optLong("file_bytes") / (1024 * 1024);
                card.addView(MiuiUi.kvRow(context, "存储测试量与模式",
                        "256 KiB 顺序读写 × " + (fileMib * 4) + " 块 · 4 KiB 随机读写 × " + sample.optInt("random_operations", 256) + " 次",
                        fileMib + " MiB · " + sample.optString("read_mode"), null, 0));
                hasRow = true;
            }
            if (suite == BenchmarkSuite.SQLITE && sample.has("sqlite_version")) {
                if (hasRow) MiuiUi.addDivider(context, card);
                int tx = sample.optInt("transactions", 300);
                card.addView(MiuiUi.kvRow(context, "SQLite 引擎与测试量",
                        "INSERT / UPDATE / DELETE 各 " + tx + " 独立事务（共 " + (tx * 3) + " 次 COMMIT）· 1 KiB/行 · DELETE 日志 + FULL 同步",
                        "v" + sample.optString("sqlite_version"), null, 0));
                hasRow = true;
            }
        }
        if (hasRow) {
            col.addView(MiuiUi.sectionTitle(context, "测试参数与运行环境"));
            col.addView(card);
        }
    }

    private static void appendPerRoundSamplesCard(Context context, LinearLayout col, BenchmarkSuite suite, JSONArray samples) {
        col.addView(MiuiUi.sectionTitle(context, "各轮原始采样明细"));
        LinearLayout card = MiuiUi.cardGroup(context);
        for (int r = 0; r < samples.length(); r++) {
            JSONObject sample = samples.optJSONObject(r);
            if (sample == null) continue;
            if (r > 0) MiuiUi.addDivider(context, card);
            String st = sample.optString("status");
            StringBuilder detail = new StringBuilder();
            for (int m = 0; m < suite.metrics.length; m++) {
                String k = suite.metrics[m];
                if (!sample.has(k)) continue;
                if (detail.length() > 0) detail.append("  |  ");
                detail.append(suite.labels[m]).append(": ")
                        .append(String.format(Locale.US, "%.2f %s", sample.optDouble(k), suite.units[m]));
            }
            card.addView(MiuiUi.kvRow(context, "第 " + (r + 1) + " 轮", detail.toString(),
                    null, BenchmarkReport.statusLabel(st), MiuiUi.statusTone(st)));
        }
        col.addView(card);
    }

    /** Sub-page C: CPU per-core topology, GPU GL extensions, /proc/cpuinfo, and JNI probe report. */
    static View buildCpuProbePage(Context context, JSONObject device, String nativeProbeText, Navigator nav) {
        LinearLayout col = pageColumn(context);

        if (device != null) {
            JSONArray cores = device.optJSONArray("cpu_cores");
            if (cores != null && cores.length() > 0) {
                col.addView(MiuiUi.sectionTitle(context, "CPU 各核心状态 (" + cores.length() + " 核)"));
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
                    coreCard.addView(MiuiUi.kvRow(context, "核心 #" + idx,
                            min + " ~ " + max + " · " + gov,
                            cur, online ? "在线" : "离线", online ? MiuiUi.TONE_OK : MiuiUi.TONE_NEUTRAL));
                }
                col.addView(coreCard);
            }

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
                col.addView(MiuiUi.sectionTitle(context, "OpenGL ES 扩展列表"));
                LinearLayout glCard = MiuiUi.cardGroup(context);
                TextView extTv = new TextView(context);
                extTv.setText(extra.optString("gpu_extensions"));
                extTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                extTv.setTypeface(Typeface.MONOSPACE);
                extTv.setTextColor(MiuiUi.TEXT_SECONDARY);
                int pad = MiuiUi.dp(context, 14);
                extTv.setPadding(pad, pad, pad, pad);
                PlatformCompat.selectable(extTv);
                glCard.addView(extTv);
                col.addView(glCard);
            }
        }

        col.addView(MiuiUi.sectionTitle(context, "JNI 原生探针"));
        LinearLayout probeCard = MiuiUi.cardGroup(context);
        TextView mono = new TextView(context);
        mono.setText(nativeProbeText != null && nativeProbeText.length() > 0 ? nativeProbeText : "暂无探针数据");
        mono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mono.setTypeface(Typeface.MONOSPACE);
        mono.setTextColor(MiuiUi.TEXT_PRIMARY);
        int pad = MiuiUi.dp(context, 14);
        mono.setPadding(pad, pad, pad, pad);
        PlatformCompat.selectable(mono);
        probeCard.addView(mono);
        if (device != null) {
            MiuiUi.addDivider(context, probeCard);
            probeCard.addView(MiuiUi.navRow(context, "复制完整设备 JSON", null, null, null, 0, () -> {
                try {
                    nav.copyText("设备 JSON", device.toString(2));
                } catch (JSONException e) {
                    nav.copyText("设备 JSON", device.toString());
                }
            }));
        }
        col.addView(probeCard);

        return wrapScroll(context, col);
    }

    /** Sub-page D: Version & Build Identity details. */
    static View buildVersionPage(Context context, Navigator nav) {
        LinearLayout col = pageColumn(context);

        col.addView(MiuiUi.sectionTitle(context, "应用版本"));
        LinearLayout appCard = MiuiUi.cardGroup(context);
        appCard.addView(MiuiUi.kvRow(context, "应用名称", null, "RetroMobileBench", null, 0));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, "软件版本", null, BuildConfig.VERSION_NAME, "Code " + BuildConfig.VERSION_CODE, MiuiUi.TONE_ACTIVE));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, "测试范围", null, "12 项离线基准测试 (fixed-suite-v1)", null, 0));
        MiuiUi.addDivider(context, appCard);
        appCard.addView(MiuiUi.kvRow(context, "兼容目标", "ARMv7 (NEON / 无 NEON) · x86", "API 8 (Android 2.2+)", null, 0));
        col.addView(appCard);

        col.addView(MiuiUi.sectionTitle(context, "构建标识（点击复制）"));
        LinearLayout buildCard = MiuiUi.cardGroup(context);
        String lib = NativeLoader.load();
        buildCard.addView(MiuiUi.navRow(context, "NATIVE_BUILD_ID",
                BuildConfig.NATIVE_BUILD_ID, "复制", null, 0,
                () -> nav.copyText("NATIVE_BUILD_ID", BuildConfig.NATIVE_BUILD_ID)));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.navRow(context, "WEB_BUILD_ID",
                BuildConfig.WEB_BUILD_ID, "复制", null, 0,
                () -> nav.copyText("WEB_BUILD_ID", BuildConfig.WEB_BUILD_ID)));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, "编译工具链", "NDK r10e · GCC 4.9 · gnustl_static (-O2)", null, null, 0));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, "原生计算库", lib != null ? lib : "未加载",
                lib != null && lib.contains("noneon") ? "标量 / VFPv3-D16" : "NEON / x86", null, 0));
        MiuiUi.addDivider(context, buildCard);
        buildCard.addView(MiuiUi.kvRow(context, "GPU 屏上浮层", "窗口提交帧率 + 电池温度", GpuHud.INTERVAL_MS + " ms", null, 0));
        col.addView(buildCard);

        col.addView(MiuiUi.sectionTitle(context, "结果统计说明"));
        LinearLayout ruleCard = MiuiUi.cardGroup(context);
        ruleCard.addView(MiuiUi.kvRow(context, "分项实测与中位数",
                "展示各阶段真实测量值与 3 轮有效中位数，不合成单一总分。", null, null, 0));
        MiuiUi.addDivider(context, ruleCard);
        ruleCard.addView(MiuiUi.kvRow(context, "一致性校验",
                "仅在构建标识、测试参数与运行状态一致时计算多轮中位数。", null, null, 0));
        col.addView(ruleCard);

        return wrapScroll(context, col);
    }

    /** Sub-page E1: Fixed Suite (12 stages) protocol overview. */
    static View buildSuiteProtocolPage(Context context) {
        LinearLayout col = pageColumn(context);
        col.addView(MiuiUi.sectionTitle(context, "12 阶段执行顺序与明确测试量说明"));

        LinearLayout listCard = MiuiUi.cardGroup(context);
        for (int i = 0; i < BenchmarkSuite.DEFAULT.length; i++) {
            BenchmarkSuite s = BenchmarkSuite.DEFAULT[i];
            if (i > 0) MiuiUi.addDivider(context, listCard);
            String rounds = s.repetitions() + " 轮" + (s.repetitions() == 3 ? "中位数" : "正式测量");
            String desc = s.progress(s == BenchmarkSuite.STORAGE ? 32 : (s == BenchmarkSuite.SQLITE ? 300 : 4));
            listCard.addView(MiuiUi.kvRow(context, (i + 1) + ". " + s.label, desc,
                    null, rounds, s.repetitions() == 3 ? MiuiUi.TONE_OK : MiuiUi.TONE_ACTIVE));
        }
        col.addView(listCard);
        return wrapScroll(context, col);
    }

    /** Sub-page E2: Bundled Open-Source Projects & Benchmark Kernels list. */
    static View buildOpenSourceProjectsPage(Context context, Navigator nav) {
        LinearLayout col = pageColumn(context);
        col.addView(MiuiUi.sectionTitle(context, "内置开源基准测试与依赖项目（共 7 项）"));

        String[][] projects = {
                {"CoreMark® 1.0", "EEMBC · COREMARK 许可证", "1f483d5b8316 · CPU 单核与多核整数核心计算（2000B 栈 / 双种子）"},
                {"SciMark 2.0 C", "NIST · 公有领域 / 学术声明", "FFT、SOR、Monte Carlo、稀疏矩阵、LU 五项单核/多核浮点内核"},
                {"FFmpeg 4.4.6", "FFmpeg 团队 · LGPL v2.1+", "44b04492bfc8 · avutil/avcodec/avformat 独立动态库视频标量软解与 SHA-256 校验"},
                {"tinymembench", "Siarhei Siamashka · MIT 许可证", "a2cf6d7e382e · 8 MiB 内存复制/填充带宽与 4 MiB 随机指针链延迟"},
                {"STREAM Triad 变体", "John D. McCalpin / Jeff Hammond · STREAM 许可", "6703f7504a38 · 共享固定 24 MiB 工作集（3×8 MiB）单核/多核 Triad 内存带宽"},
                {"SQLite 3.53.1", "Hipp, Wyrick & Company · 公有领域", "sqlite-amalgamation-3530100 · INSERT/UPDATE/DELETE 各 300 独立同步事务 TPS 基准"},
                {"GFXBench 4.0 (ES2 子集)", "Kishonti Ltd · BSD / CC BY 4.0 素材", "89aa956f4383 · OpenGL ES 2.0 Egypt HD / T-Rex / Fill 离屏与屏上渲染"}
        };

        LinearLayout card = MiuiUi.cardGroup(context);
        for (int i = 0; i < projects.length; i++) {
            if (i > 0) MiuiUi.addDivider(context, card);
            card.addView(MiuiUi.kvRow(context, projects[i][0],
                    projects[i][2] + "\n来源与协议：" + projects[i][1], null, null, 0));
        }
        col.addView(card);

        col.addView(MiuiUi.sectionTitle(context, "许可证原文"));
        LinearLayout linkCard = MiuiUi.cardGroup(context);
        linkCard.addView(MiuiUi.navRow(context, "阅读开源许可证与署名全文", "查看 licenses.txt / benchmark-notices.txt / xiph-notices.txt",
                null, null, 0, () -> nav.openSubPage("开源许可协议全文", buildLicenseReaderPage(context))));
        col.addView(linkCard);

        return wrapScroll(context, col);
    }

    /** Sub-page F: Segmented reader for the 3 bundled license/notice asset files. */
    static View buildLicenseReaderPage(final Context context) {
        LinearLayout col = pageColumn(context);
        col.addView(MiuiUi.sectionTitle(context, "选择要查看的协议文档"));

        LinearLayout segRow = new LinearLayout(context);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        int m = MiuiUi.dp(context, 12);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segLp.setMargins(m, 0, m, MiuiUi.dp(context, 8));
        segRow.setLayoutParams(segLp);

        final String[] files = {"licenses.txt", "benchmark-notices.txt", "xiph-notices.txt"};
        final String[] titles = {"第三方库许可", "基准组件声明", "媒体素材署名"};
        final Button[] tabs = new Button[3];

        LinearLayout textCard = MiuiUi.cardGroup(context);
        final TextView contentView = new TextView(context);
        contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        contentView.setTypeface(Typeface.MONOSPACE);
        contentView.setTextColor(MiuiUi.TEXT_PRIMARY);
        int pad = MiuiUi.dp(context, 14);
        contentView.setPadding(pad, pad, pad, pad);
        PlatformCompat.selectable(contentView);
        textCard.addView(contentView);

        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button b = new Button(context);
            b.setText(titles[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            int vPad = MiuiUi.dp(context, 8);
            b.setPadding(MiuiUi.dp(context, 6), vPad, MiuiUi.dp(context, 6), vPad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = MiuiUi.dp(context, 6);
            b.setOnClickListener(v -> {
                for (int j = 0; j < 3; j++) {
                    boolean sel = (j == index);
                    MiuiUi.setBackground(tabs[j], MiuiUi.segmentDrawable(context, sel));
                    tabs[j].setTextColor(sel ? Color.WHITE : MiuiUi.TEXT_PRIMARY);
                }
                contentView.setText(readAssetText(context, files[index]));
            });
            tabs[i] = b;
            segRow.addView(b, lp);
        }

        // Default select first segment
        MiuiUi.setBackground(tabs[0], MiuiUi.segmentDrawable(context, true));
        tabs[0].setTextColor(Color.WHITE);
        for (int j = 1; j < 3; j++) {
            MiuiUi.setBackground(tabs[j], MiuiUi.segmentDrawable(context, false));
            tabs[j].setTextColor(MiuiUi.TEXT_PRIMARY);
        }
        contentView.setText(readAssetText(context, files[0]));

        col.addView(segRow);
        col.addView(textCard);
        return wrapScroll(context, col);
    }

    private static String readAssetText(Context context, String assetName) {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[2048];
        try (Reader reader = new InputStreamReader(context.getAssets().open(assetName), "UTF-8")) {
            int n;
            while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
            return out.toString();
        } catch (Exception e) {
            return "读取 " + assetName + " 失败：" + e;
        }
    }

    /** Sub-page G: Clean reader for raw formatted text report + copy actions. */
    static View buildRawReportPage(Context context, final String formattedText, final String jsonText, Navigator nav) {
        LinearLayout col = pageColumn(context);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int m = MiuiUi.dp(context, 12);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(m, MiuiUi.dp(context, 6), m, MiuiUi.dp(context, 8));
        bar.setLayoutParams(barLp);

        Button copyTextBtn = MiuiUi.styledButton(context, "复制报告文本", false, false,
                () -> nav.copyText("报告文本", formattedText != null ? formattedText : ""));
        bar.addView(copyTextBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (jsonText != null && jsonText.length() > 0) {
            Button copyJsonBtn = MiuiUi.styledButton(context, "导出 JSON", true, false,
                    () -> nav.copyText("结果 JSON", jsonText));
            LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            jLp.leftMargin = MiuiUi.dp(context, 8);
            bar.addView(copyJsonBtn, jLp);
        }
        col.addView(bar);

        LinearLayout card = MiuiUi.cardGroup(context);
        TextView tv = new TextView(context);
        tv.setText(formattedText != null && formattedText.length() > 0 ? formattedText : "暂无文本内容");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(MiuiUi.TEXT_PRIMARY);
        int pad = MiuiUi.dp(context, 14);
        tv.setPadding(pad, pad, pad, pad);
        PlatformCompat.selectable(tv);
        card.addView(tv);
        col.addView(card);

        return wrapScroll(context, col);
    }
}
