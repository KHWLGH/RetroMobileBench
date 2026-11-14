package com.retromobilebench;

import android.annotation.TargetApi;
import android.media.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/** Kept out of API 8 classes: only synchronous API 16 buffer methods are used. */
@TargetApi(16)
final class MediaCodecApi16 {
    static boolean software(String name) { return name.startsWith("OMX.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.") || name.startsWith("OMX.ffmpeg."); }
    static JSONObject run(JSONObject clip, String path, Surface surface, int candidate, android.os.Messenger progress) throws Exception {
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
        if (candidate < 0 || candidate >= Math.min(2, candidates.size()))
            return MediaClient.failure("unsupported", "未枚举到匹配的系统解码器").put("candidate_missing", true);
        MediaCodecInfo info = candidates.get(candidate);
        JSONObject identity = new JSONObject().put("decoder", info.getName()).put("candidate_index", candidate)
                .put("declared_capabilities", declaredCapabilities(info, mime))
                .put("type_evidence", software(info.getName()) ? "known_software_name" : "vendor_or_unknown_name")
                .put("hardware_type", "unconfirmed");
        if (Build.VERSION.SDK_INT >= 29) identity.put("system_attributes", CodecAttributesApi29.describe(info));
        android.os.Message notice = android.os.Message.obtain(null, 4);
        notice.getData().putString("attempt", identity.toString()); notice.getData().putInt("pid", android.os.Process.myPid());
        progress.send(notice); // Parent retains identity even if configure aborts in native code.
        JSONObject result;
        try { result = decode(clip, path, surface, info); }
        catch (Exception | LinkageError e) { result = MediaClient.failure("failed", e.toString()); }
        java.util.Iterator<String> keys = identity.keys();
        while (keys.hasNext()) { String key = keys.next(); result.put(key, identity.get(key)); }
        return result;
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
    private static JSONObject failure(JSONObject result, String status, String reason) throws Exception { return result.put("status", status).put("error_stage", reason); }

    /** Surface buffers can have size zero, including the last frame with EOS attached.
     * Match them to submitted samples, so an empty EOS/control buffer is never an extra frame. */
    static final class OutputFrames {
        final HashSet<Long> pending = new HashSet<>();
        final JSONArray outputPtsPrefix = new JSONArray();
        int submitted, frames, cycleFrames, zeroSizeSurfaceFrames, ignoredEmptyBuffers;
        long firstPts = -1, lastPts = -1;
        void submit(long pts) throws IOException {
            if (pts < 0 || !pending.add(pts)) throw new IOException("invalid_or_duplicate_input_pts: " + pts);
            submitted++;
        }
        boolean consume(int size, int flags, long pts, boolean surface) throws IOException {
            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return false;
            if (size == 0 && (!surface || !pending.contains(pts))) { ignoredEmptyBuffers++; return false; }
            if (cycleFrames > 0 && pts <= lastPts) throw new IOException("non_monotonic_output_pts: " + pts + " <= " + lastPts);
            if (!pending.remove(pts)) throw new IOException("unexpected_output_pts: " + pts);
            if (cycleFrames == 0) firstPts = pts;
            lastPts = pts; cycleFrames++; frames++;
            if (outputPtsPrefix.length() < 16) outputPtsPrefix.put(pts);
            if (surface && size == 0) zeroSizeSurfaceFrames++;
            return true;
        }
        boolean complete(int expected) { return submitted == expected && cycleFrames == expected && pending.isEmpty(); }
        void nextCycle() { pending.clear(); submitted = cycleFrames = 0; firstPts = lastPts = -1; }
    }
    private static JSONObject decode(JSONObject clip, String path, Surface surface, MediaCodecInfo info) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null; boolean started = false;
        JSONObject result = new JSONObject().put("path", surface == null ? "system_throughput" : "timestamp_paced_surface_playback")
                .put("frame_accounting", "submitted_pts_v2").put("input_timestamp_source", "platform_extractor");
        OutputFrames frames = new OutputFrames(); boolean outputEos = false;
        long configureStart = SystemClock.elapsedRealtime(); String stage = "set_data_source";
        try {
            extractor.setDataSource(path); int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) { track = i; break; }
            if (track < 0) return failure(result, "failed", "video_track_missing");
            extractor.selectTrack(track);
            stage = "input_timestamp_preflight";
            AvcInputTimeline timeline = AvcInputTimeline.prepare(extractor, path, clip, result);
            MediaFormat format = extractor.getTrackFormat(track);
            if (Build.VERSION.SDK_INT <= 17 && info.getName().startsWith("OMX.TI.")) {
                // JB ACodec::setMinBufferSize CHECKs when Ducati silently rejects growth.
                // Keep the driver's native capacity; readSampleData still rejects oversized samples.
                result.put("extractor_max_input_size", integer(format, MediaFormat.KEY_MAX_INPUT_SIZE, -1));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
                result.put("input_buffer_policy", "ti_jb_driver_default");
            }
            stage = "create_decoder"; codec = MediaCodec.createByCodecName(info.getName());
            stage = "configure"; codec.configure(format, surface, null, 0);
            stage = "start"; codec.start(); started = true;
            ByteBuffer[] input = codec.getInputBuffers(), output = codec.getOutputBuffers();
            result.put("input_buffer_capacity", input.length == 0 ? 0 : input[0].capacity());
            MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
            long start = SystemClock.elapsedRealtime(), lastOutput = start;
            int cycles = 0, width = clip.getInt("width"), height = clip.getInt("height");
            boolean inputEos = false, pixels = false, pixelReadable = false;
            String pixelEvidence = surface == null ? "unavailable_color_layout" : "unavailable_surface_readback_on_this_path";
            MediaFormat actual = format;
            result.put("configure_ms", start - configureStart).put("decoder", info.getName());
            while (SystemClock.elapsedRealtime() - start < (surface == null ? 2800 : 8000)) {
                if (surface != null && !surface.isValid()) return failure(result, "interrupted", "播放窗口 Surface 丢失");
                stage = "feed_input";
                if (!inputEos) {
                    int index = codec.dequeueInputBuffer(1000);
                    if (index >= 0) {
                        ByteBuffer data = input[index]; data.clear(); int bytes = extractor.readSampleData(data, 0);
                        if (bytes < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEos = true; }
                        else {
                            long rawPts = extractor.getSampleTime();
                            long pts = timeline == null ? rawPts : timeline.presentationTime(rawPts);
                            boolean more = extractor.advance();
                            // EOS on the last sample preserves its PTS on old OMX Surface decoders.
                            codec.queueInputBuffer(index, 0, bytes, pts, more ? 0 : MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            frames.submit(pts); inputEos = !more;
                        }
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
                    if (cropW != width || cropH != height) return failure(result, "failed", "output_dimensions_mismatch: " + actual);
                    result.put("output_format", actual.toString());
                } else if (index >= 0) {
                    boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (eos) result.put("eos_pts_us", buffer.presentationTimeUs).put("eos_size", buffer.size).put("eos_flags", buffer.flags);
                    boolean frame = frames.consume(buffer.size, buffer.flags, buffer.presentationTimeUs, surface != null);
                    if (frame) {
                        if (cycles == 0 && frames.frames == 1 && surface == null) {
                            int color = integer(actual, MediaFormat.KEY_COLOR_FORMAT, -1);
                            if (color == 19 || color == 21) {
                                pixelReadable = true; pixels = validateLuma(output[index], buffer, actual, clip);
                                pixelEvidence = pixels ? "first_frame_luma_16x9_validated" : "first_frame_pixels_mismatch";
                                if (!pixels) return failure(result, "failed", pixelEvidence);
                            }
                        }
                        if (surface != null) {
                            long due = start + (buffer.presentationTimeUs - frames.firstPts) / 1000;
                            while (SystemClock.elapsedRealtime() < due && surface.isValid()) SystemClock.sleep(Math.max(1, Math.min(10, due - SystemClock.elapsedRealtime())));
                        }
                        lastOutput = SystemClock.elapsedRealtime();
                    }
                    codec.releaseOutputBuffer(index, surface != null && frame);
                    if (eos) {
                        outputEos = true;
                        if (!frames.complete(clip.getInt("frames"))) return failure(result, "failed", "incomplete_output_at_eos: " + frames.cycleFrames);
                        if (surface != null) break;
                        cycles++; codec.flush(); input = codec.getInputBuffers(); output = codec.getOutputBuffers();
                        if (timeline != null) timeline.nextCycle();
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC); inputEos = outputEos = false; frames.nextCycle();
                    }
                }
                if (SystemClock.elapsedRealtime() - lastOutput > 2500) return failure(result, "timeout", "first_frame_or_output_stall");
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            if (frames.frames == 0) return failure(result, "timeout", "no_output");
            if (surface != null && (!outputEos || !frames.complete(clip.getInt("frames")))) return failure(result, "timeout", "incomplete_playback_output: " + frames.frames);
            double fps = frames.frames * 1000.0 / Math.max(1, elapsed);
            return result.put("status", "completed").put("fps", fps).put("elapsed_ms", elapsed)
                    .put("clip_cycles", cycles).put("width", width).put("height", height)
                    .put("pixel_validation", pixelEvidence).put("pixel_readable", pixelReadable).put("pixels_validated", pixels)
                    .put("realtime", surface != null ? (lastOutput - start <= clip.getInt("frames") * 1000L / 30 + 500) : fps >= 30)
                    .put("displayed_fps", JSONObject.NULL).put("note", "输出/提交帧数不等于实际显示帧数");
        } catch (Exception | LinkageError e) {
            return result.put("status", "failed").put("error_stage", stage).put("exception", e.toString());
        } finally {
            result.put("frames", frames.frames).put("input_frames_in_cycle", frames.submitted).put("pending_frames", frames.pending.size())
                    .put("last_pts_us", frames.lastPts).put("output_eos", outputEos).put("output_pts_prefix_us", frames.outputPtsPrefix)
                    .put("zero_size_surface_frames", frames.zeroSizeSurfaceFrames).put("ignored_empty_buffers", frames.ignoredEmptyBuffers);
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
