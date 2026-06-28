package com.example.lemm;

import android.app.Activity;
import android.content.Intent;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * A simple, reusable "how to use this screen" dialog: a scrollable, plain-language list of what each
 * tool does, plus a shortcut to the animated app guide (the onboarding). Used by the drawing, 3D,
 * solver and theorems screens so help is one tap away everywhere.
 */
public final class HelpDialog {
    private HelpDialog() {}

    public static void show(Activity a, int titleRes, int bodyRes) {
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        TextView tv = new TextView(a);
        tv.setText(bodyRes);
        tv.setTextSize(15f);
        tv.setLineSpacing(6f, 1f);
        tv.setPadding(pad, pad / 2, pad, 0);

        ScrollView sv = new ScrollView(a);
        sv.addView(tv);

        new AlertDialog.Builder(a)
                .setTitle(titleRes)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.help_watch_guide,
                        (d, w) -> a.startActivity(new Intent(a, OnboardingActivity.class)))
                .show();
    }
}
