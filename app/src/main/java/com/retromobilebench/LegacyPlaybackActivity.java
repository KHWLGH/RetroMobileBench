package com.retromobilebench;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Old Surface.writeToParcel cannot forward a borrowed window. Keep the window
 * and MediaPlayer in the same disposable media process on API 8-15. */
public final class LegacyPlaybackActivity extends Activity {
    private static final class Work {
        final JSONObject clip; final String path;
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Work(JSONObject value, String file) { clip = value; path = file; }
        void publish(JSONObject value) { if (result.compareAndSet(null, value)) done.countDown(); }
    }
    private static volatile Work pending;
    private Work work;
    private boolean started;

    static JSONObject run(Context context, JSONObject clip, String path) throws Exception {
        Work value = new Work(clip, path); pending = value;
        try {
            context.startActivity(new Intent(context, LegacyPlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!value.done.await(10, TimeUnit.SECONDS)) value.publish(MediaClient.failure("timeout", "MediaPlayer window/configure/playback deadline"));
            return value.result.get();
        } finally { pending = null; }
    }
    @Override @SuppressWarnings("deprecation") protected void onCreate(Bundle state) {
        super.onCreate(state); work = pending;
        if (work == null) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this); label.setText("视频播放：" + work.clip.optString("id")); column.addView(label);
        Button cancel = new Button(this); cancel.setText("取消测试");
        cancel.setOnClickListener(v -> complete(MediaClient.failure("cancelled", "用户取消视频播放"))); column.addView(cancel);
        SurfaceView output = new SurfaceView(this);
        if (android.os.Build.VERSION.SDK_INT < 11) output.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        output.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { }
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (started || width <= 0 || height <= 0 || work.result.get() != null) return;
                started = true;
                new Thread(() -> {
                    JSONObject result;
                    try { result = LegacyPlayback.run(work.clip, work.path, holder); }
                    catch (Exception | LinkageError e) { result = MediaClient.failure("failed", e.toString()); }
                    try { result.put("output_window", "worker_activity").put("surface_created_in_worker", true); } catch (Exception ignored) { }
                    final JSONObject value = result; runOnUiThread(() -> complete(value));
                }, "rmb-legacy-playback").start();
            }
            public void surfaceDestroyed(SurfaceHolder holder) { if (!isFinishing()) complete(MediaClient.failure("interrupted", "播放窗口 Surface 丢失")); }
        });
        column.addView(output, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(column);
    }
    private void complete(JSONObject result) { if (work != null) work.publish(result); finish(); }
    @Override public void onBackPressed() { complete(MediaClient.failure("cancelled", "用户返回停止视频播放")); }
    @Override protected void onStop() {
        if (!isFinishing()) complete(MediaClient.failure("interrupted", "视频播放窗口退到后台"));
        super.onStop();
    }
}
