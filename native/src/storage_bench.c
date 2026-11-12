#include "storage_bench.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <malloc.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define BLOCK_BYTES (256 * 1024)
#define PAGE_BYTES 4096
#define MAX_RANDOM_OPS 4096

static int cancel_requested;

void rmb_storage_reset_cancel(void) {
    __atomic_store_n(&cancel_requested, 0, __ATOMIC_RELAXED);
}

void rmb_storage_cancel(void) {
    __atomic_store_n(&cancel_requested, 1, __ATOMIC_RELAXED);
}

static int cancelled(void) {
    return __atomic_load_n(&cancel_requested, __ATOMIC_RELAXED);
}

static double now_seconds(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1.0;
    return (double) ts.tv_sec + (double) ts.tv_nsec / 1e9;
}

/* Regular files may return short I/O; never count an incomplete operation. */
static int transfer(int fd, void *buffer, size_t bytes, off_t offset, int writing) {
    size_t done = 0;
    while (done < bytes) {
        if (cancelled()) { errno = ECANCELED; return -1; }
        ssize_t n = writing
                ? pwrite(fd, (char *) buffer + done, bytes - done, offset + (off_t) done)
                : pread(fd, (char *) buffer + done, bytes - done, offset + (off_t) done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (n == 0) errno = EIO; return -1; }
        done += (size_t) n;
    }
    return 0;
}

static int direct_unsupported(int error) {
    return error == EINVAL || error == EOPNOTSUPP || error == ENOSYS;
}

