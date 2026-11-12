#include <jni.h>
#include <stdlib.h>

#include "probe.h"

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
