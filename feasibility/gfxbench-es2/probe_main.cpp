// Native host for the M1 GFXBench ES2 runtime check (docs/09-roadmap.md).
//
// Does what GFXBench's Java TestRunner + testfw do, minus SWIG and Java:
//   1. create an ES2 pbuffer context (RmbPbufferContext; testfw's own pbuffer init
//      rejects EGLConfig handle 0, which Android 4.1's emulator returns)
//   2. build the test descriptor JSON (config file + read/write paths + size)
//   3. create the test through a static factory table (no dlopen/dlsym by name,
//      which is unreliable for app libraries on Android 4.1/4.2; docs/04 §4.3)
//   4. init() -> run() -> result()
//
// Two entry points use it:
//   - CLI:  main() in cli_main.cpp, the gfxprobe executable for adb shell testing
//           (scripts/feasibility-gfxbench-run.ps1)
//   - JNI:  com.retromobilebench.GfxProbe.runTest(...), for the app integration (M4);
//           no Java class calls it yet

#include "rmb_egl_context.h"
#include "testfw.h"
#include "schemas/descriptors.h"

#include <GLES2/gl2.h>
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>

extern "C" {
tfw::TestFactory::Holder create_test_gl_trex();
tfw::TestFactory::Holder create_test_gl_trex_off();
tfw::TestFactory::Holder create_test_gl_egypt();
tfw::TestFactory::Holder create_test_gl_egypt_off();
tfw::TestFactory::Holder create_test_gl_alu();
tfw::TestFactory::Holder create_test_gl_alu_off();
tfw::TestFactory::Holder create_test_gl_fill();
tfw::TestFactory::Holder create_test_gl_fill_off();
tfw::TestFactory::Holder create_test_gl_driver();
tfw::TestFactory::Holder create_test_gl_driver_off();
tfw::TestFactory::Holder create_test_gl_blending();
tfw::TestFactory::Holder create_test_gl_blending_off();
}

namespace {

const char *TAG = "RMB-GFX";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef tfw::TestFactory::Holder (*FactoryFn)();

struct FactoryEntry {
    const char *name;
    FactoryFn fn;
};

const FactoryEntry kFactories[] = {
    {"gl_trex", create_test_gl_trex},         {"gl_trex_off", create_test_gl_trex_off},
    {"gl_egypt", create_test_gl_egypt},       {"gl_egypt_off", create_test_gl_egypt_off},
    {"gl_alu", create_test_gl_alu},           {"gl_alu_off", create_test_gl_alu_off},
    {"gl_fill", create_test_gl_fill},         {"gl_fill_off", create_test_gl_fill_off},
    {"gl_driver", create_test_gl_driver},     {"gl_driver_off", create_test_gl_driver_off},
    {"gl_blending", create_test_gl_blending}, {"gl_blending_off", create_test_gl_blending_off},
};

FactoryFn findFactory(const std::string &name) {
    for (const FactoryEntry &e : kFactories) {
        if (name == e.name) return e.fn;
    }
    return nullptr;
}

bool readFile(const std::string &path, std::string *out) {
    std::ifstream f(path.c_str(), std::ios::in | std::ios::binary);
    if (!f) return false;
    std::ostringstream ss;
    ss << f.rdbuf();
    *out = ss.str();
    return true;
}

}  // namespace

