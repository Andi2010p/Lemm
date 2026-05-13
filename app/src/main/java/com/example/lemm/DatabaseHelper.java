package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "UserDatabase.db";
    private static final int DATABASE_VERSION = 5;
    
    private static final String TABLE_USERS = "users";
    private static final String KEY_ID = "id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_GOOGLE_ID = "google_id";

    // History Table (Solutions)
    private static final String TABLE_HISTORY = "history";
    private static final String KEY_HIST_ID = "hist_id";
    private static final String KEY_HIST_USERNAME = "username";
    private static final String KEY_HIST_NAME = "name";
    private static final String KEY_HIST_PROBLEM = "problem";
    private static final String KEY_HIST_SOLUTION = "solution";
    private static final String KEY_HIST_RAW_RESPONSE = "raw_response";
    private static final String KEY_HIST_DATE = "date";

    // Drawings Table
    private static final String TABLE_DRAWINGS = "drawings";
    private static final String KEY_DRW_ID = "drw_id";
    private static final String KEY_DRW_USERNAME = "username";
    private static final String KEY_DRW_NAME = "name";
    private static final String KEY_DRW_DATA = "data";
    private static final String KEY_DRW_DATE = "date";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_USERS_TABLE = "CREATE TABLE " + TABLE_USERS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_USERNAME + " TEXT UNIQUE,"
                + KEY_PASSWORD + " TEXT,"
                + KEY_GOOGLE_ID + " TEXT" + ")";
        db.execSQL(CREATE_USERS_TABLE);

        String CREATE_HISTORY_TABLE = "CREATE TABLE " + TABLE_HISTORY + "("
                + KEY_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_HIST_USERNAME + " TEXT,"
                + KEY_HIST_NAME + " TEXT,"
                + KEY_HIST_PROBLEM + " TEXT,"
                + KEY_HIST_SOLUTION + " TEXT,"
                + KEY_HIST_RAW_RESPONSE + " TEXT,"
                + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")";
        db.execSQL(CREATE_HISTORY_TABLE);

        String CREATE_DRAWINGS_TABLE = "CREATE TABLE " + TABLE_DRAWINGS + "("
                + KEY_DRW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_DRW_USERNAME + " TEXT,"
                + KEY_DRW_NAME + " TEXT,"
                + KEY_DRW_DATA + " TEXT,"
                + KEY_DRW_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")";
        db.execSQL(CREATE_DRAWINGS_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE " + TABLE_USERS + " ADD COLUMN " + KEY_GOOGLE_ID + " TEXT");
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE " + TABLE_HISTORY + "("
                    + KEY_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_HIST_USERNAME + " TEXT,"
                    + KEY_HIST_PROBLEM + " TEXT,"
                    + KEY_HIST_SOLUTION + " TEXT,"
                    + KEY_HIST_RAW_RESPONSE + " TEXT,"
                    + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")");
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE " + TABLE_HISTORY + " ADD COLUMN " + KEY_HIST_NAME + " TEXT DEFAULT 'unnamed'");
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE " + TABLE_DRAWINGS + "("
                    + KEY_DRW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_DRW_USERNAME + " TEXT,"
                    + KEY_DRW_NAME + " TEXT,"
                    + KEY_DRW_DATA + " TEXT,"
                    + KEY_DRW_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")");
        }
    }

    public boolean addUser(String u, String p) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_USERNAME, u); v.put(KEY_PASSWORD, p);
        return db.insert(TABLE_USERS, null, v) != -1;
    }

    public boolean syncGoogleUser(String email, String googleId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_USERNAME, email); v.put(KEY_GOOGLE_ID, googleId);
        return db.insertWithOnConflict(TABLE_USERS, null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public boolean checkUser(String u, String p) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + KEY_USERNAME + " = ? AND " + KEY_PASSWORD + " = ?", new String[]{u, p});
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    public boolean checkUsernameExists(String u) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + KEY_USERNAME + " = ?", new String[]{u});
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    public boolean updatePassword(String u, String p) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_PASSWORD, p);
        return db.update(TABLE_USERS, v, KEY_USERNAME + " = ?", new String[]{u}) > 0;
    }

    public void addHistory(String user, String name, String prob, String sol, String raw) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_USERNAME, user); v.put(KEY_HIST_NAME, name);
        v.put(KEY_HIST_PROBLEM, prob); v.put(KEY_HIST_SOLUTION, sol);
        v.put(KEY_HIST_RAW_RESPONSE, raw);
        db.insert(TABLE_HISTORY, null, v);
    }

    public void updateHistory(int id, String name, String prob, String sol, String raw) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_NAME, name); v.put(KEY_HIST_PROBLEM, prob);
        v.put(KEY_HIST_SOLUTION, sol); v.put(KEY_HIST_RAW_RESPONSE, raw);
        db.update(TABLE_HISTORY, v, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void renameHistory(int id, String newName) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_HIST_NAME, newName);
        db.update(TABLE_HISTORY, v, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void deleteHistory(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_HISTORY, KEY_HIST_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public Cursor getHistory(String user) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.rawQuery("SELECT * FROM " + TABLE_HISTORY + " WHERE " + KEY_HIST_USERNAME + " = ? ORDER BY " + KEY_HIST_DATE + " DESC", new String[]{user});
    }

    public int countSolutionsStartingWith(String username, String prefix) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        int count = 0;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_HISTORY + 
                               " WHERE " + KEY_HIST_USERNAME + " = ? AND " + KEY_HIST_NAME + " LIKE ?",
                               new String[]{username, prefix + "%"});
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return count;
    }

    public void addDrawing(String user, String name, String data) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_USERNAME, user); v.put(KEY_DRW_NAME, name); v.put(KEY_DRW_DATA, data);
        db.insert(TABLE_DRAWINGS, null, v);
    }

    public void updateDrawing(int id, String name, String data) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_NAME, name);
        v.put(KEY_DRW_DATA, data);
        db.update(TABLE_DRAWINGS, v, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void renameDrawing(int id, String newName) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(KEY_DRW_NAME, newName);
        db.update(TABLE_DRAWINGS, v, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void deleteDrawing(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_DRAWINGS, KEY_DRW_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public Cursor getDrawings(String user) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.rawQuery("SELECT * FROM " + TABLE_DRAWINGS + " WHERE " + KEY_DRW_USERNAME + " = ? ORDER BY " + KEY_DRW_DATE + " DESC", new String[]{user});
    }

    public void clearUserHistory(String user) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_HISTORY, KEY_HIST_USERNAME + " = ?", new String[]{user});
        db.delete(TABLE_DRAWINGS, KEY_DRW_USERNAME + " = ?", new String[]{user});
    }
}
