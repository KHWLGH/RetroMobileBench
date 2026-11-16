package com.retromobilebench;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Retro-compatible file exporter:
 * - API 8-28: writes to public Download/RetroMobileBench/ (with MediaScanner notification for MTP/UMS),
 *   gracefully falling back to app external dir / cache dir when SD card is unmounted or permission denied.
 * - API 29+: writes to MediaStore.Downloads/RetroMobileBench/ without storage permission.
 * - Share intent uses file:// on API 8-23 external files (for legacy Bluetooth/Email/File managers)
 *   and content:// via ExportFileProvider on API 24+ or internal files.
 */
final class ExportManager {
    static final String EXPORT_SUBDIR = "RetroMobileBench";

    static final class ExportResult {
        final boolean success;
        final String fileName;
        final String displayPath;
        final String storageNote;
        final Uri shareUri;
        final String mimeType;
        final String error;

        ExportResult(boolean success, String fileName, String displayPath, String storageNote,
                     Uri shareUri, String mimeType, String error) {
            this.success = success;
            this.fileName = fileName;
            this.displayPath = displayPath;
            this.storageNote = storageNote;
            this.shareUri = shareUri;
            this.mimeType = mimeType;
            this.error = error;
        }
    }

    private ExportManager() { }

    @SuppressWarnings("deprecation")
    static ExportResult exportToFile(Context context, JSONObject report, ReportExporter.Format format) {
        String fileName = ReportExporter.defaultFileName(report, format);
        try {
            byte[] data = ReportExporter.exportBytes(report, format);
            File shareCopy = writeFile(new File(ExportFileProvider.shareDir(context), fileName), data);
            Uri providerUri = ExportFileProvider.contentUriFor(fileName);

            if (Build.VERSION.SDK_INT >= 29) {
                Uri msUri = PlatformCompat.saveToMediaStoreDownloads(context, EXPORT_SUBDIR, fileName, format.mimeType, data);
                if (msUri != null) {
                    return new ExportResult(true, fileName,
                            "Download/" + EXPORT_SUBDIR + "/" + fileName,
                            "已保存至系统下载目录（Android 10+ MediaStore）",
                            providerUri, format.mimeType, null);
                }
            }

            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                    && PlatformCompat.hasWriteExternalStorage(context)) {
                try {
                    File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), EXPORT_SUBDIR);
                    if (pubDir.isDirectory() || pubDir.mkdirs()) {
                        File target = writeFile(new File(pubDir, fileName), data);
                        MediaScannerConnection.scanFile(context.getApplicationContext(),
                                new String[]{target.getAbsolutePath()}, new String[]{format.mimeType}, null);
                        Uri shareUri = Build.VERSION.SDK_INT >= 24 ? providerUri : Uri.fromFile(target);
                        return new ExportResult(true, fileName, target.getAbsolutePath(),
                                "已保存至公共下载目录，可通过文件管理或 USB/MTP 直接读取",
                                shareUri, format.mimeType, null);
                    }
                } catch (IOException | SecurityException ignored) {
                    // Fall through to app external directory.
                }
            }

            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                try {
                    File extBase = context.getExternalFilesDir(null);
                    if (extBase != null) {
                        File extDir = new File(extBase, "exports");
                        if (extDir.isDirectory() || extDir.mkdirs()) {
                            File target = writeFile(new File(extDir, fileName), data);
                            MediaScannerConnection.scanFile(context.getApplicationContext(),
                                    new String[]{target.getAbsolutePath()}, new String[]{format.mimeType}, null);
                            Uri shareUri = Build.VERSION.SDK_INT >= 24 ? providerUri : Uri.fromFile(target);
                            return new ExportResult(true, fileName, target.getAbsolutePath(),
                                    "公共目录不可写，已保存至应用外部存储目录",
                                    shareUri, format.mimeType, null);
                        }
                    }
                } catch (IOException | SecurityException ignored) {
                    // Fall through to internal share cache.
                }
            }

            return new ExportResult(true, fileName, shareCopy.getAbsolutePath(),
                    "外部存储不可用（如 U 盘挂载中或无权限），已保存至应用导出缓存，建议立即通过系统分享发送",
                    providerUri, format.mimeType, null);
        } catch (Exception e) {
            return new ExportResult(false, fileName, "", "", null, format.mimeType, e.toString());
        }
    }

    static void writeToUri(Context context, Uri uri, JSONObject report, ReportExporter.Format format) throws Exception {
        byte[] data = ReportExporter.exportBytes(report, format);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("无法打开目标文件输出流");
            out.write(data);
            out.flush();
        }
    }

    static Intent buildShareIntent(ExportResult result) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(result.mimeType);
        send.putExtra(Intent.EXTRA_SUBJECT, "RetroMobileBench 测试报告 - " + result.fileName);
        send.putExtra(Intent.EXTRA_STREAM, result.shareUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, "发送或分享导出文件");
    }

    private static File writeFile(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建导出目录：" + parent);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
            out.flush();
        }
        return target;
    }
}
