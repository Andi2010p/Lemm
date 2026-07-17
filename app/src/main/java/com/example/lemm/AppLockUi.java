package com.example.lemm;

import android.app.Activity;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * All the dialogs for turning the {@link AppLock} on/off and changing its PIN. Kept out of the
 * activities so Settings and the first-run offer share exactly one implementation.
 */
final class AppLockUi {

    private AppLockUi() {}

    /** The Settings entry point: set the lock up if there's no PIN yet, otherwise manage it. */
    static void manage(Activity a) {
        if (!AppLock.hasPin(a)) { createPin(a, null); return; }

        List<String> opts = new ArrayList<>();
        opts.add(a.getString(R.string.applock_change_pin));
        if (AppLock.canUseBiometric(a)) {
            opts.add(a.getString(AppLock.isBiometricPreferred(a)
                    ? R.string.applock_biometric_off : R.string.applock_biometric_on));
        }
        opts.add(a.getString(R.string.applock_turn_off));

        new AlertDialog.Builder(a)
                .setTitle(R.string.applock_settings)
                .setItems(opts.toArray(new CharSequence[0]), (d, which) -> {
                    String choice = opts.get(which);
                    if (choice.equals(a.getString(R.string.applock_change_pin))) {
                        createPin(a, null);
                    } else if (choice.equals(a.getString(R.string.applock_turn_off))) {
                        confirmDisable(a);
                    } else {
                        boolean now = !AppLock.isBiometricPreferred(a);
                        AppLock.setBiometricPreferred(a, now);
                        Toast.makeText(a, now ? R.string.applock_biometric_on_done
                                : R.string.applock_biometric_off_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Shown once, the first time the user reaches the home screen, unless they already set a lock. */
    static void offerSetup(Activity a) {
        if (!AppLock.shouldOfferSetup(a)) return;
        AppLock.markPrompted(a);
        new AlertDialog.Builder(a)
                .setTitle(R.string.applock_offer_title)
                .setMessage(R.string.applock_offer_msg)
                .setPositiveButton(R.string.applock_offer_yes, (d, w) -> createPin(a, null))
                .setNegativeButton(R.string.applock_offer_no, null)
                .show();
    }

    /** Enter-and-confirm a new PIN. Validates length + match before it will dismiss. */
    private static void createPin(Activity a, Runnable onDone) {
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        EditText p1 = pinField(a, a.getString(R.string.applock_new_pin));
        EditText p2 = pinField(a, a.getString(R.string.applock_confirm_pin));
        root.addView(p1);
        root.addView(p2);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.applock_set_pin)
                .setView(root)
                .setPositiveButton(R.string.save, null) // overridden below so bad input doesn't dismiss
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a1 = p1.getText().toString();
            String a2 = p2.getText().toString();
            if (a1.length() < AppLock.MIN_PIN || a1.length() > AppLock.MAX_PIN) {
                Toast.makeText(a, a.getString(R.string.applock_pin_length,
                        AppLock.MIN_PIN, AppLock.MAX_PIN), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!a1.equals(a2)) {
                Toast.makeText(a, R.string.applock_pin_mismatch, Toast.LENGTH_SHORT).show();
                return;
            }
            AppLock.setPin(a, a1);
            dialog.dismiss();
            if (AppLock.canUseBiometric(a)) askBiometricPref(a);
            else Toast.makeText(a, R.string.applock_enabled, Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.run();
        });
    }

    private static void askBiometricPref(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.applock_enabled)
                .setMessage(R.string.applock_biometric_ask)
                .setPositiveButton(R.string.applock_offer_yes, (d, w) -> {
                    AppLock.setBiometricPreferred(a, true);
                    Toast.makeText(a, R.string.applock_biometric_on_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.applock_offer_no, (d, w) ->
                        AppLock.setBiometricPreferred(a, false))
                .show();
    }

    /** Turning the lock off requires the current PIN, so a passer-by can't just disable it. */
    private static void confirmDisable(Activity a) {
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);
        EditText pin = pinField(a, a.getString(R.string.applock_enter_pin));
        root.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.applock_turn_off)
                .setView(root)
                .setPositiveButton(R.string.applock_turn_off, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (AppLock.verifyPin(a, pin.getText().toString())) {
                AppLock.disable(a);
                dialog.dismiss();
                Toast.makeText(a, R.string.applock_disabled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(a, R.string.applock_wrong_pin_simple, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static EditText pinField(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AppLock.MAX_PIN)});
        return e;
    }
}
