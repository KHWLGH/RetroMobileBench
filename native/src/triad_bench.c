/* RMB fixed-working-set variant of STREAM Triad; not standard STREAM results. */
#include "memory_bench.h"
#include "bench_common.h"
#include "worker_limits.h"
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#define ELEMENTS (1024U * 1024U)
#define CACHELINE_ELEMENTS 8U
int rmb_memory_cancelled(void);
void rmb_stream_kernel(int phase, size_t count, double *a, double *b, double *c);
typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int ready, go, abort;
    double start, deadline;
} triad_gate;
typedef struct {
    triad_gate *gate;
    double *a, *b, *c, ended;
    size_t count;
    unsigned long long elements;
    int error;
} triad_worker;
static void *triad_work(void *argument) {
    triad_worker *w = argument; triad_gate *g = w->gate;
    for (size_t i = 0; i < w->count; i++) {
        if ((i & 4095U) == 0 && rmb_memory_cancelled()) break;
        w->a[i] = 1; w->b[i] = 2; w->c[i] = 3;
    }
    if (!rmb_memory_cancelled()) rmb_stream_kernel(3, w->count, w->a, w->b, w->c);
    pthread_mutex_lock(&g->mutex);
    g->ready++; pthread_cond_broadcast(&g->condition);
    while (!g->go && !g->abort) pthread_cond_wait(&g->condition, &g->mutex);
    int abort = g->abort;
    pthread_mutex_unlock(&g->mutex);
    if (abort || rmb_memory_cancelled()) return NULL;
    do {
        if (rmb_memory_cancelled()) break;
        rmb_stream_kernel(3, w->count, w->a, w->b, w->c);
        w->elements += w->count; w->ended = rmb_now();
        if (w->ended <= 0) { w->error = EIO; break; }
    } while (w->ended < g->deadline);
    return NULL;
}
int rmb_triad_measure(int threads, int duration_ms, double *rate, double *elapsed,
                      unsigned long long *elements, int *created_workers) {
    *rate = *elapsed = 0; *elements = 0; *created_workers = 0;
    if (threads < 1 || threads > RMB_MAX_WORKERS || duration_ms < 50 || duration_ms > 10000) return EINVAL;
    if (rmb_memory_cancelled()) return ECANCELED;
    void *allocation = malloc(3U * ELEMENTS * sizeof(double) + 63U);
    triad_worker *workers = calloc((size_t)threads, sizeof(*workers));
    pthread_t *handles = calloc((size_t)threads, sizeof(*handles));
    if (!allocation || !workers || !handles) { free(allocation); free(workers); free(handles); return ENOMEM; }
    double *a = (double *)(((uintptr_t)allocation + 63U) & ~(uintptr_t)63U);
    double *b = a + ELEMENTS, *c = b + ELEMENTS;
    triad_gate g; memset(&g, 0, sizeof(g));
    int error = pthread_mutex_init(&g.mutex, NULL);
    if (error) goto free_buffers;
    error = pthread_cond_init(&g.condition, NULL);
    if (error) { pthread_mutex_destroy(&g.mutex); goto free_buffers; }
    int created = 0;
    for (int i = 0; i < threads; i++) {
        size_t start = (ELEMENTS / CACHELINE_ELEMENTS) * (size_t)i / (size_t)threads * CACHELINE_ELEMENTS;
        size_t end = (ELEMENTS / CACHELINE_ELEMENTS) * (size_t)(i + 1) / (size_t)threads * CACHELINE_ELEMENTS;
        triad_worker *w = workers + i;
        w->gate = &g; w->a = a + start; w->b = b + start; w->c = c + start; w->count = end - start;
        error = pthread_create(handles + i, NULL, triad_work, w);
        if (error) break;
        created++;
        /* Serialize worker TLS initialization on Gingerbread x86 (unguarded __set_tls). */
        pthread_mutex_lock(&g.mutex);
        while (g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
        pthread_mutex_unlock(&g.mutex);
    }
    *created_workers = created;
    pthread_mutex_lock(&g.mutex);
    while (!error && g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
    g.start = rmb_now(); g.deadline = g.start + duration_ms / 1000.0;
    if (g.start <= 0 && !error) error = EIO;
    g.abort = error || rmb_memory_cancelled(); g.go = 1;
    pthread_cond_broadcast(&g.condition); pthread_mutex_unlock(&g.mutex);
    double ended = g.start;
    for (int i = 0; i < created; i++) {
        pthread_join(handles[i], NULL);
        if (!error && workers[i].error) error = workers[i].error;
        if (workers[i].ended > ended) ended = workers[i].ended;
        *elements += workers[i].elements;
    }
    if (rmb_memory_cancelled()) error = ECANCELED;
    for (size_t i = 0; !error && i < ELEMENTS; i++) {
        if ((i & 4095U) == 0 && rmb_memory_cancelled()) { error = ECANCELED; break; }
        if (a[i] != 11 || b[i] != 2 || c[i] != 3) error = EILSEQ;
    }
    *elapsed = ended - g.start;
    if (!error && (created != threads || *elapsed <= 0 || !*elements)) error = EIO;
    if (!error) *rate = (double)*elements * 24.0 / *elapsed / 1e6;
    pthread_cond_destroy(&g.condition); pthread_mutex_destroy(&g.mutex);
free_buffers:
    free(allocation); free(workers); free(handles);
    return error;
}
