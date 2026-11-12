#include "rmb_egl_context.h"

#include <GLES2/gl2.h>

#include <cstdio>

bool RmbPbufferContext::create(int width, int height) {
    dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy_ == EGL_NO_DISPLAY || !eglInitialize(dpy_, nullptr, nullptr)) {
        std::snprintf(error_, sizeof error_, "eglInitialize failed (0x%x)", eglGetError());
        dpy_ = EGL_NO_DISPLAY;
        return false;
    }

    // Same request as testfw's pbuffer path with GLFormat(8,8,8,-1,16,...).
    const EGLint attrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint num = 0;
    // Trust the count, not the handle value: 0 can be a valid EGLConfig (see header).
    if (!eglChooseConfig(dpy_, attrs, &config, 1, &num) || num < 1) {
        std::snprintf(error_, sizeof error_, "eglChooseConfig: no ES2 pbuffer config (0x%x)", eglGetError());
        release();
        return false;
    }

    const EGLint pbAttrs[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    surf_ = eglCreatePbufferSurface(dpy_, config, pbAttrs);
    if (surf_ == EGL_NO_SURFACE) {
        std::snprintf(error_, sizeof error_, "eglCreatePbufferSurface %dx%d failed (0x%x)", width, height,
                      eglGetError());
        release();
        return false;
    }

    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    ctx_ = eglCreateContext(dpy_, config, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx_ == EGL_NO_CONTEXT) {
        std::snprintf(error_, sizeof error_, "eglCreateContext ES2 failed (0x%x)", eglGetError());
        release();
        return false;
    }
    if (!makeCurrent()) {
        std::snprintf(error_, sizeof error_, "eglMakeCurrent failed (0x%x)", eglGetError());
        release();
        return false;
    }
    return true;
}

bool RmbPbufferContext::makeCurrent() {
    return isValid() && eglMakeCurrent(dpy_, surf_, surf_, ctx_) == EGL_TRUE;
}

bool RmbPbufferContext::detachThread() {
    return eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
}

// A pbuffer has nothing to present. In offscreen mode GFXBench calls this only when
// its 100-frame preview mosaic is full (glFlush otherwise), so glFinish here is a
// periodic throttle, like the back-pressure a real swap gives, and keeps the CPU
// from queueing frames without bound. (testfw's pbuffer path uses glFlush.)
bool RmbPbufferContext::swapBuffers() {
    glFinish();
    return true;
}

void RmbPbufferContext::release() {
    if (dpy_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (ctx_ != EGL_NO_CONTEXT) eglDestroyContext(dpy_, ctx_);
    if (surf_ != EGL_NO_SURFACE) eglDestroySurface(dpy_, surf_);
    eglTerminate(dpy_);
    ctx_ = EGL_NO_CONTEXT;
    surf_ = EGL_NO_SURFACE;
    dpy_ = EGL_NO_DISPLAY;
}
