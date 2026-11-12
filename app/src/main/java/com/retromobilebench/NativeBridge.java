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

    /** Reset on the worker immediately before a new run; never reset during a run. */
    public static native void resetStorageCancellation();

    /** Thread-safe cooperative cancellation, checked between I/O calls. */
    public static native void cancelStorage();
}
