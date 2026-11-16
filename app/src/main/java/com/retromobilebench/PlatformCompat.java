package com.retromobilebench;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;

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

    static boolean hasWriteExternalStorage(Context context) {
        if (Build.VERSION.SDK_INT >= 29) return true;
        if (Build.VERSION.SDK_INT >= 23) return Api23.hasWriteExternalStorage(context);
        return true;
    }

    static boolean shouldRequestWriteExternalStorage(Context context) {
        return Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && !Api23.hasWriteExternalStorage(context);
    }

    static void requestWriteExternalStorage(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= 23) Api23.requestWriteExternalStorage(activity, requestCode);
    }

    static Intent createDocumentIntent(String mimeType, String fileName) {
        return Build.VERSION.SDK_INT >= 19 ? Api19.createDocumentIntent(mimeType, fileName) : null;
    }

    static Uri saveToMediaStoreDownloads(Context context, String subDir, String fileName, String mimeType, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT < 29) return null;
        return Api29.saveToDownloads(context, subDir, fileName, mimeType, data);
    }

    @TargetApi(11)
    private static final class Api11 {
        static void selectable(TextView view) { view.setTextIsSelectable(true); }
        static boolean changingConfigurations(Activity activity) { return activity.isChangingConfigurations(); }
    }

    @TargetApi(16)
    private static final class Api16 {
        static long totalMemory(ActivityManager.MemoryInfo memory) { return memory.totalMem; }
    }

    @TargetApi(19)
    private static final class Api19 {
        static Intent createDocumentIntent(String mimeType, String fileName) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            return intent;
        }
    }

    @TargetApi(23)
    private static final class Api23 {
        static boolean hasWriteExternalStorage(Context context) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        static void requestWriteExternalStorage(Activity activity, int requestCode) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
        }
    }

    @TargetApi(29)
    private static final class Api29 {
        static Uri saveToDownloads(Context context, String subDir, String fileName, String mimeType, byte[] data) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            String rel = Environment.DIRECTORY_DOWNLOADS + (subDir != null && subDir.length() > 0 ? "/" + subDir : "");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore.Downloads 创建文件失败");
            boolean ok = false;
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法打开 MediaStore 输出流");
                    out.write(data);
                    out.flush();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                ok = true;
                return uri;
            } finally {
                if (!ok) {
                    try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
                }
            }
        }
    }
}
