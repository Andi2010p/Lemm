package com.example.lemm;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single data-access point for History (the Repository in the MVVM stack). Wraps {@link DatabaseHelper}
 * so the UI/ViewModel never touch Cursors or SQL directly.
 */
public class HistoryRepository {
    private final DatabaseHelper db;

    public HistoryRepository(DatabaseHelper db) { this.db = db; }

    /** Loads a user's saved solutions (or drawings), newest first. */
    public List<HistoryRecord> load(String user, boolean solutions) {
        List<HistoryRecord> out = new ArrayList<>();
        Cursor cursor = solutions ? db.getHistory(user) : db.getDrawings(user);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    do {
                        if (solutions) {
                            String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("hist_id")));
                            String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                            String prob = cursor.getString(cursor.getColumnIndexOrThrow("problem"));
                            String raw = cursor.getString(cursor.getColumnIndexOrThrow("raw_response"));
                            String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                            out.add(new HistoryRecord(id, title, prob, raw, date));
                        } else {
                            String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("drw_id")));
                            String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                            String data = cursor.getString(cursor.getColumnIndexOrThrow("data"));
                            String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                            out.add(new HistoryRecord(id, title, "Date: " + date, data, date));
                        }
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        }
        Collections.sort(out, (a, b) -> b.date.compareTo(a.date));
        return out;
    }
}
