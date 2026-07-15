package com.example.lemm;

import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small motion / haptics helpers for the futuristic UI (reveal animations + tactile feedback). */
public final class Ux {

    private Ux() {}

    /** A light tactile "tick" on a tap (respects the user's system haptic setting). */
    public static void tick(View v) {
        if (v == null) return;
        try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Exception ignored) {}
    }

    /** Fades + rises a view into place — used to reveal cards/bubbles one by one. */
    public static void revealIn(View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(44f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.max(0, delayMs))
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
