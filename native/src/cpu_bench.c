#include "cpu_bench.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <zlib.h>

#if ZLIB_VERNUM != 0x1310
#error "cpu-zlib-v1 requires the pinned zlib 1.3.1 source"
#endif

#define DATA_BYTES (1024U * 1024U)
#include "worker_limits.h"
#define MAX_THREADS RMB_MAX_WORKERS
#ifdef RMB_CODEC_ZSTD
#include <zstd.h>
#include <zstd_errors.h>
#if ZSTD_VERSION_NUMBER != 10507
#error "cpu-zstd-v2 requires the pinned zstd 1.5.7 source"
#endif
#define rmb_cpu_reset_cancel rmb_zstd_reset_cancel
#define rmb_cpu_cancel rmb_zstd_cancel
#define rmb_cpu_run rmb_zstd_run
#define LEVEL 3
#define SUITE_VERSION "cpu-zstd-v2"
#define codec_bound ZSTD_compressBound
#define codec_version ZSTD_versionString
static int codec_compress(unsigned char *out, uLong *length, const unsigned char *in, uLong size, int level) {
    size_t result = ZSTD_compress(out, *length, in, size, level);
    if (ZSTD_isError(result)) return -1000 - (int) ZSTD_getErrorCode(result);
    *length = (uLong) result;
    return 0;
}
static int codec_decompress(unsigned char *out, uLong *length, const unsigned char *in, uLong size) {
    size_t result = ZSTD_decompress(out, *length, in, size);
    if (ZSTD_isError(result)) return -1000 - (int) ZSTD_getErrorCode(result);
    *length = (uLong) result;
    return 0;
}
#else
#define LEVEL 6
#define SUITE_VERSION "cpu-zlib-v2"
#define codec_bound compressBound
#define codec_version zlibVersion
#define codec_compress compress2
#define codec_decompress uncompress
#endif

static int cancelled;
void rmb_cpu_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
void rmb_cpu_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
static int is_cancelled(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }

static double seconds(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1.0;
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1000000000.0;
}

/* Fixed corpus: alternating 2 KiB text and seeded binary regions. No ROM/files. */
static void make_data(unsigned char *data) {
    static const char text[] = "RetroMobileBench offline CPU compression corpus v1. "
            "Android 4.1 ARMv7 x86; repeatable text, records and binary payload.\n";
    uint32_t seed = 0x524d4231U;
    for (size_t i = 0; i < DATA_BYTES; i++) {
        seed = seed * 1664525U + 1013904223U;
        data[i] = (i % 4096U < 2048U) ? (unsigned char) text[i % (sizeof text - 1)]
                : (unsigned char) (seed >> 24);
    }
}

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int ready, start, abort;
    double began, deadline;
} gate;

typedef struct {
    gate *sync;
    const unsigned char *input, *packed;
    uLong packed_size, bound;
    unsigned char *output, *verify;
    int compressing, error;
    uLong output_size;
    unsigned long long iterations;
    double ended;
} worker;

static int operation(worker *w) {
    w->output_size = w->compressing ? w->bound : DATA_BYTES;
    if (w->compressing)
        return codec_compress(w->output, &w->output_size, w->input, DATA_BYTES, LEVEL);
    return codec_decompress(w->output, &w->output_size, w->packed, w->packed_size);
}

static int validate(worker *w) {
    if (w->compressing) {
        uLong length = DATA_BYTES;
        int code = codec_decompress(w->verify, &length, w->output, w->output_size);
        if (code != Z_OK) return code;
        if (length != DATA_BYTES || memcmp(w->verify, w->input, DATA_BYTES) != 0)
            return Z_DATA_ERROR;
        if (w->output_size != w->packed_size
                || memcmp(w->output, w->packed, w->packed_size) != 0) return Z_DATA_ERROR;
    } else if (w->output_size != DATA_BYTES || memcmp(w->output, w->input, DATA_BYTES) != 0) {
        return Z_DATA_ERROR;
    }
    return Z_OK;
}

static void *work(void *argument) {
    worker *w = argument;
    gate *g = w->sync;
    /* Warm up and validate on every thread before the common start. */
    if (!is_cancelled()) {
        w->error = operation(w);
        if (w->error == Z_OK) w->error = validate(w);
    }
    pthread_mutex_lock(&g->mutex);
    g->ready++;
    pthread_cond_broadcast(&g->condition);
    while (!g->start && !g->abort) pthread_cond_wait(&g->condition, &g->mutex);
    int abort = g->abort;
    pthread_mutex_unlock(&g->mutex);
    if (abort || w->error != Z_OK || is_cancelled()) return NULL;
    do {
        if (is_cancelled()) break;
        w->error = operation(w);
        w->ended = seconds();
        if (w->error != Z_OK) break;
        if (w->ended < 0) { w->error = EIO; break; }
        w->iterations++;
    } while (w->ended < g->deadline);
    return NULL;
}

