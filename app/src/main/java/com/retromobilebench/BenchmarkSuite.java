package com.retromobilebench;

import android.content.Context;

import org.json.JSONObject;

/** Suite-specific protocol; the runner owns lifecycle, checkpoints and repetition. */
enum BenchmarkSuite {
    COREMARK("cpu-coremark-v1", "CPU 整数测试（CoreMark®）",
            new String[]{"single_iterations_per_second", "parallel_iterations_per_second"},
            new String[]{"单核整数", "多核整数"}, new String[]{"iterations/s", "iterations/s"},
            new String[]{"source_revision", "compiler_version", "compiler_flags", "memory_method",
                    "buffer_bytes", "minimum_seconds", "parallel_threads", "diagnostic_iterations"}),
    SCIMARK("cpu-scimark-small-v1", "CPU 浮点测试（SciMark 2.0）",
            new String[]{"single_composite_mflops", "parallel_composite_mflops",
                    "fft_mflops", "sor_mflops", "monte_carlo_mflops", "sparse_mflops", "lu_mflops",
                    "parallel_fft_mflops", "parallel_sor_mflops", "parallel_monte_carlo_mflops", "parallel_sparse_mflops", "parallel_lu_mflops"},
            new String[]{"单核综合浮点", "多核综合浮点",
                    "单核 FFT (1024)", "单核 SOR (100×100)", "单核 Monte Carlo (16384)", "单核稀疏矩阵 (N=1000)", "单核 LU (100×100)",
                    "多核 FFT", "多核 SOR", "多核 Monte Carlo", "多核稀疏矩阵", "多核 LU"},
            new String[]{"MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS",
                    "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS"},
            new String[]{"kernel_version", "parallel_threads", "duration_ms", "seed", "fft_size", "sor_size",
                    "sparse_size", "sparse_nonzeros", "lu_size", "monte_carlo_batch"}),
    MEMORY("memory-tiny-v1", "内存带宽与延迟",
            new String[]{"copy_mbps", "fill_mbps", "latency_ns", "single_triad_mbps", "parallel_triad_mbps"},
            new String[]{"复制带宽 (8MiB读+8MiB写)", "填充带宽 (8MiB写)", "随机指针链延迟 (4MiB)", "单核 Triad (24MiB共享)", "多核 Triad (24MiB共享)"},
            new String[]{"MB/s", "MB/s", "ns", "MB/s", "MB/s"},
            new String[]{"source_revision", "duration_ms", "buffer_bytes", "latency_bytes", "latency_batch", "seed", "copy_traffic_multiplier",
                    "triad_protocol", "parallel_threads", "triad_array_bytes", "triad_total_bytes", "triad_element_bytes", "triad_bytes_per_element", "triad_scalar", "triad_source_revision"}),
    STORAGE("storage-file-v1", "存储读写测试",
            new String[]{"sequential_write_mbps", "sequential_read_mbps", "random_write_iops", "random_read_iops"},
            new String[]{"顺序写入 (256KiB×128)", "顺序读取 (256KiB×128)", "随机写入 (4KiB×256)", "随机读取 (4KiB×256)"},
            new String[]{"MB/s", "MB/s", "IOPS", "IOPS"},
            new String[]{"file_bytes", "random_operations", "sequential_block_bytes", "random_block_bytes", "write_mode", "read_mode"}),
    SQLITE("sqlite-tps-v1", "SQLite 事务测试",
            new String[]{"composite_tps", "insert_tps", "update_tps", "delete_tps"},
            new String[]{"综合平均事务 (900次COMMIT)", "插入事务 (300×1KiB)", "更新事务 (300×1KiB)", "删除事务 (300×1KiB)"},
            new String[]{"TPS", "TPS", "TPS", "TPS"},
            new String[]{"sqlite_version", "sqlite_source_id", "transactions", "rows_per_transaction",
                    "payload_bytes", "page_bytes", "cache_kib", "journal_mode", "synchronous"}),
    WEB("web-daily-v1", "Web 竖屏信息流与大图",
            new String[]{"total_ms", "feed_ms", "image_ms", "page_ms", "todo_ms", "text_ms"},
            new String[]{"Web 综合总耗时 (信息流+大图)", "竖屏信息流耗时 (卡片+列表+长文)", "高清大图解码耗时 (480p/720p/1080p)",
                    "信息流卡片构建 (640张卡片)", "动态列表交互 (2000条状态)", "长文段落排版 (480段重排)"},
            new String[]{"ms", "ms", "ms", "ms", "ms", "ms"},
            new String[]{"resource_sha256", "host_build_id", "app_build_type", "app_debuggable", "units_per_subtest", "prewarm_units_per_subtest", "viewport_css", "font_css", "viewport_width", "viewport_height", "window_mode", "window_width", "window_height",
                    "bridge_origin", "clock_source", "user_agent", "provider", "os_fingerprint", "dpr", "physical_width", "physical_height",
                    "density", "initial_scale_percent", "webview_scale", "css_to_physical_x", "css_to_physical_y", "layout_algorithm", "hardware_accelerated", "text_zoom_percent", "scheduling", "measurement"}),
    VIDEO("video-decode-v1", "视频解码与播放", new String[]{}, new String[]{}, new String[]{}, new String[]{}),
    GPU_FILL("gl_fill_off", "GPU 纹理填充率", "MTexels/s"),
    GPU_TREX("gl_trex_off", "GPU T-Rex（1080p 离屏）", "frames"),
    GPU_EGYPT("gl_egypt_off", "GPU Egypt HD（1080p 离屏）", "frames"),
    GPU_EGYPT_SCREEN("gl_egypt", "GPU Egypt HD（屏上）", "frames"),
    GPU_TREX_SCREEN("gl_trex", "GPU T-Rex（屏上）", "frames");

