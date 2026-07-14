package com.example.lemm;

import android.database.Cursor;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Pushes local history + drawings up to {@code users/{uid}}.
 *
 * <p><b>Why this was rewritten.</b> It used to write to {@code users/{sanitizedUsername}} and, before
 * doing so, called {@link Social#claimUsername} to take the claim that the rules require to prove
 * ownership of that node. But the claim table is server-only and the backend is not deployed, so the
 * claim could never be granted — and {@code claimUsername} reported "backend unreachable" as
 * <i>success</i>. The push therefore ran, the server rejected every write, and the only trace was a
 * {@code Log.e}. Nothing ever reached the cloud, and the local SQLite copy was the only copy of the
 * user's work. Uninstalling the app destroyed it.
 *
 * <p>Two rules now, and they are the whole lesson:
 * <ol>
 *   <li><b>Ownership must be provable from the request itself.</b> {@code users/{uid}} satisfies
 *       {@code $key === auth.uid} with no table, no backend and no network hop.</li>
 *   <li><b>A failed backup is never silent.</b> Every write reports through {@link SyncCallback}, so
 *       the UI can tell the user their work is not backed up instead of pretending it is.</li>
 * </ol>
 */
public class CloudSyncManager {

    private static final String TAG = "CloudSync";

    /** Reports the outcome of a sync so the UI can be honest about it. */
    public interface SyncCallback {
        /** @param pushed how many rows reached the cloud */
        void onSynced(int pushed);
        /** The server refused, or we are offline. The work exists ONLY on this device. */
        void onFailed(String reason);
    }

    public static void syncLocalToCloud(DatabaseHelper db, String username) {
        syncLocalToCloud(db, username, null);
    }

    public static void syncLocalToCloud(DatabaseHelper db, String username, SyncCallback cb) {
        if (username == null || username.isEmpty() || username.startsWith("GuestUser")) {
            if (cb != null) cb.onFailed("guest");
            return;
        }
        // No Firebase account => no node to own => nothing to sync to. Bail rather than fall back to
        // a username-keyed path the server is guaranteed to reject.
        DatabaseReference userRef = FirebaseManager.getUserRef();
        if (userRef == null) {
            Log.w(TAG, "not signed in — local only");
            if (cb != null) cb.onFailed("signed-out");
            return;
        }
        doSync(db, username, userRef, cb);
    }

    private static void doSync(DatabaseHelper db, String username,
                              DatabaseReference userRef, SyncCallback cb) {
        // Off the main thread: this reads the whole local DB.
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Object> drawingsBatch = new HashMap<>();
                List<String> drawingDates = new ArrayList<>();

                Cursor drawCursor = db.getDrawings(username);
                if (drawCursor != null && drawCursor.moveToFirst()) {
                    do {
                        HashMap<String, Object> map = new HashMap<>();
                        map.put("title", drawCursor.getString(drawCursor.getColumnIndexOrThrow("name")));
                        map.put("data", drawCursor.getString(drawCursor.getColumnIndexOrThrow("data")));
                        String date = drawCursor.getString(drawCursor.getColumnIndexOrThrow("date"));
                        if (date == null) continue; // skip rather than NPE and abort the whole batch
                        map.put("date", date);
                        drawingsBatch.put(cloudKey(date), map);
                        drawingDates.add(date);
                    } while (drawCursor.moveToNext());
                    drawCursor.close();
                }

                Map<String, Object> historyBatch = new HashMap<>();
                List<String> historyDates = new ArrayList<>();

                Cursor histCursor = db.getHistory(username);
                if (histCursor != null && histCursor.moveToFirst()) {
                    do {
                        HashMap<String, Object> map = new HashMap<>();
                        map.put("title", histCursor.getString(histCursor.getColumnIndexOrThrow("name")));
                        map.put("problem", histCursor.getString(histCursor.getColumnIndexOrThrow("problem")));
                        map.put("raw_response", histCursor.getString(histCursor.getColumnIndexOrThrow("raw_response")));
                        String date = histCursor.getString(histCursor.getColumnIndexOrThrow("date"));
                        if (date == null) continue;
                        map.put("date", date);
                        historyBatch.put(cloudKey(date), map);
                        historyDates.add(date);
                    } while (histCursor.moveToNext());
                    histCursor.close();
                }

                final int total = drawingDates.size() + historyDates.size();
                if (total == 0) {
                    // Nothing local to push. This is the fresh-install case: do NOT touch the cloud.
                    // updateChildren() with an empty map is a no-op, but being explicit here is what
                    // stops someone "optimising" this into a setValue() that wipes the backup.
                    if (cb != null) cb.onSynced(0);
                    return;
                }

                // updateChildren() MERGES. Never setValue() on these nodes: a device whose local DB is
                // empty (a reinstall, before the pull lands) would overwrite the cloud with nothing.
                if (!drawingsBatch.isEmpty()) {
                    userRef.child("drawings").updateChildren(drawingsBatch)
                            .addOnSuccessListener(x -> {
                                for (String d : drawingDates) db.markDrawingSynced(username, d);
                                Log.d(TAG, "pushed " + drawingDates.size() + " drawings");
                            })
                            .addOnFailureListener(e -> fail(cb, "drawings: " + e.getMessage()));
                }

                if (!historyBatch.isEmpty()) {
                    userRef.child("history").updateChildren(historyBatch)
                            .addOnSuccessListener(x -> {
                                for (String d : historyDates) db.markHistorySynced(username, d);
                                Log.d(TAG, "pushed " + historyDates.size() + " solutions");
                                if (cb != null) cb.onSynced(total);
                            })
                            .addOnFailureListener(e -> fail(cb, "history: " + e.getMessage()));
                } else if (cb != null) {
                    cb.onSynced(total);
                }
            } catch (Exception e) {
                fail(cb, String.valueOf(e.getMessage()));
            }
        });
    }

    private static void fail(SyncCallback cb, String reason) {
        Log.e(TAG, "SYNC FAILED — work is on this device only: " + reason);
        if (cb != null) cb.onFailed(reason);
    }

    /** Firebase keys may not contain {@code . # $ / [ ]}, so the timestamp is stripped to alphanumerics. */
    static String cloudKey(String date) {
        return date.replaceAll("[^a-zA-Z0-9]", "");
    }
}
