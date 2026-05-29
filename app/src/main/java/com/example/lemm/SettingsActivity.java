package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

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

        currentLang = getSharedPreferences("Settings", MODE_PRIVATE).getString("Locale.Helper.Selected.Language", "en");
        setupLanguagePicker();

        setupThemeToggle();

        // Pull this account's saved keys from the cloud so the management screen is up to date.
        ApiKeyStore.syncFromCloud(this, null);

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

    /** Same chip + dropdown as StartActivity — keeps the language picker consistent app-wide. */
    private void setupLanguagePicker() {
        View card = findViewById(R.id.cardLanguage);
        TextView tvLang = findViewById(R.id.tvCurrentLang);
        if (card == null || tvLang == null) return;
        tvLang.setText(codeToLabel(currentLang));
        card.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, card);
            menu.getMenu().add(0, 0, 0, getString(R.string.language_en));
            menu.getMenu().add(0, 1, 1, getString(R.string.language_ru));
            menu.getMenu().add(0, 2, 2, getString(R.string.language_hy));
            menu.setOnMenuItemClickListener(item -> {
                String code = "en";
                if (item.getItemId() == 1) code = "ru";
                else if (item.getItemId() == 2) code = "hy";
                if (!code.equals(currentLang)) updateLocale(code);
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
            tvStatus.setText(R.string.pro_status_active);
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            btnBuyPro.setVisibility(View.GONE);
        }
    }

    private void openApiKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.api_key_title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 30, 60, 0);
        scroll.addView(layout);

        TextView info = new TextView(this);
        info.setText(R.string.api_key_info);
        info.setPadding(0, 0, 0, 16);
        layout.addView(info);

        // Toggle: use my own API keys or not.
        SwitchCompat useSwitch = new SwitchCompat(this);
        useSwitch.setText(R.string.api_key_use_personal);
        useSwitch.setChecked(ApiKeyStore.isEnabled(this));
        layout.addView(useSwitch);

        TextView listLabel = new TextView(this);
        listLabel.setText(R.string.api_key_your_keys);
        listLabel.setPadding(0, 24, 0, 4);
        layout.addView(listLabel);

        // One row per personal key, each removable.
        final LinearLayout keysContainer = new LinearLayout(this);
        keysContainer.setOrientation(LinearLayout.VERTICAL);
        layout.addView(keysContainer);

        List<ApiKeyStore.Entry> existing = ApiKeyStore.getEntries(this);
        if (existing.isEmpty()) {
            addKeyRow(keysContainer, "", true);
        } else {
            for (ApiKeyStore.Entry e : existing) addKeyRow(keysContainer, e.key, e.enabled);
        }

        MaterialButton addBtn = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        addBtn.setText(R.string.api_key_add_another);
        addBtn.setOnClickListener(v -> addKeyRow(keysContainer, "", true));
        layout.addView(addBtn);

        setKeysEnabled(keysContainer, addBtn, useSwitch.isChecked());
        useSwitch.setOnCheckedChangeListener((b, checked) ->
                setKeysEnabled(keysContainer, addBtn, checked));

        builder.setView(scroll);

        builder.setPositiveButton(R.string.api_key_save, (dialog, which) -> {
            List<ApiKeyStore.Entry> entries = new ArrayList<>();
            for (int i = 0; i < keysContainer.getChildCount(); i++) {
                View row = keysContainer.getChildAt(i);
                if (row instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) row;
                    SwitchCompat sw = null;
                    EditText e = null;
                    for (int j = 0; j < g.getChildCount(); j++) {
                        View child = g.getChildAt(j);
                        if (child instanceof SwitchCompat) sw = (SwitchCompat) child;
                        else if (child instanceof TextInputLayout) e = ((TextInputLayout) child).getEditText();
                    }
                    if (e != null) {
                        String k = e.getText().toString().trim();
                        if (!k.isEmpty()) {
                            entries.add(new ApiKeyStore.Entry(k, sw == null || sw.isChecked()));
                        }
                    }
                }
            }
            ApiKeyStore.setEntries(this, entries);
            ApiKeyStore.setEnabled(this, useSwitch.isChecked());
            ApiKeyStore.pushToCloud(this); // sync to this account so other devices get the same keys
            Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /** Adds a single key row: per-key on/off switch + password input + remove button. */
    private void addKeyRow(LinearLayout container, String value, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Per-key on/off: when off, this key is skipped by the auto-rotation
        // (but stays saved so it can be re-enabled later).
        SwitchCompat keySwitch = new SwitchCompat(this);
        keySwitch.setChecked(enabled);
        keySwitch.setContentDescription(getString(R.string.api_key_use_this));
        row.addView(keySwitch);

        TextInputLayout til = new TextInputLayout(this);
        til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        til.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextInputEditText edit = new TextInputEditText(this);
        edit.setHint(R.string.api_key_hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (value != null) edit.setText(value);
        til.addView(edit);

        ImageButton remove = new ImageButton(this);
        remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        remove.setBackgroundResource(android.R.color.transparent);
        remove.setContentDescription(getString(R.string.api_key_remove));
        remove.setOnClickListener(v -> container.removeView(row));

        row.addView(til);
        row.addView(remove);
        container.addView(row);
    }

    /** Greys out and disables the key list + add button when the toggle is off. */
    private void setKeysEnabled(LinearLayout container, View addBtn, boolean enabled) {
        addBtn.setEnabled(enabled);
        container.setAlpha(enabled ? 1f : 0.4f);
        addBtn.setAlpha(enabled ? 1f : 0.4f);
        for (int i = 0; i < container.getChildCount(); i++) {
            View row = container.getChildAt(i);
            if (row instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) row;
                for (int j = 0; j < g.getChildCount(); j++) {
                    View child = g.getChildAt(j);
                    child.setEnabled(enabled);
                    if (child instanceof TextInputLayout) {
                        EditText e = ((TextInputLayout) child).getEditText();
                        if (e != null) e.setEnabled(enabled);
                    }
                }
            }
        }
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