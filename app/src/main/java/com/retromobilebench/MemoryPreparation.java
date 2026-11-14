package com.retromobilebench;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;

/** Best-effort app-heap collection, always outside benchmark measurement intervals. */
final class MemoryPreparation {
    static JSONObject collect(Context context) throws Exception {
        long start = SystemClock.elapsedRealtime();
        ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo(), after = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(before);
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.gc();
        if (manager != null) manager.getMemoryInfo(after);
        return new JSONObject().put("method", "app_gc_request").put("best_effort", true)
                .put("available_before_bytes", manager == null ? JSONObject.NULL : before.availMem)
                .put("available_after_bytes", manager == null ? JSONObject.NULL : after.availMem)
                .put("heap_used_before_bytes", heapBefore)
                .put("heap_used_after_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("elapsed_ms", SystemClock.elapsedRealtime() - start);
    }
}
