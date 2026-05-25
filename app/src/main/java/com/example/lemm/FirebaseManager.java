package com.example.lemm;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FirebaseManager {

    // The Realtime Database lives in europe-west1. getInstance() without this explicit URL can
    // silently fail to reach a non-us-central1 database, so writes queue forever and never commit.
    private static final String DB_URL = "https://lemma-37061-default-rtdb.europe-west1.firebasedatabase.app";

    public static FirebaseDatabase getDatabase() {
        return FirebaseDatabase.getInstance(DB_URL);
    }

    // Forces lowercase so "Andi" and "andi" always match the exact same database folder!
    public static String sanitizeUser(String user) {
        if (user == null) return "guestuser";
        return user.replaceAll("[.#$\\[\\]]", "_").toLowerCase();
    }

    public static DatabaseReference getUserRef(String username) {
        return getDatabase().getReference("users").child(sanitizeUser(username));
    }

    public static String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}