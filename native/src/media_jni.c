#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include "media_bench.h"

JNIEXPORT void JNICALL Java_com_retromobilebench_NativeBridge_resetMediaCancellation(JNIEnv *e, jclass c) { (void)e; (void)c; rmb_media_reset_cancel(); }
JNIEXPORT void JNICALL Java_com_retromobilebench_NativeBridge_cancelMedia(JNIEnv *e, jclass c) { (void)e; (void)c; rmb_media_cancel(); }

JNIEXPORT jstring JNICALL Java_com_retromobilebench_NativeBridge_readVideoPacketTimeline(JNIEnv *e, jclass c, jstring path, jint expected) {
    (void)c; if (!path || expected < 1 || expected > 120) return NULL;
    const char *p = (*e)->GetStringUTFChars(e, path, NULL); if (!p) return NULL;
    char *json = malloc(32768);
    if (!json) { (*e)->ReleaseStringUTFChars(e, path, p); return NULL; }
    rmb_video_packet_timeline(p, expected, json, 32768);
    (*e)->ReleaseStringUTFChars(e, path, p);
    jstring result = (*e)->NewStringUTF(e, json); free(json); return result;
}

JNIEXPORT jstring JNICALL Java_com_retromobilebench_NativeBridge_runVideoSoftware(JNIEnv *e, jclass c, jstring path, jint n, jint duration, jint w, jint h, jstring expected) {
    (void)c; if (!path || !expected) return NULL;
    const char *p = (*e)->GetStringUTFChars(e, path, NULL); if (!p) return NULL;
    const char *hash = (*e)->GetStringUTFChars(e, expected, NULL); if (!hash) { (*e)->ReleaseStringUTFChars(e, path, p); return NULL; }
    char json[4096]; rmb_video_run(p, n, duration, w, h, hash, json, sizeof json);
    (*e)->ReleaseStringUTFChars(e, path, p); (*e)->ReleaseStringUTFChars(e, expected, hash); return (*e)->NewStringUTF(e, json);
}
