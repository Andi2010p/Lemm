package com.example.lemm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvMainWelcome;
    private Button btnProfile;
    private Button btnSettings;
    private Button btnLogout;
    private Button btnNewProblem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvMainWelcome = findViewById(R.id.tvMainWelcome);
        btnProfile = findViewById(R.id.btnProfile);
        btnSettings = findViewById(R.id.btnSettings);
        btnLogout = findViewById(R.id.btnLogout);
        btnNewProblem = findViewById(R.id.btnNewProblem);
        String username = getIntent().getStringExtra("LOGGED_IN_USERNAME");
        if (username == null) {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            username = pref.getString("username", "User");
        }
        tvMainWelcome.setText("Welcome back,\n" + username + "!");
        btnNewProblem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                startActivity(intent);
            }
        });
        btnProfile.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Profile screen is under development", Toast.LENGTH_SHORT).show();
        });
        btnSettings.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Settings screen is under development", Toast.LENGTH_SHORT).show();
        });
        btnLogout.setOnClickListener(v -> {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            pref.edit().clear().apply();
            Intent logoutIntent = new Intent(MainActivity.this, LoginActivity.class);
            logoutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(logoutIntent);
            finish();
        });
    }
}