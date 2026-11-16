#ifndef RMB_MEDIA_BENCH_H
#define RMB_MEDIA_BENCH_H
#include <stddef.h>
void rmb_media_cancel(void);
void rmb_media_reset_cancel(void);
int rmb_media_cancelled(void);
void rmb_video_packet_timeline(const char *path, int expected_frames, char *json, size_t capacity);
void rmb_video_run(const char *path, int threads, int duration, int width, int height, const char *expected,
                   char *json, size_t capacity);
#endif
