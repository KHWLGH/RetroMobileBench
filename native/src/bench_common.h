#ifndef RMB_BENCH_COMMON_H
#define RMB_BENCH_COMMON_H
#include <time.h>
#include <stddef.h>
#include <stdio.h>
/* API 16 bionic has CLOCK_MONOTONIC; no newer libc entry points. */
static inline double rmb_now(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1e9;
}
static inline void rmb_json_string(const char *source, char *dest, size_t capacity) {
    size_t n = 0;
    if (!capacity) return;
    while (*source && n + 7 < capacity) {
        unsigned char c = (unsigned char) *source++;
        if (c == '"' || c == '\\') { dest[n++] = '\\'; dest[n++] = (char)c; }
        else if (c < 32) { n += (size_t)snprintf(dest + n, capacity - n, "\\u%04x", c); }
        else dest[n++] = (char)c;
    }
    dest[n] = 0;
}
#endif
