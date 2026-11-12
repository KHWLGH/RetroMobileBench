#ifndef RMB_COREMARK_BENCH_H
#define RMB_COREMARK_BENCH_H
#include <stddef.h>
void rmb_coremark_reset_cancel(void);
void rmb_coremark_cancel(void);
/* iterations=0 calibrates a formal run. Positive values are validation diagnostics. */
void rmb_coremark_run(int threads, int iterations, char *json, size_t capacity);
#endif
