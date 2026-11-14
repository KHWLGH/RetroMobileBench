package com.retromobilebench;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import java.io.File;

/** Bound, unexported :gpu process. Each binding executes at most one native test. */
public final class GpuService extends Service {
    static final int RUN = 1, RESULT = 2, STOP = 3;
    private boolean started;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message request) {
            if (request.what == STOP) { terminate(); return; }
            if (request.what != RUN || started || request.replyTo == null) return;
            started = true;
            final Messenger reply = request.replyTo;
            final String test = request.getData().getString("test");
            final String root = request.getData().getString("root");
            final int duration = request.getData().getInt("duration");
            final android.view.Surface surface = request.getData().getParcelable("surface");
            final int width = request.getData().getInt("width", 800), height = request.getData().getInt("height", 480);
            new Thread(() -> {
                String raw;
                try {
                    if (!GpuClient.isTest(test) || root == null || duration < 0 || duration > 10000)
                        throw new IllegalArgumentException("GPU arguments");
                    File pack = new File(root).getCanonicalFile();
                    File packs = new File(getFilesDir(), "gpu-packs").getCanonicalFile();
                    if (!pack.getPath().startsWith(packs.getPath() + File.separator))
                        throw new IllegalArgumentException("GPU data path");
                    if (NativeLoader.load() == null) throw new IllegalStateException(String.valueOf(NativeLoader.error()));
                    File output = new File(getCacheDir(), "gpu-output");
                    if (!output.isDirectory() && !output.mkdir()) throw new IllegalStateException("GPU output directory");
                    if (!test.endsWith("_off") && android.os.Build.VERSION.SDK_INT < 11) {
                        raw = LegacyGpuActivity.run(GpuService.this, test, new File(pack, "config/" + test + ".json").getPath(),
                                new File(pack, "data/gfx").getPath() + "/", output.getPath() + "/", duration);
                    } else {
                        JavaEglContext.window = surface;
                        if (!test.endsWith("_off") && (surface == null || !surface.isValid())) throw new IllegalStateException("窗口 Surface 丢失");
                        raw = GfxProbe.runTest(new File(pack, "config/" + test + ".json").getPath(),
                                new File(pack, "data/gfx").getPath() + "/", output.getPath() + "/", width, height, duration);
                    }
                    if (!test.endsWith("_off") && JavaEglContext.windowError.length() != 0 && raw != null
                            && !raw.startsWith("CANCELLED:") && !raw.startsWith("INTERRUPTED:")) raw = "ERROR: " + JavaEglContext.windowError;
                    if (raw == null || raw.length() > 100000) throw new IllegalStateException("GPU result size");
                } catch (Exception | LinkageError failure) {
                    raw = "ERROR: " + failure;
                }
                Message result = Message.obtain(null, RESULT);
                result.getData().putString("raw", raw);
                result.getData().putInt("pid", Process.myPid());
                result.getData().putLong("window_swaps", JavaEglContext.successfulWindowSwaps);
                try { reply.send(result); } catch (RemoteException ignored) { terminate(); }
            }, "rmb-gpu").start();
        }
    });

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { terminate(); return false; }
    private void terminate() { Process.killProcess(Process.myPid()); }
}
