package com.retromobilebench;

/** Internal ES2 host. Invoke only inside GpuService's disposable process. */
final class GfxProbe {
    private GfxProbe() { }
    static native String runTest(String config, String data, String output,
                                 int width, int height, int diagnosticDurationMs);
}
