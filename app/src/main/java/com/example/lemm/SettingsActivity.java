package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class SettingsActivity extends AppCompatActivity {

    private String currentLang;
    private BillingManager billingManager;
    private MaterialButton btnBuyPro;
    private TextView tvStatus;

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

        findViewById(R.id.btnApiKeyConfig).setOnClickListener(v -> openApiKeyDialog());

        btnBuyPro = findViewById(R.id.btnBuyPro);
        tvStatus = findViewById(R.id.tvProStatus);

        checkCurrentProStatus();

        // Initialize Google Play Billing
        billingManager = new BillingManager(this, new BillingManager.BillingListener() {
            @Override
            public void onBillingReady() {
                // Connection successful
            }

            @Override
            public void onPriceFetched(String price) {
                // Updates the button to show exactly how much it costs
                btnBuyPro.setText(getString(R.string.upgrade_pro) + " (" + price + ")");
            }

            @Override
            public void onPurchaseSuccess() {
                // If payment succeeds, update UI
                checkCurrentProStatus();
            }
        });

        billingManager.startConnection();

        btnBuyPro.setOnClickListener(v -> {
            billingManager.initiatePurchase();
        });

        findViewById(R.id.btnAboutUs).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://andi2010p.github.io/Lemm/")));
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void checkCurrentProStatus() {
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (userPrefs.getBoolean("is_pro_user", false)) {
            tvStatus.setText("Status: Pro Tier Active");
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
            btnBuyPro.setVisibility(View.GONE); // Hide buy button because they already own it
        }
    }

    private void openApiKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.api_key_title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 0);

        final TextView info = new TextView(this);
        info.setText(R.string.api_key_info);
        layout.addView(info);

        final EditText input = new EditText(this);
        SharedPreferences prefs = getSharedPreferences("AI_Settings", MODE_PRIVATE);
        input.setText(prefs.getString("user_api_key", ""));
        input.setHint(R.string.api_key_hint);
        layout.addView(input);

        builder.setView(layout);
        builder.setPositiveButton(R.string.api_key_save, (dialog, which) -> {
            String key = input.getText().toString().trim();
            prefs.edit().putString("user_api_key", key).apply();
            Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show();
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