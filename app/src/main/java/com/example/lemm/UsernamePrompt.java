package com.example.lemm;

import android.app.Activity;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

/**
 * Asks the user to CHOOSE their username instead of having one derived from their email.
 *
 * Google sign-in used to name people automatically — {@code account.getDisplayName()}, or the email
 * prefix when that was empty — so a user could end up called "john.doe" with no say in it, and their
 * friends had no idea what to search for. This is shown once, on first Google sign-in, pre-filled
 * with a sanitized suggestion the user can accept or replace.
 */
public final class UsernamePrompt {

    public interface OnChosen { void chosen(String username); }

    private UsernamePrompt() {}

    public static void choose(Activity a, String suggestion, OnChosen cb) {
        final EditText input = new EditText(a);
        input.setHint(R.string.choose_username_hint);
        input.setText(UsernameRules.sanitize(suggestion == null ? "" : suggestion));
        input.setSelection(input.getText().length());

        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.choose_username_title)
                .setMessage(R.string.choose_username_msg)
                .setView(box)
                .setCancelable(false)              // they need a username to continue
                .setPositiveButton(R.string.save, null) // overridden below so it doesn't auto-dismiss
                .create();

        // Validate on click without closing the dialog when the name is invalid.
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    Integer err = UsernameRules.validate(name);
                    if (err != null) { input.setError(a.getString(err)); return; }
                    dialog.dismiss();
                    cb.chosen(name);
                }));

        dialog.show();
    }
}
