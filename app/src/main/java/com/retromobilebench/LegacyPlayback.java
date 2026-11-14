package com.retromobilebench;

import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** API 8 path with a local holder: no cross-process forwarding of old Surfaces. */
final class LegacyPlayback {
    static JSONObject run(JSONObject clip, String path, SurfaceHolder holder) throws Exception {
        // A PUSH_BUFFERS Surface need not have a client-side producer token.
        // MediaPlayer/mediaserver validate the local holder's ISurface instead.
        if (holder == null || holder.getSurface() == null) return MediaClient.failure("interrupted", "playback_surface_missing");
        MediaPlayer player = new MediaPlayer(); CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        try {
            player.setDisplay(holder);
            player.setOnCompletionListener(p -> done.countDown());
            player.setOnErrorListener((p, what, extra) -> { error.set("MediaPlayer " + what + "/" + extra); done.countDown(); return true; });
            long configure = SystemClock.elapsedRealtime();
            // mediaserver cannot open the app's private pathname on old ROMs.
            try (java.io.FileInputStream input = new java.io.FileInputStream(path)) { player.setDataSource(input.getFD()); }
            player.prepare();
            long start = SystemClock.elapsedRealtime(); player.start();
            boolean completed = done.await(7, TimeUnit.SECONDS);
            if (!completed) return MediaClient.failure("timeout", "MediaPlayer completion timeout");
            if (error.get() != null) return MediaClient.failure("failed", error.get());
            if (player.getVideoWidth() != clip.getInt("width") || player.getVideoHeight() != clip.getInt("height"))
                return MediaClient.failure("failed", "MediaPlayer dimensions mismatch");
            return new JSONObject().put("status", "completed").put("path", "legacy_mediaplayer_playback")
                    .put("configure_ms", start - configure).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                    .put("width", player.getVideoWidth()).put("height", player.getVideoHeight())
                    .put("hardware_type", "unconfirmed").put("decoder_name", JSONObject.NULL)
                    .put("frame_count", JSONObject.NULL).put("displayed_fps", JSONObject.NULL).put("pixel_validation", "unavailable_public_api");
        } finally { player.release(); }
    }
}
