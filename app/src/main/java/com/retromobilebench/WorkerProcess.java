package com.retromobilebench;

import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Never run synchronous Binder cleanup on the UI thread or ping a wedged driver. */
final class WorkerProcess {
    static void close(Context context, ServiceConnection connection, Messenger remote, IBinder binder,
                      IBinder.DeathRecipient death, CountDownLatch died, int pid) throws Exception {
        CountDownLatch unbound = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread reaper = new Thread(() -> {
            try {
                // PID arrives before entering native code; all workers share our UID.
                if (died.getCount() != 0) {
                    if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
                    else if (remote != null) remote.send(Message.obtain(null, 3));
                }
                context.unbindService(connection);
            } catch (Exception e) { error.set(e); }
            finally { unbound.countDown(); }
        }, "rmb-worker-reaper");
        reaper.setDaemon(true); reaper.start();
        try {
            if (!unbound.await(5, TimeUnit.SECONDS)) throw new IOException("工作进程解绑超时；停止后续同类负载");
            if (error.get() != null) throw error.get();
            if (binder != null && !died.await(5, TimeUnit.SECONDS))
                throw new IOException("工作进程未退出；停止后续同类负载");
        } finally {
            if (binder != null) try { binder.unlinkToDeath(death, 0); }
            catch (java.util.NoSuchElementException ignored) { }
        }
    }
}
