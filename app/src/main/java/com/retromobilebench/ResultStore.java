package com.retromobilebench;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local history, not a database benchmark. All methods run on the single worker. */
final class ResultStore extends SQLiteOpenHelper {
    ResultStore(Context context) {
        this(context, "benchmark-results.db");
    }

    // A null name gives instrumented tests a real API 16 in-memory SQLite database.
    ResultStore(Context context, String name) {
        super(context, name, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runs (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "started_ms INTEGER NOT NULL, status TEXT NOT NULL, report_json TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("No migration from " + oldVersion + " to " + newVersion);
    }

    long begin(JSONObject report) throws JSONException {
        ContentValues values = values(report);
        values.put("started_ms", report.getLong("started_ms"));
        return getWritableDatabase().insertOrThrow("runs", null, values);
    }

    void save(long id, JSONObject report) throws JSONException {
        int updated = getWritableDatabase().update("runs", values(report), "_id = ?",
                new String[]{Long.toString(id)});
        if (updated != 1) throw new SQLiteException("Run missing: " + id);
    }

    private static ContentValues values(JSONObject report) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("status", report.getString("status"));
        values.put("report_json", report.toString());
        return values;
    }

    /** Process death cannot leave a run looking successful or permanently active. */
    void recoverInterrupted() throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor cursor = db.query("runs", new String[]{"_id", "report_json"}, "status = ?",
                    new String[]{"running"}, null, null, null);
            try {
                while (cursor.moveToNext()) {
                    JSONObject report = new JSONObject(cursor.getString(1));
                    report.put("status", "interrupted");
                    report.put("ended_ms", System.currentTimeMillis());
                    report.put("message", "进程退出，测试中断；已完成的轮次已保留");
                    save(cursor.getLong(0), report);
                }
            } finally {
                cursor.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    JSONArray latest(int limit) throws JSONException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        JSONArray reports = new JSONArray();
        Cursor cursor = getReadableDatabase().query("runs", new String[]{"_id", "report_json"},
                null, null, null, null, "_id DESC", Integer.toString(limit));
        try {
            while (cursor.moveToNext()) {
                JSONObject report = new JSONObject(cursor.getString(1));
                report.put("id", cursor.getLong(0));
                reports.put(report);
            }
        } finally {
            cursor.close();
        }
        return reports;
    }
}
