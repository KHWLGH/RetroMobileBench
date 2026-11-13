package com.retromobilebench;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/** EGL10 is available on Froyo; native EGL only became an NDK API in Gingerbread.
 * Called from JNI only at context transitions, never inside the rendering loop. */
final class JavaEglContext {
    private final EGL10 egl = (EGL10) EGLContext.getEGL();
    private EGLDisplay display = EGL10.EGL_NO_DISPLAY;
    private EGLContext context = EGL10.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL10.EGL_NO_SURFACE;
    private String error = "";

    static JavaEglContext create(int width, int height) {
        JavaEglContext result = new JavaEglContext();
        if (!result.initialize(width, height)) result.release();
        return result;
    }

    private boolean initialize(int width, int height) {
        display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, new int[2]))
            return fail("eglInitialize");
        int[] attributes = {EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                0x3040, 4, // EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attributes, configs, 1, count) || count[0] < 1)
            return fail("eglChooseConfig ES2 pbuffer");
        surface = egl.eglCreatePbufferSurface(display, configs[0], new int[]{
                EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height, EGL10.EGL_NONE});
        if (surface == null || surface == EGL10.EGL_NO_SURFACE) return fail("eglCreatePbufferSurface");
        context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT,
                new int[]{0x3098, 2, EGL10.EGL_NONE}); // EGL_CONTEXT_CLIENT_VERSION
        if (context == null || context == EGL10.EGL_NO_CONTEXT) return fail("eglCreateContext ES2");
        return makeCurrent();
    }

    boolean valid() { return context != null && context != EGL10.EGL_NO_CONTEXT; }
    String error() { return error; }

    boolean makeCurrent() {
        return valid() && (egl.eglMakeCurrent(display, surface, surface, context) || fail("eglMakeCurrent"));
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
