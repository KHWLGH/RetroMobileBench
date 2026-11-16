package com.retromobilebench;

import android.content.Context;

import org.json.JSONObject;

/** Suite-specific protocol; the runner owns lifecycle, checkpoints and repetition. */
enum BenchmarkSuite {
    STORAGE("storage-file-v1", "存储测试",
            new String[]{"sequential_write_mbps", "sequential_read_mbps", "random_write_iops", "random_read_iops"},
            new String[]{"顺序写入", "顺序读取", "随机写入", "随机读取"},
            new String[]{"MB/s", "MB/s", "IOPS", "IOPS"},
            new String[]{"file_bytes", "random_operations", "sequential_block_bytes", "random_block_bytes", "write_mode", "read_mode"}),
    CPU("cpu-zlib-v2", "CPU 压缩测试（zlib）",
            new String[]{"single_compress_mbps", "single_decompress_mbps", "parallel_compress_mbps", "parallel_decompress_mbps"},
            new String[]{"单线程压缩", "单线程解压", "并行压缩", "并行解压"},
            new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"codec_version", "corpus_version", "input_bytes", "input_crc32", "compressed_bytes",
                    "compression_level", "parallel_threads", "duration_ms"}),
    ZSTD("cpu-zstd-v2", "CPU 压缩测试（zstd）",
            new String[]{"single_compress_mbps", "single_decompress_mbps", "parallel_compress_mbps", "parallel_decompress_mbps"},
            new String[]{"单线程压缩", "单线程解压", "并行压缩", "并行解压"},
            new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"codec_version", "corpus_version", "input_bytes", "input_crc32", "compressed_bytes",
                    "compression_level", "parallel_threads", "duration_ms"}),
    SQLITE("sqlite-tps-v1", "SQLite 事务测试",
            new String[]{"insert_tps", "update_tps", "delete_tps"},
            new String[]{"插入事务", "更新事务", "删除事务"},
            new String[]{"TPS", "TPS", "TPS"},
            new String[]{"sqlite_version", "sqlite_source_id", "transactions", "rows_per_transaction",
                    "payload_bytes", "page_bytes", "cache_kib", "journal_mode", "synchronous"}),
    WEB("web-daily-v2", "Web 日常响应",
            new String[]{"page_total_ms", "page_unit_ms", "todo_total_ms", "todo_unit_ms", "text_total_ms", "text_unit_ms", "data_total_ms", "data_unit_ms"},
            new String[]{"页面构建 · 20 单元", "页面构建 · 每单元", "待办交互 · 20 单元", "待办交互 · 每单元", "文本排版 · 20 单元", "文本排版 · 每单元", "数据展示 · 20 单元", "数据展示 · 每单元"},
            new String[]{"ms", "ms", "ms", "ms", "ms", "ms", "ms", "ms"},
            new String[]{"resource_sha256", "host_build_id", "app_build_type", "app_debuggable", "units_per_subtest", "prewarm_units_per_subtest", "viewport_css", "font_css", "viewport_width", "viewport_height", "window_mode", "window_width", "window_height",
                    "bridge_origin", "clock_source", "clock_resolution_ms", "user_agent", "provider", "os_fingerprint", "dpr", "physical_width", "physical_height",
                    "density", "initial_scale_percent", "webview_scale", "css_to_physical_x", "css_to_physical_y", "layout_algorithm", "hardware_accelerated", "text_zoom_percent", "scheduling", "measurement"}),
    COREMARK("cpu-coremark-v2", "CPU 整数测试（CoreMark®）",
            new String[]{"single_iterations_per_second", "parallel_iterations_per_second"},
            new String[]{"单线程", "并行"}, new String[]{"iterations/s", "iterations/s"},
            new String[]{"source_revision", "compiler_version", "compiler_flags", "memory_method",
                    "buffer_bytes", "minimum_seconds", "parallel_threads", "diagnostic_iterations"}),
    SCIMARK("cpu-scimark-small-v1", "CPU 浮点测试（SciMark 内核）",
            new String[]{"fft_mflops", "sor_mflops", "monte_carlo_mflops", "sparse_mflops", "lu_mflops"},
            new String[]{"FFT", "SOR", "Monte Carlo", "稀疏矩阵", "LU"},
            new String[]{"MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS", "MFLOPS"},
            new String[]{"kernel_version", "duration_ms", "seed", "fft_size", "sor_size",
                    "sparse_size", "sparse_nonzeros", "lu_size", "monte_carlo_batch"}),
    MEMORY("memory-tiny-v2", "内存带宽与延迟",
            new String[]{"copy_mbps", "fill_mbps", "latency_ns", "single_triad_mbps", "parallel_triad_mbps"},
            new String[]{"复制（读＋写）", "填充", "依赖访问延迟", "单线程 Triad", "并行 Triad"},
            new String[]{"MB/s", "MB/s", "ns", "MB/s", "MB/s"},
            new String[]{"source_revision", "duration_ms", "buffer_bytes", "latency_bytes", "latency_batch", "seed", "copy_traffic_multiplier",
                    "triad_protocol", "parallel_threads", "triad_array_bytes", "triad_total_bytes", "triad_element_bytes", "triad_bytes_per_element", "triad_scalar", "triad_source_revision"}),
    STREAM("memory-stream-variant-v1", "内存并行带宽（STREAM 变体）",
            new String[]{"copy_mbps", "scale_mbps", "add_mbps", "triad_mbps"},
            new String[]{"Copy", "Scale", "Add", "Triad"}, new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"source_revision", "protocol", "threads", "duration_ms", "elements_per_array_per_thread", "element_bytes", "scalar"}),
    GPU_ALU("gl_alu_off", "GPU 着色器算力", "frames"),
    GPU_FILL("gl_fill_off", "GPU 纹理填充率", "MTexels/s"),
    GPU_DRIVER("gl_driver_off", "GPU 驱动开销", "frames"),
    GPU_BLENDING("gl_blending_off", "GPU 混合", "MB/s"),
    GPU_TREX("gl_trex_off", "GPU T-Rex（1080p 离屏）", "frames"),
    GPU_EGYPT("gl_egypt_off", "GPU Egypt HD（1080p 离屏）", "frames"),
    GPU_EGYPT_SCREEN("gl_egypt", "GPU Egypt HD（屏上）", "frames"),
    GPU_TREX_SCREEN("gl_trex", "GPU T-Rex（屏上）", "frames"),
    CRYPTO("cpu-crypto-v1", "加密与摘要", new String[]{"single_encrypt_mbps", "single_decrypt_mbps", "single_sha256_mbps", "parallel_encrypt_mbps", "parallel_decrypt_mbps", "parallel_sha256_mbps"},
        new String[]{"单线程 GCM 加密", "单线程 GCM 解密认证", "单线程 SHA-256", "并行 GCM 加密", "并行 GCM 解密认证", "并行 SHA-256"},
        new String[]{"MB/s", "MB/s", "MB/s", "MB/s", "MB/s", "MB/s"}, new String[]{"parallel_threads", "duration_ms", "library_version"}),
    IMAGE("image-decode-v1", "图片解码", new String[]{}, new String[]{}, new String[]{}, new String[]{"corpus_version", "duration_ms"}),
    VIDEO("video-decode-v1", "视频解码与播放", new String[]{}, new String[]{}, new String[]{}, new String[]{});

    static final BenchmarkSuite[] DEFAULT = {COREMARK, SCIMARK, CPU, CRYPTO, IMAGE, VIDEO, MEMORY, STORAGE, SQLITE, WEB,
            GPU_EGYPT, GPU_EGYPT_SCREEN, GPU_TREX, GPU_TREX_SCREEN, GPU_FILL};
    static final String SESSION_VERSION = "fixed-suite-v3";

    int repetitions() { return isGpu() || this == VIDEO ? 1 : 3; }
    static boolean isSession(String version) { return SESSION_VERSION.equals(version) || "fixed-suite-v2".equals(version) || "fixed-suite-v1".equals(version); }

    static final int CPU_DURATION_MS = 1500;
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
        if ("cpu-zlib-v1".equals(version)) return CPU;
        if ("cpu-zstd-v1".equals(version)) return ZSTD;
        if ("cpu-coremark-v1".equals(version)) return COREMARK;
        if ("memory-tiny-v1".equals(version)) return MEMORY;
        if ("web-daily-v1".equals(version)) return WEB;
        for (BenchmarkSuite suite : values()) if (suite.version.equals(version)) return suite;
        return null;
    }

    boolean isCompression() { return this == CPU || this == ZSTD; }

    @Override public String toString() { return label; }

    int parameter(JSONObject device) {
        if (isGpu() || this == WEB) return 0;
        if (this == SQLITE) return 300;
        if (this == SCIMARK) return CPU_DURATION_MS;
        if (this == STREAM) return Math.max(1, Math.min(4, device.optInt("available_processors", 1))); // Legacy diagnostics only.
        if (isCompression() || this == COREMARK || this == CRYPTO || this == VIDEO || this == MEMORY) return CpuTopology.workers(device);
        if (this == IMAGE) return CPU_DURATION_MS;
        return (int) Math.min(32L, (device.optLong("available_storage_bytes") - 16 * 1024L * 1024L)
                / (1024L * 1024L));
    }

    String preflight(JSONObject device, int parameter) {
        if (!isGpu() && (isCompression() || this == COREMARK || this == CRYPTO || this == VIDEO || this == MEMORY) && parameter > CpuTopology.CAPACITY)
            return "检测到 " + parameter + " 个 CPU，超过当前资源容量 " + CpuTopology.CAPACITY + "；未截断线程数。";
        if (isGpu()) return BenchmarkRunner.gpuPreflight(device, gpuTest().contains("trex") || gpuTest().contains("egypt") ? 192 : 128);
        if (this == SQLITE) return BenchmarkRunner.sqlitePreflight(device);
        if (this == MEMORY || this == STREAM || this == IMAGE || this == VIDEO) return BenchmarkRunner.memoryPreflight(device);
        return this == STORAGE ? BenchmarkRunner.preflight(device, parameter) : BenchmarkRunner.cpuPreflight(device);
    }

    void resetCancellation() {
        if (this == WEB) WebClient.resetCancellation();
        else if (this == VIDEO) MediaClient.resetCancellation();
        else if (this == CRYPTO || this == IMAGE) { MediaClient.resetCancellation(); MediaNative.load(); NativeBridge.resetMediaCancellation(); }
        else if (isGpu()) GpuClient.resetCancellation();
        else if (this == MEMORY || this == STREAM) NativeBridge.resetMemoryCancellation();
        else if (this == SCIMARK) NativeBridge.resetScimarkCancellation();
        else if (this == COREMARK) NativeBridge.resetCoremarkCancellation();
        else if (this == ZSTD) NativeBridge.resetZstdCancellation();
        else if (this == SQLITE) NativeBridge.resetSqliteCancellation();
        else if (this == CPU) NativeBridge.resetCpuCancellation();
        else NativeBridge.resetStorageCancellation();
    }

    String run(Context context, int parameter) {
        if (this == WEB) return WebClient.run(context);
        if (this == CRYPTO) return NativeBridge.runCrypto(parameter, CPU_DURATION_MS);
        if (this == IMAGE) return MediaCorpus.images(context, parameter);
        if (this == VIDEO) return MediaCorpus.video(context, parameter);
        if (isGpu()) return GpuClient.run(context, gpuTest(), 0);
        if (this == MEMORY) return NativeBridge.runMemoryV2(parameter, CPU_DURATION_MS);
        if (this == STREAM) return NativeBridge.runStream(parameter, CPU_DURATION_MS);
        if (this == SCIMARK) return NativeBridge.runScimark(parameter);
        if (this == COREMARK) return NativeBridge.runCoremark(parameter, 0);
        if (this == ZSTD) return NativeBridge.runZstd(parameter, CPU_DURATION_MS);
        if (this == SQLITE) return NativeBridge.runSqlite(context.getCacheDir().getAbsolutePath(), parameter);
        return this == CPU ? NativeBridge.runCpu(parameter, CPU_DURATION_MS)
                : NativeBridge.runStorage(context.getCacheDir().getAbsolutePath(), parameter, 256);
    }

    String progress(int parameter) {
        if (this == WEB) return "独立横向全屏 Web 视口；4 项任务各 20 单元，3 轮取中位数。\n测量任务完成与布局检查点耗时（含单元间调度，越低越好）。";
        if (this == CRYPTO) return "AES-128-GCM 加密、认证解密与 SHA-256；单线程及 " + parameter + " 线程，先校验后计时。";
        if (this == IMAGE || this == VIDEO) return "内置离线媒体素材；执行真实解码并核对校验值，系统媒体路径在独立子进程运行。";
        if (isGpu()) return (gpuTest().endsWith("_off") ? "1920 × 1080 离屏渲染" : "实际窗口分辨率屏上渲染") + " / ETC1 纹理 / 正式时长。\n在独立子进程中运行，可随时取消。";
        if (this == MEMORY) return "内存复制、填充、依赖访问延迟；Triad 单线程及 " + parameter + " 线程。\nTriad 共享固定 24 MiB 工作集；各项至少 1.5 秒，3 轮取中位数。";
        if (this == STREAM) return parameter + " 线程；每线程 3 × 2 MiB 数组。\nCopy、Scale、Add、Triad（STREAM 变体），每项至少 1.5 秒。";
        if (this == SCIMARK) return "FFT、SOR、Monte Carlo、稀疏矩阵与 LU 五项浮点内核。\n单线程小数据集，每项独立计时至少 1.5 秒。";
        if (this == COREMARK) return "单线程及 " + parameter + " 线程；每项配置验证两组种子。\n单段正式计时至少 10 秒；取消时将在当前计算段落结束后停止。";
        if (this == SQLITE) return "插入、更新、删除各 " + parameter + " 个独立事务（1 KiB / 行）。\n每次提交同步落盘；取消时将在当前事务完成后停止。";
        return isCompression()
                ? "固定 1 MiB 语料；单线程及 " + parameter + " 线程流压缩与解压。\n每项至少 1.5 秒，计时外执行预热与数据校验；可随时取消。"
                : "测试文件 " + parameter + " MiB；256 KiB 顺序读写与 4 KiB 随机读写。\n可随时取消，当前 I/O 操作返回后停止。";
    }
}
