#include "probe.h"

extern void *rmb_egl_create(int width, int height);
extern void rmb_egl_release(void *context);
#include <GLES2/gl2.h>
#include <android/api-level.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef RMB_BUILD_VARIANT
#define RMB_BUILD_VARIANT "unknown"
#endif

typedef struct {
    char *buf;
    size_t cap;
    size_t len;
} sb_t;

static void sb_printf(sb_t *sb, const char *fmt, ...) {
    if (sb->len + 1 >= sb->cap) {
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(sb->buf + sb->len, sb->cap - sb->len, fmt, ap);
    va_end(ap);
    if (n > 0) {
        sb->len += (size_t) n;
        if (sb->len >= sb->cap) {
            sb->len = sb->cap - 1;
        }
    }
}

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec + (double) ts.tv_nsec * 1e-9;
}

/* Copy the first line of a small file (e.g. /sys/.../online) without the newline. */
static void read_first_line(const char *path, char *out, size_t cap) {
    out[0] = '\0';
    FILE *f = fopen(path, "r");
    if (!f) {
        snprintf(out, cap, "(unreadable)");
        return;
    }
    if (fgets(out, (int) cap, f)) {
        out[strcspn(out, "\r\n")] = '\0';
    }
    fclose(f);
}

/*
 * Pull a few interesting lines out of /proc/cpuinfo. getline() is API 18, so
 * fgets() is used (docs/02-platform-constraints.md §3).
 */
static void probe_cpuinfo(sb_t *sb) {
    FILE *f = fopen("/proc/cpuinfo", "r");
    if (!f) {
        sb_printf(sb, "cpuinfo: (unreadable)\n");
        return;
    }
    char line[512];
    int processors = 0;
    int printed_features = 0, printed_hw = 0, printed_model = 0;
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = '\0';
        if (strncmp(line, "processor", 9) == 0) {
            processors++;
        } else if (!printed_features && (strncmp(line, "Features", 8) == 0 || strncmp(line, "flags", 5) == 0)) {
            sb_printf(sb, "cpu %s\n", line);
            printed_features = 1;
        } else if (!printed_hw && strncmp(line, "Hardware", 8) == 0) {
            sb_printf(sb, "cpu %s\n", line);
            printed_hw = 1;
        } else if (!printed_model && (strncmp(line, "model name", 10) == 0 || strncmp(line, "Processor", 9) == 0)) {
            sb_printf(sb, "cpu %s\n", line);
            printed_model = 1;
        }
    }
    fclose(f);
    sb_printf(sb, "cpu processor entries: %d\n", processors);
}

/* A deliberately tiny timing loop: proves the toolchain output runs and gives a rough number. */
static void probe_timing(sb_t *sb) {
    volatile unsigned int acc = 1u;
    double t0 = now_sec();
    for (unsigned int i = 0; i < 50u * 1000u * 1000u; i++) {
        acc = acc * 1664525u + 1013904223u;
    }
    double t1 = now_sec();

    volatile double x = 1.0;
    for (int i = 0; i < 20 * 1000 * 1000; i++) {
        x = x * 1.0000001 + 0.0000001;
    }
    double t2 = now_sec();

    sb_printf(sb, "timing int-lcg 50M: %.3f s, fp-mac 20M: %.3f s (checksum %u %.6f)\n",
              t1 - t0, t2 - t1, acc, x);
}


/*
 * Create a 1x1 pbuffer ES2 context (no window needed) and query the GPU.
 * This is the same EGL path GFXBench's testfw uses on Android (eglgraphicscontext.cpp).
 */
static void probe_gles2(sb_t *sb) {
    void *context = rmb_egl_create(1, 1);
    if (!context) {
        sb_printf(sb, "egl: Java EGL10 ES2 context unavailable\n");
        return;
    }
    {
        sb_printf(sb, "gl vendor: %s\n", (const char *) glGetString(GL_VENDOR));
        sb_printf(sb, "gl renderer: %s\n", (const char *) glGetString(GL_RENDERER));
        sb_printf(sb, "gl version: %s\n", (const char *) glGetString(GL_VERSION));
        sb_printf(sb, "glsl version: %s\n", (const char *) glGetString(GL_SHADING_LANGUAGE_VERSION));

        const char *ext = (const char *) glGetString(GL_EXTENSIONS);
        int has_etc1 = ext && strstr(ext, "GL_OES_compressed_ETC1_RGB8_texture") != NULL;
        int has_depth_tex = ext && strstr(ext, "GL_OES_depth_texture") != NULL;
        int has_highp_frag_ext = ext && strstr(ext, "GL_OES_fragment_precision_high") != NULL;
        sb_printf(sb, "gl ext: ETC1=%d depth_texture=%d fragment_precision_high=%d (len %u)\n",
                  has_etc1, has_depth_tex, has_highp_frag_ext, ext ? (unsigned) strlen(ext) : 0u);

        /* Fragment highp support decides whether GFXBench's ui.fs compiles (docs/04 §4.7). */
        GLint range[2] = {0, 0}, precision = 0;
        glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range, &precision);
        sb_printf(sb, "fs highp float: range [%d,%d] precision %d -> %s\n", range[0], range[1], precision,
                  precision > 0 ? "supported" : "NOT supported");
        glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_MEDIUM_FLOAT, range, &precision);
        sb_printf(sb, "fs mediump float: range [%d,%d] precision %d\n", range[0], range[1], precision);

        GLint max_tex = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_tex);
        sb_printf(sb, "gl max texture size: %d\n", max_tex);
    }
    rmb_egl_release(context);
}

size_t rmb_probe_report(char *buf, size_t cap) {
    if (!buf || cap == 0) {
        return 0;
    }
    sb_t sb = {buf, cap, 0};
    buf[0] = '\0';

    sb_printf(&sb, "build variant: %s\n", RMB_BUILD_VARIANT);
    sb_printf(&sb, "built for API: %d\n", __ANDROID_API__);
#if defined(__clang__)
    sb_printf(&sb, "compiler: clang %s\n", __clang_version__);
#else
    sb_printf(&sb, "compiler: gcc %s\n", __VERSION__);
#endif
    sb_printf(&sb, "native build: %s\n", RMB_NATIVE_BUILD_ID);
#if defined(__ARM_NEON__) || defined(__ARM_NEON)
    sb_printf(&sb, "compiled with NEON: yes\n");
#else
    sb_printf(&sb, "compiled with NEON: no\n");
#endif

    char online[64];
    read_first_line("/sys/devices/system/cpu/online", online, sizeof online);
    sb_printf(&sb, "cpu online: %s, sysconf cores: %ld\n", online, sysconf(_SC_NPROCESSORS_CONF));

    probe_cpuinfo(&sb);
    probe_timing(&sb);
    probe_gles2(&sb);
    return sb.len;
}
