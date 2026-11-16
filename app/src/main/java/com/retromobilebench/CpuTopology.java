package com.retromobilebench;

import org.json.JSONObject;

/** Present/configured CPUs include cores temporarily offlined by old hotplug governors. */
final class CpuTopology {
    static final int CAPACITY = 64; // Shared with native worker_limits.h; reject rather than silently truncate.
    static int count(String mask) {
        try {
            boolean[] seen = new boolean[4096];
            int count = 0;
            for (String part : mask.trim().split(",")) {
                String[] range = part.split("-", -1);
                if (range.length > 2) return 0;
                int first = Integer.parseInt(range[0]);
                int last = range.length == 2 ? Integer.parseInt(range[1]) : first;
                if (first < 0 || last < first || last >= seen.length) return 0;
                for (int i = first; i <= last; i++) if (!seen[i]) { seen[i] = true; count++; }
            }
            return count;
        } catch (RuntimeException ignored) { return 0; }
    }
    static JSONObject enrich(JSONObject device) throws Exception { return enrich(device, true); }

    static JSONObject enrich(JSONObject device, boolean includeNative) throws Exception {
        if (includeNative) {
            device.put("configured_processors", NativeBridge.configuredProcessors());
        }
        device.put("cpu_present", line("/sys/devices/system/cpu/present"));
        device.put("cpu_possible", line("/sys/devices/system/cpu/possible"));
        java.io.File[] directories = new java.io.File("/sys/devices/system/cpu").listFiles(
                file -> file.isDirectory() && file.getName().matches("cpu[0-9]+"));
        device.put("cpu_directory_count", directories == null ? 0 : directories.length);
        device.put("selected_workers", workers(device));
        device.put("worker_capacity", CAPACITY);
        return device;
    }
    private static String line(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) { return r.readLine(); }
        catch (Exception ignored) { return "unknown"; }
    }
    static int workers(JSONObject device) {
        int present = Math.max(count(device.optString("cpu_present")), device.optInt("cpu_directory_count"));
        int observed = Math.max(count(device.optString("cpu_online")), device.optInt("available_processors", 1));
        // Some kernels report a configured capacity larger than the CPUs actually present.
        // Use configured count only when sysfs presence information is unavailable.
        return Math.max(1, Math.max(observed, present > 0 ? present : device.optInt("configured_processors")));
    }
}