// Runs one GFXBench test. Returns the test's result JSON, or a string starting with
// "ERROR:" describing where it failed.
//   configPath:  path to e.g. .../config/gl_alu_off.json
//   dataPath:    directory that contains common/, fill/, shaders_40/ ... (ends with '/')
//   writePath:   writable directory (ends with '/')
//   playTimeMs:  > 0 overrides raw_config.play_time (shorter runs for smoke tests)
std::string rmbRunGfxTest(const std::string &configPath, const std::string &dataPath,
                          const std::string &writePath, int width, int height, int playTimeMs) {
    std::string json;
    if (!readFile(configPath, &json)) return "ERROR: cannot read config " + configPath;

    tfw::Descriptor desc;
    std::string err;
    if (!tfw::Descriptor::fromJsonString(json, &desc, &err)) return "ERROR: bad config: " + err;

    desc.env().setReadPath(dataPath);
    desc.env().setWritePath(writePath);
    desc.env().setWidth(width);
    desc.env().setHeight(height);
    if (playTimeMs > 0) desc.setRawConfig("play_time", (double) playTimeMs);
    // Enforce bounded, offscreen runs even when a user-supplied pack has altered config.
    desc.setRawConfig("endless", false);
    desc.setRawConfig("battery", false);
    desc.setRawConfig("screenmode", 1.0);
    desc.setRawConfig("offscreen_width", 1920.0);
    desc.setRawConfig("offscreen_height", 1080.0);
    // ETC1 is the one compressed format every ES2 device has (docs/04 §4.6).
    desc.setRawConfig("texture_type", std::string("ETC1"));
    // Debug aid: RMB_SCREENSHOT_FRAMES="5000,20000" makes GFXBench save those
    // animation times (ms) as scr_<test>_<ms>.tga in writePath, to check that a scene
    // really draws. Reading back pixels costs time, so never set it for scoring runs.
    if (const char *shots = std::getenv("RMB_SCREENSHOT_FRAMES")) {
        desc.setRawConfig("screenshot_frames", std::string(shots));
    }

    FactoryFn fn = findFactory(desc.testId());
    if (!fn) return "ERROR: no factory for " + desc.testId();

    RmbPbufferContext ctx;
    if (!ctx.create(width, height)) return std::string("ERROR: ") + ctx.error();
    LOGI("%s: ES2 pbuffer %dx%d on %s / %s", desc.testId().c_str(), width, height,
         (const char *) glGetString(GL_VENDOR), (const char *) glGetString(GL_RENDERER));

    tfw::TestFactory::Holder holder = fn();
    if (!holder.test || holder.version != TFW_VERSION) return "ERROR: factory returned no test";
    tfw::TestBase *test = holder.test;

    test->setConfig(desc.toJsonString());
    test->setName(desc.testId().c_str());
    test->setGraphicsContext(&ctx);
    ctx.detachThread();  // GFXBench calls makeCurrent() itself

    std::string result;
    if (!test->init()) {
        result = "ERROR: init() failed; partial result: " + test->result();
    } else {
        LOGI("init ok, running %s", desc.testId().c_str());
        test->run();
        result = test->result();
    }
    test->terminate();
    delete test;
    ctx.release();
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_retromobilebench_GfxProbe_runTest(JNIEnv *env, jclass, jstring jconfig, jstring jdata,
                                           jstring jwrite, jint width, jint height, jint playTimeMs) {
    if (!jconfig || !jdata || !jwrite) return env->NewStringUTF("ERROR: null GPU path");
    const char *c = env->GetStringUTFChars(jconfig, nullptr);
    if (!c) return nullptr;
    const char *d = env->GetStringUTFChars(jdata, nullptr);
    if (!d) { env->ReleaseStringUTFChars(jconfig, c); return nullptr; }
    const char *w = env->GetStringUTFChars(jwrite, nullptr);
    if (!w) { env->ReleaseStringUTFChars(jconfig, c); env->ReleaseStringUTFChars(jdata, d); return nullptr; }
    std::string res;
    try { res = rmbRunGfxTest(c, d, w, width, height, playTimeMs); }
    catch (const std::exception &error) { res = std::string("ERROR: ") + error.what(); }
    env->ReleaseStringUTFChars(jconfig, c);
    env->ReleaseStringUTFChars(jdata, d);
    env->ReleaseStringUTFChars(jwrite, w);
    return env->NewStringUTF(res.c_str());
}

extern "C" __attribute__((visibility("default"))) int rmb_gfx_factory_count() {
    return (int) (sizeof(kFactories) / sizeof(kFactories[0]));
}
