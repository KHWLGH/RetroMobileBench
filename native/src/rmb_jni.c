#include <jni.h>
#include <stdlib.h>

#include "probe.h"
#include "storage_bench.h"

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
