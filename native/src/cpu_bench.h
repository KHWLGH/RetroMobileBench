#ifndef RMB_CPU_BENCH_H
#define RMB_CPU_BENCH_H

#include <stddef.h>

/* Serial caller only. Cancellation may be requested from another thread. */
void rmb_cpu_reset_cancel(void);
void rmb_cpu_cancel(void);
void rmb_cpu_run(int parallel_threads, int duration_ms, char *json, size_t capacity);

void rmb_zstd_reset_cancel(void);
void rmb_zstd_cancel(void);
void rmb_zstd_run(int parallel_threads, int duration_ms, char *json, size_t capacity);

#endif
