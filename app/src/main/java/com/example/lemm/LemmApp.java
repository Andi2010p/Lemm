package com.example.lemm;

import android.app.Application;
import android.content.SharedPreferences;

public class LemmApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Clean up GuestUser history on every app start
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean isGuest = pref.getBoolean("is_guest", false);
        String username = pref.getString("username", "");
        
        if (isGuest && username.startsWith("GuestUser_")) {
            DatabaseHelper db = new DatabaseHelper(this);
            db.clearUserHistory(username);
            
            // Log out the guest user so they need to re-enter
            pref.edit().clear().apply();
        }
    }
}
