/* tinymembench C copy/fill kernels plus RMB dependent-load latency protocol. */
#include "memory_bench.h"
#include "bench_common.h"
#include "util.h"
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define BUFFER_BYTES (8 * 1024 * 1024)
#define CHAIN_COUNT (1024 * 1024)
static int cancelled;
void rmb_memory_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
void rmb_memory_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
int rmb_memory_cancelled(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }
void rmb_memory_run(int duration_ms, char *json, size_t capacity) {
    int error = 0, completed = 0; const char *stage = "arguments";
    double rates[3] = {0}, elapsed[3] = {0}; uint64_t operations[3] = {0};
    void *allocation = NULL;
    if (duration_ms < 50 || duration_ms > 10000) { error = EINVAL; goto finish; }
    if (rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
    stage = "allocation";
    allocation = malloc(2 * BUFFER_BYTES + 64);
    if (!allocation) { error = ENOMEM; goto finish; }
    int64_t *src = (int64_t *)(((uintptr_t)allocation + 63) & ~(uintptr_t)63);
    int64_t *dst = src + BUFFER_BYTES / 8;
    stage = "initialization";
    for (int i = 0; i < BUFFER_BYTES / 8; i++) src[i] = (int64_t)i * 104729 + 0x524d4233;
    for (int phase = 0; phase < 2; phase++) {
        stage = "measurement";
        if (rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
        void (*operation)(int64_t *, int64_t *, int) = phase ? aligned_block_fill : aligned_block_copy;
        operation(dst, src, BUFFER_BYTES); /* Untimed page touch and kernel warmup. */
        double start = rmb_now();
        if (start <= 0) { error = EIO; goto finish; }
        do {
            if (rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
            operation(dst, src, BUFFER_BYTES);
            operations[phase]++;
            double end = rmb_now();
            if (end <= 0) { error = EIO; goto finish; }
            elapsed[phase] = end - start;
        } while (elapsed[phase] < duration_ms / 1000.0);
        stage = "validation";
        for (int i = 0; i < BUFFER_BYTES / 8; i++) {
            if ((i & 4095) == 0 && rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
            if (dst[i] != src[phase ? 0 : i]) { error = EILSEQ; goto finish; }
        }
        rates[phase] = operations[phase] * (double)BUFFER_BYTES * (phase ? 1 : 2) / elapsed[phase] / 1e6;
        completed++;
    }
    stage = "latency_initialization";
    uint32_t *chain = (uint32_t *)src, *order = (uint32_t *)dst;
    for (uint32_t i = 0; i < CHAIN_COUNT; i++) order[i] = i;
    uint32_t seed = 0x524d4233;
    for (uint32_t i = CHAIN_COUNT - 1; i > 0; i--) {
        if ((i & 4095) == 0 && rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
        seed = seed * 1664525U + 1013904223U;
        uint32_t j = seed % (i + 1), temp = order[i]; order[i] = order[j]; order[j] = temp;
    }
    for (uint32_t i = 0; i < CHAIN_COUNT; i++) chain[order[i]] = order[(i+1) % CHAIN_COUNT];
    volatile const uint32_t *links = chain;
    uint32_t cursor = order[0];
    for (int i = 0; i < CHAIN_COUNT; i++) cursor = links[cursor];
    if (cursor != order[0]) { error = EILSEQ; goto finish; }
    stage = "latency_measurement";
    double start = rmb_now();
    if (start <= 0) { error = EIO; goto finish; }
    do {
        if (rmb_memory_cancelled()) { error = ECANCELED; goto finish; }
        for (int i = 0; i < 4096; i++) cursor = links[cursor];
        operations[2] += 4096;
        double end = rmb_now();
        if (end <= 0) { error = EIO; goto finish; }
        elapsed[2] = end - start;
    } while (elapsed[2] < duration_ms / 1000.0);
    stage = "latency_validation";
    if (cursor != order[operations[2] % CHAIN_COUNT]) { error = EILSEQ; goto finish; }
    rates[2] = elapsed[2] * 1e9 / (double)operations[2];
    completed++; stage = "";
finish:
    free(allocation);
    snprintf(json, capacity,
        "{\"test_version\":\"memory-tiny-v1\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,\"completed_metrics\":%d,"
        "\"source_revision\":\"a2cf6d7e382e3aea1eb39173174d9fa28cad15f3\",\"duration_ms\":%d,\"buffer_bytes\":8388608,"
        "\"latency_bytes\":4194304,\"latency_batch\":4096,\"seed\":1380794931,\"copy_traffic_multiplier\":2,"
        "\"copy_mbps\":%.9g,\"fill_mbps\":%.9g,\"latency_ns\":%.9g,"
        "\"elapsed_seconds\":[%.9f,%.9f,%.9f],\"operations\":[%llu,%llu,%llu]}",
        error == ECANCELED ? "cancelled" : error ? "failed" : "completed", stage, error, completed, duration_ms,
        rates[0],rates[1],rates[2],elapsed[0],elapsed[1],elapsed[2],
        (unsigned long long)operations[0],(unsigned long long)operations[1],(unsigned long long)operations[2]);
}
