package com.example.lemm;

import android.app.Application;
import android.content.SharedPreferences;
import com.google.firebase.database.FirebaseDatabase;

public class LemmApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Enable Firebase Offline Persistence (Cloud Sync Cache)
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) {}

        // Clean up GuestUser history on every app start
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean isGuest = pref.getBoolean("is_guest", false);
        String username = pref.getString("username", "");

        if (isGuest && username.startsWith("GuestUser_")) {
            // Delete guest data from Cloud
            FirebaseDatabase.getInstance().getReference("users").child(username).removeValue();
            pref.edit().clear().apply();
        }
    }
}