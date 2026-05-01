package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        if (isNetworkAvailable()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                
                // If no user is logged in, automatically log in as Admin_Teacher
                if (!pref.contains("username")) {
                    pref.edit()
                        .putString("username", "Admin_Teacher")
                        .putBoolean("is_guest", false)
                        .apply();
                }
                
                // Always go to MainActivity now since we auto-login if empty
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                finish();
            }, 2000);

            DatabaseHelper db = new DatabaseHelper(this);
            SQLiteDatabase sqlDb = db.getWritableDatabase();
            sqlDb.delete("history", "username LIKE 'TEMP_%'", null);
        } else {
            showNoInternetDialog();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
            if (capabilities != null) {
                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            }
        }
        return false;
    }

    private void showNoInternetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("No Internet Connection")
                .setMessage("This app requires an internet connection to function. Please check your connection and try again.")
                .setCancelable(false)
                .setPositiveButton("Retry", (dialog, which) -> {
                    recreate();
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    finish();
                })
                .show();
    }
}
