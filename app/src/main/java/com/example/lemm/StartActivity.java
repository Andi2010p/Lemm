package com.example.lemm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import java.util.UUID;

public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (pref.contains("username")) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_start);

        Button btnLogin = findViewById(R.id.btnGoToLogin);
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        }

        Button btnRegister = findViewById(R.id.btnGoToRegister);
        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        }
/* admin mode
        Button btnQuickStart = findViewById(R.id.btnQuickStart);
        if (btnQuickStart != null) {
            btnQuickStart.setOnClickListener(v -> {
                String uniqueGuestId = "GuestUser_" + UUID.randomUUID().toString().substring(0, 8);
                SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                userPrefs.edit()
                        .putString("username", uniqueGuestId)
                        .putBoolean("is_guest", true)
                        .apply();

                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }

        Button btnAdminMode = findViewById(R.id.btnAdminMode);
        if (btnAdminMode != null) {
            btnAdminMode.setOnClickListener(v -> {
                SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                userPrefs.edit()
                        .putString("username", "Admin_Teacher")
                        .putBoolean("is_guest", false)
                        .apply();

                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }*/
    }
}