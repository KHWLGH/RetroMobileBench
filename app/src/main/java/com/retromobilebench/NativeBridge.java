package com.retromobilebench;

/** JNI entry points implemented in native/src/rmb_jni.c. Call {@link NativeLoader#load()} first. */
public final class NativeBridge {

    private NativeBridge() {
    }

    /** Device and build report from the M0 feasibility probe (native/src/probe.c). */
    public static native String probeReport();

    /** Native file I/O suite. Single worker only; JSON includes parameters and cache mode. */
    public static native String runStorage(String directory, int fileMiB, int randomOperations);

    /** Fixed zlib corpus; single and parallel compression/decompression, 1-4 threads. */
    public static native String runCpu(int parallelThreads, int durationMs);

    public static native void resetCpuCancellation();

    /** Checked between bounded 1 MiB codec operations, including warm-up. */
    public static native void cancelCpu();

    /** Independent streams with the same corpus and timing protocol as zlib. */
    public static native String runZstd(int parallelThreads, int durationMs);
    public static native void resetZstdCancellation();
    public static native void cancelZstd();

    /** Bundled SQLite, one committed row per transaction; call on the serial worker. */
    public static native String runSqlite(String directory, int transactions);
    public static native void resetSqliteCancellation();
    public static native void cancelSqlite();

    /** Memory suites share cancellation and must execute serially. */
    public static native String runMemory(int durationMs);
    public static native String runStream(int threads, int durationMs);
    public static native void resetMemoryCancellation();
    public static native void cancelMemory();

    /** NIST small-data kernels, fixed RMB wall-time protocol. */
    public static native String runScimark(int durationMs);
    public static native void resetScimarkCancellation();
    public static native void cancelScimark();

    /** Official kernels, both seed sets; iterations=0 is the formal >=10 second run. */
    public static native String runCoremark(int parallelThreads, int diagnosticIterations);
    public static native void resetCoremarkCancellation();
    public static native void cancelCoremark();

    /** Reset on the worker immediately before a new run; never reset during a run. */
    public static native void resetStorageCancellation();

    /** Thread-safe cooperative cancellation, checked between I/O calls. */
    public static native void cancelStorage();
}
