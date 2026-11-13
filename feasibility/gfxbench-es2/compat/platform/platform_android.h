// API 8 platform subset. GPU data is extracted by Java, never AAssetManager.
#ifndef PLATFORM_ANDROID_H_
#define PLATFORM_ANDROID_H_
#include <jni.h>
namespace kishonti { namespace android {
namespace detail { struct Battery { float level; int status; float temperature; }; }
class Platform {
public:
    static Platform *instance();
    jobject context() { return nullptr; }
    JNIEnv *env() { return nullptr; }
    void detachThread() {}
    static float batteryTemperature();
    static float batteryLevel();
    static bool batteryCharging();
    static bool isJavaInitialized() { return false; }
};
}}
#endif
