package com.retromobilebench;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;
import android.view.Surface;
import org.json.JSONObject;
import java.io.File;

public final class MediaService extends Service {
    private boolean started;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what == 3) { Process.killProcess(Process.myPid()); return; }
            if (message.what != 1 || started || message.replyTo == null) return;
            started = true; final Messenger reply = message.replyTo; final Bundle args = message.getData();
            new Thread(() -> {
                JSONObject result;
                try {
                    JSONObject clip = new JSONObject(args.getString("clip")); File path = new File(args.getString("path")).getCanonicalFile();
                    if (!path.getParentFile().equals(new File(getFilesDir(), "media-v1").getCanonicalFile())) throw new SecurityException("media path");
                    String mode = args.getString("mode"); Surface surface = args.getParcelable("surface");
                    if ("software".equals(mode)) {
                        MediaNative.load(); NativeBridge.resetMediaCancellation();
                        result = new JSONObject(NativeBridge.runVideoSoftware(path.getPath(), args.getInt("threads"), 2500,
                                clip.getInt("width"), clip.getInt("height"), clip.getString("first_yuv_sha256")));
                    } else if (Build.VERSION.SDK_INT >= 16) result = MediaCodecApi16.run(clip, path.getPath(), "playback".equals(mode) ? surface : null);
                    else if ("playback".equals(mode)) result = LegacyPlaybackActivity.run(MediaService.this, clip, path.getPath());
                    else result = MediaClient.failure("unsupported", "API 8–15 无公开的命名解码吞吐接口；硬件类型未确认");
                } catch (Exception | LinkageError e) { result = MediaClient.failure("failed", e.toString()); }
                Message response = Message.obtain(null, 2); response.getData().putString("result", result.toString()); response.getData().putInt("pid", Process.myPid());
                try { reply.send(response); } catch (RemoteException ignored) { Process.killProcess(Process.myPid()); }
            }, "rmb-media").start();
        }
    });
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { Process.killProcess(Process.myPid()); return false; }
}
