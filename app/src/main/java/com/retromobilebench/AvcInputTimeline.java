package com.retromobilebench;

import android.annotation.TargetApi;
import android.media.MediaExtractor;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashSet;

/** Detects a legacy MP4 extractor returning decode times for reordered AVC samples.
 * Only input metadata is repaired, after matching every compressed sample byte-for-byte
 * (SHA-256, Annex-B representation) to the independent container demux. No decoder is
 * opened by this preflight; MediaCodec output timestamps are never changed. */
@TargetApi(16)
final class AvcInputTimeline {
    private final long[] raw, presentation;
    private int position;
    private AvcInputTimeline(long[] raw, long[] presentation) { this.raw = raw; this.presentation = presentation; }

    static AvcInputTimeline prepare(MediaExtractor extractor, String path, JSONObject clip, JSONObject evidence) throws Exception {
        if (Build.VERSION.SDK_INT > 17 || !"video/avc".equals(clip.getString("mime")) || clip.optInt("b_frames") == 0) return null;
        long start = SystemClock.elapsedRealtime();
        MediaNative.load();
        JSONObject metadata = new JSONObject(NativeBridge.readVideoPacketTimeline(path, clip.getInt("frames")));
        if (!"completed".equals(metadata.optString("status"))) throw new IOException("container_timeline_preflight: " + metadata);
        JSONArray packets = metadata.getJSONArray("packets");
        int count = packets.length(), capacity = metadata.getInt("max_sample_size");
        if (count != clip.getInt("frames") || count < 1 || count > 120 || capacity < 1 || capacity > 2 * 1024 * 1024)
            throw new IOException("invalid_container_timeline_bounds");
        ByteBuffer sample = ByteBuffer.allocate(capacity);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long[] raw = new long[count], pts = new long[count], dts = new long[count];
        JSONArray observed = new JSONArray(), reference = new JSONArray();
        for (int i = 0; i < count; i++) {
            JSONObject packet = packets.getJSONObject(i);
            raw[i] = extractor.getSampleTime(); pts[i] = packet.getLong("pts_us"); dts[i] = packet.getLong("normalized_dts_us");
            sample.clear(); int bytes = extractor.readSampleData(sample, 0);
            if (bytes != packet.getInt("size")) throw new IOException("container_sample_size_mismatch: " + i + " " + bytes);
            sample.position(0); sample.limit(bytes); sha.reset(); sha.update(sample);
            if (!packet.getString("annexb_sha256").equals(GpuDataPack.hex(sha.digest())))
                throw new IOException("container_sample_identity_mismatch: " + i);
            observed.put(raw[i]); reference.put(pts[i]); extractor.advance();
        }
        if (extractor.getSampleTime() != -1) throw new IOException("extra_extractor_sample");
        boolean corrected = needsCompositionTimes(raw, pts, dts);
        evidence.put("input_timestamp_source", corrected ? "container_pts_after_verified_extractor_dts" : "platform_pts_verified_against_container")
                .put("timestamp_preflight", "FFmpeg 4.4.6 MP4 demux only; no software decoder opened")
                .put("input_samples_sha256_verified", count).put("input_timestamps_corrected", corrected)
                .put("extractor_pts_us", observed).put("container_pts_us", reference)
                .put("timestamp_preflight_ms", SystemClock.elapsedRealtime() - start);
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        return new AvcInputTimeline(raw, corrected ? pts : raw);
    }

    /** Preserve PTS (including a constant edit-list origin/1us rescaling difference).
     * Correct only when ALL raw times match normalized DTS; other damage fails. */
    static boolean needsCompositionTimes(long[] raw, long[] pts, long[] dts) throws IOException {
        if (raw.length == 0 || raw.length != pts.length || raw.length != dts.length) throw new IOException("timeline_length_mismatch");
        boolean composition = true, decode = true; HashSet<Long> seen = new HashSet<>();
        long origin = raw[0] - pts[0];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] < 0 || pts[i] < 0 || !seen.add(pts[i]) || dts[i] < 0 || (i > 0 && dts[i] <= dts[i - 1]))
                throw new IOException("invalid_container_timestamps: " + i);
            long originDifference = raw[i] - pts[i] - origin;
            composition &= originDifference >= -1 && originDifference <= 1;
            decode &= raw[i] == dts[i];
        }
        if (!composition && !decode) throw new IOException("extractor_timestamps_match_neither_pts_nor_dts");
        return !composition;
    }

    long presentationTime(long extractorTime) throws IOException {
        if (position >= raw.length || extractorTime != raw[position]) throw new IOException("extractor_sample_order_changed: " + position);
        return presentation[position++];
    }
    void nextCycle() throws IOException {
        if (position != raw.length) throw new IOException("incomplete_timeline_cycle: " + position);
        position = 0;
    }
}
