#ifndef RMB_SQLITE_BENCH_H
#define RMB_SQLITE_BENCH_H
#include <stddef.h>
void rmb_sqlite_reset_cancel(void);
void rmb_sqlite_cancel(void);
void rmb_sqlite_run(const char *directory, int transactions, char *json, size_t capacity);
#endif
