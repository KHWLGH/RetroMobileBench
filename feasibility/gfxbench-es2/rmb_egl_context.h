// API 8 EGL host: Java EGL10 owns the display/context, native code owns rendering.
#ifndef RMB_EGL_CONTEXT_H
#define RMB_EGL_CONTEXT_H
#ifdef RMB_JAVA_EGL
#include <jni.h>
#else
#include <EGL/egl.h>
#endif
#include "graphics/graphicscontext.h"

class RmbPbufferContext : public GraphicsContext {
public:
    RmbPbufferContext() = default;
    ~RmbPbufferContext() override { release(); }
    bool create(int width, int height);
    void release();
    const char *error() const { return error_; }
    bool isValid() override {
#ifdef RMB_JAVA_EGL
        return context_ != nullptr;
#else
        return context_ != EGL_NO_CONTEXT;
#endif
    }
    bool makeCurrent() override;
    bool detachThread() override;
    bool swapBuffers() override;
    GraphicsType type() override { return GLES; }
    int versionMajor() override { return 2; }
    int versionMinor() override { return 0; }
    bool hasFlag(int) override { return false; }
private:
#ifdef RMB_JAVA_EGL
    jobject context_ = nullptr;
#else
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
#endif
    char error_[256] = {};
};
#endif
