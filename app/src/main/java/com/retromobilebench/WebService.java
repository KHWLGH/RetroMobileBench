package com.retromobilebench;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;
import org.json.JSONObject;

/** One visible round per disposable process. There is deliberately no JNI dependency. */
public final class WebService extends Service {
    static final int RUN = 1, EVENT = 2, STOP = 3, READY = 4;
    static Work current;
    static final class Work {
        final Messenger reply; final String nonce, fault;
        boolean terminal;
        Work(Messenger reply, String nonce, String fault) { this.reply = reply; this.nonce = nonce; this.fault = fault; }
        void send(JSONObject value) {
            if (terminal) return;
            try {
                String raw = value.toString(); if (raw.length() > 50000) throw new IllegalStateException("Web IPC bound");
                Message message = Message.obtain(null, EVENT); message.getData().putString("raw", raw);
                message.getData().putString("nonce", nonce); message.getData().putInt("pid", Process.myPid()); reply.send(message);
                String kind = value.optString("kind"); terminal = "done".equals(kind) || "error".equals(kind) || "cancelled".equals(kind) || "interrupted".equals(kind);
            } catch (Exception error) { Process.killProcess(Process.myPid()); }
        }
    }
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what == STOP) { Process.killProcess(Process.myPid()); return; }
            if (message.what != RUN || current != null || message.replyTo == null) return;
            String nonce = message.getData().getString("nonce");
            if (nonce == null || nonce.length() != 36) return;
            String fault = BuildConfig.DEBUG ? message.getData().getString("fault") : "";
            current = new Work(message.replyTo, nonce, fault == null ? "" : fault);
            try {
                Message ready = Message.obtain(null, READY); ready.getData().putInt("pid", Process.myPid()); ready.getData().putString("nonce", nonce); message.replyTo.send(ready);
                if ("crash".equals(current.fault)) { Process.killProcess(Process.myPid()); return; }
                if ("stale_callback".equals(current.fault)) {
                    Message stale = Message.obtain(null, EVENT); stale.getData().putString("nonce", "expired-request");
                    stale.getData().putString("raw", "invalid old result"); message.replyTo.send(stale);
                }
                startActivity(new Intent(WebService.this, WebBenchmarkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception error) {
                try { current.send(new JSONObject().put("kind", "error").put("reason", error.toString())); } catch (Exception ignored) { }
            }
        }
    });
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { Process.killProcess(Process.myPid()); return false; }
}
