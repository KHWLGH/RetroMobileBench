package com.retromobilebench;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

/** Use actual file measurements with real SQLite state and injected history write failures. */
final class PersistenceFailureChecks {
    static void run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        for (boolean beginFailure : new boolean[]{true, false}) {
            ResultStore store = new ResultStore(context, null) {
                @Override long begin(JSONObject report) throws org.json.JSONException {
                    if (beginFailure) throw new android.database.sqlite.SQLiteException("injected begin failure");
                    return super.begin(report);
                }
                @Override void save(long id, JSONObject report) { throw new android.database.sqlite.SQLiteException("injected save failure"); }
            };
            BenchmarkRunner runner = new BenchmarkRunner((Application)context.getApplicationContext(), store);
            AtomicReference<BenchmarkRunner.State> observed = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> runner.attach(observed::set));
            try {
                waitIdle(observed);
                instrumentation.runOnMainSync(() -> runner.startStorage(false));
                waitIdle(observed);
                JSONObject report = new JSONObject(observed.get().exportJson);
                check("completed".equals(report.optString("status")), "history failure cannot invalidate measurement: " + report);
                check(report.getJSONArray("samples").getJSONObject(0).getInt("completed_metrics") == 4, "measurements retained after write failure");
                check(report.has("persistence_warning") && observed.get().exportJson.length() > 100, "unsaved complete result can be exported");
            } finally { instrumentation.runOnMainSync(runner::closeAfterChecks); }
        }
    }
    private static void waitIdle(AtomicReference<BenchmarkRunner.State> state) {
        long until = SystemClock.elapsedRealtime() + 60000;
        while (state.get() == null || state.get().busy) {
            if (SystemClock.elapsedRealtime() > until) throw new AssertionError("persistence test worker deadline");
            SystemClock.sleep(50);
        }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
