package com.example.lemm;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        Button btnLogin = findViewById(R.id.btnGoToLogin);
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> {
                startActivity(new Intent(this, LoginActivity.class));
            });
        }

        Button btnRegister = findViewById(R.id.btnGoToRegister);
        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> {
                startActivity(new Intent(this, RegisterActivity.class));
            });
        }

        Button btnTestMode = findViewById(R.id.btnTestMode);
        if (btnTestMode != null) {
            btnTestMode.setOnClickListener(v -> {
                Intent intent = new Intent(this, GeometryInputActivity.class);
                intent.putExtra("isTestMode", true);
                startActivity(intent);
            });
        }
    }
}
