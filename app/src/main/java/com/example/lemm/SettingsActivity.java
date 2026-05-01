package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialButtonToggleGroup toggleLanguage = findViewById(R.id.toggleLanguage);
        
        // Load current language
        String currentLang = getSharedPreferences("Settings", MODE_PRIVATE)
                .getString("Locale.Helper.Selected.Language", "en");
        
        if (currentLang.equals("en")) toggleLanguage.check(R.id.btnLangEn);
        else if (currentLang.equals("ru")) toggleLanguage.check(R.id.btnLangRu);
        else if (currentLang.equals("hy")) toggleLanguage.check(R.id.btnLangHy);

        toggleLanguage.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String lang = "en";
                if (checkedId == R.id.btnLangRu) lang = "ru";
                else if (checkedId == R.id.btnLangHy) lang = "hy";
                
                updateLocale(lang);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void updateLocale(String lang) {
        LocaleHelper.setLocale(this, lang);
        
        // Restart app to apply changes
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
