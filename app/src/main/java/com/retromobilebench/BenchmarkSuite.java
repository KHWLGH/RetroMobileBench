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
    CPU("cpu-zlib-v1", "CPU 压缩测试",
            new String[]{"single_compress_mbps", "single_decompress_mbps", "parallel_compress_mbps", "parallel_decompress_mbps"},
            new String[]{"单线程压缩", "单线程解压", "并行压缩", "并行解压"},
            new String[]{"MB/s", "MB/s", "MB/s", "MB/s"},
            new String[]{"codec_version", "corpus_version", "input_bytes", "input_crc32", "compressed_bytes",
                    "compression_level", "parallel_threads", "duration_ms"});

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

    static BenchmarkSuite fromReport(JSONObject report) {
        // Early storage records/tests had no suite_version. Keep them readable.
        String version = report.optString("suite_version", STORAGE.version);
        for (BenchmarkSuite suite : values()) if (suite.version.equals(version)) return suite;
        return null;
    }

    int parameter(JSONObject device) {
        if (this == CPU) return Math.max(1, Math.min(4, device.optInt("available_processors", 1)));
        return (int) Math.min(32L, (device.optLong("available_storage_bytes") - 16 * 1024L * 1024L)
                / (1024L * 1024L));
    }

    String preflight(JSONObject device, int parameter) {
        return this == CPU ? BenchmarkRunner.cpuPreflight(device) : BenchmarkRunner.preflight(device, parameter);
    }

    void resetCancellation() {
        if (this == CPU) NativeBridge.resetCpuCancellation();
        else NativeBridge.resetStorageCancellation();
    }

    String run(Context context, int parameter) {
        return this == CPU ? NativeBridge.runCpu(parameter, CPU_DURATION_MS)
                : NativeBridge.runStorage(context.getCacheDir().getAbsolutePath(), parameter, 256);
    }

    String progress(int parameter) {
        return this == CPU
                ? "固定 1 MiB 数据；单线程及 " + parameter + " 线程压缩、解压。\n每项至少 1.5 秒，计时外预热和校验；可随时取消。"
                : "文件 " + parameter + " MiB；顺序读写及 4 KiB 随机读写。\n可随时取消，当前 I/O 返回后停止。";
    }
}
