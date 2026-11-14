#include "media_bench.h"
#include "bench_common.h"
#include <turbojpeg.h>
#include <png.h>
#include <mbedtls/sha256.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static int decode(const unsigned char *input, size_t size, unsigned char *output, tjhandle jpeg,
                  int is_png, int width, int height) {
    if (!is_png) {
        int w, h, sampling, color;
        if (tjDecompressHeader3(jpeg, input, (unsigned long)size, &w, &h, &sampling, &color) || w != width || h != height) return EILSEQ;
        return tjDecompress2(jpeg, input, (unsigned long)size, output, width, width * 4, height, TJPF_RGBA, TJFLAG_ACCURATEDCT) ? EILSEQ : 0;
    }
    png_image image; memset(&image, 0, sizeof image); image.version = PNG_IMAGE_VERSION;
    if (!png_image_begin_read_from_memory(&image, input, size)) return EILSEQ;
    image.format = PNG_FORMAT_RGBA;
    int valid = image.width == (png_uint_32)width && image.height == (png_uint_32)height;
    if (valid) valid = png_image_finish_read(&image, NULL, output, 0, NULL);
    png_image_free(&image); return valid ? 0 : EILSEQ;
}
/* Reference pixels are checked by the JNI wrapper before and after this timing loop. */
void rmb_image_run(const unsigned char *input, size_t size, int is_png, int width, int height,
                   int duration, char *json, size_t cap) {
    unsigned char *output = NULL; tjhandle jpeg = NULL; int error = 0, frames = 0;
    double start = 0, end = 0; unsigned char digest[32]; char hash[65] = "";
    if (!input || size < 1 || size > 16U*1024U*1024U || width < 1 || width > 1920 || height < 1 || height > 1080 || duration < 50 || duration > 10000) { error = EINVAL; goto done; }
    output = malloc((size_t)width * height * 4); jpeg = tjInitDecompress();
    if (!output || !jpeg) { error = ENOMEM; goto done; }
    error = decode(input, size, output, jpeg, is_png, width, height);
    if (error) goto done;
    start = rmb_now();
    do {
        if (rmb_media_cancelled()) break;
        error = decode(input, size, output, jpeg, is_png, width, height); end = rmb_now();
        if (error) break; frames++;
    } while (end - start < duration / 1000.0);
    if (!error && frames > 0 && end > start) {
        mbedtls_sha256_ret(output, (size_t)width * height * 4, digest, 0);
        for (int i = 0; i < 32; i++) snprintf(hash + i * 2, 3, "%02x", digest[i]);
    } else if (!rmb_media_cancelled() && !error) error = EIO;
done:
    free(output); if (jpeg) tjDestroy(jpeg);
    double seconds = end > start ? end - start : 0;
    snprintf(json, cap, "{\"status\":\"%s\",\"error_code\":%d,\"error_stage\":\"%s\",\"frames\":%d,\"width\":%d,\"height\":%d,"
        "\"output_format\":\"RGBA8\",\"library\":\"%s\",\"elapsed_seconds\":%.9f,\"mpixels_per_second\":%.6f,\"ms_per_image\":%.6f,\"pixel_sha256\":\"%s\"}",
        rmb_media_cancelled() ? "cancelled" : error ? "failed" : "completed", error, error ? "decode_or_dimensions" : "", frames, width, height,
        is_png ? "libpng 1.6.43" : "libjpeg-turbo 2.1.5.1 scalar accurate DCT", seconds,
        seconds > 0 ? (double)width * height * frames / seconds / 1e6 : 0, frames ? seconds * 1000 / frames : 0, hash);
}

int rmb_image_validate(const unsigned char *input, size_t size, int is_png, int width, int height, const char *reference) {
    if (width < 1 || width > 1920 || height < 1 || height > 1080 || !reference) return EINVAL;
    size_t bytes = (size_t)width * height * 4;
    unsigned char *out = malloc(bytes); tjhandle jpeg = tjInitDecompress();
    int error = (!out || !jpeg) ? ENOMEM : decode(input, size, out, jpeg, is_png, width, height);
    /* Do not copy an 8 MiB golden image through the small legacy Java heap.
       Reference I/O is outside the timed decode loop. Both formats are byte-exact. */
    FILE *file = !error ? fopen(reference, "rb") : NULL;
    if (!error && !file) error = errno ? errno : EIO;
    unsigned char chunk[4096]; size_t offset = 0;
    while (!error && offset < bytes) {
        size_t count = bytes - offset; if (count > sizeof chunk) count = sizeof chunk;
        if (fread(chunk, 1, count, file) != count || memcmp(out + offset, chunk, count)) error = EILSEQ;
        offset += count;
    }
    if (!error && fgetc(file) != EOF) error = EILSEQ;
    if (file) fclose(file); free(out); if (jpeg) tjDestroy(jpeg); return error;
}
