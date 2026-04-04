package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "UserDatabase.db";
    private static final int DATABASE_VERSION = 3; // Incremented version for history table
    
    private static final String TABLE_USERS = "users";
    private static final String KEY_ID = "id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_GOOGLE_ID = "google_id";

    // History Table
    private static final String TABLE_HISTORY = "history";
    private static final String KEY_HIST_ID = "hist_id";
    private static final String KEY_HIST_USERNAME = "username";
    private static final String KEY_HIST_PROBLEM = "problem";
    private static final String KEY_HIST_SOLUTION = "solution";
    private static final String KEY_HIST_RAW_RESPONSE = "raw_response";
    private static final String KEY_HIST_DATE = "date";

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
                + KEY_HIST_PROBLEM + " TEXT,"
                + KEY_HIST_SOLUTION + " TEXT,"
                + KEY_HIST_RAW_RESPONSE + " TEXT,"
                + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")";
        db.execSQL(CREATE_HISTORY_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_USERS + " ADD COLUMN " + KEY_GOOGLE_ID + " TEXT");
        }
        if (oldVersion < 3) {
            String CREATE_HISTORY_TABLE = "CREATE TABLE " + TABLE_HISTORY + "("
                    + KEY_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_HIST_USERNAME + " TEXT,"
                    + KEY_HIST_PROBLEM + " TEXT,"
                    + KEY_HIST_SOLUTION + " TEXT,"
                    + KEY_HIST_RAW_RESPONSE + " TEXT,"
                    + KEY_HIST_DATE + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")";
            db.execSQL(CREATE_HISTORY_TABLE);
        }
    }

    public boolean addUser(String username, String password) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_USERNAME, username);
        values.put(KEY_PASSWORD, password);
        long result = db.insert(TABLE_USERS, null, values);
        return result != -1;
    }

    public boolean syncGoogleUser(String email, String googleId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_USERNAME, email);
        values.put(KEY_GOOGLE_ID, googleId);
        long result = db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return result != -1;
    }

    public boolean isUsernameTaken(String username) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM users WHERE " + KEY_USERNAME + " = ?", new String[]{username});
        boolean taken = cursor.getCount() > 0;
        cursor.close();
        return taken;
    }

    public boolean checkUser(String username, String password) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " +
                        KEY_USERNAME + " = ? AND " +
                        KEY_PASSWORD + " = ?",
                new String[]{username, password});
        boolean exists = (cursor.getCount() > 0);
        cursor.close();
        return exists;
    }

    // History Methods
    public void addHistory(String username, String problem, String solution, String rawResponse) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_HIST_USERNAME, username);
        values.put(KEY_HIST_PROBLEM, problem);
        values.put(KEY_HIST_SOLUTION, solution);
        values.put(KEY_HIST_RAW_RESPONSE, rawResponse);
        db.insert(TABLE_HISTORY, null, values);
    }

    public Cursor getHistory(String username) {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.rawQuery("SELECT * FROM " + TABLE_HISTORY + " WHERE " + KEY_HIST_USERNAME + " = ? ORDER BY " + KEY_HIST_DATE + " DESC", new String[]{username});
    }
}
