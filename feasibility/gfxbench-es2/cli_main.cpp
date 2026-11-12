// adb-shell runner for the M1 GFXBench ES2 runtime check.
//
//   adb push gfxprobe /data/local/tmp/
//   adb shell /data/local/tmp/gfxprobe <config.json> <data_dir/> <write_dir/> [width height [play_ms]]
//   adb shell /data/local/tmp/gfxprobe --egl-diag
//
// Prints the test's result JSON (or "ERROR: ...") to stdout and the peak resident
// memory to stderr. Logs go to logcat (tag RMB-GFX, and "_logcat_" for GFXBench's
// own NGLOG output).

#include <EGL/egl.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

std::string rmbRunGfxTest(const std::string &configPath, const std::string &dataPath,
                          const std::string &writePath, int width, int height, int playTimeMs);

// Peak resident memory of this process. CPU side only: GPU allocations are not in it
// (on the emulator they live in the host GPU; on real devices, in driver memory).
static void printPeakRss() {
    std::FILE *f = std::fopen("/proc/self/status", "r");
    if (!f) return;
    char line[128];
    while (std::fgets(line, sizeof line, f)) {
        if (std::strncmp(line, "VmHWM:", 6) == 0) std::fprintf(stderr, "%s", line);
    }
    std::fclose(f);
}

// Lists what eglChooseConfig returns for a few attribute sets, so a failing
// initPBufferSurface() can be explained.
static int eglDiag() {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj = 0, min = 0;
    if (!eglInitialize(dpy, &maj, &min)) {
        std::printf("eglInitialize failed: 0x%x\n", eglGetError());
        return 1;
    }
    std::printf("EGL %d.%d vendor=%s\n", maj, min, eglQueryString(dpy, EGL_VENDOR));

    struct Case {
        const char *name;
        EGLint attrs[16];
    } cases[] = {
        {"pbuffer+es2 (testfw)", {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE}},
        {"pbuffer+es2+rgb888+d16", {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 16, EGL_NONE}},
        {"es2 only", {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE}},
        {"pbuffer only", {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}},
    };
    for (const Case &c : cases) {
        EGLint num = 0;
        EGLConfig cfg = nullptr;
        EGLBoolean ok = eglChooseConfig(dpy, c.attrs, &cfg, 1, &num);
        std::printf("%-26s ok=%d num=%d cfg=%p err=0x%x\n", c.name, (int) ok, num, cfg, eglGetError());
    }

    EGLint total = 0;
    eglGetConfigs(dpy, nullptr, 0, &total);
    std::printf("total configs: %d\n", total);
    EGLConfig all[64];
    EGLint n = 0;
    eglGetConfigs(dpy, all, total < 64 ? total : 64, &n);
    for (int i = 0; i < n; i++) {
        EGLint id, r, g, b, a, d, s, st, rt, mpw, mph;
        eglGetConfigAttrib(dpy, all[i], EGL_CONFIG_ID, &id);
        eglGetConfigAttrib(dpy, all[i], EGL_RED_SIZE, &r);
        eglGetConfigAttrib(dpy, all[i], EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(dpy, all[i], EGL_BLUE_SIZE, &b);
        eglGetConfigAttrib(dpy, all[i], EGL_ALPHA_SIZE, &a);
        eglGetConfigAttrib(dpy, all[i], EGL_DEPTH_SIZE, &d);
        eglGetConfigAttrib(dpy, all[i], EGL_STENCIL_SIZE, &s);
        eglGetConfigAttrib(dpy, all[i], EGL_SURFACE_TYPE, &st);
        eglGetConfigAttrib(dpy, all[i], EGL_RENDERABLE_TYPE, &rt);
        eglGetConfigAttrib(dpy, all[i], EGL_MAX_PBUFFER_WIDTH, &mpw);
        eglGetConfigAttrib(dpy, all[i], EGL_MAX_PBUFFER_HEIGHT, &mph);
        std::printf("  cfg %2d: rgba %d%d%d%d d%-2d s%d surf=0x%x rend=0x%x maxpb=%dx%d\n", id, r, g, b, a, d, s, st,
                    rt, mpw, mph);
    }
    eglTerminate(dpy);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && std::strcmp(argv[1], "--egl-diag") == 0) {
        return eglDiag();
    }
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <config.json> <data_dir/> <write_dir/> [width height [play_ms]]\n", argv[0]);
        std::fprintf(stderr, "       %s --egl-diag\n", argv[0]);
        return 2;
    }
    int width = argc > 5 ? std::atoi(argv[4]) : 1920;
    int height = argc > 5 ? std::atoi(argv[5]) : 1080;
    int playMs = argc > 6 ? std::atoi(argv[6]) : 0;
    std::string res = rmbRunGfxTest(argv[1], argv[2], argv[3], width, height, playMs);
    std::printf("%s\n", res.c_str());
    printPeakRss();
    return res.compare(0, 6, "ERROR:") == 0 ? 1 : 0;
}
