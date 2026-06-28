package com.example.lemm;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;

import static com.example.lemm.DbSchema.*;

/** Saved CAD drawings in the {@code drawings} table. */
class DrawingDao {
    private final SQLiteOpenHelper helper;
    DrawingDao(SQLiteOpenHelper helper) { this.helper = helper; }

    void addDrawing(String user, String name, String data) {
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_USERNAME, user); v.put(KEY_DRW_NAME, name); v.put(KEY_DRW_DATA, data);
        helper.getWritableDatabase().insert(TABLE_DRAWINGS, null, v);
    }

    void addDrawingWithDate(String user, String name, String data, String date) {
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_USERNAME, user); v.put(KEY_DRW_NAME, name);
        v.put(KEY_DRW_DATA, data); v.put(KEY_DRW_DATE, date);
        v.put(KEY_SYNCED, 0); // local-only until the cloud write confirms
        helper.getWritableDatabase().insert(TABLE_DRAWINGS, null, v);
    }

    void markDrawingSynced(String user, String date) {
        ContentValues v = new ContentValues();
        v.put(KEY_SYNCED, 1);
        helper.getWritableDatabase().update(TABLE_DRAWINGS, v,
                KEY_DRW_USERNAME + " = ? AND " + KEY_DRW_DATE + " = ?", new String[]{user, date});
    }

    void updateDrawing(int id, String name, String data) {
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_NAME, name);
        v.put(KEY_DRW_DATA, data);
        helper.getWritableDatabase().update(TABLE_DRAWINGS, v, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    void renameDrawing(int id, String newName) {
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_NAME, newName);
        helper.getWritableDatabase().update(TABLE_DRAWINGS, v, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    void deleteDrawing(int id) {
        helper.getWritableDatabase().delete(TABLE_DRAWINGS, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    Cursor getDrawings(String user) {
        return helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + TABLE_DRAWINGS + " WHERE " + KEY_DRW_USERNAME + " = ? ORDER BY " + KEY_DRW_DATE + " DESC",
                new String[]{user});
    }

    void clearFor(String user) {
        helper.getWritableDatabase().delete(TABLE_DRAWINGS, KEY_DRW_USERNAME + " = ?", new String[]{user});
    }
}