static int measure(const unsigned char *input, const unsigned char *packed, uLong packed_size,
                   int threads, int compressing, int duration_ms, double *elapsed,
                   unsigned long long *iterations, double *mbps) {
    gate g;
    memset(&g, 0, sizeof g);
    int error = pthread_mutex_init(&g.mutex, NULL);
    if (error) return error;
    error = pthread_cond_init(&g.condition, NULL);
    if (error) { pthread_mutex_destroy(&g.mutex); return error; }
    pthread_t *handles = calloc((size_t)threads, sizeof(*handles));
    worker *workers = calloc((size_t)threads, sizeof(*workers));
    if (!handles || !workers) {
        free(handles); free(workers);
        pthread_cond_destroy(&g.condition); pthread_mutex_destroy(&g.mutex);
        return ENOMEM;
    }
    int created = 0;
    for (int i = 0; i < threads; i++) {
        worker *w = &workers[i];
        w->sync = &g;
        w->input = input;
        w->packed = packed;
        w->packed_size = packed_size;
        w->bound = codec_bound(DATA_BYTES);
        w->compressing = compressing;
        w->output = malloc(w->bound);
        if (compressing) w->verify = malloc(DATA_BYTES);
        if (!w->output || (compressing && !w->verify)) { error = ENOMEM; break; }
        error = pthread_create(&handles[i], NULL, work, w);
        if (error) break;
        created++;
    }
    pthread_mutex_lock(&g.mutex);
    if (!error) {
        while (g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
        for (int i = 0; i < created; i++) {
            if (workers[i].error) { error = workers[i].error; break; }
        }
    }
    g.began = seconds();
    if (g.began < 0 && !error) error = EIO;
    g.deadline = g.began + (double) duration_ms / 1000.0;
    g.abort = error != 0 || is_cancelled();
    g.start = 1;
    pthread_cond_broadcast(&g.condition);
    pthread_mutex_unlock(&g.mutex);
    double ended = g.began;
    for (int i = 0; i < created; i++) {
        pthread_join(handles[i], NULL);
        if (!error && workers[i].error) error = workers[i].error;
        *iterations += workers[i].iterations;
        if (workers[i].ended > ended) ended = workers[i].ended;
    }
    /* All timed work has stopped, so verification cannot disturb a slower worker. */
    for (int i = 0; i < created && !error && !is_cancelled(); i++) {
        if (workers[i].iterations > 0) error = validate(&workers[i]);
    }
    for (int i = 0; i < threads; i++) {
        free(workers[i].output);
        free(workers[i].verify);
    }
    free(workers); free(handles);
    pthread_cond_destroy(&g.condition);
    pthread_mutex_destroy(&g.mutex);
    *elapsed = ended - g.began;
    if (!error && !is_cancelled()) {
        if (*iterations == 0 || *elapsed <= 0) return EIO;
        *mbps = (double) *iterations * DATA_BYTES / *elapsed / 1000000.0;
    }
    return error;
}

void rmb_cpu_run(int parallel_threads, int duration_ms, char *json, size_t capacity) {
    const char *stage = "parameters";
    int error = 0, completed = 0;
    unsigned char *input = NULL, *packed = NULL;
    uLong packed_size = 0, checksum = 0;
    double elapsed[4] = {0}, mbps[4] = {0};
    unsigned long long iterations[4] = {0};
    const char *stages[] = {"single_compress", "single_decompress", "parallel_compress", "parallel_decompress"};
    if (parallel_threads < 1 || parallel_threads > MAX_THREADS || duration_ms < 50 || duration_ms > 10000) {
        error = EINVAL;
        goto finish;
    }
    if (is_cancelled()) goto finish;
    stage = "allocate";
    input = malloc(DATA_BYTES);
    packed_size = codec_bound(DATA_BYTES);
    packed = malloc(packed_size);
    if (!input || !packed) { error = ENOMEM; goto finish; }
    stage = "corpus";
    make_data(input);
    checksum = crc32(0L, input, DATA_BYTES);
    error = codec_compress(packed, &packed_size, input, DATA_BYTES, LEVEL);
    if (error != Z_OK) goto finish;
    for (int i = 0; i < (parallel_threads == 1 ? 2 : 4); i++) {
        stage = stages[i];
        if (is_cancelled()) break;
        error = measure(input, packed, packed_size, i < 2 ? 1 : parallel_threads,
                        i % 2 == 0, duration_ms, &elapsed[i], &iterations[i], &mbps[i]);
        if (error || is_cancelled()) break;
        completed++;
    }
    if (completed == (parallel_threads == 1 ? 2 : 4)) stage = "done";
finish:
    free(packed);
    free(input);
    const char *status = error ? "failed" : is_cancelled() ? "cancelled" : "completed";
    snprintf(json, capacity,
        "{\"test_version\":\"" SUITE_VERSION "\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,"
        "\"codec_version\":\"%s\",\"corpus_version\":\"mixed-1m-v1\",\"input_bytes\":%u,"
        "\"input_crc32\":%lu,\"compressed_bytes\":%lu,\"compression_level\":%d,"
        "\"parallel_threads\":%d,\"duration_ms\":%d,\"completed_metrics\":%d,"
        "\"single_compress_mbps\":%.6f,\"single_decompress_mbps\":%.6f,"
        "\"parallel_compress_mbps\":%.6f,\"parallel_decompress_mbps\":%.6f,"
        "\"elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f],"
        "\"iterations\":[%llu,%llu,%llu,%llu]}",
        status, stage, error, codec_version(), DATA_BYTES, checksum, packed_size, LEVEL,
        parallel_threads, duration_ms, completed, mbps[0], mbps[1], mbps[2], mbps[3],
        elapsed[0], elapsed[1], elapsed[2], elapsed[3],
        iterations[0], iterations[1], iterations[2], iterations[3]);
}
