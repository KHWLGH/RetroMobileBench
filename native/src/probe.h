#ifndef RMB_PROBE_H
#define RMB_PROBE_H

#include <stddef.h>

/*
 * Build/device diagnostics (docs/development.md, development diagnostics).
 *
 * Writes a human-readable report about the build and the device into buf:
 * build variant, compiler, API level the library was built for, CPU features
 * from /proc/cpuinfo, online cores, a short integer/float timing loop, and
 * an off-screen EGL/GLES2 query (renderer, version, extensions, fragment
 * shader precision). Returns the number of bytes written (excluding NUL).
 */
size_t rmb_probe_report(char *buf, size_t cap);

#endif
