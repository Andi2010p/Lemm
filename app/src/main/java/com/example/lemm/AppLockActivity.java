package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * The unlock screen shown at cold start when {@link AppLock} is enabled.
 *
 * <p>Offers biometric unlock (if enrolled and preferred) with a PIN fallback. On success it launches
 * {@link #EXTRA_NEXT} (default {@link MainActivity}). Back does NOT bypass it — it sends the app to the
 * background instead. {@code FLAG_SECURE} keeps the locked screen out of the recent-apps thumbnail.
 */
public class AppLockActivity extends AppCompatActivity {

    public static final String EXTRA_NEXT = "next_activity";

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 30_000;

    private TextInputEditText etPin;
    private TextView tvError;
    private MaterialButton btnBiometric;
    private int wrongAttempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StyleManager.apply(this);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        // If somehow launched without a PIN set, don't trap the user out of their app.
        if (!AppLock.isEnabled(this)) { proceed(); return; }

        setContentView(buildUi());

        if (AppLock.isBiometricPreferred(this) && AppLock.canUseBiometric(this)) {
            promptBiometric();
        }
    }

    private View buildUi() {
        int pad = dp(28);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        // appScreenBg is a drawable reference (bg_futuristic / bg_gradient_light), not a color.
        android.util.TypedValue bg = new android.util.TypedValue();
        if (getTheme().resolveAttribute(R.attr.appScreenBg, bg, true) && bg.resourceId != 0) {
            root.setBackgroundResource(bg.resourceId);
        }

        TextView icon = new TextView(this);
        icon.setText("🔒"); // 🔒
        icon.setTextSize(48f);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(this);
        title.setText(R.string.applock_title);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(StyleManager.color(this, android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(12);
        tlp.bottomMargin = dp(24);
        title.setLayoutParams(tlp);
        root.addView(title);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.applock_enter_pin));
        til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        til.setLayoutParams(new LinearLayout.LayoutParams(dp(240), -2));

        etPin = new TextInputEditText(til.getContext());
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setImeOptions(EditorInfo.IME_ACTION_DONE);
        etPin.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(AppLock.MAX_PIN)});
        etPin.setGravity(Gravity.CENTER);
        etPin.setTextSize(24f);
        etPin.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitPin(); return true; }
            return false;
        });
        til.addView(etPin);
        root.addView(til);

        tvError = new TextView(this);
        tvError.setTextColor(0xFFE53935);
        tvError.setTextSize(13f);
        tvError.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.topMargin = dp(8);
        tvError.setLayoutParams(elp);
        root.addView(tvError);

        MaterialButton btnUnlock = new MaterialButton(this);
        btnUnlock.setText(R.string.applock_unlock);
        btnUnlock.setOnClickListener(v -> submitPin());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(240), -2);
        ulp.topMargin = dp(20);
        btnUnlock.setLayoutParams(ulp);
        root.addView(btnUnlock);

        btnBiometric = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBiometric.setText(R.string.applock_use_biometric);
        btnBiometric.setOnClickListener(v -> promptBiometric());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(240), -2);
        blp.topMargin = dp(8);
        btnBiometric.setLayoutParams(blp);
        btnBiometric.setVisibility(
                AppLock.canUseBiometric(this) ? View.VISIBLE : View.GONE);
        root.addView(btnBiometric);

        return root;
    }

    private void submitPin() {
        if (etPin == null) return;
        String pin = etPin.getText() == null ? "" : etPin.getText().toString();
        if (pin.isEmpty()) return;

        if (AppLock.verifyPin(this, pin)) { proceed(); return; }

        wrongAttempts++;
        etPin.setText("");
        if (wrongAttempts >= MAX_ATTEMPTS) {
            lockOutTemporarily();
        } else {
            tvError.setText(getString(R.string.applock_wrong_pin,
                    MAX_ATTEMPTS - wrongAttempts));
        }
    }

    /** After too many misses, freeze entry briefly. Deters shoulder-surf brute forcing. */
    private void lockOutTemporarily() {
        etPin.setEnabled(false);
        tvError.setText(getString(R.string.applock_locked_out, LOCKOUT_MS / 1000));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            wrongAttempts = 0;
            if (!isFinishing()) {
                etPin.setEnabled(true);
                tvError.setText("");
            }
        }, LOCKOUT_MS);
    }

    private void promptBiometric() {
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        proceed();
                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        // User cancelled or hit "Use PIN" — just fall back to the PIN field silently.
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            Toast.makeText(AppLockActivity.this, errString, Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.applock_title))
                .setSubtitle(getString(R.string.applock_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.applock_use_pin))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
        prompt.authenticate(info);
    }

    private void proceed() {
        Intent intent = null;
        String next = getIntent().getStringExtra(EXTRA_NEXT);
        if (next != null) {
            try {
                intent = new Intent(this, Class.forName(next));
            } catch (ClassNotFoundException ignored) { /* fall through to default */ }
        }
        if (intent == null) intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Back must not skip the lock — send the app to the background instead of unlocking.
        moveTaskToBack(true);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
