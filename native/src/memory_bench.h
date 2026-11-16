#ifndef RMB_MEMORY_BENCH_H
#define RMB_MEMORY_BENCH_H
#include <stddef.h>
void rmb_memory_reset_cancel(void);
void rmb_memory_cancel(void);
void rmb_memory_run(int threads, int duration_ms, char *json, size_t capacity);
int rmb_triad_measure(int threads, int duration_ms, double *rate, double *elapsed,
                      unsigned long long *elements, int *created_workers);
#endif