    static final BenchmarkSuite[] DEFAULT = {
            COREMARK, SCIMARK, MEMORY, STORAGE, SQLITE, WEB, VIDEO,
            GPU_EGYPT, GPU_EGYPT_SCREEN, GPU_TREX, GPU_TREX_SCREEN, GPU_FILL
    };
    static final String SESSION_VERSION = "fixed-suite-v1";

    int repetitions() { return isGpu() || this == VIDEO ? 1 : 3; }
    static boolean isSession(String version) { return SESSION_VERSION.equals(version); }

    static final int CPU_DURATION_MS = 1500;
    static final int SCIMARK_DURATION_MS = 1000;
    final String version, label;
    final String[] metrics, labels, units, parameters;

    BenchmarkSuite(String version, String label, String[] metrics, String[] labels,
                   String[] units, String[] parameters) {
        this.version = version;
        this.label = label;
        this.metrics = metrics;
        this.labels = labels;
        this.units = units;
        this.parameters = parameters;
    }

    BenchmarkSuite(String test, String label, String unit) {
        this("gpu-" + test + "-v1", label, new String[]{"score"}, new String[]{label}, new String[]{unit},
                new String[]{"source_revision", "data_sha256", "test_id", "texture_type", "offscreen_width", "offscreen_height",
                        "pbuffer_width", "pbuffer_height", "diagnostic_duration_ms", "unit", "renderer", "vendor", "gl_version",
                        "presentation", "render_width", "render_height", "refresh_hz", "swap_interval_request"});
    }

    boolean isGpu() { return name().startsWith("GPU_"); }
    String gpuTest() { return version.substring(4, version.length() - 3); }

    static BenchmarkSuite fromReport(JSONObject report) {
        // Early storage records/tests had no suite_version. Keep them readable.
        String version = report.optString("suite_version", STORAGE.version);
        for (BenchmarkSuite suite : values()) if (suite.version.equals(version)) return suite;
        return null;
    }

    @Override public String toString() { return label; }

    int parameter(JSONObject device) {
        if (isGpu() || this == WEB) return 0;
        if (this == SQLITE) return 300;
        if (this == COREMARK || this == SCIMARK || this == VIDEO || this == MEMORY) return CpuTopology.workers(device);
        return (int) Math.min(32L, (device.optLong("available_storage_bytes") - 16 * 1024L * 1024L)
                / (1024L * 1024L));
    }

    String preflight(JSONObject device, int parameter) {
        if (!isGpu() && (this == COREMARK || this == SCIMARK || this == VIDEO || this == MEMORY) && parameter > CpuTopology.CAPACITY)
            return "检测到 " + parameter + " 个 CPU，超过当前资源容量 " + CpuTopology.CAPACITY + "；未截断线程数。";
        if (isGpu()) return BenchmarkRunner.gpuPreflight(device, gpuTest().contains("trex") || gpuTest().contains("egypt") ? 192 : 128);
        if (this == SQLITE) return BenchmarkRunner.sqlitePreflight(device);
        if (this == MEMORY || this == VIDEO) return BenchmarkRunner.memoryPreflight(device);
        return this == STORAGE ? BenchmarkRunner.preflight(device, parameter) : BenchmarkRunner.cpuPreflight(device);
    }

