#ifndef RMB_SCIMARK_BENCH_H
#define RMB_SCIMARK_BENCH_H
#include <stddef.h>
void rmb_scimark_reset_cancel(void);
void rmb_scimark_cancel(void);
void rmb_scimark_run(int threads, int duration_ms, char *json, size_t capacity);
#endif
