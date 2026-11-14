package com.retromobilebench;

import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One terminal result per disposable worker. No periodic parent-side polling. */
final class WorkerRequest {
    private final AtomicReference<JSONObject> result = new AtomicReference<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final JSONObject cancelledResult;

    WorkerRequest(JSONObject cancelledResult) { this.cancelledResult = cancelledResult; }
    boolean isComplete() { return result.get() != null; }
    boolean complete(JSONObject value) { return complete(value, null); }

    /** Only the winning callback may propagate a legacy window's cancel/interruption. */
    boolean complete(JSONObject value, Runnable beforeWake) {
        if (!result.compareAndSet(null, value)) return false;
        try { if (beforeWake != null) beforeWake.run(); }
        finally { ready.countDown(); }
        return true;
    }
    JSONObject await(long deadline, JSONObject timeoutResult) throws InterruptedException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0 || !ready.await(remaining, TimeUnit.MILLISECONDS)) complete(timeoutResult);
        // A callback can have claimed its result just before the deadline. Wait
        // for its lifecycle flags before the runner starts another stage.
        ready.await();
        return result.get();
    }

    /** Registration and cancellation share a lock, so cancellation cannot be lost. */
    static final class Cancellation {
        private volatile boolean cancelled;
        private WorkerRequest active;
        boolean isCancelled() { return cancelled; }
        synchronized void reset() {
            if (active != null) throw new IllegalStateException("worker request still active");
            cancelled = false;
        }
        synchronized void attach(WorkerRequest request) {
            if (active != null) throw new IllegalStateException("concurrent worker requests");
            active = request;
            if (cancelled) request.complete(request.cancelledResult);
        }
        synchronized void detach(WorkerRequest request) { if (active == request) active = null; }
        synchronized void cancel() {
            cancelled = true;
            if (active != null) active.complete(active.cancelledResult);
        }
    }
}
