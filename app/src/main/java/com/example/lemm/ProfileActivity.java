package com.example.lemm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class ProfileActivity extends AppCompatActivity {

    private TextView tvUsername, tvHistoryCount, tvDrawingCount;
    private ImageButton btnBack;
    private MaterialButton btnLogout;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        dbHelper = new DatabaseHelper(this);
        
        tvUsername = findViewById(R.id.tvProfileUsername);
        tvHistoryCount = findViewById(R.id.tvHistoryCount);
        tvDrawingCount = findViewById(R.id.tvDrawingCount);
        btnBack = findViewById(R.id.btnBack);
        btnLogout = findViewById(R.id.btnProfileLogout);

        setupUserData();

        btnBack.setOnClickListener(v -> finish());

        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(getString(R.string.logout))
                .setMessage(getString(R.string.logout_confirm))
                .setPositiveButton(getString(R.string.logout), (dialog, which) -> {
                    SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                    pref.edit().clear().apply();
                    
                    // After logout, bring user to the Start Page (StartActivity)
                    Intent intent = new Intent(ProfileActivity.this, StartActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
        });
    }

    private void setupUserData() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        tvUsername.setText(username);

        // Fetch counts from DB
        int historyCount = getCountFromCursor(dbHelper.getHistory(username));
        int drawingCount = getCountFromCursor(dbHelper.getDrawings(username));

        tvHistoryCount.setText(String.valueOf(historyCount));
        tvDrawingCount.setText(String.valueOf(drawingCount));
    }

    private int getCountFromCursor(Cursor cursor) {
        if (cursor != null) {
            int count = cursor.getCount();
            cursor.close();
            return count;
        }
        return 0;
    }
}
