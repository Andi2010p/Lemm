package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class SettingsActivity extends AppCompatActivity {
    private int proTapCount = 0;
    private String currentLang;
    private MaterialButton btnBuyPro;
    private TextView tvStatus;

    private BillingManager billingManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialButtonToggleGroup toggleLanguage = findViewById(R.id.toggleLanguage);
        currentLang = getSharedPreferences("Settings", MODE_PRIVATE).getString("Locale.Helper.Selected.Language", "en");

        if (currentLang.equals("en")) toggleLanguage.check(R.id.btnLangEn);
        else if (currentLang.equals("ru")) toggleLanguage.check(R.id.btnLangRu);
        else if (currentLang.equals("hy")) toggleLanguage.check(R.id.btnLangHy);

        toggleLanguage.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selectedLang = "en";
                if (checkedId == R.id.btnLangRu) selectedLang = "ru";
                else if (checkedId == R.id.btnLangHy) selectedLang = "hy";
                if (!selectedLang.equals(currentLang)) updateLocale(selectedLang);
            }
        });

        setupThemeToggle();

        findViewById(R.id.btnApiKeyConfig).setOnClickListener(v -> openApiKeyDialog());

        btnBuyPro = findViewById(R.id.btnBuyPro);
        tvStatus = findViewById(R.id.tvProStatus);

        checkCurrentProStatus();

        billingManager = new BillingManager(this, new BillingManager.BillingListener() {
            @Override public void onBillingReady() {}
            @Override public void onPriceFetched(String price) {
                btnBuyPro.setText(getString(R.string.upgrade_pro) + " (" + price + ")");
            }
            @Override public void onPurchaseSuccess() { checkCurrentProStatus(); }
            @Override public void onBillingError() {}
        });
        billingManager.startConnection();

        btnBuyPro.setOnClickListener(v -> billingManager.initiatePurchase());

        // --- SECURED ADMIN BACKDOOR ---
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        String username = userPrefs.getString("username", "");

        // --- SECRET 5-TAP PRO UNLOCKER ---
        tvStatus.setOnClickListener(v -> {
            // CHANGED: userPrefs to proPrefs
            SharedPreferences proPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            boolean isAlreadyPro = proPrefs.getBoolean("is_pro_user", false);

            if (!isAlreadyPro) {
                proTapCount++;

                if (proTapCount >= 3 && proTapCount < 5) {
                    int tapsLeft = 5 - proTapCount;
                    Toast.makeText(this, "Tap " + tapsLeft + " more times to unlock Pro", Toast.LENGTH_SHORT).show();
                }

                if (proTapCount >= 5) {
                    proTapCount = 0;

                    // Save BOTH the Pro status AND the secret bypass key
                    proPrefs.edit()
                            .putBoolean("is_pro_user", true)
                            .putBoolean("pro_bypass", true) // The permanent bypass key!
                            .apply();

                    Toast.makeText(this, "🎉 Secret Unlocked: Pro Tier Activated!", Toast.LENGTH_LONG).show();
                    checkCurrentProStatus();
                }
            }
        });

        findViewById(R.id.btnAboutUs).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://andi2010p.github.io/Lemm/")));
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void setupThemeToggle() {
        MaterialButtonToggleGroup toggleTheme = findViewById(R.id.toggleTheme);
        if (toggleTheme == null) return;

        int mode = getSharedPreferences("Settings", MODE_PRIVATE)
                .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) toggleTheme.check(R.id.btnThemeLight);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) toggleTheme.check(R.id.btnThemeDark);
        else toggleTheme.check(R.id.btnThemeSystem);

        toggleTheme.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (checkedId == R.id.btnThemeLight) newMode = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.btnThemeDark) newMode = AppCompatDelegate.MODE_NIGHT_YES;

            int current = getSharedPreferences("Settings", MODE_PRIVATE)
                    .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            if (newMode == current) return;

            getSharedPreferences("Settings", MODE_PRIVATE).edit().putInt("night_mode", newMode).apply();
            AppCompatDelegate.setDefaultNightMode(newMode); // recreates activities to apply instantly
        });
    }

    private void checkCurrentProStatus() {
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (userPrefs.getBoolean("is_pro_user", false)) {
            tvStatus.setText("Status: Pro Tier Active");
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            btnBuyPro.setVisibility(View.GONE);
        }
    }

    private void openApiKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.api_key_title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 0);

        TextView info = new TextView(this);
        info.setText(R.string.api_key_info);
        info.setPadding(0, 0, 0, 20);
        layout.addView(info);

        TextInputLayout textInputLayout = new TextInputLayout(this);
        textInputLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);

        final TextInputEditText input = new TextInputEditText(this);
        SharedPreferences prefs = getSharedPreferences("AI_Settings", MODE_PRIVATE);
        input.setText(prefs.getString("user_api_key", ""));
        input.setHint(R.string.api_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        textInputLayout.addView(input);
        layout.addView(textInputLayout);

        builder.setView(layout);

        builder.setPositiveButton(R.string.api_key_save, (dialog, which) -> {
            String key = input.getText().toString().trim();
            prefs.edit().putString("user_api_key", key).apply();
            Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show();
        });

        builder.setNeutralButton("Clear Key", (dialog, which) -> {
            prefs.edit().remove("user_api_key").apply();
            Toast.makeText(this, "API Key Cleared", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void updateLocale(String lang) {
        LocaleHelper.setLocale(this, lang);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}