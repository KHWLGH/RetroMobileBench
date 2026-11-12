// ES2 off-screen (pbuffer) context for running GFXBench tests without a window.
//
// Why not testfw's EGLGraphicsContext::initPBufferSurface():
//   it treats an EGLConfig handle equal to 0 as "no config found". EGLConfig is an
//   opaque handle, and Android 4.1's emulator EGL hands out index-based handles, so
//   its first config IS 0: eglChooseConfig returns ok=1 num=1 cfg=0x0 and testfw
//   gives up on a working device. This class does the same job but trusts only the
//   count returned by eglChooseConfig.
//
// It derives from EGLGraphicsContext (not just GraphicsContext) because gfxbench.cpp
// casts its context to EGLGraphicsContext* on Android to read selectedConfig(). The
// base part stays default-constructed (no EGL objects), so that read returns 0 and the
// base destructor does nothing. Our EGL objects live in the members below.

#ifndef RMB_EGL_CONTEXT_H
#define RMB_EGL_CONTEXT_H

#include <EGL/egl.h>

#include "graphics/eglgraphicscontext.h"

class RmbPbufferContext : public EGLGraphicsContext {
public:
    RmbPbufferContext() {}
    ~RmbPbufferContext() override { release(); }

    // Creates an ES2 context on a width x height pbuffer and makes it current on the
    // calling thread. On failure, error() names the EGL call that failed.
    bool create(int width, int height);
    void release();
    const char *error() const { return error_; }

    // GraphicsContext
    bool isValid() override { return ctx_ != EGL_NO_CONTEXT; }
    bool makeCurrent() override;
    bool detachThread() override;
    bool swapBuffers() override;
    GraphicsType type() override { return GLES; }
    int versionMajor() override { return 2; }
    int versionMinor() override { return 0; }
    bool hasFlag(int) override { return false; }

private:
    EGLDisplay dpy_ = EGL_NO_DISPLAY;
    EGLSurface surf_ = EGL_NO_SURFACE;
    EGLContext ctx_ = EGL_NO_CONTEXT;
    char error_[160] = {0};
};

#endif
