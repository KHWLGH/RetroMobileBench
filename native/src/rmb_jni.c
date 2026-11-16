#include <jni.h>
#include <stdlib.h>
#include <unistd.h>

JNIEXPORT jint JNICALL Java_com_retromobilebench_NativeBridge_configuredProcessors(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; long n = sysconf(_SC_NPROCESSORS_CONF); return n > 0 ? (jint)n : 0;
}

#include "probe.h"
#include "storage_bench.h"
#include "sqlite_bench.h"
#include "coremark_bench.h"
#include "scimark_bench.h"
#include "memory_bench.h"

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_resetMemoryCancellation(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_memory_reset_cancel();
}
JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_cancelMemory(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_memory_cancel();
}
JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_runMemory(JNIEnv *env, jclass clazz, jint threads, jint duration) {
    (void)clazz; char json[4096]; rmb_memory_run(threads, duration, json, sizeof(json));
    return (*env)->NewStringUTF(env, json);
}

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_resetScimarkCancellation(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_scimark_reset_cancel();
}
JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_cancelScimark(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_scimark_cancel();
}
JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_runScimark(JNIEnv *env, jclass clazz, jint threads, jint duration) {
    (void)clazz; char json[4096]; rmb_scimark_run(threads, duration, json, sizeof(json));
    return (*env)->NewStringUTF(env, json);
}

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_resetCoremarkCancellation(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_coremark_reset_cancel();
}
JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_cancelCoremark(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz; rmb_coremark_cancel();
}
JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_runCoremark(JNIEnv *env, jclass clazz, jint threads, jint iterations) {
    (void)clazz;
    char *json = malloc(262144);
    if (!json) return (*env)->NewStringUTF(env, "{\"status\":\"failed\",\"error_stage\":\"allocation\"}");
    rmb_coremark_run(threads, iterations, json, 262144);
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_resetSqliteCancellation(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz; rmb_sqlite_reset_cancel();
}
JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_cancelSqlite(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz; rmb_sqlite_cancel();
}
JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_runSqlite(JNIEnv *env, jclass clazz, jstring directory, jint transactions) {
    (void) clazz;
    if (!directory) return (*env)->NewStringUTF(env, "{\"status\":\"failed\",\"error_stage\":\"directory\"}");
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return NULL;
    char json[2048];
    rmb_sqlite_run(path, transactions, json, sizeof json);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return (*env)->NewStringUTF(env, json);
}

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_resetStorageCancellation(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    rmb_storage_reset_cancel();
}

JNIEXPORT void JNICALL
Java_com_retromobilebench_NativeBridge_cancelStorage(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    rmb_storage_cancel();
}

JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_runStorage(JNIEnv *env, jclass clazz,
                                                jstring directory, jint file_mib, jint random_ops) {
    (void) clazz;
    if (!directory) {
        return (*env)->NewStringUTF(env, "{\"status\":\"failed\",\"error_stage\":\"directory\"}");
    }
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return NULL; /* VM has already raised OutOfMemoryError. */
    char json[2048];
    rmb_storage_run(path, file_mib, random_ops, json, sizeof json);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return (*env)->NewStringUTF(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_retromobilebench_NativeBridge_probeReport(JNIEnv *env, jclass clazz) {
    (void) clazz;
    const size_t cap = 16 * 1024;
    char *buf = (char *) malloc(cap);
    if (!buf) {
        return (*env)->NewStringUTF(env, "probe: out of memory");
    }
    rmb_probe_report(buf, cap);
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}
