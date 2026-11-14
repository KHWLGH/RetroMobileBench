package com.retromobilebench;

/** Explicit dependency order is required by the Android 2.x/4.1 dynamic loader. */
final class MediaNative {
    private static boolean loaded;
    static synchronized void load() {
        if (loaded) return;
        System.loadLibrary("avutil"); System.loadLibrary("avcodec"); System.loadLibrary("avformat"); System.loadLibrary("rmbmedia"); loaded = true;
    }
    static synchronized boolean loaded() { return loaded; }
}
