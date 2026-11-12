/* Based on a variant of the STREAM benchmark code.
 * Copyright 1991-2003: John D. McCalpin.
 * Full license: third_party/stream/LICENSE.txt, included in APK notices.
 * RMB modifications: caller-owned arrays, pthread partitioning, fixed working
 * sets and wall-time repetitions. These are NOT standard STREAM results.
 */
#include <stddef.h>
void rmb_stream_kernel(int phase, size_t count, double *restrict a,
                       double *restrict b, double *restrict c) {
    switch (phase) {
        case 0: for (size_t j = 0; j < count; j++) c[j] = a[j]; break;
        case 1: for (size_t j = 0; j < count; j++) b[j] = 3.0 * c[j]; break;
        case 2: for (size_t j = 0; j < count; j++) c[j] = a[j] + b[j]; break;
        case 3: for (size_t j = 0; j < count; j++) a[j] = b[j] + 3.0 * c[j]; break;
    }
}
