/* Bounded-memory pthread STREAM variant. See stream_kernels.c for attribution. */
#include "memory_bench.h"
#include "bench_common.h"
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#define ELEMENTS 262144
int rmb_memory_cancelled(void);
void rmb_stream_kernel(int phase, size_t count, double *restrict a, double *restrict b, double *restrict c);
typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int ready, go, abort;
    double start, deadline;
} gate;
typedef struct {
    gate *sync;
    void *allocation;
    double *a, *b, *c, ended;
    int phase, error;
    uint64_t passes;
} worker;
static void *work(void *argument) {
    worker *w = argument; gate *g = w->sync;
    if (!rmb_memory_cancelled()) {
        for (int j = 0; j < ELEMENTS; j++) { w->a[j] = 1; w->b[j] = 2; w->c[j] = 3; }
        rmb_stream_kernel(w->phase, ELEMENTS, w->a, w->b, w->c);
    }
    pthread_mutex_lock(&g->mutex);
    g->ready++; pthread_cond_broadcast(&g->condition);
    while (!g->go && !g->abort) pthread_cond_wait(&g->condition, &g->mutex);
    int abort = g->abort;
    pthread_mutex_unlock(&g->mutex);
    if (abort || rmb_memory_cancelled()) return NULL;
    do {
        if (rmb_memory_cancelled()) break;
        rmb_stream_kernel(w->phase, ELEMENTS, w->a, w->b, w->c);
        w->passes++; w->ended = rmb_now();
        if (w->ended <= 0) { w->error = EIO; break; }
    } while (w->ended < g->deadline);
    return NULL;
}
static int measure(int threads, int phase, int duration, uint64_t *passes, double *seconds, double *rate) {
    gate g; memset(&g, 0, sizeof(g));
    int error = pthread_mutex_init(&g.mutex, NULL); if (error) return error;
    error = pthread_cond_init(&g.condition, NULL);
    if (error) { pthread_mutex_destroy(&g.mutex); return error; }
    worker workers[4]; pthread_t handles[4]; memset(workers, 0, sizeof(workers));
    int created = 0;
    for (int i = 0; i < threads; i++) {
        worker *w = workers + i;
        w->sync = &g; w->phase = phase;
        w->allocation = malloc(3 * ELEMENTS * sizeof(double) + 64);
        if (!w->allocation) { error = ENOMEM; break; }
        w->a = (double *)(((uintptr_t)w->allocation + 63) & ~(uintptr_t)63);
        w->b = w->a + ELEMENTS; w->c = w->b + ELEMENTS;
        error = pthread_create(handles + i, NULL, work, w);
        if (error) break;
        created++;
    }
    pthread_mutex_lock(&g.mutex);
    if (!error) while (g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
    g.start = rmb_now(); if (g.start <= 0 && !error) error = EIO;
    g.deadline = g.start + duration / 1000.0;
    g.abort = error || rmb_memory_cancelled(); g.go = 1;
    pthread_cond_broadcast(&g.condition); pthread_mutex_unlock(&g.mutex);
    double end = g.start;
    for (int i = 0; i < created; i++) {
        pthread_join(handles[i], NULL);
        if (!error && workers[i].error) error = workers[i].error;
        if (workers[i].ended > end) end = workers[i].ended;
        *passes += workers[i].passes;
    }
    /* Verification after all timed workers have exited. */
    for (int i = 0; i < created && !error && !rmb_memory_cancelled(); i++) {
        worker *w = workers + i;
        for (int j = 0; j < ELEMENTS; j++)
            if (w->a[j] != (phase == 3 ? 11 : 1) || w->b[j] != (phase == 1 ? 9 : 2)
                    || w->c[j] != (phase == 0 ? 1 : 3)) { error = EILSEQ; break; }
    }
    for (int i = 0; i < threads; i++) free(workers[i].allocation);
    pthread_cond_destroy(&g.condition); pthread_mutex_destroy(&g.mutex);
    if (rmb_memory_cancelled()) return ECANCELED;
    *seconds = end - g.start;
    if (!error && (*seconds <= 0 || !*passes)) error = EIO;
    if (!error) *rate = (double)*passes * ELEMENTS * sizeof(double) * (phase < 2 ? 2 : 3) / *seconds / 1e6;
    return error;
}
void rmb_stream_run(int threads, int duration_ms, char *json, size_t capacity) {
    int error = 0, completed = 0; const char *stage = "arguments";
    uint64_t passes[4] = {0}; double seconds[4] = {0}, rates[4] = {0};
    if (threads < 1 || threads > 4 || duration_ms < 50 || duration_ms > 10000) { error = EINVAL; goto finish; }
    for (int phase = 0; phase < 4; phase++) {
        stage = phase == 0 ? "copy" : phase == 1 ? "scale" : phase == 2 ? "add" : "triad";
        if (rmb_memory_cancelled()) { error = ECANCELED; break; }
        error = measure(threads, phase, duration_ms, passes + phase, seconds + phase, rates + phase);
        if (error) break;
        completed++;
    }
    if (!error) stage = "";
finish:
    snprintf(json, capacity,
        "{\"test_version\":\"memory-stream-variant-v1\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,\"completed_metrics\":%d,"
        "\"source_revision\":\"6703f7504a38a8da96b353cadafa64d3c2d7a2d3\",\"protocol\":\"RMB STREAM variant\","
        "\"threads\":%d,\"duration_ms\":%d,\"elements_per_array_per_thread\":262144,\"element_bytes\":8,\"scalar\":3,"
        "\"copy_mbps\":%.9g,\"scale_mbps\":%.9g,\"add_mbps\":%.9g,\"triad_mbps\":%.9g,"
        "\"elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f],\"passes\":[%llu,%llu,%llu,%llu]}",
        error == ECANCELED ? "cancelled" : error ? "failed" : "completed", stage,error,completed,threads,duration_ms,
        rates[0],rates[1],rates[2],rates[3],seconds[0],seconds[1],seconds[2],seconds[3],
        (unsigned long long)passes[0],(unsigned long long)passes[1],(unsigned long long)passes[2],(unsigned long long)passes[3]);
}
