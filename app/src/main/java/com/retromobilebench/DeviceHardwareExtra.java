package com.retromobilebench;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

/**
 * UI-only extended hardware inspection (GPU/OpenGL, Display, Storage partitions, Kernel, Battery details).
 * Kept separate from {@link DeviceSnapshot} so benchmark JSON schemas stay strictly unchanged.
 * API 8+ compatible; call on the background worker thread.
 */
final class DeviceHardwareExtra {
    private DeviceHardwareExtra() { }

    @SuppressWarnings("deprecation")
    static JSONObject collect(Context context) {
        JSONObject extra = new JSONObject();
        try {
            extra.put("brand", Build.BRAND);
            extra.put("board", Build.BOARD);
            extra.put("hardware", Build.HARDWARE);
            extra.put("product", Build.PRODUCT);
            extra.put("display_id", Build.DISPLAY);
            String osVer = System.getProperty("os.version");
            String osArch = System.getProperty("os.arch");
            extra.put("kernel_version", osVer != null ? osVer : I18n.t("未知", "Unknown"));
            extra.put("kernel_arch", osArch != null ? osArch : I18n.t("未知", "Unknown"));

            // Display metrics
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm != null) {
                extra.put("screen_width_px", dm.widthPixels);
                extra.put("screen_height_px", dm.heightPixels);
                extra.put("screen_density_dpi", dm.densityDpi);
                extra.put("screen_density", dm.density);
                if (dm.xdpi > 10f && dm.ydpi > 10f) {
                    double wIn = dm.widthPixels / (double) dm.xdpi;
                    double hIn = dm.heightPixels / (double) dm.ydpi;
                    double diag = Math.sqrt(wIn * wIn + hIn * hIn);
                    extra.put("screen_inches", String.format(Locale.US, "%.1f", diag));
                }
            }
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                extra.put("screen_refresh_hz", wm.getDefaultDisplay().getRefreshRate());
            }

