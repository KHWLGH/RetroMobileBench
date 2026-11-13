package com.retromobilebench;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Activity;
import android.os.Build;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.FileReader;

/** Keep newer framework references out of classes verified by old Dalvik. */
final class PlatformCompat {
    private PlatformCompat() { }

    static void selectable(TextView view) {
        if (Build.VERSION.SDK_INT >= 11) Api11.selectable(view);
    }

    static boolean changingConfigurations(Activity activity) {
        return Build.VERSION.SDK_INT >= 11 ? Api11.changingConfigurations(activity)
                : activity.getChangingConfigurations() != 0;
    }

    static long totalMemory(ActivityManager.MemoryInfo memory) {
        if (Build.VERSION.SDK_INT >= 16) return Api16.totalMemory(memory);
        try (Reader reader = new FileReader("/proc/meminfo")) {
            return parseTotalMemory(reader);
        } catch (IOException | SecurityException ignored) {
            return -1;
        }
    }

    static long parseTotalMemory(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        for (int count = 0; count < 256; count++) {
            String line = reader.readLine();
            if (line == null) break;
            if (!line.startsWith("MemTotal:")) continue;
            String[] fields = line.substring(9).trim().split("\\s+");
            if (fields.length != 2 || !"kB".equals(fields[1])) return -1;
            try {
                long kib = Long.parseLong(fields[0]);
                return kib > 0 && kib <= Long.MAX_VALUE / 1024 ? kib * 1024 : -1;
            } catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    @TargetApi(11)
    private static final class Api11 {
        static void selectable(TextView view) { view.setTextIsSelectable(true); }
        static boolean changingConfigurations(Activity activity) { return activity.isChangingConfigurations(); }
    }

    @TargetApi(16)
    private static final class Api16 {
        static boolean changingConfigurations(Activity activity) {
        return Build.VERSION.SDK_INT >= 11 ? Api11.changingConfigurations(activity)
                : activity.getChangingConfigurations() != 0;
    }

    static long totalMemory(ActivityManager.MemoryInfo memory) { return memory.totalMem; }
    }
}
