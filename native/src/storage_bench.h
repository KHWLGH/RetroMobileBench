#ifndef RMB_STORAGE_BENCH_H
#define RMB_STORAGE_BENCH_H

#include <stddef.h>

/* One caller at a time. Cancellation is safe from another Java/JNI thread. */
void rmb_storage_reset_cancel(void);
void rmb_storage_cancel(void);
void rmb_storage_run(const char *directory, int file_mib, int random_ops,
                     char *json, size_t capacity);

#endif
