package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
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

        setupLanguagePicker();

        Button btnLogin = findViewById(R.id.btnGoToLogin);
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        }

        Button btnRegister = findViewById(R.id.btnGoToRegister);
        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        }

        Button btnGuest = findViewById(R.id.btnGuestMode);
        if (btnGuest != null) {
            btnGuest.setOnClickListener(v -> launchGuestPrefilled());
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

    private void setupLanguagePicker() {
        View card = findViewById(R.id.cardLanguage);
        TextView tvLang = findViewById(R.id.tvCurrentLang);
        if (card == null || tvLang == null) return;

        final String current = getSharedPreferences("Settings", MODE_PRIVATE)
                .getString("Locale.Helper.Selected.Language", "en");
        tvLang.setText(codeToLabel(current));

        card.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, card);
            menu.getMenu().add(0, 0, 0, getString(R.string.language_en));
            menu.getMenu().add(0, 1, 1, getString(R.string.language_ru));
            menu.getMenu().add(0, 2, 2, getString(R.string.language_hy));
            menu.setOnMenuItemClickListener(item -> {
                String code = "en";
                if (item.getItemId() == 1) code = "ru";
                else if (item.getItemId() == 2) code = "hy";
                if (!code.equals(current)) {
                    LocaleHelper.setLocale(this, code);
                    recreate(); // reload the welcome screen in the chosen language
                }
                return true;
            });
            menu.show();
        });
    }

    private String codeToLabel(String code) {
        switch (code) {
            case "ru": return "RU";
            case "hy": return "HY";
            default:   return "EN";
        }
    }

    // Shared judge / demo account used by the "Guest mode" button. Hardcoded so it Just Works
    // (no BuildConfig dependency that could leave fields empty after a stale build).
    public static final String GUEST_EMAIL = "innovationcampus26@gmail.com";
    public static final String GUEST_PASSWORD = "Samsung26";

    /** "Guest mode" = open the Login screen with the demo email + password pre-filled. The user
     *  just taps Login. Avoids any in-app auth path that could fail silently. */
    private void launchGuestPrefilled() {
        Intent i = new Intent(this, LoginActivity.class);
        i.putExtra("PREFILL_EMAIL", GUEST_EMAIL);
        i.putExtra("PREFILL_PASSWORD", GUEST_PASSWORD);
        startActivity(i);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}