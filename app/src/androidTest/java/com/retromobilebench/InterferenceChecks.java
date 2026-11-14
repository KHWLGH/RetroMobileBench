package com.retromobilebench;

import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Targeted regressions for host work that can interfere with measured workers. */
final class InterferenceChecks {
    static void run() throws Exception {
        requests();
        temperatures();
    }
    private static WorkerRequest request() { return new WorkerRequest(MediaClient.failure("cancelled", "test")); }
    private static JSONObject timeout() { return MediaClient.failure("timeout", "test"); }
    private static void requests() throws Exception {
        WorkerRequest.Cancellation cancellation = new WorkerRequest.Cancellation();
        cancellation.cancel();
        WorkerRequest early = request(); cancellation.attach(early);
        check("cancelled".equals(early.await(SystemClock.elapsedRealtime() + 1000, timeout()).getString("status")), "cancel before registration");
        cancellation.detach(early); cancellation.reset();
        WorkerRequest blocked = request(); cancellation.attach(blocked);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<JSONObject> observed = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try { observed.set(blocked.await(SystemClock.elapsedRealtime() + 30000, timeout())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        waiter.start(); check(entered.await(1, TimeUnit.SECONDS), "waiter started");
        cancellation.cancel(); waiter.join(1000);
        check(!waiter.isAlive() && observed.get() != null && "cancelled".equals(observed.get().getString("status")), "cancel directly wakes long wait");
        cancellation.detach(blocked); cancellation.reset();
        WorkerRequest timed = request(); cancellation.attach(timed);
        check("timeout".equals(timed.await(SystemClock.elapsedRealtime() + 20, timeout()).getString("status")), "deadline returns timeout");
        cancellation.detach(timed);
        AtomicBoolean lateSideEffect = new AtomicBoolean();
        check(!timed.complete(MediaClient.failure("interrupted", "late"), () -> lateSideEffect.set(true)) && !lateSideEffect.get(), "late lifecycle callback discarded");
        WorkerRequest next = request(); cancellation.attach(next);
        blocked.complete(MediaClient.failure("failed", "old death"));
        check(!next.isComplete(), "old request cannot complete new request");
        next.complete(MediaClient.failure("completed", "next"));
        check("completed".equals(next.await(SystemClock.elapsedRealtime() + 1000, timeout()).getString("status")), "restart accepts fresh result");
        cancellation.detach(next);
        for (int i = 0; i < 30; i++) {
            cancellation.reset(); WorkerRequest race = request(); cancellation.attach(race);
            CountDownLatch go = new CountDownLatch(1); AtomicBoolean winner = new AtomicBoolean();
            Thread callback = new Thread(() -> {
                try { go.await(); race.complete(MediaClient.failure("completed", "race"), () -> winner.set(true)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            callback.start(); go.countDown(); cancellation.cancel();
            JSONObject result = race.await(SystemClock.elapsedRealtime() + 1000, timeout());
            callback.join(1000); check(!callback.isAlive(), "result/cancel race finishes");
            check("completed".equals(result.getString("status")) == winner.get(), "winner side effects completed before wake");
            cancellation.detach(race);
        }
        WorkerRequest death = request(); death.complete(MediaClient.failure("failed", "worker death"));
        check("failed".equals(death.await(SystemClock.elapsedRealtime() + 1000, timeout()).getString("status")), "death wakes waiter");
    }
    private static void temperatures() throws Exception {
        AtomicInteger reads = new AtomicInteger(); HashSet<Integer> tids = new HashSet<>();
        AtomicBoolean background = new AtomicBoolean(true);
        TemperatureTrace session = new TemperatureTrace(() -> {
            synchronized (tids) { tids.add(Process.myTid()); }
            if (Process.getThreadPriority(Process.myTid()) < Process.THREAD_PRIORITY_BACKGROUND) background.set(false);
            int n = reads.incrementAndGet();
            return new TemperatureTrace.Point(n, n % 2 == 0, n == 1 ? 0 : n == 2 ? 1200 : 300 + n);
        }, 40);
        JSONObject whole;
        try {
            TemperatureTrace stage = session.beginStage();
            SystemClock.sleep(130);
            JSONObject part = stage.finish(); int atFinish = reads.get();
            check(stage.finish() == part && reads.get() == atFinish, "stage finish idempotent");
            TemperatureTrace second = session.beginStage(); SystemClock.sleep(90); JSONObject other = second.finish();
            whole = session.finish();
            JSONArray all = whole.getJSONArray("samples"), one = part.getJSONArray("samples"), two = other.getJSONArray("samples");
            check(all.length() == reads.get(), "session stores each physical read once");
            check(one.length() >= 2 && two.length() >= 2, "stage boundary and periodic points retained");
            HashSet<Long> timestamps = new HashSet<>();
            for (int i = 0; i < all.length(); i++) timestamps.add(all.getJSONObject(i).getLong("timestamp_ms"));
            for (JSONArray slice : new JSONArray[]{one, two}) for (int i = 0; i < slice.length(); i++)
                check(timestamps.contains(slice.getJSONObject(i).getLong("timestamp_ms")), "stage points come from session source");
            check(one.getJSONObject(one.length() - 1).getLong("timestamp_ms") < two.getJSONObject(0).getLong("timestamp_ms"), "adjacent stages do not include another stage's points");
            check(!all.getJSONObject(0).has("tenths_c") && !all.getJSONObject(1).has("tenths_c"), "invalid temperatures omitted");
            check(whole.getInt("sampled_max_tenths_c") == 300 + reads.get() && whole.getInt("delta_tenths_c") == reads.get() - 3, "summary preserves valid start/end/max");
            check(tids.size() == 1 && background.get(), "single low-priority source handles boundaries and ticks");
        } finally { session.close(); }
        int stopped = reads.get(); SystemClock.sleep(130);
        check(reads.get() == stopped, "no periodic reads after finish");
        TemperatureTrace missing = new TemperatureTrace(() -> { throw new IllegalStateException("sensor unavailable"); }, 40);
        try { check(!missing.finish().getBoolean("available"), "sensor failure cannot fail measurement"); }
        finally { missing.close(); }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
