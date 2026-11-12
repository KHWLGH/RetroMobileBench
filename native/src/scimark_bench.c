/* NIST SciMark 2.0 kernels, RMB fixed small-data wall-time protocol.
   The upstream main/kernel/Stopwatch drivers are deliberately not compiled. */
#include "scimark_bench.h"
#include "bench_common.h"
#include "FFT.h"
#include "SOR.h"
#include "LU.h"
#include "MonteCarlo.h"
#include "SparseCompRow.h"
#include "Random.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>

static int cancelled;
void rmb_scimark_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
void rmb_scimark_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
static int stop(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }
typedef struct {
    double fft[2048], original[2048];
    double a[10000], b[10000], reference[10000];
    double *rows[100], *reference_rows[100];
    double x[1000], y[1000], val[5000];
    int col[5000], row[1001], pivot[100];
    double pi;
} data;
static int initialize(data *d) {
    Random random = new_Random_seed(101010);
    if (!random) return ENOMEM;
    for (int i = 0; i < 2048; i++) d->fft[i] = Random_nextDouble(random);
    memcpy(d->original, d->fft, sizeof(d->fft));
    for (int i = 0; i < 10000; i++) d->a[i] = Random_nextDouble(random);
    for (int i = 0; i < 100; i++) {
        d->rows[i] = d->b + i * 100;
        d->reference_rows[i] = d->reference + i * 100;
    }
    memcpy(d->b, d->a, sizeof(d->a));
    for (int i = 0; i < 1000; i++) d->x[i] = Random_nextDouble(random);
    for (int i = 0; i < 5000; i++) d->val[i] = Random_nextDouble(random);
    for (int r = 0; r < 1000; r++) {
        d->row[r] = r * 5;
        int step = r / 5; if (step < 1) step = 1;
        for (int i = 0; i < 5; i++) d->col[r * 5 + i] = i * step;
    }
    d->row[1000] = 5000;
    Random_delete(random);
    return 0;
}
static int kernel(data *d, int phase) {
    switch (phase) {
        case 0: FFT_transform(2048, d->fft); FFT_inverse(2048, d->fft); break;
        case 1: SOR_execute(100, 100, 1.25, d->rows, 1); break;
        case 2: d->pi = MonteCarlo_integrate(16384); if (!isfinite(d->pi)) return ENOMEM; break;
        case 3: SparseCompRow_matmult(1000, d->y, d->val, d->row, d->col, d->x, 1); break;
        case 4:
            for (int r = 0; r < 100; r++) memcpy(d->rows[r], d->a + r * 100, 100 * sizeof(double));
            if (LU_factor(100, 100, d->rows, d->pivot)) return EILSEQ;
            break;
    }
    return 0;
}
static int verify(data *d, int phase) {
    if (phase == 0) {
        for (int i = 0; i < 2048; i++)
            if (!isfinite(d->fft[i]) || fabs(d->fft[i] - d->original[i]) > 1e-6) return EILSEQ;
    } else if (phase == 1) {
        for (int i = 0; i < 10000; i++) if (!isfinite(d->b[i])) return EILSEQ;
        for (int i = 0; i < 100; i++)
            if (d->b[i] != d->a[i] || d->b[9900+i] != d->a[9900+i]
                    || d->b[i*100] != d->a[i*100] || d->b[i*100+99] != d->a[i*100+99]) return EILSEQ;
    } else if (phase == 2) {
        if (!isfinite(d->pi) || fabs(d->pi - 3.141592653589793) > 0.05) return EILSEQ;
    } else if (phase == 3) {
        for (int r = 0; r < 1000; r++) {
            double expected = 0;
            for (int i = 0; i < 5; i++) expected += d->x[d->col[r*5+i]] * d->val[r*5+i];
            if (!isfinite(d->y[r]) || fabs(d->y[r] - expected) > 1e-12) return EILSEQ;
        }
    } else {
        int permutation[100];
        for (int i = 0; i < 100; i++) permutation[i] = i;
        for (int i = 0; i < 100; i++) {
            int p = d->pivot[i]; if (p < i || p >= 100) return EILSEQ;
            int temp = permutation[i]; permutation[i] = permutation[p]; permutation[p] = temp;
        }
        for (int i = 0; i < 100; i++) for (int j = 0; j < 100; j++) {
            double value = 0;
            for (int k = 0; k <= i && k <= j; k++) value += (i == k ? 1 : d->rows[i][k]) * d->rows[k][j];
            if (!isfinite(value) || fabs(value - d->a[permutation[i]*100+j]) > 1e-9) return EILSEQ;
        }
    }
    return 0;
}
/* Independently check one SOR sweep before timing (row-major Gauss-Seidel). */
static int verify_sor(data *d) {
    memcpy(d->reference, d->a, sizeof(d->a));
    for (int i = 1; i < 99; i++) for (int j = 1; j < 99; j++) {
        double *v = d->reference + i*100+j;
        *v = (v[-100] + v[100] + v[-1] + v[1]) * 0.3125 - *v * 0.25;
    }
    for (int i = 0; i < 10000; i++) if (fabs(d->b[i] - d->reference[i]) > 1e-12) return EILSEQ;
    return 0;
}
void rmb_scimark_run(int duration_ms, char *json, size_t capacity) {
    double rates[5] = {0}, seconds[5] = {0}; uint64_t iterations[5] = {0};
    int error = 0, completed = 0; const char *stage = "arguments";
    data *d = NULL;
    if (duration_ms < 50 || duration_ms > 10000) { error = EINVAL; goto finish; }
    if (stop()) { error = ECANCELED; goto finish; }
    stage = "allocation"; d = malloc(sizeof(*d));
    if (!d) { error = ENOMEM; goto finish; }
    for (int phase = 0; phase < 5; phase++) {
        stage = "initialization";
        if (stop()) { error = ECANCELED; break; }
        if ((error = initialize(d)) != 0) break;
        stage = "warmup_validation";
        if ((error = kernel(d, phase)) || (error = verify(d, phase))) break;
        if (phase == 1 && (error = verify_sor(d))) break;
        stage = "measurement";
        double start = rmb_now();
        if (start <= 0) { error = EIO; break; }
        do {
            if (stop()) { error = ECANCELED; break; }
            if ((error = kernel(d, phase))) break;
            iterations[phase]++;
            double end = rmb_now();
            if (end <= 0) { error = EIO; break; }
            seconds[phase] = end - start;
        } while (seconds[phase] < duration_ms / 1000.0);
        if (error) break;
        if (start <= 0 || seconds[phase] <= 0) { error = EIO; break; }
        stage = "validation";
        if ((error = verify(d, phase))) break;
        double flops = phase == 0 ? FFT_num_flops(1024) : phase == 1 ? SOR_num_flops(100,100,1)
            : phase == 2 ? MonteCarlo_num_flops(16384) : phase == 3 ? SparseCompRow_num_flops(1000,5000,1) : LU_num_flops(100);
        rates[phase] = flops * (double)iterations[phase] / seconds[phase] / 1e6;
        completed++;
    }
    if (!error) stage = "";
finish:
    free(d);
    snprintf(json, capacity,
        "{\"test_version\":\"cpu-scimark-small-v1\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,"
        "\"completed_metrics\":%d,\"kernel_version\":\"SciMark2-C-2.0\",\"duration_ms\":%d,\"seed\":101010,"
        "\"fft_size\":1024,\"sor_size\":100,\"sparse_size\":1000,\"sparse_nonzeros\":5000,\"lu_size\":100,\"monte_carlo_batch\":16384,"
        "\"fft_mflops\":%.9g,\"sor_mflops\":%.9g,\"monte_carlo_mflops\":%.9g,\"sparse_mflops\":%.9g,\"lu_mflops\":%.9g,"
        "\"elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f,%.9f],\"iterations\":[%llu,%llu,%llu,%llu,%llu]}",
        error == ECANCELED ? "cancelled" : error ? "failed" : "completed", stage, error, completed, duration_ms,
        rates[0],rates[1],rates[2],rates[3],rates[4],seconds[0],seconds[1],seconds[2],seconds[3],seconds[4],
        (unsigned long long)iterations[0],(unsigned long long)iterations[1],(unsigned long long)iterations[2],
        (unsigned long long)iterations[3],(unsigned long long)iterations[4]);
}
