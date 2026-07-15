package com.example.lemm;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

/**
 * The app's visual STYLE — "Glass" (frosted/translucent) or "Basic" (solid classic cards) — chosen in
 * Settings and applied to every themed screen. Each activity calls {@link #apply(Activity)} before
 * setContentView so its layout resolves the appScreen / appCard attributes from the right overlay.
 * The choice is stored in the same "Settings" prefs the light/dark toggle uses.
 */
public final class StyleManager {

    private static final String PREFS = "Settings";
    private static final String KEY = "app_style_glass";

    private StyleManager() {}

    /** True = Glass style (default), false = Basic style. */
    public static boolean isGlass(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setGlass(Context c, boolean glass) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, glass).apply();
    }

    /** Applies the chosen style overlay to the activity's theme. Call BEFORE setContentView. */
    public static void apply(Activity a) {
        a.getTheme().applyStyle(
                isGlass(a) ? R.style.ThemeOverlay_Lemm_Glass : R.style.ThemeOverlay_Lemm_Basic, true);
    }

    /** Re-creates the activity if the style was changed elsewhere (e.g. in Settings) since it was built. */
    public static void recreateIfChanged(Activity a, boolean builtWithGlass) {
        if (isGlass(a) != builtWithGlass) a.recreate();
    }

    /** Resolves a color from a theme attribute (e.g. R.attr.appCardFill) for code-built views. */
    public static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(c, tv.resourceId);
            return tv.data;
        }
        return 0;
    }
}
