package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class SettingsActivity extends AppCompatActivity {

    private String currentLang; // Store the current language

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialButtonToggleGroup toggleLanguage = findViewById(R.id.toggleLanguage);

        // 1. Load current language
        currentLang = getSharedPreferences("Settings", MODE_PRIVATE)
                .getString("Locale.Helper.Selected.Language", "en");

        // 2. Set the visually checked button
        if (currentLang.equals("en")) toggleLanguage.check(R.id.btnLangEn);
        else if (currentLang.equals("ru")) toggleLanguage.check(R.id.btnLangRu);
        else if (currentLang.equals("hy")) toggleLanguage.check(R.id.btnLangHy);

        // 3. Listen for clicks
        toggleLanguage.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selectedLang = "en";
                if (checkedId == R.id.btnLangRu) selectedLang = "ru";
                else if (checkedId == R.id.btnLangHy) selectedLang = "hy";

                // THE FIX: Only update if they picked a DIFFERENT language!
                if (!selectedLang.equals(currentLang)) {
                    currentLang = selectedLang; // Update it
                    updateLocale(selectedLang);
                }
            }
        });

        // About Us Button - Opens the Website
        MaterialButton btnAboutUs = findViewById(R.id.btnAboutUs);
        if (btnAboutUs != null) {
            btnAboutUs.setOnClickListener(v -> {
                try {
                    String websiteUrl = "https://andi2010p.github.io/Lemm/";
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "No web browser found to open the link!", Toast.LENGTH_LONG).show();
                }
            });
        }

        // Back Button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void updateLocale(String lang) {
        LocaleHelper.setLocale(this, lang);

        // Restart app completely to apply changes everywhere instantly
        Intent intent = new Intent(this, MainActivity.class);
        // We add CLEAR_TASK to wipe the background stack so no old-language screens remain
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // Required for the language to apply to this specific screen
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}