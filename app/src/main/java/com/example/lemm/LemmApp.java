package com.example.lemm;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

public class LemmApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Apply the saved theme preference (defaults to following the device's system setting).
        int nightMode = getSharedPreferences("Settings", MODE_PRIVATE)
                .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(nightMode);

        // Enable Firebase Offline Persistence (Cloud Sync Cache)
        try {
            FirebaseManager.getDatabase().setPersistenceEnabled(true);
        } catch (Exception e) {
            // Expected if persistence was already enabled (DB touched before this call) — log, don't crash.
            Log.d("LemmApp", "Firebase persistence already enabled: " + e.getMessage());
        }

        // Clean up GuestUser history on every app start
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean isGuest = pref.getBoolean("is_guest", false);
        String username = pref.getString("username", "");

        if (isGuest && username.startsWith("GuestUser_")) {
            // Delete guest data from Cloud
            FirebaseManager.getDatabase().getReference("users").child(username).removeValue();
            pref.edit().clear().apply();
        }
    }
}