package com.retromobilebench;

import android.annotation.TargetApi;
import android.media.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Kept out of API 8 classes: only synchronous API 16 buffer methods are used. */
@TargetApi(16)
final class MediaCodecApi16 {
    static boolean software(String name) { return name.startsWith("OMX.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.") || name.startsWith("OMX.ffmpeg."); }
    static JSONObject run(JSONObject clip, String path, Surface surface) throws Exception {
        ArrayList<MediaCodecInfo> candidates = new ArrayList<>(); String mime = clip.getString("mime");
        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i); if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) { candidates.add(info); break; }
        }
        Collections.sort(candidates, new Comparator<MediaCodecInfo>() {
            public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                int groupA = software(a.getName()) ? 1 : 0, groupB = software(b.getName()) ? 1 : 0;
                return groupA != groupB ? groupA - groupB : a.getName().compareTo(b.getName());
            }
        });
        JSONArray attempts = new JSONArray(); JSONObject result = MediaClient.failure("unsupported", "未枚举到匹配的系统解码器");
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            MediaCodecInfo info = candidates.get(i);
            try { result = decode(clip, path, surface, info); }
            catch (Exception | LinkageError e) { result = MediaClient.failure("failed", e.toString()); }
            result.put("declared_capabilities", declaredCapabilities(info, mime));
            result.put("decoder", info.getName()).put("type_evidence", software(info.getName()) ? "known_software_name" : "vendor_or_unknown_name")
                    .put("hardware_type", "unconfirmed");
            if (Build.VERSION.SDK_INT >= 29) result.put("system_attributes", CodecAttributesApi29.describe(info));
            attempts.put(result);
            if ("completed".equals(result.optString("status"))) break;
        }
        return new JSONObject(result.toString()).put("attempts", attempts).put("selection", "vendor_or_unknown_then_known_software_name_lexical_max2");
    }
    private static JSONObject declaredCapabilities(MediaCodecInfo info, String mime) {
        JSONObject result = new JSONObject();
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            JSONArray profiles = new JSONArray(), colors = new JSONArray();
            for (MediaCodecInfo.CodecProfileLevel value : caps.profileLevels) profiles.put(new JSONObject().put("profile", value.profile).put("level", value.level));
            for (int color : caps.colorFormats) colors.put(color);
            result.put("profile_levels", profiles).put("color_formats", colors).put("evidence", "system_declaration_only");
        } catch (Exception e) { try { result.put("unavailable", e.toString()); } catch (Exception ignored) { } }
        return result;
    }
    private static int integer(MediaFormat f, String key, int fallback) { try { return f.containsKey(key) ? f.getInteger(key) : fallback; } catch (Exception e) { return fallback; } }
    private static JSONObject decode(JSONObject clip, String path, Surface surface, MediaCodecInfo info) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null; boolean started = false;
        JSONObject result = new JSONObject().put("path", surface == null ? "system_throughput" : "timestamp_paced_surface_playback");
        long configureStart = SystemClock.elapsedRealtime(); String stage = "set_data_source";
        try {
            extractor.setDataSource(path); int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) { track = i; break; }
            if (track < 0) return MediaClient.failure("failed", "video_track_missing");
            extractor.selectTrack(track); MediaFormat format = extractor.getTrackFormat(track);
            stage = "create_decoder"; codec = MediaCodec.createByCodecName(info.getName());
            stage = "configure"; codec.configure(format, surface, null, 0);
            stage = "start"; codec.start(); started = true;
            ByteBuffer[] input = codec.getInputBuffers(), output = codec.getOutputBuffers();
            MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
            long start = SystemClock.elapsedRealtime(), lastOutput = start, firstPts = -1, lastPts = -1;
            int frames = 0, cycles = 0, width = clip.getInt("width"), height = clip.getInt("height");
            boolean inputEos = false, outputEos = false, pixels = false, pixelReadable = false;
            String pixelEvidence = surface == null ? "unavailable_color_layout" : "unavailable_surface_readback_on_this_path";
            MediaFormat actual = format;
            result.put("configure_ms", start - configureStart).put("decoder", info.getName());
            while (SystemClock.elapsedRealtime() - start < (surface == null ? 2800 : 8000)) {
                if (surface != null && !surface.isValid()) return MediaClient.failure("interrupted", "播放窗口 Surface 丢失");
                stage = "feed_input";
                if (!inputEos) {
                    int index = codec.dequeueInputBuffer(1000);
                    if (index >= 0) {
                        ByteBuffer data = input[index]; data.clear(); int bytes = extractor.readSampleData(data, 0);
                        if (bytes < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEos = true; }
                        else { codec.queueInputBuffer(index, 0, bytes, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                stage = "drain_output";
                int index = codec.dequeueOutputBuffer(buffer, 1000);
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) output = codec.getOutputBuffers();
                else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    actual = codec.getOutputFormat();
                    int codedW = integer(actual, "width", width), codedH = integer(actual, "height", height);
                    int cropW = integer(actual, "crop-right", codedW - 1) - integer(actual, "crop-left", 0) + 1;
                    int cropH = integer(actual, "crop-bottom", codedH - 1) - integer(actual, "crop-top", 0) + 1;
                    if (cropW != width || cropH != height) return MediaClient.failure("failed", "output_dimensions_mismatch: " + actual);
                    result.put("output_format", actual.toString());
                } else if (index >= 0) {
                    boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean frame = (buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && (buffer.size > 0 || !eos && surface != null);
                    if (frame) {
                        if (frames > 0 && buffer.presentationTimeUs <= lastPts) return MediaClient.failure("failed", "non_monotonic_output_pts");
                        if (firstPts < 0) firstPts = buffer.presentationTimeUs;
                        if (cycles == 0 && frames == 0 && surface == null) {
                            int color = integer(actual, MediaFormat.KEY_COLOR_FORMAT, -1);
                            if (color == 19 || color == 21) {
                                pixelReadable = true; pixels = validateLuma(output[index], buffer, actual, clip);
                                pixelEvidence = pixels ? "first_frame_luma_16x9_validated" : "first_frame_pixels_mismatch";
                                if (!pixels) return MediaClient.failure("failed", pixelEvidence);
                            }
                        }
                        if (surface != null) {
                            long due = start + (buffer.presentationTimeUs - firstPts) / 1000;
                            while (SystemClock.elapsedRealtime() < due && surface.isValid()) SystemClock.sleep(Math.max(1, Math.min(10, due - SystemClock.elapsedRealtime())));
                        }
                        lastPts = buffer.presentationTimeUs; frames++; lastOutput = SystemClock.elapsedRealtime();
                    }
                    codec.releaseOutputBuffer(index, surface != null && frame);
                    if (eos) {
                        if (surface != null) { outputEos = true; break; }
                        if (frames == 0) return MediaClient.failure("failed", "empty_output");
                        cycles++; codec.flush(); input = codec.getInputBuffers(); output = codec.getOutputBuffers();
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC); inputEos = false; lastPts = -1;
                    }
                }
                if (SystemClock.elapsedRealtime() - lastOutput > 2500) return MediaClient.failure("timeout", "first_frame_or_output_stall");
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            if (frames == 0) return MediaClient.failure("timeout", "no_output");
            if (surface != null && (!outputEos || frames != clip.getInt("frames"))) return MediaClient.failure("timeout", "incomplete_playback_output: " + frames);
            double fps = frames * 1000.0 / Math.max(1, elapsed);
            return result.put("status", "completed").put("frames", frames).put("fps", fps).put("elapsed_ms", elapsed)
                    .put("clip_cycles", cycles).put("width", width).put("height", height).put("last_pts_us", lastPts)
                    .put("pixel_validation", pixelEvidence).put("pixel_readable", pixelReadable).put("pixels_validated", pixels)
                    .put("realtime", surface != null ? (lastOutput - start <= clip.getInt("frames") * 1000L / 30 + 500) : fps >= 30)
                    .put("displayed_fps", JSONObject.NULL).put("note", "输出/提交帧数不等于实际显示帧数");
        } catch (Exception | LinkageError e) {
            return result.put("status", "failed").put("error_stage", stage).put("exception", e.toString());
        } finally {
            if (codec != null) {
                try { if (started) codec.stop(); } catch (Exception e) { result.put("cleanup_warning", "stop: " + e); }
                try { codec.release(); } catch (Exception e) { result.put("cleanup_warning", "release: " + e); }
            }
            extractor.release();
        }
    }
    private static boolean validateLuma(ByteBuffer data, MediaCodec.BufferInfo b, MediaFormat format, JSONObject clip) throws Exception {
        int width = clip.getInt("width"), height = clip.getInt("height");
        int stride = integer(format, "stride", integer(format, "width", width));
        int left = integer(format, "crop-left", 0), top = integer(format, "crop-top", 0);
        JSONArray expected = clip.getJSONArray("first_luma_16x9"); long totalError = 0;
        for (int by = 0; by < 9; by++) for (int bx = 0; bx < 16; bx++) {
            long sum = 0; int count = 0;
            for (int y = by * height / 9; y < (by + 1) * height / 9; y += 4) for (int x = bx * width / 16; x < (bx + 1) * width / 16; x += 4) {
                int offset = b.offset + (y + top) * stride + x + left;
                if (offset < 0 || offset >= data.limit()) return false;
                sum += data.get(offset) & 255; count++;
            }
            int error = Math.abs((int)Math.round(sum / (double)Math.max(1, count)) - expected.getInt(by * 16 + bx));
            if (error > 12) return false; totalError += error;
        }
        return totalError <= 144 * 5;
    }
}
