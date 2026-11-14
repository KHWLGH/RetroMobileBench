#include "media_bench.h"
#include "bench_common.h"
#include "worker_limits.h"
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <mbedtls/sha256.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static int verify_frame(AVFrame *frame, int width, int height, int first, const char *expected, char hash[65]) {
    if (frame->width != width || frame->height != height || frame->format != AV_PIX_FMT_YUV420P
            || frame->decode_error_flags || (frame->flags & AV_FRAME_FLAG_CORRUPT)) return AVERROR_INVALIDDATA;
    if (!first) return 0;
    mbedtls_sha256_context sha; unsigned char digest[32]; mbedtls_sha256_init(&sha); mbedtls_sha256_starts_ret(&sha, 0);
    for (int plane = 0; plane < 3; plane++) {
        int w = plane ? (width + 1) / 2 : width, h = plane ? (height + 1) / 2 : height;
        if (!frame->data[plane] || frame->linesize[plane] < w) { mbedtls_sha256_free(&sha); return AVERROR_INVALIDDATA; }
        for (int y = 0; y < h; y++) mbedtls_sha256_update_ret(&sha, frame->data[plane] + (size_t)y * frame->linesize[plane], (size_t)w);
    }
    mbedtls_sha256_finish_ret(&sha, digest); mbedtls_sha256_free(&sha);
    for (int i = 0; i < 32; i++) snprintf(hash + i * 2, 3, "%02x", digest[i]);
    return expected && strcmp(hash, expected) ? AVERROR_INVALIDDATA : 0;
}
static int interrupt_io(void *opaque) { double *deadline = opaque; return rmb_media_cancelled() || rmb_now() >= *deadline; }
void rmb_video_run(const char *path, int threads, int duration, int width, int height, const char *expected, char *json, size_t cap) {
    AVFormatContext *format = NULL; AVCodecContext *codec = NULL; AVFrame *frame = NULL; AVPacket *packet = NULL;
    int error = 0, frames = 0, stream = -1, cycles = 0; char hash[65] = "";
    double start = 0, end = 0, io_deadline = rmb_now() + 10;
    const char *stage = "configure";
    if (!path || threads < 1 || threads > RMB_MAX_WORKERS || duration < 50 || duration > 5000) { error = EINVAL; goto done; }
    av_log_set_level(AV_LOG_ERROR);
    format = avformat_alloc_context();
    if (!format) { error = ENOMEM; goto done; }
    format->interrupt_callback.callback = interrupt_io; format->interrupt_callback.opaque = &io_deadline;
    error = avformat_open_input(&format, path, NULL, NULL);
    if (error < 0) goto done;
    error = avformat_find_stream_info(format, NULL); if (error < 0) goto done;
    stream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (stream < 0) { error = stream; goto done; }
    const AVCodec *decoder = avcodec_find_decoder(format->streams[stream]->codecpar->codec_id);
    if (!decoder) { error = AVERROR_DECODER_NOT_FOUND; goto done; }
    codec = avcodec_alloc_context3(decoder); frame = av_frame_alloc(); packet = av_packet_alloc();
    if (!codec || !frame || !packet) { error = ENOMEM; goto done; }
    error = avcodec_parameters_to_context(codec, format->streams[stream]->codecpar); if (error < 0) goto done;
    codec->thread_count = threads; codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    error = avcodec_open2(codec, decoder, NULL); if (error < 0) goto done;
    stage = "decode_or_pixel_validation"; start = rmb_now(); io_deadline = start + duration / 1000.0 + 2;
    for (;;) {
        if (rmb_media_cancelled() || (frames > 0 && rmb_now() - start >= duration / 1000.0)) break;
        int read = av_read_frame(format, packet);
        if (read < 0 && read != AVERROR_EOF) { error = read; break; }
        if (read >= 0 && packet->stream_index != stream) { av_packet_unref(packet); continue; }
        error = avcodec_send_packet(codec, read == AVERROR_EOF ? NULL : packet); av_packet_unref(packet);
        if (error < 0) break;
        for (;;) {
            error = avcodec_receive_frame(codec, frame);
            if (error == AVERROR(EAGAIN) || error == AVERROR_EOF) { error = 0; break; }
            if (error < 0) break;
            error = verify_frame(frame, width, height, frames == 0, expected, hash);
            av_frame_unref(frame); if (error) break;
            frames++; end = rmb_now();
            if (rmb_media_cancelled() || end - start >= duration / 1000.0) break;
        }
        if (error) break;
        if (read == AVERROR_EOF) {
            cycles++; error = av_seek_frame(format, stream, 0, AVSEEK_FLAG_BACKWARD); if (error < 0) break;
            avcodec_flush_buffers(codec);
        }
    }
    end = rmb_now(); if (!error && !frames && !rmb_media_cancelled()) error = AVERROR_INVALIDDATA;
done:;
    int actual_threads = codec ? codec->thread_count : 0, thread_type = codec ? codec->active_thread_type : 0;
    const char *name = codec && codec->codec ? codec->codec->name : "";
    char decoder_name[128]; rmb_json_string(name, decoder_name, sizeof decoder_name);
    if (packet) av_packet_free(&packet); if (frame) av_frame_free(&frame); if (codec) avcodec_free_context(&codec); if (format) avformat_close_input(&format);
    double seconds = end > start ? end - start : 0;
    snprintf(json, cap, "{\"status\":\"%s\",\"error_stage\":\"%s\",\"error_code\":%d,\"path\":\"fixed_software\","
        "\"library\":\"FFmpeg 4.4.6 LGPL scalar\",\"decoder\":\"%s\",\"frames\":%d,\"complete_clip_cycles\":%d,\"fps\":%.6f,"
        "\"elapsed_seconds\":%.9f,\"requested_threads\":%d,\"threads\":%d,\"active_thread_type\":%d,"
        "\"width\":%d,\"height\":%d,\"first_yuv_sha256\":\"%s\",\"first_pixels_validated\":%s,\"timing\":\"decode_and_demux_cached_file\"}",
        rmb_media_cancelled() ? "cancelled" : error ? "failed" : "completed", error ? stage : "", error, decoder_name, frames, cycles,
        seconds > 0 ? frames / seconds : 0, seconds, threads, actual_threads, thread_type, width, height, hash,
        !error && frames > 0 ? "true" : "false");
}
