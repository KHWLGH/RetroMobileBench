package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

/** Diagnostic only: distinguish EGL pixels from SurfaceFlinger/overlay composition. */
final class GpuSurfaceChecks {
    static String run(Instrumentation test, GpuBackendChecks.Progress progress) throws Exception {
        Context context = test.getTargetContext();
        if (android.os.Build.VERSION.SDK_INT < 11) throw new IllegalStateException("shared Surface diagnostic needs API 11");
        android.app.Activity main = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        long focusDeadline = SystemClock.elapsedRealtime() + 10000;
        do {
            test.runOnMainSync(() -> focused.set(main.hasWindowFocus()));
            if (focused.get()) break;
            check(SystemClock.elapsedRealtime() < focusDeadline, "unlock device and focus main window");
            SystemClock.sleep(50);
        } while (true);
        check(NativeLoader.load() != null, "native EGL host loads");
        JavaEglContext egl = null;
        try {
            Surface surface = DisplaySurface.acquire(context, () -> false, true);
            int width = DisplaySurface.width(), height = DisplaySurface.height();
            JavaEglContext.window = surface;
            egl = JavaEglContext.create(width, height);
            check(egl.valid(), egl.error());
            JSONObject report = describeConfig();
            check(report.getInt("red") == 8 && report.getInt("green") == 8 && report.getInt("blue") == 8
                    && report.getInt("alpha") == 0 && report.getInt("depth") >= 16, "opaque RGB888 window config: " + report);
            JSONObject hostConfig = new JSONObject(JavaEglContext.lastConfig);
            check(hostConfig.getInt("id") == report.getInt("egl_config_id") && hostConfig.getInt("samples") == 0
                    && JavaEglContext.WINDOW_CONFIG_POLICY.equals(hostConfig.getString("policy")), "host reports actual selected config");
            report.put("diagnostic_only", true).put("score_eligible", false)
                    .put("width", width).put("height", height)
                    .put("renderer", GLES20.glGetString(GLES20.GL_RENDERER))
                    .put("gl_version", GLES20.glGetString(GLES20.GL_VERSION));
            JSONArray samples = new JSONArray();
            float[][] colors = {{1, 0, 0, 0}, {0, 1, 0, .25f}, {0, 0, 1, .5f}, {.75f, .5f, .25f, 1}};
            GLES20.glDisable(GLES20.GL_DITHER);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            for (int i = 0; i < colors.length; i++) {
                float[] color = colors[i];
                int left = width * i / 4, right = width * (i + 1) / 4;
                GLES20.glScissor(left, 0, right - left, height);
                GLES20.glClearColor(color[0], color[1], color[2], color[3]);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR, "clear and RGBA readback");
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int r = pixels.get(offset) & 255, g = pixels.get(offset + 1) & 255, b = pixels.get(offset + 2) & 255;
                // Opaque export: preserve the GL RGB values without viewer alpha blending.
                argb[(height - 1 - y) * width + x] = 0xff000000 | r << 16 | g << 8 | b;
            }
            for (int i = 0; i < colors.length; i++) {
                int offset = ((height / 2) * width + width * (2 * i + 1) / 8) * 4;
                JSONArray rgba = new JSONArray();
                for (int channel = 0; channel < 4; channel++) rgba.put(pixels.get(offset + channel) & 255);
                for (int channel = 0; channel < 3; channel++)
                    check(Math.abs(rgba.getInt(channel) - Math.round(colors[i][channel] * 255)) <= 2, "known RGB readback " + i + ": " + rgba);
                samples.put(new JSONObject().put("requested_alpha", colors[i][3]).put("read_rgba", rgba));
            }
            report.put("samples", samples);
            Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "gpu-surface-readback.png"))) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "save GL readback");
            } finally { bitmap.recycle(); }
            check(egl.swap(), "known-color window swap");
            test.runOnMainSync(() -> GpuHud.publish(0));
            progress.stage("diagnostic only: red(alpha=0), green(.25), blue(.5), brown(1); GL RGB verified; capture display now");
            // Keep the submitted frame visible for external capture; this is never a score.
            SystemClock.sleep(20000);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "gpu-surface-diagnostic.json"))) {
                out.write(report.toString(2).getBytes("UTF-8"));
            }
            return report.toString();
        } finally {
            if (egl != null) egl.release();
            JavaEglContext.window = null;
            DisplaySurface.hide();
        }
    }
    private static JSONObject describeConfig() throws Exception {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetCurrentDisplay();
        int[] value = new int[1];
        check(egl.eglQueryContext(display, egl.eglGetCurrentContext(), EGL10.EGL_CONFIG_ID, value), "query current EGL config");
        int selected = value[0];
        check(egl.eglGetConfigs(display, null, 0, value), "count EGL configs");
        EGLConfig[] configs = new EGLConfig[value[0]];
        check(egl.eglGetConfigs(display, configs, configs.length, value), "enumerate EGL configs");
        for (EGLConfig config : configs) {
            if (!egl.eglGetConfigAttrib(display, config, EGL10.EGL_CONFIG_ID, value) || value[0] != selected) continue;
            JSONObject result = new JSONObject().put("egl_config_id", selected);
            int[] attributes = {EGL10.EGL_RED_SIZE, EGL10.EGL_GREEN_SIZE, EGL10.EGL_BLUE_SIZE, EGL10.EGL_ALPHA_SIZE,
                    EGL10.EGL_DEPTH_SIZE, EGL10.EGL_STENCIL_SIZE, EGL10.EGL_NATIVE_VISUAL_ID};
            String[] names = {"red", "green", "blue", "alpha", "depth", "stencil", "native_visual_id"};
            for (int i = 0; i < attributes.length; i++) {
                check(egl.eglGetConfigAttrib(display, config, attributes[i], value), "EGL " + names[i]);
                result.put(names[i], value[0]);
            }
            return result;
        }
        throw new AssertionError("selected EGL config not enumerated");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private GpuSurfaceChecks() { }
}
