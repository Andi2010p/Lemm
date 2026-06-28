package com.example.lemm;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;

import static com.example.lemm.DbSchema.*;

/** Saved AI solutions in the {@code history} table. */
class HistoryDao {
    private final SQLiteOpenHelper helper;
    HistoryDao(SQLiteOpenHelper helper) { this.helper = helper; }

    void addHistory(String user, String name, String prob, String sol, String raw) {
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_USERNAME, user); v.put(KEY_HIST_NAME, name);
        v.put(KEY_HIST_PROBLEM, prob); v.put(KEY_HIST_SOLUTION, sol);
        v.put(KEY_HIST_RAW_RESPONSE, raw);
        helper.getWritableDatabase().insert(TABLE_HISTORY, null, v);
    }

    long addHistoryWithDate(String user, String name, String prob, String sol, String raw, String date) {
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_USERNAME, user); v.put(KEY_HIST_NAME, name);
        v.put(KEY_HIST_PROBLEM, prob); v.put(KEY_HIST_SOLUTION, sol);
        v.put(KEY_HIST_RAW_RESPONSE, raw); v.put(KEY_HIST_DATE, date);
        v.put(KEY_SYNCED, 0); // local-only until the cloud write confirms
        return helper.getWritableDatabase().insert(TABLE_HISTORY, null, v);
    }

    void markHistorySynced(String user, String date) {
        ContentValues v = new ContentValues();
        v.put(KEY_SYNCED, 1);
        helper.getWritableDatabase().update(TABLE_HISTORY, v,
                KEY_HIST_USERNAME + " = ? AND " + KEY_HIST_DATE + " = ?", new String[]{user, date});
    }

    void updateHistory(int id, String name, String prob, String sol, String raw) {
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_NAME, name); v.put(KEY_HIST_PROBLEM, prob);
        v.put(KEY_HIST_SOLUTION, sol); v.put(KEY_HIST_RAW_RESPONSE, raw);
        helper.getWritableDatabase().update(TABLE_HISTORY, v, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    void renameHistory(int id, String newName) {
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_NAME, newName);
        helper.getWritableDatabase().update(TABLE_HISTORY, v, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    void deleteHistory(int id) {
        helper.getWritableDatabase().delete(TABLE_HISTORY, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    Cursor getHistory(String user) {
        return helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + TABLE_HISTORY + " WHERE " + KEY_HIST_USERNAME + " = ? ORDER BY " + KEY_HIST_DATE + " DESC",
                new String[]{user});
    }

    void clearFor(String user) {
        helper.getWritableDatabase().delete(TABLE_HISTORY, KEY_HIST_USERNAME + " = ?", new String[]{user});
    }
}
