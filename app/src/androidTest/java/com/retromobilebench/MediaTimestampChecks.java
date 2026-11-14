package com.retromobilebench;

import android.app.Instrumentation;
import android.content.Context;
import android.media.MediaExtractor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Read-only demux diagnostic: preserves sample order and raw platform timestamps. */
final class MediaTimestampChecks {
    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        accounting();
        MediaClient.resetCancellation();
        MediaCorpus.prepare(context, () -> false);
        JSONArray clips = MediaCorpus.manifest(context).getJSONArray("videos"), records = new JSONArray();
        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.getJSONObject(i);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(new File(new File(context.getFilesDir(), "media-v1"), clip.getString("file")).getPath());
                for (int track = 0; track < extractor.getTrackCount(); track++) {
                    if (extractor.getTrackFormat(track).getString("mime").startsWith("video/")) { extractor.selectTrack(track); break; }
                }
                JSONArray pts = new JSONArray();
                while (extractor.getSampleTime() >= 0) { pts.put(extractor.getSampleTime()); extractor.advance(); }
                JSONObject record = new JSONObject().put("id", clip.getString("id")).put("extractor_pts_us", pts);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                AvcInputTimeline timeline = AvcInputTimeline.prepare(extractor,
                        new File(new File(context.getFilesDir(), "media-v1"), clip.getString("file")).getPath(), clip, record);
                if (timeline != null) {
                    JSONObject incomplete = new JSONObject(NativeBridge.readVideoPacketTimeline(
                            new File(new File(context.getFilesDir(), "media-v1"), clip.getString("file")).getPath(), clip.getInt("frames") - 1));
                    check("failed".equals(incomplete.optString("status")) && !incomplete.has("packets"), "wrong packet count cannot produce a usable timeline");
                    JSONArray submitted = new JSONArray();
                    while (extractor.getSampleTime() >= 0) {
                        submitted.put(timeline.presentationTime(extractor.getSampleTime())); extractor.advance();
                    }
                    timeline.nextCycle(); record.put("verified_input_pts_us", submitted);
                }
                records.put(record);
            } finally { extractor.release(); }
        }
        JSONObject result = new JSONObject().put("diagnostic_only", true).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("fingerprint", android.os.Build.FINGERPRINT).put("native_build_id", BuildConfig.NATIVE_BUILD_ID).put("results", records);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "media-timestamps.json"))) {
            out.write(result.toString(2).getBytes("UTF-8"));
        }
        return result.toString();
    }
    static void accounting() throws Exception {
        long[] pts = {0, 100000, 33333, 66666}, dts = {0, 33333, 66666, 100000};
        check(AvcInputTimeline.needsCompositionTimes(dts, pts, dts), "recognized decode times need container composition times");
        check(!AvcInputTimeline.needsCompositionTimes(pts, pts, dts), "correct extractor PTS remain unchanged");
        check(!AvcInputTimeline.needsCompositionTimes(new long[]{66666, 166666, 100000, 133333}, pts, dts),
                "correct PTS retain their edit-list origin and microsecond rounding");
        check(!AvcInputTimeline.needsCompositionTimes(dts, dts, dts), "no reordering needs no correction");
        boolean rejected = false;
        try { AvcInputTimeline.needsCompositionTimes(new long[]{0, 33333, 99999, 100000}, pts, dts); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "unknown timestamp damage is not rewritten");
        rejected = false;
        try { AvcInputTimeline.needsCompositionTimes(dts, new long[]{0, 33333, 33333, 100000}, dts); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "duplicate container PTS rejected");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
