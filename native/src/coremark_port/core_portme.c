/* Android port and serial suite wrapper. No changes to the EEMBC core sources. */
#include "coremark.h"
#include "../coremark_bench.h"
#include "../bench_common.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <limits.h>

volatile ee_s32 seed1_volatile, seed2_volatile, seed3_volatile = 0x66;
volatile ee_s32 seed4_volatile, seed5_volatile;
ee_u32 default_num_contexts = 1;
static int cancelled, port_error, overflow;
static double started, elapsed;
static char output[16384];
static size_t used;
int rmb_coremark_entry(void);

void rmb_coremark_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
void rmb_coremark_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
static int is_cancelled(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }
void start_time(void) { started = rmb_now(); }
void stop_time(void) {
    elapsed = rmb_now() - started;
    if (started <= 0 || elapsed <= 0 || elapsed >= 4294) port_error = EIO;
}
CORE_TICKS get_time(void) { return elapsed > 0 && elapsed < 4294 ? (CORE_TICKS)(elapsed * 1e6) : 0; }
secs_ret time_in_secs(CORE_TICKS ticks) { return ticks / 1e6; }
void portable_init(core_portable *p, int *argc, char *argv[]) {
    (void)argc; (void)argv; p->portable_id = 1;
}
void portable_fini(core_portable *p) { p->portable_id = 0; }
void *portable_malloc(ee_size_t size) { return malloc(size); }
void portable_free(void *p) { free(p); }
typedef struct {
    core_results *res;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int tls_ready;
} rmb_core_launch;

static void *core_thread_trampoline(void *arg) {
    rmb_core_launch *launch = arg;
    core_results *res = launch->res;
    pthread_mutex_lock(&launch->mutex);
    launch->tls_ready = 1;
    pthread_cond_signal(&launch->cond);
    pthread_mutex_unlock(&launch->mutex);
    return iterate(res);
}

