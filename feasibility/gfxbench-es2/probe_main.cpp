// GFXBench ES2 host shared by the app and diagnostic CLI (docs/development.md).
//
// Runs the original GFXBench tests through a project-owned EGL context host:
//   1. create an ES2 pbuffer context (RmbPbufferContext; testfw's own pbuffer init
//      rejects EGLConfig handle 0, which Android 4.1's emulator returns)
//   2. build the test descriptor JSON (config file + read/write paths + size)
//   3. create the test through a static factory table (no dlopen/dlsym by name,
//      which is unreliable on old Android linkers; docs/development.md, GPU host)
//   4. init() -> run() -> result()
//
// Two entry points use it:
//   - CLI:  main() in cli_main.cpp, the gfxprobe executable for adb shell testing
//           (scripts/feasibility-gfxbench-run.ps1)
//   - JNI:  com.retromobilebench.GfxProbe.runTest(...), called by the GPU worker

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
#include <memory>

static std::string checkOffscreenSupport() {
    GLint texture = 0, renderbuffer = 0, viewport[2] = {0, 0};
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &texture);
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &renderbuffer);
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, viewport);
    if (texture < 1920 || renderbuffer < 1920 || viewport[0] < 1920 || viewport[1] < 1080)
        return "SKIP: GPU limits do not support the fixed 1920x1080 workload";
    GLint count = 0;
    glGetIntegerv(GL_NUM_COMPRESSED_TEXTURE_FORMATS, &count);
    if (count < 0 || count > 1024) return "ERROR: invalid compressed texture format count";
    std::unique_ptr<GLint[]> formats(new GLint[count]);
    if (count) glGetIntegerv(GL_COMPRESSED_TEXTURE_FORMATS, formats.get());
    bool etc1 = false;
    for (int i = 0; i < count; ++i) if (formats[i] == 0x8D64) etc1 = true;
    if (!etc1) return "SKIP: ETC1 textures unsupported";
    GLuint fbo = 0, color = 0, depth = 0;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenTextures(1, &color); glBindTexture(GL_TEXTURE_2D, color);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1920, 1080, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    glGenRenderbuffers(1, &depth); glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, 1920, 1080);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER), error = glGetError();
    glBindFramebuffer(GL_FRAMEBUFFER, 0); glBindTexture(GL_TEXTURE_2D, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    glDeleteRenderbuffers(1, &depth); glDeleteTextures(1, &color); glDeleteFramebuffers(1, &fbo);
    if (error == GL_OUT_OF_MEMORY) return "SKIP: insufficient GPU memory for the fixed 1080p framebuffer";
    if (error != GL_NO_ERROR) return "ERROR: unexpected GL error during framebuffer preflight";
    if (status != GL_FRAMEBUFFER_COMPLETE) return "SKIP: fixed 1080p framebuffer unsupported";
    return "";
}

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
    const bool offscreen = desc.testId().size() >= 4 && desc.testId().substr(desc.testId().size() - 4) == "_off";
    // Fixed bounded protocol; onscreen draws directly to the window EGLSurface.
    desc.setRawConfig("endless", false);
    desc.setRawConfig("battery", false);
    desc.setRawConfig("screenmode", offscreen ? 1.0 : 0.0);
    desc.setRawConfig("offscreen_width", 1920.0);
    desc.setRawConfig("offscreen_height", 1080.0);
    // Fix ETC1 for the shared resource protocol (docs/development.md, GPU host).
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
    if (!ctx.create(width, height)) {
        std::string error = ctx.error();
        bool unsupported = error.find("0x3003") != std::string::npos || error.find("0x3005") != std::string::npos
                || error.find("0x3009") != std::string::npos || error.find("eglChooseConfig") != std::string::npos;
        return std::string(unsupported ? "SKIP: " : "ERROR: ") + error;
    }
    std::string capability = offscreen ? checkOffscreenSupport() : "";
    if (!capability.empty()) return capability;
    LOGI("%s: ES2 pbuffer %dx%d on %s / %s", desc.testId().c_str(), width, height,
         (const char *) glGetString(GL_VENDOR), (const char *) glGetString(GL_RENDERER));

    tfw::TestFactory::Holder holder = fn();
    if (!holder.test || holder.version != TFW_VERSION) return "ERROR: factory returned no test";
    std::unique_ptr<tfw::TestBase> test(holder.test);

    test->setConfig(desc.toJsonString());
    test->setName(desc.testId().c_str());
    test->setGraphicsContext(&ctx);
    ctx.detachThread();  // GFXBench calls makeCurrent() itself

    std::string result;
    if (!test->init()) {
        result = test->result();
        if (result.empty()) result = "ERROR: init() failed without a result";
    } else {
        LOGI("init ok, running %s", desc.testId().c_str());
        test->run();
        result = test->result();
    }
    test->terminate();
    test.reset();
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
    catch (const std::bad_alloc &) { res = "SKIP: insufficient process memory for GPU workload"; }
    catch (const std::exception &error) { res = std::string("ERROR: ") + error.what(); }
    env->ReleaseStringUTFChars(jconfig, c);
    env->ReleaseStringUTFChars(jdata, d);
    env->ReleaseStringUTFChars(jwrite, w);
    return env->NewStringUTF(res.c_str());
}

extern "C" __attribute__((visibility("default"))) int rmb_gfx_factory_count() {
    return (int) (sizeof(kFactories) / sizeof(kFactories[0]));
}
