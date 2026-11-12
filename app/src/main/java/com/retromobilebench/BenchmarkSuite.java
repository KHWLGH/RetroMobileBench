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
    CPU("cpu-zlib-v1", "CPU 压缩测试（zlib）",
            new String[]{"single_compress_mbps", "single_decompress_mbps", "parallel_compress_mbps", "parallel_decompress_mbps"},
            new String[]{"单线程压缩", "单线程解压", "并行压缩", "并行解压"},
            new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"codec_version", "corpus_version", "input_bytes", "input_crc32", "compressed_bytes",
                    "compression_level", "parallel_threads", "duration_ms"}),
    ZSTD("cpu-zstd-v1", "CPU 压缩测试（zstd）",
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
    COREMARK("cpu-coremark-v1", "CPU 整数测试（CoreMark®）",
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
    MEMORY("memory-tiny-v1", "内存复制、填充与延迟",
            new String[]{"copy_mbps", "fill_mbps", "latency_ns"},
            new String[]{"复制（读＋写）", "填充", "依赖访问延迟"}, new String[]{"MB/s", "MB/s", "ns"},
            new String[]{"source_revision", "duration_ms", "buffer_bytes", "latency_bytes", "latency_batch", "seed", "copy_traffic_multiplier"}),
    STREAM("memory-stream-variant-v1", "内存并行带宽（STREAM 变体）",
            new String[]{"copy_mbps", "scale_mbps", "add_mbps", "triad_mbps"},
            new String[]{"Copy", "Scale", "Add", "Triad"}, new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"source_revision", "protocol", "threads", "duration_ms", "elements_per_array_per_thread", "element_bytes", "scalar"}),
    GPU_ALU("gl_alu_off", "GPU 着色器算力", "frames"),
    GPU_FILL("gl_fill_off", "GPU 纹理填充率", "MTexels/s"),
    GPU_DRIVER("gl_driver_off", "GPU 驱动开销", "frames"),
    GPU_BLENDING("gl_blending_off", "GPU 混合", "MB/s"),
    GPU_TREX("gl_trex_off", "GPU T-Rex（1080p 离屏）", "frames"),
    GPU_EGYPT("gl_egypt_off", "GPU Egypt（1080p 离屏）", "frames");

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
                        "pbuffer_width", "pbuffer_height", "diagnostic_duration_ms", "unit", "renderer", "vendor", "gl_version"});
    }

    boolean isGpu() { return name().startsWith("GPU_"); }
    String gpuTest() { return version.substring(4, version.length() - 3); }

    static BenchmarkSuite fromReport(JSONObject report) {
        // Early storage records/tests had no suite_version. Keep them readable.
        String version = report.optString("suite_version", STORAGE.version);
        for (BenchmarkSuite suite : values()) if (suite.version.equals(version)) return suite;
        return null;
    }

    boolean isCompression() { return this == CPU || this == ZSTD; }

    @Override public String toString() { return label; }

    int parameter(JSONObject device) {
        if (isGpu()) return 0;
        if (this == SQLITE) return 300;
        if (this == SCIMARK || this == MEMORY) return 1500;
        if (isCompression() || this == COREMARK || this == STREAM) return Math.max(1, Math.min(4, device.optInt("available_processors", 1)));
        return (int) Math.min(32L, (device.optLong("available_storage_bytes") - 16 * 1024L * 1024L)
                / (1024L * 1024L));
    }

    String preflight(JSONObject device, int parameter) {
        if (isGpu()) return BenchmarkRunner.gpuPreflight(device, this == GPU_TREX || this == GPU_EGYPT ? 192 : 128);
        if (this == SQLITE) return BenchmarkRunner.sqlitePreflight(device);
        if (this == MEMORY || this == STREAM) return BenchmarkRunner.memoryPreflight(device);
        return this == STORAGE ? BenchmarkRunner.preflight(device, parameter) : BenchmarkRunner.cpuPreflight(device);
    }

    void resetCancellation() {
        if (isGpu()) GpuClient.resetCancellation();
        else if (this == MEMORY || this == STREAM) NativeBridge.resetMemoryCancellation();
        else if (this == SCIMARK) NativeBridge.resetScimarkCancellation();
        else if (this == COREMARK) NativeBridge.resetCoremarkCancellation();
        else if (this == ZSTD) NativeBridge.resetZstdCancellation();
        else if (this == SQLITE) NativeBridge.resetSqliteCancellation();
        else if (this == CPU) NativeBridge.resetCpuCancellation();
        else NativeBridge.resetStorageCancellation();
    }

    String run(Context context, int parameter) {
        if (isGpu()) return GpuClient.run(context, gpuTest(), 0);
        if (this == MEMORY) return NativeBridge.runMemory(parameter);
        if (this == STREAM) return NativeBridge.runStream(parameter, CPU_DURATION_MS);
        if (this == SCIMARK) return NativeBridge.runScimark(parameter);
        if (this == COREMARK) return NativeBridge.runCoremark(parameter, 0);
        if (this == ZSTD) return NativeBridge.runZstd(parameter, CPU_DURATION_MS);
        if (this == SQLITE) return NativeBridge.runSqlite(context.getCacheDir().getAbsolutePath(), parameter);
        return this == CPU ? NativeBridge.runCpu(parameter, CPU_DURATION_MS)
                : NativeBridge.runStorage(context.getCacheDir().getAbsolutePath(), parameter, 256);
    }

    String progress(int parameter) {
        if (isGpu()) return "1920 × 1080 离屏 / ETC1 / 官方完整时长。\n加载数据后在独立进程运行；可取消，单项上限 4 分钟。";
        if (this == MEMORY) return "8 MiB 复制与填充；4 MiB 随机依赖访问；每项至少 1.5 秒。\n基于 tinymembench C 内核；RMB 独立协议。";
        if (this == STREAM) return parameter + " 线程；每线程 3 × 2 MiB 数组。\nCopy、Scale、Add、Triad；STREAM 变体结果，每项至少 1.5 秒。";
        if (this == SCIMARK) return "FFT、SOR、Monte Carlo、稀疏矩阵与 LU；每项至少 1.5 秒。\n单线程小数据集；使用独立计时协议，不与原版综合分数混用。";
        if (this == COREMARK) return "单线程及 " + parameter + " 线程；每种配置验证两组种子。\n正式计时每段至少 10 秒；取消需等待当前核心计算完成。";
        if (this == SQLITE) return "插入、更新、删除各 " + parameter + " 个独立事务，1 KiB 数据 / 行。\n每次提交同步落盘；当前事务完成后可取消。";
        return isCompression()
                ? "固定 1 MiB 数据；单线程及 " + parameter + " 线程压缩、解压。\n每项至少 1.5 秒，计时外预热和校验；可随时取消。"
                : "文件 " + parameter + " MiB；顺序读写及 4 KiB 随机读写。\n可随时取消，当前 I/O 返回后停止。";
    }
}
