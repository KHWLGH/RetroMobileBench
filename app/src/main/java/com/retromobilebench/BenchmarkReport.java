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
            JSONObject device = report.optJSONObject("device");
            if (device != null && device.has("native_build_id"))
                matches &= device.getString("native_build_id").equals(sample.optString("native_build_id"));
            for (String key : parameterKeys(suite, sample)) {
                if (!sample.has(key) || !first.has(key)
                        || !sample.get(key).toString().equals(first.get(key).toString())) matches = false;
            }
            if (!matches) {
                report.put("message", "各轮参数、测试版本或完成状态不同，仅保留原始结果，不合并中位数");
                return;
            }
        }
        JSONObject median = new JSONObject();
        if (suite == BenchmarkSuite.IMAGE && !summarizeImages(samples, median)) {
            report.put("message", "图片各轮的素材、参考像素或完成状态不同，仅保留原始结果");
            return;
        }
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
        if (suite == BenchmarkSuite.MEMORY && "memory-tiny-v1".equals(sample.optString("test_version")))
            return prefix(suite.metrics, 3);
        boolean omitParallel = sample.optInt("parallel_threads") == 1
                && (!sample.optString("test_version").endsWith("-v1") || suite == BenchmarkSuite.CRYPTO);
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (String key : suite.metrics) if (!omitParallel || !key.startsWith("parallel_")) keys.add(key);
        return keys.toArray(new String[0]);
    }

    static String[] parameterKeys(BenchmarkSuite suite, JSONObject sample) {
        if (suite == BenchmarkSuite.IMAGE && !sample.has("duration_ms")) return new String[]{"corpus_version"};
        return suite == BenchmarkSuite.MEMORY && "memory-tiny-v1".equals(sample.optString("test_version"))
                ? prefix(suite.parameters, 7) : suite.parameters;
    }

    private static String[] prefix(String[] source, int count) {
        String[] result = new String[count]; System.arraycopy(source, 0, result, 0, count); return result;
    }

    static int metricCount(BenchmarkSuite suite, JSONObject sample) { return metricKeys(suite, sample).length; }

    private static boolean summarizeImages(JSONArray samples, JSONObject median) throws JSONException {
        JSONObject images = new JSONObject();
        JSONArray first = samples.getJSONObject(0).optJSONArray("results");
        if (first == null || first.length() != 6) return false;
        for (int n = 0; n < first.length(); n++) {
            JSONObject reference = first.getJSONObject(n); String id = reference.getString("id");
            if (images.has(id)) return false;
            JSONObject result = new JSONObject();
            double[][] values = new double[2][samples.length()];
            for (int round = 0; round < samples.length(); round++) {
                JSONObject sample = samples.getJSONObject(round);
                if (!samples.getJSONObject(0).optString("corpus_version").equals(sample.optString("corpus_version"))) return false;
                JSONArray list = sample.optJSONArray("results");
                if (list == null || list.length() != first.length()) return false;
                JSONObject item = null;
                for (int i = 0; i < list.length(); i++) if (id.equals(list.getJSONObject(i).optString("id"))) {
                    if (item != null) return false;
                    item = list.getJSONObject(i);
                }
                if (item == null || !"completed".equals(item.optString("status"))) return false;
                for (String key : new String[]{"asset_sha256", "reference_sha256", "reference_validation", "width", "height", "output_format", "library"})
                    if (!item.has(key) || !reference.has(key) || !reference.get(key).equals(item.get(key))) return false;
                if (!item.getString("reference_sha256").equals(item.optString("pixel_sha256"))) return false;
                values[0][round] = item.getDouble("mpixels_per_second"); values[1][round] = item.getDouble("ms_per_image");
                for (double[] metric : values) if (metric[round] <= 0 || Double.isNaN(metric[round]) || Double.isInfinite(metric[round])) return false;
            }
            Arrays.sort(values[0]); Arrays.sort(values[1]);
            result.put("mpixels_per_second", values[0][samples.length() / 2]).put("ms_per_image", values[1][samples.length() / 2]);
            images.put(id, result);
        }
        median.put("images", images); return true;
    }

    static String format(JSONObject report) throws JSONException {
        if (BenchmarkSuite.isSession(report.optString("suite_version"))) {
            StringBuilder text = new StringBuilder("本次固定套件 · ").append(statusLabel(report.optString("status"))).append('\n');
            text.append("会话：").append(report.optString("session_id")).append('\n');
            if (report.has("persistence_warning")) text.append(report.optString("persistence_warning")).append('\n');
            if (report.has("preparation_warning")) text.append("准备：").append(report.optString("preparation_warning")).append('\n');
            if (report.has("media_preparation_warning")) text.append("媒体准备：").append(report.optString("media_preparation_warning")).append('\n');
            if (report.has("cancel_stop_latency_ms")) text.append("请求停止后用时：").append(report.optLong("cancel_stop_latency_ms")).append(" ms\n");
            appendTemperature(text, report.optJSONObject("temperature"));
            JSONArray stages = report.optJSONArray("results");
            if (stages != null) for (int i = 0; i < stages.length(); i++) text.append("\n────────────────\n").append(format(stages.getJSONObject(i)));
            JSONObject active = report.optJSONObject("active_report");
            if (active != null) text.append("\n──────── 当前阶段已保存轮次 ────────\n").append(format(active));
            return text.toString();
        }
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
        appendTemperature(text, report.optJSONObject("temperature"));
        JSONObject median = report.optJSONObject("median");
        boolean summaryOnly = median != null && report.optInt("repetitions") == 3
                && (suite != BenchmarkSuite.IMAGE || median.has("images"));
        if (summaryOnly) {
            text.append("\n三轮中位数（各轮原始值见 JSON）\n");
            if (suite == BenchmarkSuite.IMAGE) {
                JSONObject images = median.getJSONObject("images");
                java.util.Iterator<String> ids = images.keys();
                while (ids.hasNext()) { String id = ids.next(); JSONObject image = images.getJSONObject(id);
                    text.append(String.format(Locale.US, "%s：%.2f MPix/s · %.2f ms/张%n", id, image.getDouble("mpixels_per_second"), image.getDouble("ms_per_image")));
                }
            } else for (int i = 0; i < suite.metrics.length; i++) appendMetric(text, suite, median, i);
            if (suite == BenchmarkSuite.MEMORY && median.has("single_triad_mbps")) text.append("Triad 总工作集 24 MiB；固定工作集 STREAM 变体，含缓存影响。\n");
        }
        JSONArray samples = report.optJSONArray("samples");
        if (samples != null && !summaryOnly) {
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                text.append("\n第 ").append(i + 1).append(" 轮 · ")
                        .append(statusLabel(sample.optString("status"))).append('\n');
                if (suite == BenchmarkSuite.IMAGE || suite == BenchmarkSuite.VIDEO) {
                    JSONArray details = sample.optJSONArray("results");
                    if (details != null) for (int n = 0; n < details.length(); n++) {
                        JSONObject item = details.getJSONObject(n); text.append(item.optString("id")).append('\n');
                        if (suite == BenchmarkSuite.IMAGE) {
                            text.append("  ").append(statusLabel(item.optString("status")));
                            if ("completed".equals(item.optString("status"))) text.append(String.format(Locale.US, " · %.2f MPix/s · %.2f ms/张", item.optDouble("mpixels_per_second"), item.optDouble("ms_per_image")));
                            else text.append(" · ").append(item.optString("error_stage"));
                            text.append('\n');
                        } else for (String path : new String[]{"software", "system", "playback"}) {
                            JSONObject value = item.optJSONObject(path); if (value == null) continue;
                            text.append("  ").append("software".equals(path) ? "固定软解" : "system".equals(path) ? "系统解码" : "实时播放").append("：")
                                    .append(statusLabel(value.optString("status")));
                            if (value.has("fps") && !value.isNull("fps")) text.append(String.format(Locale.US, " · %.2f fps", value.optDouble("fps")));
                            if (value.has("realtime")) text.append(value.optBoolean("realtime") ? " · 达到实时" : " · 低于实时");
                            if (value.has("error_stage")) text.append(" · ").append(value.optString("error_stage"));
                            if (value.has("decoder")) text.append(" · ").append(value.optString("decoder"));
                            if (!"software".equals(path)) appendDecoderEvidence(text, value);
                            text.append('\n');
                        }
                    }
                } else if (suite == BenchmarkSuite.CRYPTO) {
                    text.append("Mbed TLS ").append(sample.optString("library_version")).append(" / AES-128-GCM 含认证 / SHA-256\n");
                } else if (suite.isGpu()) {
                    text.append(sample.optString("vendor")).append(" / ").append(sample.optString("renderer")).append('\n')
                            .append(sample.optInt("render_width", 1920)).append(" × ").append(sample.optInt("render_height", 1080))
                            .append(suite.gpuTest().endsWith("_off") ? " 离屏" : " 屏上提交（受刷新率限制）")
                            .append(" / ETC1；数据摘要 ").append(sample.optString("data_sha256")).append('\n');
                    if (sample.optInt("diagnostic_duration_ms") > 0) text.append("短时诊断结果，不能与完整时长成绩比较。\n");
                } else if (suite.isCompression()) {
                    text.append(suite == BenchmarkSuite.CPU ? "zlib " : "zstd ").append(sample.optString("codec_version"))
                            .append(" / 压缩级别 ").append(sample.optInt("compression_level"))
                            .append(" / 数据 ").append(sample.optLong("input_bytes") / 1024).append(" KiB\n")
                            .append("并行阶段：").append(sample.optInt("parallel_threads")).append(" 线程；每项至少 ")
                            .append(sample.optInt("duration_ms")).append(" ms\n");
                    if (sample.optInt("parallel_threads") == 1 && !sample.optString("test_version").endsWith("-v1")) text.append("单核配置，未重复运行并行阶段。\n");
                } else if (suite == BenchmarkSuite.MEMORY) {
                    text.append("tinymembench C 内核 / RMB 协议；8 MiB 缓冲区，4 MiB 依赖访问\n")
                            .append("复制计读＋写字节；延迟越低越好，包含循环及索引开销。\n");
                    if (sample.has("triad_protocol")) text.append("Triad：1 + ").append(sample.optInt("parallel_threads"))
                            .append(" 线程；总工作集固定 24 MiB，含缓存影响；STREAM 变体。\n");
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
        text.append("\n仅显示原始吞吐量，尚未换算综合分数。\n");
        return text.toString();
    }

    private static void appendMetric(StringBuilder text, BenchmarkSuite suite, JSONObject sample, int index) throws JSONException {
        if (!sample.has(suite.metrics[index])) return;
        text.append(String.format(Locale.US, "%s：%.2f %s%n", suite.labels[index],
                sample.getDouble(suite.metrics[index]), suite.units[index]));
        if (suite.isGpu() && suite != BenchmarkSuite.GPU_FILL && sample.has("fps")) text.append(String.format(Locale.US, "平均：%.2f FPS（%s）%n", sample.getDouble("fps"), suite.gpuTest().endsWith("_off") ? "离屏渲染" : "窗口提交"));
    }

    private static void appendDecoderEvidence(StringBuilder text, JSONObject value) {
        JSONObject attributes = value.optJSONObject("system_attributes");
        if (attributes != null && attributes.has("hardware_accelerated")) {
            text.append(attributes.optBoolean("hardware_accelerated") ? " · 系统声明硬件加速" : attributes.optBoolean("software_only") ? " · 系统声明软件解码" : " · 系统声明未分类");
        } else if ("known_software_name".equals(value.optString("type_evidence"))) text.append(" · 已知软件解码器名称");
        else text.append(" · 硬件类型未确认");
    }

    private static void appendTemperature(StringBuilder text, JSONObject trace) {
        if (trace == null || !trace.optBoolean("available")) { text.append("电池温度：不可获得\n"); return; }
        text.append(String.format(Locale.US, "电池温度 %.1f → %.1f°C；采样峰值 %.1f°C；温升 %+.1f°C%n",
                trace.optInt("start_tenths_c") / 10.0, trace.optInt("end_tenths_c") / 10.0,
                trace.optInt("sampled_max_tenths_c") / 10.0, trace.optInt("delta_tenths_c") / 10.0));
    }

    static String statusLabel(String status) {
        if ("partial".equals(status)) return "部分完成";
        if ("unsupported".equals(status)) return "不支持";
        if ("timeout".equals(status)) return "超时";
        if ("completed".equals(status)) return "完成";
        if ("cancelled".equals(status)) return "已取消";
        if ("interrupted".equals(status)) return "已中断";
        if ("skipped".equals(status)) return "已跳过";
        if ("running".equals(status)) return "运行中";
        return "失败";
    }
}
