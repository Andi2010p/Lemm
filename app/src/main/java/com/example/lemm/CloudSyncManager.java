package com.example.lemm;

import android.database.Cursor;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;

public class CloudSyncManager {

    // UPLOAD: Push local SQLite data to Firebase
    public static void syncLocalToCloud(DatabaseHelper db, String username) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        DatabaseReference userRef = FirebaseDatabase.getInstance().getReference("users").child(user.getUid());

        // 1. Sync Drawings
        Cursor drawCursor = db.getDrawings(username);
        if (drawCursor != null && drawCursor.moveToFirst()) {
            do {
                HashMap<String, Object> map = new HashMap<>();
                map.put("name", drawCursor.getString(drawCursor.getColumnIndexOrThrow("name")));
                map.put("data", drawCursor.getString(drawCursor.getColumnIndexOrThrow("data")));
                map.put("date", drawCursor.getString(drawCursor.getColumnIndexOrThrow("date")));

                String cloudKey = drawCursor.getString(drawCursor.getColumnIndexOrThrow("date")).replaceAll("[^a-zA-Z0-9]", "");
                userRef.child("drawings").child(cloudKey).setValue(map);
            } while (drawCursor.moveToNext());
            drawCursor.close();
        }

        // 2. Sync Solutions (History)
        Cursor histCursor = db.getHistory(username);
        if (histCursor != null && histCursor.moveToFirst()) {
            do {
                HashMap<String, Object> map = new HashMap<>();
                map.put("name", histCursor.getString(histCursor.getColumnIndexOrThrow("name")));
                map.put("problem", histCursor.getString(histCursor.getColumnIndexOrThrow("problem")));
                map.put("raw_response", histCursor.getString(histCursor.getColumnIndexOrThrow("raw_response")));
                map.put("date", histCursor.getString(histCursor.getColumnIndexOrThrow("date")));

                String cloudKey = histCursor.getString(histCursor.getColumnIndexOrThrow("date")).replaceAll("[^a-zA-Z0-9]", "");
                userRef.child("history").child(cloudKey).setValue(map);
            } while (histCursor.moveToNext());
            histCursor.close();
        }
    }
}