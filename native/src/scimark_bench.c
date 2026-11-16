/* NIST SciMark 2.0 kernels, RMB fixed small-data single + parallel wall-time protocol.
   The upstream main/kernel/Stopwatch drivers are deliberately not compiled. */
#include "scimark_bench.h"
#include "bench_common.h"
#include "worker_limits.h"
#include "FFT.h"
#include "SOR.h"
#include "LU.h"
#include "MonteCarlo.h"
#include "SparseCompRow.h"
#include "Random.h"
#include <math.h>
#include <pthread.h>
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

static double kernel_flops(int phase) {
    return phase == 0 ? FFT_num_flops(1024) : phase == 1 ? SOR_num_flops(100,100,1)
        : phase == 2 ? MonteCarlo_num_flops(16384) : phase == 3 ? SparseCompRow_num_flops(1000,5000,1) : LU_num_flops(100);
}

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int ready, go, abort;
    double start, deadline;
} scimark_gate;

typedef struct {
    scimark_gate *gate;
    data *d;
    int phase, error;
    uint64_t iterations;
    double ended;
} scimark_worker;

static void *scimark_work(void *arg) {
    scimark_worker *w = arg;
    scimark_gate *g = w->gate;
    w->error = initialize(w->d);
    if (!w->error) w->error = kernel(w->d, w->phase);
    if (!w->error) w->error = verify(w->d, w->phase);
    if (!w->error && w->phase == 1) w->error = verify_sor(w->d);
    pthread_mutex_lock(&g->mutex);
    g->ready++;
    pthread_cond_broadcast(&g->condition);
    while (!g->go && !g->abort) pthread_cond_wait(&g->condition, &g->mutex);
    int abort = g->abort;
    pthread_mutex_unlock(&g->mutex);
    if (abort || w->error || stop()) return NULL;
    do {
        if (stop()) break;
        w->error = kernel(w->d, w->phase);
        if (w->error) break;
        w->iterations++;
        w->ended = rmb_now();
        if (w->ended <= 0) { w->error = EIO; break; }
    } while (w->ended < g->deadline);
    return NULL;
}

