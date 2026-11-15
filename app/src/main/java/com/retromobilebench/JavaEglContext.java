package com.retromobilebench;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/** EGL10 is available on Froyo; native EGL only became an NDK API in Gingerbread.
 * Window swaps also use EGL10 through JNI to retain API 8 compatibility. */
final class JavaEglContext {
    private final EGL10 egl = (EGL10) EGLContext.getEGL();
    private EGLDisplay display = EGL10.EGL_NO_DISPLAY;
    private EGLContext context = EGL10.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL10.EGL_NO_SURFACE;
    static android.view.Surface window;
    static volatile String windowError = "";
    static volatile long successfulWindowSwaps;
    static final String WINDOW_CONFIG_POLICY = "opaque-rgb888-v1";
    static volatile String lastConfig = "{}";
    private boolean onscreen;
    private String error = "";

    static JavaEglContext create(int width, int height) {
        JavaEglContext result = new JavaEglContext();
        try { if (!result.initialize(width, height)) result.release(); }
        catch (RuntimeException e) { result.error = e.toString(); result.release(); }
        return result;
    }

    private boolean initialize(int width, int height) {
        display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, new int[2]))
            return fail("eglInitialize");
        onscreen = window != null;
        windowError = ""; successfulWindowSwaps = 0; lastConfig = "{}";
        int[] attributes = {EGL10.EGL_SURFACE_TYPE, onscreen ? EGL10.EGL_WINDOW_BIT : EGL10.EGL_PBUFFER_BIT,
                0x3040, 4, // EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE};
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attributes, null, 0, count) || count[0] < 1)
            return fail("eglChooseConfig ES2 " + (onscreen ? "window" : "pbuffer"));
        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, attributes, configs, configs.length, count)) return fail("eglChooseConfig list");
        EGLConfig selected = onscreen ? chooseOpaqueWindowConfig(configs) : configs[0];
        if (selected == null) {
            error = "No opaque RGB888 ES2 window config with depth >= 16 and no MSAA";
            return false;
        }
        lastConfig = describeConfig(selected);
        surface = onscreen ? egl.eglCreateWindowSurface(display, selected, new WindowSurfaceHolder(window, width, height), new int[]{EGL10.EGL_NONE})
                : egl.eglCreatePbufferSurface(display, selected, new int[]{EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height, EGL10.EGL_NONE});
        if (surface == null || surface == EGL10.EGL_NO_SURFACE) return fail(onscreen ? "eglCreateWindowSurface" : "eglCreatePbufferSurface");
        context = egl.eglCreateContext(display, selected, EGL10.EGL_NO_CONTEXT,
                new int[]{0x3098, 2, EGL10.EGL_NONE}); // EGL_CONTEXT_CLIENT_VERSION
        if (context == null || context == EGL10.EGL_NO_CONTEXT) return fail("eglCreateContext ES2");
        if (!makeCurrent()) return false;
        int[] actual = new int[1];
        if (!egl.eglQuerySurface(display, surface, EGL10.EGL_WIDTH, actual) || actual[0] != width) return fail("EGL width mismatch");
        if (!egl.eglQuerySurface(display, surface, EGL10.EGL_HEIGHT, actual) || actual[0] != height) return fail("EGL height mismatch");
        return !onscreen || swapInterval(1) || fail("eglSwapInterval");
    }

    // eglChooseConfig sizes are minima, including ALPHA_SIZE=0. Check the actual
    // components like GLSurfaceView's ComponentSizeChooser; never fall back to an
    // alpha window. On GT-I9260, that path corrupts SurfaceFlinger composition.
    private EGLConfig chooseOpaqueWindowConfig(EGLConfig[] configs) {
        EGLConfig selected = null;
        int best = 0;
        for (EGLConfig candidate : configs) {
            if (attribute(candidate, EGL10.EGL_RED_SIZE) != 8 || attribute(candidate, EGL10.EGL_GREEN_SIZE) != 8
                    || attribute(candidate, EGL10.EGL_BLUE_SIZE) != 8 || attribute(candidate, EGL10.EGL_ALPHA_SIZE) != 0
                    || attribute(candidate, EGL10.EGL_SAMPLES) != 0) continue;
            int visual = attribute(candidate, EGL10.EGL_NATIVE_VISUAL_ID);
            int preference = visual == android.graphics.PixelFormat.RGBX_8888 ? 3 : visual == android.graphics.PixelFormat.RGB_888 ? 2 : 1;
            if (preference > best) { selected = candidate; best = preference; }
        }
        return selected;
    }

    private int attribute(EGLConfig config, int name) {
        int[] value = new int[1];
        if (!egl.eglGetConfigAttrib(display, config, name, value))
            throw new IllegalStateException("eglGetConfigAttrib 0x" + Integer.toHexString(name));
        return value[0];
    }

    private String describeConfig(EGLConfig config) {
        int[] attributes = {EGL10.EGL_CONFIG_ID, EGL10.EGL_RED_SIZE, EGL10.EGL_GREEN_SIZE, EGL10.EGL_BLUE_SIZE,
                EGL10.EGL_ALPHA_SIZE, EGL10.EGL_DEPTH_SIZE, EGL10.EGL_STENCIL_SIZE, EGL10.EGL_SAMPLES, EGL10.EGL_NATIVE_VISUAL_ID};
        String[] names = {"id", "red", "green", "blue", "alpha", "depth", "stencil", "samples", "native_visual_id"};
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            result.put("policy", onscreen ? WINDOW_CONFIG_POLICY : "pbuffer-driver-first-v1");
            for (int i = 0; i < attributes.length; i++) result.put(names[i], attribute(config, attributes[i]));
            return result.toString();
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    private static native boolean swapInterval(int interval);

    boolean valid() { return context != null && context != EGL10.EGL_NO_CONTEXT; }
    String error() { return error; }

    boolean makeCurrent() {
        return valid() && (egl.eglMakeCurrent(display, surface, surface, context) || fail("eglMakeCurrent"));
    }

    boolean swap() {
        if (!onscreen) { javax.microedition.khronos.opengles.GL10 gl = (javax.microedition.khronos.opengles.GL10)context.getGL(); gl.glFinish(); return true; }
        if (window == null || !window.isValid()) { windowError = "window Surface lost"; return false; }
        if (!egl.eglSwapBuffers(display, surface)) { fail("eglSwapBuffers"); windowError = error; return false; }
        successfulWindowSwaps++; return true;
    }

    boolean detach() {
        return display != EGL10.EGL_NO_DISPLAY && (egl.eglMakeCurrent(display,
                EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT) || fail("eglDetach"));
    }

    void release() {
        if (display == EGL10.EGL_NO_DISPLAY) return;
        detach();
        if (valid()) egl.eglDestroyContext(display, context);
        if (surface != null && surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface);
        egl.eglTerminate(display);
        context = EGL10.EGL_NO_CONTEXT;
        surface = EGL10.EGL_NO_SURFACE;
        display = EGL10.EGL_NO_DISPLAY;
    }

    private boolean fail(String operation) {
        error = operation + " (0x" + Integer.toHexString(egl.eglGetError()) + ")";
        return false;
    }
}
