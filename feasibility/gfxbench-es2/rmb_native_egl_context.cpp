// Preserve the standalone adb-shell feasibility probe. The APK always uses EGL10.
#include "rmb_egl_context.h"
#include <GLES2/gl2.h>
#include <cstdio>

bool RmbPbufferContext::create(int width, int height) {
    release();
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
        ::snprintf(error_, sizeof error_, "eglInitialize failed (0x%x)", eglGetError());
        release(); return false;
    }
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 16, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint count = 0;
    // Config handle zero is valid on the API 16 emulator; trust the count.
    if (!eglChooseConfig(display_, attributes, &config, 1, &count) || count < 1) {
        ::snprintf(error_, sizeof error_, "eglChooseConfig failed (0x%x)", eglGetError());
        release(); return false;
    }
    const EGLint pbuffer[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    surface_ = eglCreatePbufferSurface(display_, config, pbuffer);
    const EGLint context[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, context);
    if (surface_ == EGL_NO_SURFACE || context_ == EGL_NO_CONTEXT || !makeCurrent()) {
        ::snprintf(error_, sizeof error_, "ES2 context creation failed (0x%x)", eglGetError());
        release(); return false;
    }
    return true;
}
bool RmbPbufferContext::makeCurrent() {
    return isValid() && eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE;
}
bool RmbPbufferContext::detachThread() {
    return display_ != EGL_NO_DISPLAY && eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
}
bool RmbPbufferContext::swapBuffers() { glFinish(); return true; }
void RmbPbufferContext::release() {
    if (display_ == EGL_NO_DISPLAY) return;
    detachThread();
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
    eglTerminate(display_);
    context_ = EGL_NO_CONTEXT; surface_ = EGL_NO_SURFACE; display_ = EGL_NO_DISPLAY;
}
