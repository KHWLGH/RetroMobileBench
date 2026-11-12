package com.retromobilebench;

/** JNI entry points implemented in native/src/rmb_jni.c. Call {@link NativeLoader#load()} first. */
public final class NativeBridge {

    private NativeBridge() {
    }

    /** Device and build report from the M0 feasibility probe (native/src/probe.c). */
    public static native String probeReport();
}
