#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include "media_bench.h"
extern int rmb_image_validate(const unsigned char *, size_t, int, int, int, const char *);
JNIEXPORT void JNICALL Java_com_retromobilebench_NativeBridge_resetMediaCancellation(JNIEnv *e, jclass c) { (void)e; (void)c; rmb_media_reset_cancel(); }
JNIEXPORT void JNICALL Java_com_retromobilebench_NativeBridge_cancelMedia(JNIEnv *e, jclass c) { (void)e; (void)c; rmb_media_cancel(); }
JNIEXPORT jstring JNICALL Java_com_retromobilebench_NativeBridge_runCrypto(JNIEnv *e, jclass c, jint n, jint duration) {
    (void)c; char json[4096]; rmb_crypto_run(n, duration, json, sizeof json); return (*e)->NewStringUTF(e, json);
}
JNIEXPORT jstring JNICALL Java_com_retromobilebench_NativeBridge_runImage(JNIEnv *e, jclass c, jbyteArray data, jstring reference, jboolean png, jint w, jint h, jint duration) {
    (void)c; char json[2048];
    if (!data || !reference || w < 1 || w > 1920 || h < 1 || h > 1080)
        return (*e)->NewStringUTF(e, "{\"status\":\"failed\",\"error_stage\":\"arguments\"}");
    jbyte *bytes = (*e)->GetByteArrayElements(e, data, NULL);
    const char *path = (*e)->GetStringUTFChars(e, reference, NULL);
    if (!bytes || !path) {
        if (bytes) (*e)->ReleaseByteArrayElements(e, data, bytes, JNI_ABORT);
        if (path) (*e)->ReleaseStringUTFChars(e, reference, path);
        return NULL;
    }
    size_t size = (size_t)(*e)->GetArrayLength(e, data);
    int error = rmb_image_validate((unsigned char *)bytes, size, png, w, h, path);
    if (!error) {
        rmb_image_run((unsigned char *)bytes, size, png, w, h, duration, json, sizeof json);
        error = rmb_image_validate((unsigned char *)bytes, size, png, w, h, path);
    }
    if (error) snprintf(json, sizeof json, "{\"status\":\"failed\",\"error_stage\":\"reference_pixels\",\"error_code\":%d}", error);
    (*e)->ReleaseByteArrayElements(e, data, bytes, JNI_ABORT); (*e)->ReleaseStringUTFChars(e, reference, path);
    return (*e)->NewStringUTF(e, json);
}
JNIEXPORT jstring JNICALL Java_com_retromobilebench_NativeBridge_runVideoSoftware(JNIEnv *e, jclass c, jstring path, jint n, jint duration, jint w, jint h, jstring expected) {
    (void)c; if (!path || !expected) return NULL;
    const char *p = (*e)->GetStringUTFChars(e, path, NULL); if (!p) return NULL;
    const char *hash = (*e)->GetStringUTFChars(e, expected, NULL); if (!hash) { (*e)->ReleaseStringUTFChars(e, path, p); return NULL; }
    char json[4096]; rmb_video_run(p, n, duration, w, h, hash, json, sizeof json);
    (*e)->ReleaseStringUTFChars(e, path, p); (*e)->ReleaseStringUTFChars(e, expected, hash); return (*e)->NewStringUTF(e, json);
}
