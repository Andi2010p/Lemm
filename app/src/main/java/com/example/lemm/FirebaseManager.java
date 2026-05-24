package com.example.lemm;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FirebaseManager {
    // Sanitizes emails/usernames so they are valid Firebase paths
    public static String sanitizeUser(String user) {
        if (user == null) return "GuestUser";
        return user.replaceAll("[.#$\\[\\]]", "_");
    }

    public static DatabaseReference getUserRef(String username) {
        // TODO: IF YOUR DATABASE IS REGIONAL, REPLACE THE URL BELOW WITH YOUR ACTUAL FIREBASE DATABASE URL
        // Example: return FirebaseDatabase.getInstance("https://your-project.europe-west1.firebasedatabase.app").getReference("users").child(sanitizeUser(username));

        return FirebaseDatabase.getInstance().getReference("users").child(sanitizeUser(username));
    }

    public static String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}