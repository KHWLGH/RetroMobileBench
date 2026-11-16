package com.retromobilebench;

/** JNI entry points implemented in native/src/rmb_jni.c. Call {@link NativeLoader#load()} first. */
public final class NativeBridge {

    private NativeBridge() {
    }

    /** Device and build report from the M0 feasibility probe (native/src/probe.c). */
    public static native String probeReport();
    public static native int configuredProcessors();
    public static native void resetMediaCancellation();
    public static native void cancelMedia();
    /** MP4 packet timing/identity preflight only; never opens or invokes a software decoder. */
    public static native String readVideoPacketTimeline(String path, int expectedFrames);
    public static native String runVideoSoftware(String path, int threads, int durationMs, int width, int height, String firstYuvSha256);

    /** Native file I/O suite. Single worker only; JSON includes parameters and cache mode. */
    public static native String runStorage(String directory, int fileMiB, int randomOperations);

    /** Bundled SQLite, one committed row per transaction; call on the serial worker. */
    public static native String runSqlite(String directory, int transactions);
    public static native void resetSqliteCancellation();
    public static native void cancelSqlite();

    /** Fixed 24 MiB Triad working set, single plus N workers; memory-tiny-v1. */
    public static native String runMemory(int threads, int durationMs);
    public static native void resetMemoryCancellation();
    public static native void cancelMemory();

    /** NIST small-data kernels, single plus N workers, fixed RMB wall-time protocol. */
    public static native String runScimark(int parallelThreads, int durationMs);
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
