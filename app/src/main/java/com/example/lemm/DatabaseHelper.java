package com.example.lemm;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import static com.example.lemm.DbSchema.*;

/**
 * Owns the SQLite database (creation/upgrade) and exposes the same public API the rest of the app
 * already calls. The actual per-table operations live in focused DAOs ({@link UserDao},
 * {@link HistoryDao}, {@link DrawingDao}) — this class is a thin facade that delegates to them, so
 * responsibilities are separated without changing any call sites.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private final UserDao userDao = new UserDao(this);
    private final HistoryDao historyDao = new HistoryDao(this);
    private final DrawingDao drawingDao = new DrawingDao(this);

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_USERS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_USERNAME + " TEXT UNIQUE,"
                + KEY_EMAIL + " TEXT,"
                + KEY_PASSWORD + " TEXT,"
                + KEY_GOOGLE_ID + " TEXT" + ")");

        db.execSQL("CREATE TABLE " + TABLE_HISTORY + "("
                + KEY_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_HIST_USERNAME + " TEXT,"
                + KEY_HIST_NAME + " TEXT,"
                + KEY_HIST_PROBLEM + " TEXT,"
                + KEY_HIST_SOLUTION + " TEXT,"
                + KEY_HIST_RAW_RESPONSE + " TEXT,"
                + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + KEY_SYNCED + " INTEGER DEFAULT 0" + ")");

        db.execSQL("CREATE TABLE " + TABLE_DRAWINGS + "("
                + KEY_DRW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_DRW_USERNAME + " TEXT,"
                + KEY_DRW_NAME + " TEXT,"
                + KEY_DRW_DATA + " TEXT,"
                + KEY_DRW_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + KEY_SYNCED + " INTEGER DEFAULT 0" + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE " + TABLE_USERS + " ADD COLUMN " + KEY_GOOGLE_ID + " TEXT");
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_HISTORY + "("
                    + KEY_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_HIST_USERNAME + " TEXT,"
                    + KEY_HIST_PROBLEM + " TEXT,"
                    + KEY_HIST_SOLUTION + " TEXT,"
                    + KEY_HIST_RAW_RESPONSE + " TEXT,"
                    + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")");
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE " + TABLE_HISTORY + " ADD COLUMN " + KEY_HIST_NAME + " TEXT DEFAULT 'unnamed'");
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DRAWINGS + "("
                    + KEY_DRW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_DRW_USERNAME + " TEXT,"
                    + KEY_DRW_NAME + " TEXT,"
                    + KEY_DRW_DATA + " TEXT,"
                    + KEY_DRW_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")");
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE " + TABLE_USERS + " ADD COLUMN " + KEY_EMAIL + " TEXT");
        if (oldVersion < 7) {
            // Existing rows default to 1 (treated as already-synced, same as old behavior).
            db.execSQL("ALTER TABLE " + TABLE_HISTORY + " ADD COLUMN " + KEY_SYNCED + " INTEGER DEFAULT 1");
            db.execSQL("ALTER TABLE " + TABLE_DRAWINGS + " ADD COLUMN " + KEY_SYNCED + " INTEGER DEFAULT 1");
        }
    }

    // ---- Users (delegate to UserDao) ----
    public boolean addUser(String u, String email, String p) { return userDao.addUser(u, email, p); }
    public void deleteUser(String username) { userDao.deleteUser(username); }
    public boolean checkEmailExists(String email) { return userDao.checkEmailExists(email); }
    public boolean syncGoogleUser(String username, String email, String googleId) { return userDao.syncGoogleUser(username, email, googleId); }
    public boolean checkUser(String u, String p) { return userDao.checkUser(u, p); }
    public boolean checkUsernameExists(String u) { return userDao.checkUsernameExists(u); }
    public String getUserEmail(String identifier) { return userDao.getUserEmail(identifier); }
    public boolean updatePassword(String identifier, String p) { return userDao.updatePassword(identifier, p); }
    public String authenticateUser(String identifier, String password) { return userDao.authenticateUser(identifier, password); }
    public boolean renameUser(String oldName, String newName) { return userDao.renameUser(oldName, newName); }

    // ---- History (delegate to HistoryDao) ----
    public void addHistory(String user, String name, String prob, String sol, String raw) { historyDao.addHistory(user, name, prob, sol, raw); }
    public long addHistoryWithDate(String user, String name, String prob, String sol, String raw, String date) { return historyDao.addHistoryWithDate(user, name, prob, sol, raw, date); }
    public void markHistorySynced(String user, String date) { historyDao.markHistorySynced(user, date); }
    public void updateHistory(int id, String name, String prob, String sol, String raw) { historyDao.updateHistory(id, name, prob, sol, raw); }
    public void renameHistory(int id, String newName) { historyDao.renameHistory(id, newName); }
    public void deleteHistory(int id) { historyDao.deleteHistory(id); }
    public Cursor getHistory(String user) { return historyDao.getHistory(user); }

    // ---- Drawings (delegate to DrawingDao) ----
    public void addDrawing(String user, String name, String data) { drawingDao.addDrawing(user, name, data); }
    public void addDrawingWithDate(String user, String name, String data, String date) { drawingDao.addDrawingWithDate(user, name, data, date); }
    public void markDrawingSynced(String user, String date) { drawingDao.markDrawingSynced(user, date); }
    public void updateDrawing(int id, String name, String data) { drawingDao.updateDrawing(id, name, data); }
    public void renameDrawing(int id, String newName) { drawingDao.renameDrawing(id, newName); }
    public void deleteDrawing(int id) { drawingDao.deleteDrawing(id); }
    public Cursor getDrawings(String user) { return drawingDao.getDrawings(user); }

    /** Wipes a user's saved solutions and drawings (used when clearing guest data). */
    public void clearUserHistory(String user) {
        historyDao.clearFor(user);
        drawingDao.clearFor(user);
    }

    /**
     * Returns the next default name for a save, like "Drawing (3)" or "Solution (1)". Scans names
     * matching "&lt;basePrefix&gt; (N)" for the user and returns one higher than the current max — so
     * deleting a middle entry doesn't reuse its number.
     *
     * @param table      "drawings" or "history"
     * @param username   the current account
     * @param basePrefix the localized base name (e.g. "Drawing"/"Рисунок"/"Գծագիր")
     */
    public String nextDefaultName(String table, String username, String basePrefix) {
        SQLiteDatabase db = this.getReadableDatabase();
        int max = 0;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT name FROM " + table + " WHERE username = ? AND name LIKE ?",
                    new String[]{username, basePrefix + " (%"});
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                    "^" + java.util.regex.Pattern.quote(basePrefix) + "\\s*\\((\\d+)\\)$");
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n == null) continue;
                java.util.regex.Matcher m = pat.matcher(n);
                if (m.matches()) {
                    try {
                        int v = Integer.parseInt(m.group(1));
                        if (v > max) max = v;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            android.util.Log.e("DatabaseHelper", "nextDefaultName failed: " + e.getMessage(), e);
        } finally {
            if (c != null) c.close();
        }
        return basePrefix + " (" + (max + 1) + ")";
    }
}
