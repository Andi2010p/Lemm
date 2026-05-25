package com.example.lemm;

import android.database.Cursor;
import com.google.firebase.database.DatabaseReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class CloudSyncManager {

    public static void syncLocalToCloud(DatabaseHelper db, String username) {
        if (username == null || username.isEmpty() || username.startsWith("GuestUser")) return;

        // Move to a background thread so it NEVER freezes your login screen!
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DatabaseReference userRef = FirebaseManager.getUserRef(username);
                Map<String, Object> drawingsBatch = new HashMap<>();
                List<String> drawingDates = new ArrayList<>();

                // Gather drawings
                Cursor drawCursor = db.getDrawings(username);
                if (drawCursor != null && drawCursor.moveToFirst()) {
                    do {
                        HashMap<String, Object> map = new HashMap<>();
                        map.put("title", drawCursor.getString(drawCursor.getColumnIndexOrThrow("name")));
                        map.put("data", drawCursor.getString(drawCursor.getColumnIndexOrThrow("data")));
                        String date = drawCursor.getString(drawCursor.getColumnIndexOrThrow("date"));
                        if (date == null) continue; // skip rather than NPE and abort the whole batch
                        map.put("date", date);

                        String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");
                        drawingsBatch.put(cloudKey, map);
                        drawingDates.add(date);
                    } while (drawCursor.moveToNext());
                    drawCursor.close();
                }

                // Upload all drawings as a single, ultra-fast batch write
                if (!drawingsBatch.isEmpty()) {
                    userRef.child("drawings").updateChildren(drawingsBatch)
                            .addOnSuccessListener(x -> {
                                for (String d : drawingDates) db.markDrawingSynced(username, d);
                                android.util.Log.d("CloudSync", "Pushed " + drawingDates.size() + " drawings to cloud");
                            })
                            .addOnFailureListener(e -> android.util.Log.e("CloudSync", "Drawings push failed: " + e.getMessage()));
                }

                Map<String, Object> historyBatch = new HashMap<>();
                List<String> historyDates = new ArrayList<>();
                // Gather solutions
                Cursor histCursor = db.getHistory(username);
                if (histCursor != null && histCursor.moveToFirst()) {
                    do {
                        HashMap<String, Object> map = new HashMap<>();
                        map.put("title", histCursor.getString(histCursor.getColumnIndexOrThrow("name")));
                        map.put("problem", histCursor.getString(histCursor.getColumnIndexOrThrow("problem")));
                        map.put("raw_response", histCursor.getString(histCursor.getColumnIndexOrThrow("raw_response")));
                        String date = histCursor.getString(histCursor.getColumnIndexOrThrow("date"));
                        if (date == null) continue; // skip rather than NPE and abort the whole batch
                        map.put("date", date);

                        String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");
                        historyBatch.put(cloudKey, map);
                        historyDates.add(date);
                    } while (histCursor.moveToNext());
                    histCursor.close();
                }

                // Upload all solutions as a single, ultra-fast batch write
                if (!historyBatch.isEmpty()) {
                    userRef.child("history").updateChildren(historyBatch)
                            .addOnSuccessListener(x -> {
                                for (String d : historyDates) db.markHistorySynced(username, d);
                                android.util.Log.d("CloudSync", "Pushed " + historyDates.size() + " solutions to cloud");
                            })
                            .addOnFailureListener(e -> android.util.Log.e("CloudSync", "History push failed: " + e.getMessage()));
                }

                android.util.Log.d("CloudSync", "✅ Background sync completed successfully!");
            } catch (Exception e) {
                android.util.Log.e("CloudSync", "Background sync failed: " + e.getMessage());
            }
        });
    }
}