    void resetCancellation() {
        if (this == WEB) WebClient.resetCancellation();
        else if (this == VIDEO) MediaClient.resetCancellation();
        else if (isGpu()) GpuClient.resetCancellation();
        else if (this == MEMORY) NativeBridge.resetMemoryCancellation();
        else if (this == SCIMARK) NativeBridge.resetScimarkCancellation();
        else if (this == COREMARK) NativeBridge.resetCoremarkCancellation();
        else if (this == SQLITE) NativeBridge.resetSqliteCancellation();
        else NativeBridge.resetStorageCancellation();
    }

    String run(Context context, int parameter) {
        if (this == WEB) return WebClient.run(context);
        if (this == VIDEO) return MediaCorpus.video(context, parameter);
        if (isGpu()) return GpuClient.run(context, gpuTest(), 0);
        if (this == MEMORY) return NativeBridge.runMemory(parameter, CPU_DURATION_MS);
        if (this == SCIMARK) return NativeBridge.runScimark(parameter, SCIMARK_DURATION_MS);
        if (this == COREMARK) return NativeBridge.runCoremark(parameter, 0);
        if (this == SQLITE) return NativeBridge.runSqlite(context.getCacheDir().getAbsolutePath(), parameter);
        return NativeBridge.runStorage(context.getCacheDir().getAbsolutePath(), parameter, 256);
    }

    String progress(int parameter) {
        if (this == WEB) return "独立竖屏全屏 WebView；3 项信息流任务（640 卡片 / 2000 列表项 / 480 段落）+ 1 项 480p/720p/1080p 高清大图解码（20\u00A0单元）。\n各 20\u00A0单元 × 3\u00A0轮取中位数，测量任务完成与布局/像素检查点耗时（越低越好）。";
        if (this == VIDEO) return "7\u00A0段离线视频（240p H.263 / 360p MPEG-4 / 480p–1080p H.264 / 720p VP8+VP9）；依次执行 FFmpeg 标量软解、MediaCodec 系统解码与 Surface 实时播放。";
        if (isGpu()) return (gpuTest().endsWith("_off") ? "1920\u00A0×\u00A01080 离屏渲染" : "实际窗口分辨率屏上渲染") + " / ETC1 纹理 / 正式时长。\n在独立子进程中运行，可随时取消。";
        if (this == MEMORY) return "8\u00A0MiB 复制/填充（共 16\u00A0MiB 缓冲）· 4\u00A0MiB 随机指针链延迟（100 万节点）。\nTriad 共享固定 24\u00A0MiB 工作集（3×8\u00A0MiB），单核及 " + parameter + "\u00A0线程；每项至少 1.5\u00A0秒，3\u00A0轮取中位数。";
        if (this == SCIMARK) return "NIST SciMark 2.0 五项浮点内核（FFT 1024 / SOR 100×100 / Monte Carlo 16384 / 稀疏矩阵 N=1000 / LU 100×100）。\n单核及 " + parameter + "\u00A0线程，输出单核/多核综合 MFLOPS，3\u00A0轮取中位数。";
        if (this == COREMARK) return "2000\u00A0字节栈工作区 · 双种子完整校验；单核及 " + parameter + "\u00A0线程。\n单段正式计时至少 10\u00A0秒，3\u00A0轮取中位数；取消时在当前计算段结束后停止。";
        if (this == SQLITE) return "INSERT / UPDATE / DELETE 各 " + parameter + "\u00A0个独立事务（共 " + (parameter * 3) + "\u00A0次 COMMIT）。\n每行 1\u00A0KiB 载荷 · 4\u00A0KiB 页 · DELETE 日志 + FULL 同步落盘；3\u00A0轮取中位数。";
        return "测试文件 " + parameter + "\u00A0MiB（自适应 4–32\u00A0MiB，预留 ≥16\u00A0MiB 空间）。\n256\u00A0KiB 顺序读写（" + (parameter * 4) + "\u00A0块）与 4\u00A0KiB 随机读写（256\u00A0次）；写入 O_SYNC 同步落盘，读取优先 O_DIRECT。";
    }
}
