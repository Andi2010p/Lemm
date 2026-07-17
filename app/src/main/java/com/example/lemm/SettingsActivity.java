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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    /** Must stay in sync with the URL entered in the Play Console listing. Source: /privacy.html */
    private static final String PRIVACY_POLICY_URL = "https://andi2010p.github.io/Lemm/privacy.html";
    private static final String TERMS_URL = "https://andi2010p.github.io/Lemm/terms.html";

    private int proTapCount = 0;
    private String currentLang;
    private MaterialButton btnBuyPro;
    private MaterialButton btnUnsubscribe;
    private MaterialButton btnBuyTokens;
    private TextView tvStatus;
    private TextView tvTokenBalance;

    private BillingManager billingManager;


    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_settings);
        styleGlass = StyleManager.isGlass(this);

        currentLang = getSharedPreferences("Settings", MODE_PRIVATE).getString("Locale.Helper.Selected.Language", "en");
        setupLanguagePicker();

        setupThemeToggle();
        setupStyleToggle();

        // Pull this account's saved keys from the cloud so the management screen is up to date.
        ApiKeyStore.syncFromCloud(this, null);

        findViewById(R.id.btnApiKeyConfig).setOnClickListener(v -> openApiKeyDialog());
        View btnAiProvider = findViewById(R.id.btnAiProvider);
        if (btnAiProvider != null) btnAiProvider.setOnClickListener(v -> showAiProviderDialog());

        btnBuyPro = findViewById(R.id.btnBuyPro);
        tvStatus = findViewById(R.id.tvProStatus);

        checkCurrentProStatus();

        // Pull this account's Pro status from the cloud (e.g. activated on another device), then refresh.
        ProStatusManager.syncFromCloud(this, this::checkCurrentProStatus);

        billingManager = new BillingManager(this, new BillingManager.BillingListener() {
            @Override public void onBillingReady() {}
            @Override public void onPriceFetched(String price) {
                btnBuyPro.setText(getString(R.string.upgrade_pro) + " (" + price + ")");
            }
            @Override public void onPurchaseSuccess() { checkCurrentProStatus(); }
            @Override public void onBillingError() {}
        });
        billingManager.startConnection();

        // The Pro card now opens the Plans page — one place that explains what the money buys,
        // instead of a bare "buy" button with no context.
        btnBuyPro.setOnClickListener(v -> startActivity(new Intent(this, PlansActivity.class)));

        btnBuyTokens = findViewById(R.id.btnBuyTokens);
        tvTokenBalance = findViewById(R.id.tvTokenBalance);
        if (btnBuyTokens != null) btnBuyTokens.setOnClickListener(v -> showBuyTokensDialog());
        // Pull the account's token wallet from the cloud, then show the balance.
        TokenWallet.syncFromCloud(this, this::refreshTokenBalance);

        btnUnsubscribe = findViewById(R.id.btnUnsubscribe);
        btnUnsubscribe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.unsubscribe_title)
                .setMessage(R.string.unsubscribe_msg)
                .setPositiveButton(R.string.unsubscribe_yes, (d, w) -> {
                    ProStatusManager.revoke(this);
                    Toast.makeText(this, R.string.unsubscribe_done, Toast.LENGTH_SHORT).show();
                    checkCurrentProStatus();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

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

                    // Grant Pro (with the permanent bypass) AND mirror it to this account in the cloud.
                    ProStatusManager.grant(this, true);

                    Toast.makeText(this, "🎉 Secret Unlocked: Pro Tier Activated!", Toast.LENGTH_LONG).show();
                    checkCurrentProStatus();
                }
            }
        });

        findViewById(R.id.btnAboutUs).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://andi2010p.github.io/Lemm/")));
        });

        View btnPrivacy = findViewById(R.id.btnPrivacy);
        if (btnPrivacy != null) btnPrivacy.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))));

        View btnTerms = findViewById(R.id.btnTerms);
        if (btnTerms != null) btnTerms.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))));

        findViewById(R.id.btnHowToUse).setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)));

        View btnAppLock = findViewById(R.id.btnAppLock);
        if (btnAppLock != null) btnAppLock.setOnClickListener(v -> AppLockUi.manage(this));

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

    /** Choose the AI provider (Gemini / OpenAI GPT / Anthropic Claude) + the model + your own key. */
    private void showAiProviderDialog() {
        final AiConfig.Provider cur = AiConfig.provider(this);
        int density = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24 * density, 8 * density, 24 * density, 0);

        // Cloud AI: the recommended pipe once the backend is deployed. Server holds a paid key and
        // meters plan credits, so the app needs no key of its own. When off, the choices below apply.
        final android.widget.CheckBox cbCloud = new android.widget.CheckBox(this);
        cbCloud.setText(R.string.cloud_ai_toggle);
        cbCloud.setChecked(AiPrefs.cloudEnabled(this));
        root.addView(cbCloud);

        final TextView cloudNote = new TextView(this);
        cloudNote.setText(R.string.cloud_ai_note);
        cloudNote.setTextSize(12f);
        cloudNote.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_subtitle));
        LinearLayout.LayoutParams cnp = new LinearLayout.LayoutParams(-1, -2);
        cnp.bottomMargin = 12 * density;
        cloudNote.setLayoutParams(cnp);
        root.addView(cloudNote);

        final RadioGroup rg = new RadioGroup(this);
        RadioButton rGemini = new RadioButton(this); rGemini.setId(1); rGemini.setText(R.string.provider_gemini);
        RadioButton rOpenai = new RadioButton(this); rOpenai.setId(2); rOpenai.setText(R.string.provider_openai);
        RadioButton rClaude = new RadioButton(this); rClaude.setId(3); rClaude.setText(R.string.provider_claude);
        rg.addView(rGemini); rg.addView(rOpenai); rg.addView(rClaude);
        rg.check(cur == AiConfig.Provider.OPENAI ? 2 : cur == AiConfig.Provider.CLAUDE ? 3 : 1);
        root.addView(rg);

        // Picking GPT / Claude and a specific model is a Plus feature — free users stay on Gemini.
        final boolean canChoose = Entitlements.canChooseModel(this);
        if (!canChoose) {
            rOpenai.setEnabled(false);
            rClaude.setEnabled(false);
            rg.check(1);
            TextView gate = new TextView(this);
            gate.setText(R.string.model_choice_plus);
            gate.setTextSize(12f);
            gate.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_subtitle));
            root.addView(gate);
        }

        final EditText etKey = new EditText(this);
        etKey.setHint(R.string.ext_api_key_hint);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint(R.string.ext_model_hint);
        etModel.setSingleLine(true);
        root.addView(etModel);

        final TextView suggestLabel = new TextView(this);
        suggestLabel.setText(R.string.ext_suggested);
        suggestLabel.setTextSize(12f);
        suggestLabel.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_subtitle));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = 12 * density;
        suggestLabel.setLayoutParams(slp);
        root.addView(suggestLabel);

        final LinearLayout suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        root.addView(suggestions);

        final TextView note = new TextView(this);
        note.setTextSize(12f);
        note.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_subtitle));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = 8 * density;
        note.setLayoutParams(nlp);
        root.addView(note);

        final int rowPad = 6 * density;
        final Runnable refresh = () -> {
            AiConfig.Provider sel = rg.getCheckedRadioButtonId() == 2 ? AiConfig.Provider.OPENAI
                    : rg.getCheckedRadioButtonId() == 3 ? AiConfig.Provider.CLAUDE : AiConfig.Provider.GEMINI;
            boolean ext = sel != AiConfig.Provider.GEMINI;
            etKey.setVisibility(ext ? View.VISIBLE : View.GONE);
            etModel.setVisibility(ext ? View.VISIBLE : View.GONE);
            suggestLabel.setVisibility(ext ? View.VISIBLE : View.GONE);
            suggestions.removeAllViews();
            if (ext) {
                etKey.setText(AiConfig.key(this, sel));
                etModel.setText(AiConfig.model(this, sel));
                note.setText(R.string.ext_byok_note);
                // Curated model list — tap a row to use it; the everyday pick is starred.
                for (ModelCatalog.Model m : ModelCatalog.forProvider(sel)) {
                    TextView row = new TextView(this);
                    String star = (m.tier == ModelCatalog.TIER_BALANCED) ? "   ★" : "";
                    row.setText(m.name + "  ·  " + ModelCatalog.tierLabel(m.tier) + "  ·  "
                            + ModelCatalog.costLabel(m.cost) + star + "\n" + m.goodFor);
                    row.setTextSize(13f);
                    row.setPadding(0, rowPad, 0, rowPad);
                    row.setClickable(true);
                    row.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_title));
                    final String id = m.id;
                    row.setOnClickListener(v -> etModel.setText(id));
                    suggestions.addView(row);
                }
            } else {
                note.setText(R.string.ext_gemini_note);
            }
        };
        rg.setOnCheckedChangeListener((g, id) -> refresh.run());
        refresh.run();

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_ai_model)
                .setView(root)
                .setPositiveButton(R.string.save, (d, w) -> {
                    AiPrefs.setCloudEnabled(this, cbCloud.isChecked());
                    AiConfig.Provider sel = rg.getCheckedRadioButtonId() == 2 ? AiConfig.Provider.OPENAI
                            : rg.getCheckedRadioButtonId() == 3 ? AiConfig.Provider.CLAUDE : AiConfig.Provider.GEMINI;
                    AiConfig.setProvider(this, sel);
                    if (sel != AiConfig.Provider.GEMINI) {
                        AiConfig.setKey(this, sel, etKey.getText().toString());
                        AiConfig.setModel(this, sel, etModel.getText().toString());
                    }
                    Toast.makeText(this, getString(R.string.ext_saved, AiConfig.label(sel)), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Glass vs Basic app-style picker — applies instantly (recreates) and every page follows it. */
    private void setupStyleToggle() {
        MaterialButtonToggleGroup toggleStyle = findViewById(R.id.toggleStyle);
        if (toggleStyle == null) return;

        toggleStyle.check(StyleManager.isGlass(this) ? R.id.btnStyleGlass : R.id.btnStyleBasic);

        toggleStyle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean wantGlass = checkedId == R.id.btnStyleGlass;
            if (wantGlass == StyleManager.isGlass(this)) return;
            StyleManager.setGlass(this, wantGlass);
            recreate(); // re-themes this screen now; other pages re-apply on their next onResume
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
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
        boolean isPro = ProStatusManager.isPro(this);
        if (isPro) {
            tvStatus.setText(R.string.pro_status_active);
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            btnBuyPro.setVisibility(View.GONE);
            if (btnUnsubscribe != null) btnUnsubscribe.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText(R.string.pro_status_free);
            tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_subtitle));
            btnBuyPro.setVisibility(View.VISIBLE);
            if (btnUnsubscribe != null) btnUnsubscribe.setVisibility(View.GONE);
        }
        refreshTokenBalance();
    }

    /**
     * Shows the balance in plain terms: roughly how many problems are left this month, with the raw
     * token count and a one-line "what tokens are" reminder underneath. Top-ups (btnBuyTokens) only
     * make sense for Plus users. Tapping the line opens a fuller "what are tokens?" explainer.
     */
    private void refreshTokenBalance() {
        if (tvTokenBalance == null) return;
        boolean plus = Entitlements.isPlus(this);
        long balance = TokenWallet.balance(this);
        long solves = Entitlements.approxSolves(balance);
        tvTokenBalance.setText(getString(R.string.tokens_balance, solves, Entitlements.grouped(balance)));
        tvTokenBalance.setOnClickListener(v -> showTokensInfoDialog());
        if (btnBuyTokens != null) btnBuyTokens.setVisibility(plus ? View.VISIBLE : View.GONE);
    }

    /** Plain-language explainer of what credits are and how money buys them. */
    private void showTokensInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.tokens_what_title)
                .setMessage(getString(R.string.tokens_what_msg,
                        Entitlements.APPROX_TOKENS_PER_SOLVE,
                        Entitlements.grouped(Entitlements.PLUS_MONTHLY_TOKENS),
                        Entitlements.approxSolves(Entitlements.PLUS_MONTHLY_TOKENS)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Buying now lives on the Plans page, which shows the whole money → credits → problems picture in
     * one place instead of a bare list of packs. Keeping a second buying surface here would just be
     * two things to keep in sync.
     */
    private void showBuyTokensDialog() {
        startActivity(new Intent(this, PlansActivity.class));
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