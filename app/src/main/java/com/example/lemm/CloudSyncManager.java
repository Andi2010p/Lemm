package com.example.lemm;

import android.database.Cursor;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;

public class CloudSyncManager {

    public static void syncLocalToCloud(DatabaseHelper db, String username) {
        if (username == null || username.isEmpty() || username.startsWith("GuestUser_")) return;

        DatabaseReference userRef = FirebaseManager.getUserRef(username);

        Cursor drawCursor = db.getDrawings(username);
        if (drawCursor != null && drawCursor.moveToFirst()) {
            do {
                HashMap<String, Object> map = new HashMap<>();
                map.put("title", drawCursor.getString(drawCursor.getColumnIndexOrThrow("name")));
                map.put("data", drawCursor.getString(drawCursor.getColumnIndexOrThrow("data")));

                String date = drawCursor.getString(drawCursor.getColumnIndexOrThrow("date"));
                map.put("date", date);

                String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");
                userRef.child("drawings").child(cloudKey).setValue(map);
            } while (drawCursor.moveToNext());
            drawCursor.close();
        }

        Cursor histCursor = db.getHistory(username);
        if (histCursor != null && histCursor.moveToFirst()) {
            do {
                HashMap<String, Object> map = new HashMap<>();
                map.put("title", histCursor.getString(histCursor.getColumnIndexOrThrow("name")));
                map.put("problem", histCursor.getString(histCursor.getColumnIndexOrThrow("problem")));
                map.put("raw_response", histCursor.getString(histCursor.getColumnIndexOrThrow("raw_response")));

                String date = histCursor.getString(histCursor.getColumnIndexOrThrow("date"));
                map.put("date", date);

                String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");
                userRef.child("history").child(cloudKey).setValue(map);
            } while (histCursor.moveToNext());
            histCursor.close();
        }
    }
}