ee_u8 core_start_parallel(core_results *res) {
    rmb_core_launch launch = { res, PTHREAD_MUTEX_INITIALIZER, PTHREAD_COND_INITIALIZER, 0 };
    int error = pthread_create(&res->port.thread, NULL, core_thread_trampoline, &launch);
    res->port.started = error == 0;
    if (!error) {
        /* Wait until libc __init_tls/__set_tls finishes for this thread (Gingerbread x86 race). */
        pthread_mutex_lock(&launch.mutex);
        while (!launch.tls_ready) pthread_cond_wait(&launch.cond, &launch.mutex);
        pthread_mutex_unlock(&launch.mutex);
    } else {
        /* main does not check the return code. Fill valid outputs synchronously,
           then reject the entire run; never join an uninitialized pthread_t. */
        port_error = error;
        iterate(res);
    }
    pthread_cond_destroy(&launch.cond);
    pthread_mutex_destroy(&launch.mutex);
    return (ee_u8)(error != 0);
}
ee_u8 core_stop_parallel(core_results *res) {
    if (res->port.started) {
        int error = pthread_join(res->port.thread, NULL);
        if (error) port_error = error;
        return (ee_u8)(error != 0);
    }
    return 1;
}
int ee_printf(const char *format, ...) {
    va_list args;
    va_start(args, format);
    int n = vsnprintf(output + used, sizeof(output) - used, format, args);
    va_end(args);
    if (n < 0 || (size_t)n >= sizeof(output) - used) { overflow = 1; return n; }
    used += (size_t)n;
    return n;
}
static int execute(int threads, int validation, int iterations) {
    default_num_contexts = (ee_u32)threads;
    seed1_volatile = seed2_volatile = validation ? 0x3415 : 0;
    seed3_volatile = 0x66; seed4_volatile = iterations; seed5_volatile = 0;
    port_error = overflow = 0; used = 0; output[0] = 0; elapsed = 0;
    rmb_coremark_entry();
    if (port_error) return port_error;
    if (overflow) return EOVERFLOW;
    if (strstr(output, "crc 0x") || strstr(output, "Cannot validate")) return EILSEQ;
    return 0;
}
static int formal_run(int threads, int validation, int forced_iterations, int *iterations) {
    elapsed = 0;
    int n = forced_iterations ? forced_iterations : 100;
    for (int attempt = 0; attempt < 6; attempt++) {
        if (is_cancelled()) return ECANCELED;
        int error = execute(threads, validation, n);
        *iterations = n;
        if (error) return error;
        if (is_cancelled()) return ECANCELED;
        if (elapsed >= 10.0 && strstr(output, "Correct operation validated.")) return 0;
        if (forced_iterations) return ERANGE; /* No score for short diagnostics. */
        if (elapsed <= 0) return EIO;
        double next = (double)n * 12.0 / elapsed;
        if (next > INT_MAX / 4 || next <= n) return ERANGE;
        n = (int)next + 1;
    }
    return ERANGE;
}
void rmb_coremark_run(int threads, int iterations, char *json, size_t capacity) {
    int error = 0, completed = 0, counts[4] = {0};
    double seconds[4] = {0}, rates[2] = {0};
    char (*logs)[16384] = calloc(4, sizeof(*logs));
    if (!logs) { snprintf(json, capacity, "{\"status\":\"failed\",\"error_stage\":\"allocation\",\"completed_metrics\":0}"); return; }
    const char *stage = "arguments";
    if (threads < 1 || threads > RMB_MAX_WORKERS || iterations < 0 || iterations > 1000000) { error = EINVAL; goto finish; }
    for (int phase = 0; phase < (threads == 1 ? 2 : 4); phase++) {
        stage = phase == 0 ? "single_performance" : phase == 1 ? "single_validation"
                : phase == 2 ? "parallel_performance" : "parallel_validation";
        error = formal_run(phase < 2 ? 1 : threads, phase & 1, iterations, &counts[phase]);
        seconds[phase] = elapsed;
        if (counts[phase]) memcpy(logs[phase], output, sizeof(output));
        if (error) break;
        if (phase & 1) {
            int performance = phase - 1;
            rates[completed++] = (double)counts[performance] * (phase < 2 ? 1 : threads) / seconds[performance];
        }
    }
    if (!error) stage = "";
finish:;
    char (*escaped)[32768] = calloc(4, sizeof(*escaped));
    char compiler[2048], flags[4096];
    if (!escaped) { free(logs); snprintf(json, capacity, "{\"status\":\"failed\",\"error_stage\":\"allocation\",\"completed_metrics\":0}"); return; }
    for (int i = 0; i < 4; i++) rmb_json_string(logs[i], escaped[i], sizeof(escaped[i]));
    rmb_json_string(COMPILER_VERSION, compiler, sizeof(compiler));
    rmb_json_string(COMPILER_FLAGS, flags, sizeof(flags));
    int n = snprintf(json, capacity,
        "{\"test_version\":\"cpu-coremark-v1\",\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,"
        "\"completed_metrics\":%d,\"source_revision\":\"1f483d5b8316753a742cbf5590caf5bd0a4e4777\","
        "\"compiler_version\":\"%s\",\"compiler_flags\":\"%s\",\"memory_method\":\"stack\","
        "\"buffer_bytes\":2000,\"minimum_seconds\":10,\"parallel_threads\":%d,\"diagnostic_iterations\":%d,"
        "\"single_iterations_per_second\":%.9g,\"parallel_iterations_per_second\":%.9g,"
        "\"phase_iterations\":[%d,%d,%d,%d],\"phase_seconds\":[%.6f,%.6f,%.6f,%.6f],"
        "\"single_output\":\"%s\",\"single_validation_output\":\"%s\","
        "\"parallel_output\":\"%s\",\"parallel_validation_output\":\"%s\"}",
        error == ECANCELED ? "cancelled" : error ? "failed" : "completed", stage, error, completed,
        compiler, flags, threads, iterations, rates[0], rates[1], counts[0], counts[1], counts[2], counts[3],
        seconds[0], seconds[1], seconds[2], seconds[3], escaped[0], escaped[1], escaped[2], escaped[3]);
    free(escaped); free(logs);
    if (n < 0 || (size_t)n >= capacity)
        snprintf(json, capacity, "{\"status\":\"failed\",\"error_stage\":\"json_capacity\",\"completed_metrics\":0}");
}
