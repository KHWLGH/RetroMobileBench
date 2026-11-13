#include "rmb_egl_context.h"
#include <GLES2/gl2.h>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <EGL/egl.h>

namespace {
JavaVM *vm;
jclass eglClass;
jmethodID createMethod, validMethod, errorMethod, currentMethod, detachMethod, releaseMethod;

pthread_key_t attachedThread;
pthread_once_t threadKeyOnce = PTHREAD_ONCE_INIT;
void detachWorker(void *) { if (vm) vm->DetachCurrentThread(); }
void createThreadKey() { pthread_key_create(&attachedThread, detachWorker); }

// Context transitions are infrequent; no Java calls occur in the frame loop.
class Env {
public:
    JNIEnv *value;
    bool attached;
    Env() : value(nullptr), attached(false) {
        if (!vm) return;
        if (vm->GetEnv(reinterpret_cast<void **>(&value), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = vm->AttachCurrentThread(&value, nullptr) == JNI_OK;
            if (attached) {
                pthread_once(&threadKeyOnce, createThreadKey);
                pthread_setspecific(attachedThread, vm);
            }
        }
    }
    bool clearException() {
        if (!value || !value->ExceptionCheck()) return false;
        value->ExceptionClear();
        return true;
    }
};
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *machine, void *) {
    vm = machine;
    Env env;
    if (!env.value) return JNI_ERR;
    jclass local = env.value->FindClass("com/retromobilebench/JavaEglContext");
    if (!local) return JNI_ERR;
    eglClass = static_cast<jclass>(env.value->NewGlobalRef(local));
    env.value->DeleteLocalRef(local);
    if (!eglClass) return JNI_ERR;
    createMethod = env.value->GetStaticMethodID(eglClass, "create", "(II)Lcom/retromobilebench/JavaEglContext;");
    validMethod = env.value->GetMethodID(eglClass, "valid", "()Z");
    errorMethod = env.value->GetMethodID(eglClass, "error", "()Ljava/lang/String;");
    currentMethod = env.value->GetMethodID(eglClass, "makeCurrent", "()Z");
    detachMethod = env.value->GetMethodID(eglClass, "detach", "()Z");
    releaseMethod = env.value->GetMethodID(eglClass, "release", "()V");
    if (env.clearException() || !createMethod || !validMethod || !errorMethod ||
        !currentMethod || !detachMethod || !releaseMethod) return JNI_ERR;
    return JNI_VERSION_1_6;
}

bool RmbPbufferContext::create(int width, int height) {
    release();
    Env env;
    if (!env.value || !eglClass) { ::snprintf(error_, sizeof error_, "EGL10 JNI bridge unavailable"); return false; }
    jobject local = env.value->CallStaticObjectMethod(eglClass, createMethod, width, height);
    if (env.clearException() || !local) { ::snprintf(error_, sizeof error_, "EGL10 create exception"); return false; }
    bool valid = env.value->CallBooleanMethod(local, validMethod);
    if (env.clearException()) valid = false;
    if (valid) context_ = env.value->NewGlobalRef(local);
    else {
        jstring error = static_cast<jstring>(env.value->CallObjectMethod(local, errorMethod));
        if (!env.clearException() && error) {
            const char *text = env.value->GetStringUTFChars(error, nullptr);
            if (text) { ::snprintf(error_, sizeof error_, "%s", text); env.value->ReleaseStringUTFChars(error, text); }
            env.value->DeleteLocalRef(error);
        }
    }
    if (valid && !context_) {
        env.clearException();
        env.value->CallVoidMethod(local, releaseMethod);
        ::snprintf(error_, sizeof error_, "EGL10 global reference allocation failed");
    }
    env.value->DeleteLocalRef(local);
    return context_ != nullptr;
}
bool RmbPbufferContext::makeCurrent() {
    Env env;
    if (!context_ || !env.value) return false;
    bool result = env.value->CallBooleanMethod(context_, currentMethod);
    return !env.clearException() && result;
}
bool RmbPbufferContext::detachThread() {
    Env env;
    if (!context_ || !env.value) return false;
    bool result = env.value->CallBooleanMethod(context_, detachMethod);
    return !env.clearException() && result;
}
bool RmbPbufferContext::swapBuffers() { glFinish(); return true; }
void RmbPbufferContext::release() {
    if (!context_) return;
    Env env;
    if (env.value) {
        env.value->CallVoidMethod(context_, releaseMethod);
        env.clearException();
        env.value->DeleteGlobalRef(context_);
    }
    context_ = nullptr;
}

extern "C" void *rmb_egl_create(int width, int height) {
    RmbPbufferContext *context = new RmbPbufferContext;
    if (context->create(width, height)) return context;
    delete context;
    return nullptr;
}
extern "C" void rmb_egl_release(void *context) { delete static_cast<RmbPbufferContext *>(context); }

// Only extension lookup needs native EGL. Resolve it dynamically so API 8 has no
// hard libEGL dependency. All returned extension pointers remain optional.
extern "C" __eglMustCastToProperFunctionPointerType EGLAPIENTRY eglGetProcAddress(const char *name) {
    typedef __eglMustCastToProperFunctionPointerType (*Resolver)(const char *);
    static void *library = dlopen("libEGL.so", RTLD_LAZY | RTLD_LOCAL);
    static Resolver resolver = library ? reinterpret_cast<Resolver>(dlsym(library, "eglGetProcAddress")) : nullptr;
    return resolver ? resolver(name) : nullptr;
}
