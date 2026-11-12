package com.retromobilebench;

import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Picks which native library build to load (docs/01-architecture.md §3.3).
 *
 * <p>The check runs in Java, before any native code is loaded: the NEON build may
 * already execute NEON instructions in static initializers, which would crash a
 * Tegra 2 with SIGILL.
 */
public final class NativeLoader {

    /** Library names without the "lib" prefix and ".so" suffix. */
    public static final String LIB_DEFAULT = "rmb";
    public static final String LIB_NO_NEON = "rmb_noneon";

    private static String sLoaded;
    private static Throwable sError;

    private NativeLoader() {
    }

    /** Loads the right library once. Returns the loaded name, or null on failure (see {@link #error()}). */
    public static synchronized String load() {
        if (sLoaded != null || sError != null) {
            return sLoaded;
        }
        String name = chooseLibrary();
        try {
            System.loadLibrary(name);
            sLoaded = name;
        } catch (UnsatisfiedLinkError e) {
            if (!LIB_DEFAULT.equals(name)) {
                // No-NEON build missing from this APK: fall back so the app still starts, and say so.
                try {
                    System.loadLibrary(LIB_DEFAULT);
                    sLoaded = LIB_DEFAULT;
                    sError = new IllegalStateException(
                            "no-NEON library missing, loaded default build instead: " + e.getMessage());
                } catch (UnsatisfiedLinkError e2) {
                    sError = e2;
                }
            } else {
                sError = e;
            }
        }
        return sLoaded;
    }

    public static synchronized Throwable error() {
        return sError;
    }

    @SuppressWarnings("deprecation") // CPU_ABI / CPU_ABI2: SUPPORTED_ABIS needs API 21.
    static String chooseLibrary() {
        String abi = Build.CPU_ABI == null ? "" : Build.CPU_ABI;
        if (abi.startsWith("x86") || abi.startsWith("mips")) {
            return LIB_DEFAULT;
        }
        if (abi.startsWith("armeabi")) {
            return cpuHasNeon() ? LIB_DEFAULT : LIB_NO_NEON;
        }
        return LIB_DEFAULT;
    }

    /** True if the "Features" line of /proc/cpuinfo lists neon (ARMv7 kernels report it there). */
    static boolean cpuHasNeon() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features")) {
                    for (String tok : line.substring(line.indexOf(':') + 1).trim().split("\\s+")) {
                        if ("neon".equals(tok) || "asimd".equals(tok)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (IOException ignored) {
            // Unreadable cpuinfo: assume a modern CPU rather than refusing to start.
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        return true;
    }
}
