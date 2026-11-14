/* Standalone diagnostic: includes the real wrapper, with test-only pthread/clock
 * interception. Never compiled into the application or used for scores. */
#include "../../native/src/bench_common.h"
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
static int test_create(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *);
static int test_join(pthread_t, void **);
static double test_now(void);
static int test_compare(const void *, const void *, size_t);
#define pthread_create test_create
#define pthread_join test_join
#define rmb_now test_now
#define memcmp test_compare
#include "../../native/src/crypto_bench.c"
#undef pthread_create
#undef pthread_join
#undef rmb_now
#undef memcmp

static pthread_t main_thread;
static pthread_key_t ordinal_key;
static int created_count, joined_count, violations, fail_at = -1, skew_at = -1;
typedef struct { void *(*routine)(void *); void *argument; int ordinal; } invocation;
static void *test_work(void *value) {
    invocation call = *(invocation *)value; free(value);
    pthread_setspecific(ordinal_key, (void *)(intptr_t)(call.ordinal + 1));
    return call.routine(call.argument);
}
static int test_create(pthread_t *thread, const pthread_attr_t *attr, void *(*routine)(void *), void *argument) {
    if (created_count == fail_at) return EAGAIN;
    invocation *call = malloc(sizeof(*call));
    if (!call) return ENOMEM;
    call->routine = routine; call->argument = argument; call->ordinal = created_count;
    int error = pthread_create(thread, attr, test_work, call);
    if (error) free(call); else created_count++;
    return error;
}
static int test_join(pthread_t thread, void **result) {
    int error = pthread_join(thread, result);
    if (!error) joined_count++;
    return error;
}
static double test_now(void) {
    int ordinal = (int)(intptr_t)pthread_getspecific(ordinal_key) - 1;
    if (!pthread_equal(pthread_self(), main_thread) && ordinal == skew_at) {
        /* Hold the last worker in its first timed operation while the others
           finish. The old join/verify interleaving would read buffers here. */
        pthread_setspecific(ordinal_key, NULL);
        struct timespec delay = {0, 200000000}; nanosleep(&delay, NULL);
    }
    return rmb_now();
}
static int test_compare(const void *a, const void *b, size_t bytes) {
    if (pthread_equal(pthread_self(), main_thread) && joined_count > 0 && joined_count < created_count) violations++;
    return memcmp(a, b, bytes);
}
int main(void) {
    main_thread = pthread_self();
    if (pthread_key_create(&ordinal_key, NULL)) return 1;
    if (mbedtls_aes_self_test(0) || mbedtls_gcm_self_test(0) || mbedtls_sha256_self_test(0)) return 2;
    for (int mode = 0; mode < 3; mode++) {
        created_count = joined_count = violations = 0; skew_at = 3;
        double rate = 0; rmb_media_reset_cancel();
        int error = measure(4, mode, 50, &rate);
        if (error || rate <= 0 || violations || created_count != 4 || joined_count != 4) {
            printf("FAIL mode=%d error=%d rate=%f overlapping_verifications=%d joined=%d/%d\n", mode, error, rate, violations, joined_count, created_count);
            return 3;
        }
    }
    created_count = joined_count = violations = 0; fail_at = 2; skew_at = -1;
    double rate = 0;
    if (measure(4, 0, 50, &rate) != EAGAIN || created_count != 2 || joined_count != 2) return 4;
    fail_at = -1; created_count = joined_count = 0; rmb_media_cancel();
    if (measure(4, 0, 50, &rate) != 0 || joined_count != created_count) return 5;
    rmb_media_reset_cancel(); pthread_key_delete(ordinal_key);
    puts("PASS: delayed crypto workers fully joined before verification; KAT, creation failure and cancellation cleanup");
    return 0;
}
