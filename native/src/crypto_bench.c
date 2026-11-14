#include "media_bench.h"
#include "worker_limits.h"
#include "bench_common.h"
#include <mbedtls/gcm.h>
#include <mbedtls/sha256.h>
#include <mbedtls/aes.h>
#include <mbedtls/version.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#define BYTES (256U * 1024U)
static int cancelled;
void rmb_media_cancel(void) { __atomic_store_n(&cancelled, 1, __ATOMIC_RELAXED); }
void rmb_media_reset_cancel(void) { __atomic_store_n(&cancelled, 0, __ATOMIC_RELAXED); }
int rmb_media_cancelled(void) { return __atomic_load_n(&cancelled, __ATOMIC_RELAXED); }
typedef struct { pthread_mutex_t mutex; pthread_cond_t condition; int ready, start, abort; double began, deadline; } sync_gate;
typedef struct {
    sync_gate *gate; int mode, error; unsigned long long iterations; double end;
    mbedtls_gcm_context gcm; unsigned char *plain, *cipher, *out;
    unsigned char tag[16], generated_tag[16], digest[32], expected[32];
} crypto_worker;
static const unsigned char key[16] = {0}, iv[12] = {0}, aad[16] = {0};
static int operation(crypto_worker *w) {
    if (w->mode == 0) return mbedtls_gcm_crypt_and_tag(&w->gcm, MBEDTLS_GCM_ENCRYPT, BYTES,
            iv, sizeof iv, aad, sizeof aad, w->plain, w->out, 16, w->generated_tag);
    if (w->mode == 1) return mbedtls_gcm_auth_decrypt(&w->gcm, BYTES, iv, sizeof iv,
            aad, sizeof aad, w->tag, 16, w->cipher, w->out);
    return mbedtls_sha256_ret(w->plain, BYTES, w->digest, 0);
}
static int verify(crypto_worker *w) {
    if (w->mode == 0) return memcmp(w->cipher, w->out, BYTES) || memcmp(w->tag, w->generated_tag, 16);
    if (w->mode == 1) return memcmp(w->plain, w->out, BYTES);
    return memcmp(w->expected, w->digest, 32);
}
static void *work(void *arg) {
    crypto_worker *w = arg; sync_gate *g = w->gate;
    w->error = operation(w); if (!w->error) w->error = verify(w);
    pthread_mutex_lock(&g->mutex); g->ready++; pthread_cond_broadcast(&g->condition);
    while (!g->start) pthread_cond_wait(&g->condition, &g->mutex);
    int abort = g->abort; pthread_mutex_unlock(&g->mutex);
    if (abort || w->error) return NULL;
    do {
        if (rmb_media_cancelled()) break;
        w->error = operation(w); w->end = rmb_now();
        if (w->error || w->end <= 0) { if (!w->error) w->error = EIO; break; }
        w->iterations++;
    } while (w->end < g->deadline);
    return NULL;
}
static int measure(int threads, int mode, int duration, double *rate) {
    sync_gate g = {PTHREAD_MUTEX_INITIALIZER, PTHREAD_COND_INITIALIZER, 0, 0, 0, 0, 0};
    crypto_worker *workers = calloc((size_t)threads, sizeof(*workers));
    pthread_t *handles = calloc((size_t)threads, sizeof(*handles));
    int error = (!workers || !handles) ? ENOMEM : 0, created = 0;
    for (int i = 0; !error && i < threads; i++) {
        crypto_worker *w = &workers[i]; w->gate = &g; w->mode = mode;
        mbedtls_gcm_init(&w->gcm);
        w->plain = malloc(BYTES); w->cipher = malloc(BYTES); w->out = malloc(BYTES);
        if (!w->plain || !w->cipher || !w->out) { error = ENOMEM; break; }
        for (size_t j = 0; j < BYTES; j++) w->plain[j] = (unsigned char)(j * 31U + j / 4096U);
        error = mbedtls_gcm_setkey(&w->gcm, MBEDTLS_CIPHER_ID_AES, key, 128);
        if (!error) error = mbedtls_gcm_crypt_and_tag(&w->gcm, MBEDTLS_GCM_ENCRYPT, BYTES,
                iv, sizeof iv, aad, sizeof aad, w->plain, w->cipher, 16, w->tag);
        if (!error) error = mbedtls_sha256_ret(w->plain, BYTES, w->expected, 0);
        // Authentication failure must be rejected, not benchmarked as successful decryption.
        unsigned char bad[16]; memcpy(bad, w->tag, 16); bad[0] ^= 1;
        if (!error && mbedtls_gcm_auth_decrypt(&w->gcm, BYTES, iv, sizeof iv, aad, sizeof aad,
                bad, 16, w->cipher, w->out) != MBEDTLS_ERR_GCM_AUTH_FAILED) error = EILSEQ;
        if (!error) error = pthread_create(&handles[i], NULL, work, w);
        if (!error) created++;
    }
    pthread_mutex_lock(&g.mutex);
    while (!error && g.ready < created) pthread_cond_wait(&g.condition, &g.mutex);
    g.began = rmb_now(); g.deadline = g.began + duration / 1000.0;
    g.abort = error || rmb_media_cancelled(); g.start = 1; pthread_cond_broadcast(&g.condition); pthread_mutex_unlock(&g.mutex);
    unsigned long long iterations = 0; double end = g.began;
    for (int i = 0; i < created; i++) { pthread_join(handles[i], NULL); iterations += workers[i].iterations;
        if (workers[i].end > end) end = workers[i].end;
        if (!error && (workers[i].error || (workers[i].iterations && verify(&workers[i])))) error = EILSEQ;
    }
    if (workers) for (int i = 0; i < threads; i++) { mbedtls_gcm_free(&workers[i].gcm); free(workers[i].plain); free(workers[i].cipher); free(workers[i].out); }
    free(workers); free(handles); pthread_cond_destroy(&g.condition); pthread_mutex_destroy(&g.mutex);
    if (!error && !rmb_media_cancelled()) {
        if (end <= g.began || !iterations) error = EIO;
        else *rate = (double)iterations * BYTES / (end - g.began) / 1e6;
    }
    return error;
}
void rmb_crypto_run(int threads, int duration, char *json, size_t cap) {
    int error = 0, completed = 0; double rates[6] = {0};
    if (threads < 1 || threads > RMB_MAX_WORKERS || duration < 50 || duration > 10000) error = EINVAL;
    // Upstream KATs for AES/GCM/SHA include independent published vectors.
    if (!error && (mbedtls_aes_self_test(0) || mbedtls_gcm_self_test(0) || mbedtls_sha256_self_test(0))) error = EILSEQ;
    int phases = threads == 1 ? 3 : 6;
    for (int i = 0; !error && !rmb_media_cancelled() && i < phases; i++) {
        error = measure(i < 3 ? 1 : threads, i % 3, duration, &rates[i]);
        if (!error && !rmb_media_cancelled()) completed++;
    }
    snprintf(json, cap, "{\"test_version\":\"cpu-crypto-v1\",\"status\":\"%s\",\"completed_metrics\":%d,"
        "\"error_stage\":\"%s\",\"error_code\":%d,\"library_version\":\"" MBEDTLS_VERSION_STRING "\","
        "\"parallel_threads\":%d,\"duration_ms\":%d,\"input_bytes\":%u,\"tag_bytes\":16,\"aad_bytes\":16,"
        "\"single_encrypt_mbps\":%.6f,\"single_decrypt_mbps\":%.6f,\"single_sha256_mbps\":%.6f,"
        "\"parallel_encrypt_mbps\":%.6f,\"parallel_decrypt_mbps\":%.6f,\"parallel_sha256_mbps\":%.6f}",
        rmb_media_cancelled() ? "cancelled" : error ? "failed" : "completed", completed,
        error ? "parameters_or_validation_or_workers" : "", error, threads, duration, BYTES,
        rates[0], rates[1], rates[2], rates[3], rates[4], rates[5]);
}
