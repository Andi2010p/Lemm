package com.example.lemm;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import static com.example.lemm.DbSchema.*;

/** Account/auth rows in the {@code users} table. */
class UserDao {
    private final SQLiteOpenHelper helper;
    UserDao(SQLiteOpenHelper helper) { this.helper = helper; }

    boolean addUser(String u, String email, String p) {
        ContentValues v = new ContentValues();
        v.put(KEY_USERNAME, u);
        v.put(KEY_EMAIL, email);
        v.put(KEY_PASSWORD, PasswordHasher.hash(p)); // never store the raw password
        return helper.getWritableDatabase().insert(TABLE_USERS, null, v) != -1;
    }

    void deleteUser(String username) {
        helper.getWritableDatabase().delete(TABLE_USERS, KEY_USERNAME + " = ?", new String[]{username});
    }

    boolean checkEmailExists(String email) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + TABLE_USERS + " WHERE " + KEY_EMAIL + " = ?", new String[]{email});
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    boolean syncGoogleUser(String username, String email, String googleId) {
        ContentValues v = new ContentValues();
        v.put(KEY_USERNAME, username);
        v.put(KEY_EMAIL, email);
        v.put(KEY_GOOGLE_ID, googleId);
        // Google handles auth; store a hashed random so there's no known/guessable local password.
        v.put(KEY_PASSWORD, PasswordHasher.hash(java.util.UUID.randomUUID().toString()));
        return helper.getWritableDatabase()
                .insertWithOnConflict(TABLE_USERS, null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    boolean checkUser(String u, String p) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + KEY_PASSWORD + " FROM " + TABLE_USERS + " WHERE " + KEY_USERNAME + " = ?",
                new String[]{u});
        boolean ok = false;
        if (c.moveToFirst()) {
            String stored = c.getString(0);
            ok = PasswordHasher.verify(p, stored);
            if (ok && !PasswordHasher.isHashed(stored)) migratePassword(u, p); // upgrade legacy plaintext
        }
        c.close();
        return ok;
    }

    boolean checkUsernameExists(String u) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM " + TABLE_USERS + " WHERE " + KEY_USERNAME + " = ?", new String[]{u});
        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    String getUserEmail(String identifier) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + KEY_EMAIL + " FROM " + TABLE_USERS + " WHERE " + KEY_USERNAME + " = ? OR " + KEY_EMAIL + " = ?",
                new String[]{identifier, identifier});
        String email = null;
        if (c.moveToFirst()) email = c.getString(0);
        c.close();
        return email;
    }

    boolean updatePassword(String identifier, String p) {
        ContentValues v = new ContentValues();
        v.put(KEY_PASSWORD, PasswordHasher.hash(p));
        return helper.getWritableDatabase().update(TABLE_USERS, v,
                KEY_USERNAME + " = ? OR " + KEY_EMAIL + " = ?", new String[]{identifier, identifier}) > 0;
    }

    /** Re-stores a legacy plaintext password as a salted hash after a successful verify. */
    private void migratePassword(String username, String plaintext) {
        ContentValues v = new ContentValues();
        v.put(KEY_PASSWORD, PasswordHasher.hash(plaintext));
        helper.getWritableDatabase().update(TABLE_USERS, v, KEY_USERNAME + " = ?", new String[]{username});
    }

    String authenticateUser(String identifier, String password) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + KEY_USERNAME + ", " + KEY_PASSWORD + " FROM " + TABLE_USERS +
                        " WHERE " + KEY_USERNAME + " = ? OR " + KEY_EMAIL + " = ?",
                new String[]{identifier, identifier});
        String username = null;
        try {
            while (c.moveToNext()) {
                String uname = c.getString(0);
                String stored = c.getString(1);
                if (PasswordHasher.verify(password, stored)) {
                    username = uname;
                    if (!PasswordHasher.isHashed(stored)) migratePassword(uname, password); // upgrade legacy plaintext
                    break;
                }
            }
        } finally {
            c.close();
        }
        return username;
    }

    /** Renames a user across the users row plus all of their history/drawing rows, atomically. */
    boolean renameUser(String oldName, String newName) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues u = new ContentValues();
            u.put(KEY_USERNAME, newName);
            db.update(TABLE_USERS, u, KEY_USERNAME + " = ?", new String[]{oldName});

            ContentValues h = new ContentValues();
            h.put(KEY_HIST_USERNAME, newName);
            db.update(TABLE_HISTORY, h, KEY_HIST_USERNAME + " = ?", new String[]{oldName});

            ContentValues d = new ContentValues();
            d.put(KEY_DRW_USERNAME, newName);
            db.update(TABLE_DRAWINGS, d, KEY_DRW_USERNAME + " = ?", new String[]{oldName});

            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            android.util.Log.e("UserDao", "renameUser failed: " + e.getMessage(), e);
            return false;
        } finally {
            db.endTransaction();
        }
    }
}