void rmb_storage_run(const char *directory, int file_mib, int random_ops,
                     char *json, size_t capacity) {
    int write_fd = -1, read_fd = -1, cached_fd = -1;
    int file_exists = 0, error = 0, direct_error = 0, direct = 0, completed = 0;
    const char *stage = "parameters";
    const char *status = "failed";
    char path[PATH_MAX] = {0};
    void *source = NULL, *destination = NULL;
    double values[4] = {0}, seconds[4] = {0};
    size_t file_bytes = 0;

    if (!directory || file_mib < 4 || file_mib > 32 || random_ops < 1
            || random_ops > MAX_RANDOM_OPS) { error = EINVAL; goto cleanup; }
    file_bytes = (size_t) file_mib * 1024 * 1024;
    if (cancelled()) { error = ECANCELED; goto cleanup; }
    int length = snprintf(path, sizeof path, "%s/rmb-io-XXXXXX", directory);
    if (length < 0 || (size_t) length >= sizeof path) { error = ENAMETOOLONG; goto cleanup; }

    stage = "allocate";
    long page_size = sysconf(_SC_PAGESIZE);
    size_t alignment = page_size > PAGE_BYTES ? (size_t) page_size : PAGE_BYTES;
    /* posix_memalign is API 17; memalign is available on API 16. */
    source = memalign(alignment, BLOCK_BYTES);
    destination = memalign(alignment, BLOCK_BYTES);
    if (!source || !destination) { error = ENOMEM; goto cleanup; }
    uint32_t seed = 0x524d4231u;
    for (size_t i = 0; i < PAGE_BYTES; i++) {
        seed = seed * 1664525u + 1013904223u;
        ((unsigned char *) source)[i] = (unsigned char) (seed >> 24);
    }
    for (size_t i = PAGE_BYTES; i < BLOCK_BYTES; i += PAGE_BYTES)
        memcpy((char *) source + i, source, PAGE_BYTES);

    stage = "create";
    write_fd = mkstemp(path);
    if (write_fd < 0) { error = errno; goto cleanup; }
    file_exists = 1;
    close(write_fd);
    write_fd = open(path, O_RDWR | O_SYNC);
    if (write_fd < 0) { error = errno; goto cleanup; }
    cached_fd = open(path, O_RDONLY);
    if (cached_fd < 0) { error = errno; goto cleanup; }
    read_fd = open(path, O_RDONLY | O_DIRECT);
    if (read_fd < 0) {
        direct_error = errno;
        if (!direct_unsupported(direct_error)) { error = direct_error; goto cleanup; }
    } else {
        direct = 1;
    }
    /* All needed handles are open. Unlink NOW so even process death leaves no
     * test file behind. Storage is released when the last descriptor closes. */
    stage = "unlink";
    if (unlink(path) != 0) { error = errno; goto cleanup; }
    file_exists = 0;

    stage = "warmup";
    if (transfer(write_fd, source, BLOCK_BYTES, 0, 1) != 0) { error = errno; goto cleanup; }
    for (int phase = 0; phase < 4; phase++) {
        const char *stages[] = {"sequential_write", "sequential_read", "random_write", "random_read"};
        stage = stages[phase];
        int writing = phase == 0 || phase == 2;
        size_t block = phase < 2 ? BLOCK_BYTES : PAGE_BYTES;
        int operations = phase < 2 ? (int) (file_bytes / block) : random_ops;
        if (!writing) {
            /* Detect filesystems that accept O_DIRECT at open but reject reads.
             * Probe each block size outside the measured interval. */
            int fd = direct ? read_fd : cached_fd;
            if (transfer(fd, destination, block, 0, 0) != 0) {
                int probe_error = errno;
                if (!direct || !direct_unsupported(probe_error)) { error = probe_error; goto cleanup; }
                direct_error = probe_error;
                direct = 0;
                /* Mixing direct/cached results inside a run is not comparable. */
                if (phase == 3) { error = probe_error; goto cleanup; }
                if (transfer(cached_fd, destination, block, 0, 0) != 0) { error = errno; goto cleanup; }
            }
        }
        int fd = writing ? write_fd : (direct ? read_fd : cached_fd);
        uint32_t random = 0x12345678u;
        double start = now_seconds();
        if (start < 0) { error = errno; goto cleanup; }
        for (int i = 0; i < operations; i++) {
            off_t offset;
            if (phase < 2) {
                offset = (off_t) i * (off_t) block;
            } else {
                /* Fixed, repeatable positions. The file is at most 32 MiB, so
                 * API 16's 32-bit off_t suffices; no unavailable *64 APIs. */
                random = random * 1664525u + 1013904223u;
                offset = (off_t) ((random >> 8) % (file_bytes / PAGE_BYTES)) * PAGE_BYTES;
            }
            if (transfer(fd, writing ? source : destination, block, offset, writing) != 0) {
                error = errno;
                goto cleanup;
            }
        }
        double end = now_seconds();
        seconds[phase] = end - start;
        if (end < 0 || seconds[phase] <= 0) { error = EIO; goto cleanup; }
        /* Validate the last read block outside timing, with no Java/JIT work. */
        if (!writing && memcmp(source, destination, block) != 0) { error = EIO; goto cleanup; }
        values[phase] = phase < 2
                ? (double) file_bytes / seconds[phase] / 1000000.0
                : (double) operations / seconds[phase];
        completed++;
    }
    status = "completed";
    stage = "done";

cleanup:
    if (error == ECANCELED || cancelled()) status = "cancelled";
    if (read_fd >= 0) close(read_fd);
    if (cached_fd >= 0) close(cached_fd);
    if (write_fd >= 0) close(write_fd);
    if (file_exists) unlink(path);
    free(source);
    free(destination);
    /* Only fixed identifiers and numeric errno are serialized: no unescaped
     * filenames or platform-localized strerror text can break JSON. */
    snprintf(json, capacity,
             "{\"test_version\":\"storage-file-v1\",\"status\":\"%s\","
             "\"error_stage\":\"%s\",\"errno\":%d,\"file_bytes\":%u,"
             "\"sequential_block_bytes\":%d,\"random_block_bytes\":%d,"
             "\"random_operations\":%d,\"write_mode\":\"O_SYNC\","
             "\"read_mode\":\"%s\",\"direct_errno\":%d,\"completed_metrics\":%d,"
             "\"sequential_write_mbps\":%.9f,\"sequential_read_mbps\":%.9f,"
             "\"random_write_iops\":%.9f,\"random_read_iops\":%.9f,"
             "\"elapsed_seconds\":[%.9f,%.9f,%.9f,%.9f]}",
             status, stage, error, (unsigned) file_bytes, BLOCK_BYTES, PAGE_BYTES,
             random_ops, direct ? "O_DIRECT" : "cached", direct_error, completed,
             values[0], values[1], values[2], values[3],
             seconds[0], seconds[1], seconds[2], seconds[3]);
}