            // Memory threshold
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mem);
                extra.put("memory_threshold_bytes", mem.threshold);
            }

            // Internal /data partition
            File dataDir = Environment.getDataDirectory();
            if (dataDir != null) {
                StatFs stat = new StatFs(dataDir.getAbsolutePath());
                long blockSize = stat.getBlockSize();
                extra.put("data_total_bytes", (long) stat.getBlockCount() * blockSize);
                extra.put("data_avail_bytes", (long) stat.getAvailableBlocks() * blockSize);
            }

            // External storage if mounted
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                File extDir = Environment.getExternalStorageDirectory();
                if (extDir != null && extDir.exists()) {
                    StatFs stat = new StatFs(extDir.getAbsolutePath());
                    long blockSize = stat.getBlockSize();
                    extra.put("ext_total_bytes", (long) stat.getBlockCount() * blockSize);
                    extra.put("ext_avail_bytes", (long) stat.getAvailableBlocks() * blockSize);
                }
            }

            // Extended battery telemetry
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                if (tech != null && tech.length() > 0) extra.put("battery_tech", tech);
                int mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                if (mv > 0) extra.put("battery_voltage_mv", mv);
                extra.put("battery_plugged_type", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
                extra.put("battery_health", battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
            }

            // GPU / OpenGL ES probe via lightweight EGL10 pbuffer
            probeGpu(extra);

            // Offline SoC & CPU microarchitecture hints
            CpuSocResolver.collectExtra(extra);
        } catch (JSONException | SecurityException ignored) { }
        return extra;
    }

    private static void probeGpu(JSONObject out) {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = EGL10.EGL_NO_DISPLAY;
        EGLSurface surface = EGL10.EGL_NO_SURFACE;
        EGLContext glContext = EGL10.EGL_NO_CONTEXT;
        try {
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (display == null || display == EGL10.EGL_NO_DISPLAY) return;
            int[] ver = new int[2];
            if (!egl.eglInitialize(display, ver)) return;
            out.put("egl_version", ver[0] + "." + ver[1]);
            String eglVendor = egl.eglQueryString(display, EGL10.EGL_VENDOR);
            if (eglVendor != null) out.put("egl_vendor", eglVendor);

            int[] es2Attribs = {
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    0x3040, 4, // EGL_RENDERABLE_TYPE = EGL_OPENGL_ES2_BIT
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfig = new int[1];
            boolean es2 = egl.eglChooseConfig(display, es2Attribs, configs, 1, numConfig) && numConfig[0] > 0;
            if (!es2) {
                int[] fallbackAttribs = {
                        EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                        EGL10.EGL_NONE
                };
                if (!egl.eglChooseConfig(display, fallbackAttribs, configs, 1, numConfig) || numConfig[0] <= 0) {
                    return;
                }
            }
            int[] pbufferAttribs = {EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE};
            surface = egl.eglCreatePbufferSurface(display, configs[0], pbufferAttribs);
            if (surface == null || surface == EGL10.EGL_NO_SURFACE) return;

            int[] ctxAttribs = es2 ? new int[]{0x3098, 2, EGL10.EGL_NONE} : new int[]{EGL10.EGL_NONE};
            glContext = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, ctxAttribs);
            if (glContext == null || glContext == EGL10.EGL_NO_CONTEXT) return;
            if (!egl.eglMakeCurrent(display, surface, surface, glContext)) return;

            GL10 gl = (GL10) glContext.getGL();
            String renderer = gl.glGetString(GL10.GL_RENDERER);
            String vendor = gl.glGetString(GL10.GL_VENDOR);
            String glVersion = gl.glGetString(GL10.GL_VERSION);
            String exts = gl.glGetString(GL10.GL_EXTENSIONS);
            if (renderer != null) {
                out.put("gpu_renderer", renderer);
                out.put("gpu_display_name", formatGpuModel(renderer));
                out.put("gpu_translated", isEmulatorTranslatedGpu(renderer));
                out.put("gpu_software", isSoftwareGpu(renderer));
            }
            if (vendor != null) {
                out.put("gpu_vendor", vendor);
                out.put("gpu_vendor_display", formatGpuVendor(vendor));
            }
            if (glVersion != null) out.put("gpu_version", glVersion);
            if (exts != null) {
                out.put("gpu_etc1", exts.contains("GL_OES_compressed_ETC1_RGB8_texture"));
                out.put("gpu_extensions", exts);
            }
            int[] maxTex = new int[1];
            gl.glGetIntegerv(GL10.GL_MAX_TEXTURE_SIZE, maxTex, 0);
            if (maxTex[0] > 0) out.put("gpu_max_texture_size", maxTex[0]);
        } catch (Throwable ignored) {
            // Gracefully leave GPU fields absent if an emulator/device lacks pbuffer EGL.
        } finally {
            if (egl != null && display != null && display != EGL10.EGL_NO_DISPLAY) {
                try {
                    egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    if (glContext != null && glContext != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, glContext);
                    if (surface != null && surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface);
                    egl.eglTerminate(display);
                } catch (Throwable ignored) { }
            }
        }
    }

    static boolean isEmulatorTranslatedGpu(String renderer) {
        if (renderer == null) return false;
        return renderer.startsWith("Android Emulator OpenGL ES Translator (")
                || renderer.startsWith("ANGLE (");
    }

    static boolean isSoftwareGpu(String renderer) {
        if (renderer == null) return false;
        String lower = renderer.toLowerCase(Locale.US);
        return lower.contains("pixelflinger")
                || lower.contains("swiftshader")
                || lower.contains("llvmpipe")
                || lower.contains("softpipe");
    }

    static String formatGpuModel(String renderer) {
        if (renderer == null) return "";
        String trimmed = renderer.trim();
        String emuPrefix = "Android Emulator OpenGL ES Translator (";
        if (trimmed.startsWith(emuPrefix) && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(emuPrefix.length(), trimmed.length() - 1).trim();
        } else if (trimmed.startsWith("ANGLE (") && trimmed.endsWith(")")) {
            String inner = trimmed.substring("ANGLE (".length(), trimmed.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length >= 2 && parts[1].trim().length() > 0) {
                trimmed = parts[1].trim();
            } else {
                trimmed = inner.trim();
            }
        }
        int pcieIdx = trimmed.indexOf("/PCIe");
        if (pcieIdx > 0) {
            trimmed = trimmed.substring(0, pcieIdx).trim();
        }
        return trimmed;
    }

    static String formatGpuVendor(String vendor) {
        if (vendor == null) return "";
        String trimmed = vendor.trim();
        String emuPrefix = "Google (";
        if (trimmed.startsWith(emuPrefix) && trimmed.endsWith(")")) {
            String inner = trimmed.substring(emuPrefix.length(), trimmed.length() - 1).trim();
            if (inner.length() > 0) return inner;
        }
        return trimmed;
    }
}
