#ifndef RMB_MEDIA_BENCH_H
#define RMB_MEDIA_BENCH_H
#include <stddef.h>
void rmb_media_cancel(void);
void rmb_media_reset_cancel(void);
int rmb_media_cancelled(void);
void rmb_crypto_run(int threads, int duration, char *json, size_t capacity);
void rmb_image_run(const unsigned char *input, size_t size, int png, int width, int height,
                   int duration, char *json, size_t capacity);
void rmb_video_run(const char *path, int threads, int duration, int width, int height, const char *expected,
                   char *json, size_t capacity);
#endif
