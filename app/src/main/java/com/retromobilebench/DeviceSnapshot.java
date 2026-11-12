package com.retromobilebench;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/** API 16-only device/measurement metadata; collect on the benchmark worker. */
final class DeviceSnapshot {
    private DeviceSnapshot() { }

    @SuppressWarnings("deprecation") // StatFs long variants and SUPPORTED_ABIS are newer than API 16.
    static JSONObject collect(Context context) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("manufacturer", Build.MANUFACTURER);
        data.put("model", Build.MODEL);
        data.put("device", Build.DEVICE);
        data.put("fingerprint", Build.FINGERPRINT);
        data.put("android_release", Build.VERSION.RELEASE);
        data.put("sdk", Build.VERSION.SDK_INT);
        data.put("abi", Build.CPU_ABI);
        data.put("abi2", Build.CPU_ABI2);
        data.put("vm", System.getProperty("java.vm.version"));
        data.put("app_version", BuildConfig.VERSION_NAME);
        data.put("app_version_code", BuildConfig.VERSION_CODE);
        data.put("native_library", NativeLoader.load());
        String identity = (Build.FINGERPRINT + " " + Build.MODEL + " " + Build.HARDWARE)
                .toLowerCase(Locale.US);
        data.put("emulator", identity.contains("generic") || identity.contains("emulator")
                || identity.contains("goldfish") || identity.contains("ranchu")
                || identity.contains("sdk_gphone"));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            data.put("available_memory_bytes", memory.availMem);
            data.put("total_memory_bytes", memory.totalMem);
            data.put("low_memory", memory.lowMemory);
        }
        data.put("available_storage_bytes", availableBytes(context.getCacheDir()));
        data.put("cpu_online", firstLine("/sys/devices/system/cpu/online"));
        data.put("cpu0_frequency_khz", firstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"));
        data.put("cpu0_max_frequency_khz", firstLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"));
        data.put("cpu0_governor", firstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) data.put("battery_percent", level * 100 / scale);
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
                data.put("battery_temperature_tenths_c", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0));
            data.put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        }
        return data;
    }

    @SuppressWarnings("deprecation")
    static long availableBytes(File directory) {
        StatFs stat = new StatFs(directory.getAbsolutePath());
        // Cast BEFORE multiplying: even a small old phone can overflow a 32-bit product.
        return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
    }

    private static String firstLine(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line = reader.readLine();
            return line == null ? "unknown" : line;
        } catch (IOException | SecurityException e) {
            return "unknown";
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }
}