static int measure_phase(int threads, int phase, int duration_ms, double *rate, double *elapsed, uint64_t *total_iters, const char **stage) {
    *rate = *elapsed = 0;
    *total_iters = 0;
    if (stop()) return ECANCELED;
    if (threads == 1) {
        *stage = "allocation";
        data *d = malloc(sizeof(*d));
        if (!d) return ENOMEM;
        *stage = "initialization";
        int error = initialize(d);
        if (!error) {
            *stage = "warmup_validation";
            error = kernel(d, phase);
            if (!error) error = verify(d, phase);
            if (!error && phase == 1) error = verify_sor(d);
        }
        if (!error) {
            *stage = "measurement";
            double start = rmb_now();
            if (start <= 0) error = EIO;
            while (!error) {
                if (stop()) { error = ECANCELED; break; }
                error = kernel(d, phase);
                if (error) break;
                (*total_iters)++;
                double end = rmb_now();
                if (end <= 0) { error = EIO; break; }
                *elapsed = end - start;
                if (*elapsed >= duration_ms / 1000.0) break;
            }
            if (!error && (*elapsed <= 0 || !*total_iters)) error = EIO;
        }
        if (!error) {
            *stage = "validation";
            error = verify(d, phase);
        }
        if (!error) *rate = kernel_flops(phase) * (double)(*total_iters) / (*elapsed) / 1e6;
        free(d);
        return error;
    }

    *stage = "allocation";
    data *buffers = calloc((size_t)threads, sizeof(*buffers));
    scimark_worker *workers = calloc((size_t)threads, sizeof(*workers));
    pthread_t *handles = calloc((size_t)threads, sizeof(*handles));
    if (!buffers || !workers || !handles) {
        free(buffers); free(workers); free(handles);
        return ENOMEM;
    }
    scimark_gate g;
    memset(&g, 0, sizeof(g));
    int error = pthread_mutex_init(&g.mutex, NULL);
    if (error) { free(buffers); free(workers); free(handles); return error; }
    error = pthread_cond_init(&g.condition, NULL);
    if (error) { pthread_mutex_destroy(&g.mutex); free(buffers); free(workers); free(handles); return error; }

    *stage = "workers";
    int created = 0;
    for (int i = 0; i < threads; i++) {
        workers[i].gate = &g;
        workers[i].d = &buffers[i];
        workers[i].phase = phase;
        error = pthread_create(&handles[i], NULL, scimark_work, &workers[i]);
        if (error) break;
        created++;
        /* Gingerbread x86 __set_tls uses an unguarded static user_desc; wait for each
           worker to finish libc TLS setup and reach the gate before spawning the next. */
        pthread_mutex_lock(&g.mutex);
        while (g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
        pthread_mutex_unlock(&g.mutex);
    }
    pthread_mutex_lock(&g.mutex);
    while (!error && g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
    for (int i = 0; !error && i < created; i++) if (workers[i].error) error = workers[i].error;
    g.start = rmb_now();
    g.deadline = g.start + duration_ms / 1000.0;
    if (g.start <= 0 && !error) error = EIO;
    g.abort = error || stop();
    g.go = 1;
    pthread_cond_broadcast(&g.condition);
    pthread_mutex_unlock(&g.mutex);

    double ended = g.start;
    for (int i = 0; i < created; i++) pthread_join(handles[i], NULL);
    for (int i = 0; i < created; i++) {
        if (!error && workers[i].error) error = workers[i].error;
        if (workers[i].ended > ended) ended = workers[i].ended;
        *total_iters += workers[i].iterations;
    }
    if (stop()) error = ECANCELED;
    if (!error) {
        *stage = "validation";
        for (int i = 0; i < created; i++) {
            error = verify(&buffers[i], phase);
            if (error) break;
        }
    }
    *elapsed = ended - g.start;
    if (!error && (created != threads || *elapsed <= 0 || !*total_iters)) error = EIO;
    if (!error) *rate = kernel_flops(phase) * (double)(*total_iters) / (*elapsed) / 1e6;

    pthread_cond_destroy(&g.condition);
    pthread_mutex_destroy(&g.mutex);
    free(buffers); free(workers); free(handles);
    return error;
}

void rmb_scimark_run(int threads, int duration_ms, char *json, size_t capacity) {
    double rates[5] = {0}, seconds[5] = {0}; uint64_t iterations[5] = {0};
    double prates[5] = {0}, pseconds[5] = {0}; uint64_t piterations[5] = {0};
    double single_comp = 0, parallel_comp = 0;
    int error = 0, completed = 0; const char *stage = "arguments";
    if (threads < 1 || threads > RMB_MAX_WORKERS || duration_ms < 50 || duration_ms > 10000) {
        error = EINVAL; goto finish;
    }
    if (stop()) { error = ECANCELED; goto finish; }
    for (int phase = 0; phase < 5; phase++) {
        error = measure_phase(1, phase, duration_ms, &rates[phase], &seconds[phase], &iterations[phase], &stage);
        if (error) goto finish;
        single_comp += rates[phase] / 5.0;
        completed++;
    }
    completed++; /* single_composite_mflops */
    if (threads > 1) {
        for (int phase = 0; phase < 5; phase++) {
            error = measure_phase(threads, phase, duration_ms, &prates[phase], &pseconds[phase], &piterations[phase], &stage);
            if (error) goto finish;
            parallel_comp += prates[phase] / 5.0;
            completed++;
        }
        completed++; /* parallel_composite_mflops */
    }
    stage = "";
finish:
    snprintf(json, capacity,
        "{\"test_version\":\"cpu-scimark-small-v1\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,"
        "\"completed_metrics\":%d,\"kernel_version\":\"SciMark2-C-2.0\",\"duration_ms\":%d,\"parallel_threads\":%d,\"seed\":101010,"
        "\"fft_size\":1024,\"sor_size\":100,\"sparse_size\":1000,\"sparse_nonzeros\":5000,\"lu_size\":100,\"monte_carlo_batch\":16384,"
        "\"single_composite_mflops\":%.9g,\"parallel_composite_mflops\":%.9g,"
        "\"fft_mflops\":%.9g,\"sor_mflops\":%.9g,\"monte_carlo_mflops\":%.9g,\"sparse_mflops\":%.9g,\"lu_mflops\":%.9g,"
        "\"parallel_fft_mflops\":%.9g,\"parallel_sor_mflops\":%.9g,\"parallel_monte_carlo_mflops\":%.9g,\"parallel_sparse_mflops\":%.9g,\"parallel_lu_mflops\":%.9g,"
        "\"elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f,%.9f],\"iterations\":[%llu,%llu,%llu,%llu,%llu],"
        "\"parallel_elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f,%.9f],\"parallel_iterations\":[%llu,%llu,%llu,%llu,%llu]}",
        error == ECANCELED ? "cancelled" : error ? "failed" : "completed", stage, error, completed, duration_ms, threads,
        single_comp, parallel_comp,
        rates[0],rates[1],rates[2],rates[3],rates[4],
        prates[0],prates[1],prates[2],prates[3],prates[4],
        seconds[0],seconds[1],seconds[2],seconds[3],seconds[4],
        (unsigned long long)iterations[0],(unsigned long long)iterations[1],(unsigned long long)iterations[2],
        (unsigned long long)iterations[3],(unsigned long long)iterations[4],
        pseconds[0],pseconds[1],pseconds[2],pseconds[3],pseconds[4],
        (unsigned long long)piterations[0],(unsigned long long)piterations[1],(unsigned long long)piterations[2],
        (unsigned long long)piterations[3],(unsigned long long)piterations[4]);
}
