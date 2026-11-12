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
    static final String[] METRICS = {"sequential_write_mbps", "sequential_read_mbps",
            "random_write_iops", "random_read_iops"};
    private static final String[] LABELS = {"顺序写入", "顺序读取", "随机写入", "随机读取"};

    private BenchmarkReport() { }

    static void summarize(JSONObject report) throws JSONException {
        report.remove("median");
        JSONArray samples = report.getJSONArray("samples");
        int count = report.getInt("repetitions");
        if (count != 1 && count != 3) throw new JSONException("Expected one or three repetitions");
        if (!"completed".equals(report.optString("status")) || samples.length() != count) return;
        JSONObject first = samples.getJSONObject(0);
        for (int i = 0; i < count; i++) {
            JSONObject sample = samples.getJSONObject(i);
            if (!"completed".equals(sample.optString("status"))
                    || sample.optInt("completed_metrics") != 4
                    || sample.getLong("file_bytes") != first.getLong("file_bytes")
                    || sample.getInt("random_operations") != first.getInt("random_operations")
                    || sample.getInt("sequential_block_bytes") != first.getInt("sequential_block_bytes")
                    || sample.getInt("random_block_bytes") != first.getInt("random_block_bytes")
                    || !sample.getString("write_mode").equals(first.getString("write_mode"))
                    || !sample.getString("read_mode").equals(first.getString("read_mode"))
                    || !sample.getString("test_version").equals(first.getString("test_version"))) {
                report.put("message", "各轮参数或缓存模式不同，仅保留原始结果，不合并中位数");
                return;
            }
        }
        JSONObject median = new JSONObject();
        for (String key : METRICS) {
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
        StringBuilder text = new StringBuilder();
        JSONObject device = report.optJSONObject("device");
        text.append("存储测试 · ").append(statusLabel(report.optString("status"))).append('\n');
        text.append(DateFormat.getDateTimeInstance().format(new Date(report.getLong("started_ms")))).append('\n');
        if (device != null) {
            text.append(device.optString("manufacturer")).append(' ').append(device.optString("model"))
                    .append(" / Android ").append(device.optString("android_release"))
                    .append(" (API ").append(device.optInt("sdk")).append(")\n");
            if (device.optBoolean("emulator")) text.append("模拟器：成绩仅用于验证流程，不能代表真机性能。\n");
            text.append("原生库：").append(device.optString("native_library", "加载失败")).append('\n');
        }
        String message = report.optString("message");
        if (!message.isEmpty()) text.append(message).append('\n');
        JSONArray samples = report.optJSONArray("samples");
        if (samples != null) {
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                text.append("\n第 ").append(i + 1).append(" 轮 · ")
                        .append(statusLabel(sample.optString("status"))).append('\n');
                text.append("文件 ").append(sample.optLong("file_bytes") / (1024 * 1024))
                        .append(" MiB / 随机 ").append(sample.optInt("random_operations")).append(" 次\n");
                if ("cached".equals(sample.optString("read_mode")))
                    text.append("读取含系统缓存，不能与 O_DIRECT 结果直接比较。\n");
                else text.append("读取绕过页缓存 / 写入 O_SYNC\n");
                int completed = sample.optInt("completed_metrics");
                for (int j = 0; j < completed; j++) appendMetric(text, sample, j);
                if (!"completed".equals(sample.optString("status")))
                    text.append("停止位置：").append(sample.optString("error_stage"))
                            .append(" / errno ").append(sample.optInt("errno")).append('\n');
            }
        }
        JSONObject median = report.optJSONObject("median");
        if (median != null && report.optInt("repetitions") > 1) {
            text.append("\n三轮中位数\n");
            for (int i = 0; i < METRICS.length; i++) appendMetric(text, median, i);
        }
        text.append("\n仅显示原始成绩；CPU、内存、SQLite 事务和 GPU 尚未接入此运行流程。\n");
        return text.toString();
    }

    private static void appendMetric(StringBuilder text, JSONObject sample, int index) throws JSONException {
        text.append(String.format(Locale.US, "%s：%.2f %s%n", LABELS[index],
                sample.getDouble(METRICS[index]), index < 2 ? "MB/s" : "IOPS"));
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
