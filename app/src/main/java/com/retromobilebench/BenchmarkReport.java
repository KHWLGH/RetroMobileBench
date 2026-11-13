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
                    && sample.optInt("completed_metrics") == suite.metrics.length
                    && suite.version.equals(sample.optString("test_version"))
                    && first.optString("native_build_id", "legacy").equals(sample.optString("native_build_id", "legacy"))
                    && first.optString("native_library", "legacy").equals(sample.optString("native_library", "legacy"))
                    && first.optString("native_abi", "legacy").equals(sample.optString("native_abi", "legacy"));
            JSONObject device = report.optJSONObject("device");
            if (device != null && device.has("native_build_id"))
                matches &= device.getString("native_build_id").equals(sample.optString("native_build_id"));
            for (String key : suite.parameters) {
                if (!sample.has(key) || !first.has(key)
                        || !sample.get(key).toString().equals(first.get(key).toString())) matches = false;
            }
            if (!matches) {
                report.put("message", "各轮参数、测试版本或完成状态不同，仅保留原始结果，不合并中位数");
                return;
            }
        }
        JSONObject median = new JSONObject();
        for (String key : suite.metrics) {
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

    static String format(JSONObject report) throws JSONException {
        BenchmarkSuite suite = BenchmarkSuite.fromReport(report);
        if (suite == null) return "未知测试版本，保留原始记录：\n" + report.toString(2);
        StringBuilder text = new StringBuilder();
        JSONObject device = report.optJSONObject("device");
        text.append(suite.label).append(" · ").append(statusLabel(report.optString("status"))).append('\n');
        text.append(DateFormat.getDateTimeInstance().format(new Date(report.getLong("started_ms")))).append('\n');
        if (device != null) {
            text.append(device.optString("manufacturer")).append(' ').append(device.optString("model"))
                    .append(" / Android ").append(device.optString("android_release"))
                    .append(" (API ").append(device.optInt("sdk")).append(")\n");
            if (device.optBoolean("emulator")) text.append("模拟器：成绩仅用于验证流程，不能代表真机性能。\n");
            text.append("原生库：").append(device.optString("native_library", "加载失败")).append('\n');
            text.append("构建：").append(device.optString("native_build_id", "旧版构建（独立比较组）")).append('\n');
        }
        String message = report.optString("message");
        if (message.length() != 0) text.append(message).append('\n');
        JSONArray samples = report.optJSONArray("samples");
        if (samples != null) {
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                text.append("\n第 ").append(i + 1).append(" 轮 · ")
                        .append(statusLabel(sample.optString("status"))).append('\n');
                if (suite.isGpu()) {
                    text.append(sample.optString("vendor")).append(" / ").append(sample.optString("renderer")).append('\n')
                            .append("1920 × 1080 离屏 / ETC1；数据摘要 ").append(sample.optString("data_sha256")).append('\n');
                    if (sample.optInt("diagnostic_duration_ms") > 0) text.append("短时诊断结果，不能与完整时长成绩比较。\n");
                } else if (suite.isCompression()) {
                    text.append(suite == BenchmarkSuite.CPU ? "zlib " : "zstd ").append(sample.optString("codec_version"))
                            .append(" / 压缩级别 ").append(sample.optInt("compression_level"))
                            .append(" / 数据 ").append(sample.optLong("input_bytes") / 1024).append(" KiB\n")
                            .append("并行阶段：").append(sample.optInt("parallel_threads")).append(" 线程；每项至少 ")
                            .append(sample.optInt("duration_ms")).append(" ms\n");
                    if (sample.optInt("parallel_threads") == 1) text.append("仅 1 个可用线程，并行阶段仍为单线程。\n");
                } else if (suite == BenchmarkSuite.MEMORY) {
                    text.append("tinymembench C 内核 / RMB 协议；8 MiB 缓冲区，4 MiB 依赖访问\n")
                            .append("复制计读＋写字节；延迟越低越好，包含循环及索引开销。\n");
                } else if (suite == BenchmarkSuite.STREAM) {
                    text.append("基于 STREAM 变体的结果 / ").append(sample.optInt("threads"))
                            .append(" 线程；每线程 3 × 2 MiB；含缓存影响\n");
                } else if (suite == BenchmarkSuite.SCIMARK) {
                    text.append("NIST SciMark 2.0 内核 / 单线程 / 小数据集\n")
                            .append("独立 RMB 计时协议；每项至少 ").append(sample.optInt("duration_ms")).append(" ms\n");
                } else if (suite == BenchmarkSuite.COREMARK) {
                    text.append("2000 字节 / 上游双种子校验 / 每段至少 10 秒\n")
                            .append(sample.optString("compiler_version")).append('\n')
                            .append(sample.optString("compiler_flags")).append('\n')
                            .append("栈内存；PThreads / 并行线程数：").append(sample.optInt("parallel_threads")).append('\n')
                            .append("CoreMark® 是 EEMBC 的注册商标；完整校验输出见 JSON。\n");
                } else if (suite == BenchmarkSuite.SQLITE) {
                    text.append("SQLite ").append(sample.optString("sqlite_version"))
                            .append(" / 每项 ").append(sample.optInt("transactions")).append(" 个事务\n")
                            .append("1 KiB / 行；DELETE 日志；FULL 同步提交\n");
                } else {
                    text.append("文件 ").append(sample.optLong("file_bytes") / (1024 * 1024))
                            .append(" MiB / 随机 ").append(sample.optInt("random_operations")).append(" 次\n");
                    if ("cached".equals(sample.optString("read_mode")))
                        text.append("读取含系统缓存，不能与 O_DIRECT 结果直接比较。\n");
                    else text.append("读取绕过页缓存 / 写入 O_SYNC\n");
                }
                int completed = Math.min(sample.optInt("completed_metrics"), suite.metrics.length);
                for (int j = 0; j < completed; j++) appendMetric(text, suite, sample, j);
                if (!"completed".equals(sample.optString("status"))) {
                    String errorKey = suite == BenchmarkSuite.STORAGE ? "errno" : "error_code";
                    text.append("停止位置：").append(sample.optString("error_stage"))
                            .append(" / ").append(errorKey).append(' ').append(sample.optInt(errorKey)).append('\n');
                }
            }
        }
        JSONObject median = report.optJSONObject("median");
        if (median != null && report.optInt("repetitions") > 1) {
            text.append("\n三轮中位数\n");
            for (int i = 0; i < suite.metrics.length; i++) appendMetric(text, suite, median, i);
        }
        text.append("\n仅显示原始吞吐量，尚未换算综合分数。\n");
        return text.toString();
    }

    private static void appendMetric(StringBuilder text, BenchmarkSuite suite, JSONObject sample, int index) throws JSONException {
        text.append(String.format(Locale.US, "%s：%.2f %s%n", suite.labels[index],
                sample.getDouble(suite.metrics[index]), suite.units[index]));
    }

    static String statusLabel(String status) {
        if ("completed".equals(status)) return "完成";
        if ("cancelled".equals(status)) return "已取消";
        if ("interrupted".equals(status)) return "进程中断";
        if ("skipped".equals(status)) return "已跳过";
        if ("running".equals(status)) return "运行中";
        return "失败";
    }
}
