package com.retromobilebench;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Zero-dependency read-only ContentProvider for sharing exported files on API 24+ and internal fallback. */
public final class ExportFileProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".export";

    static File shareDir(Context context) {
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static Uri contentUriFor(String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("exports")
                .appendPath(fileName)
                .build();
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolveSafeFile(uri);
        if (file == null || !file.isFile()) return new MatrixCursor(new String[0]);
        String[] cols = projection != null && projection.length > 0
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = file.length();
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read-only provider");
        File file = resolveSafeFile(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("Exported file not found: " + uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    private File resolveSafeFile(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null) return null;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 2 || !"exports".equals(segments.get(0))) return null;
        String name = segments.get(1);
        if (name == null || name.length() == 0 || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        File root = shareDir(context);
        try {
            File canonicalRoot = root.getCanonicalFile();
            File target = new File(canonicalRoot, name).getCanonicalFile();
            if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) return null;
            return target;
        } catch (IOException e) {
            return null;
        }
    }
}
