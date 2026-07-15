package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Makes theorem names mentioned inside an AI solution tappable: a tap opens that theorem's page
 * ({@link GradeCurriculumActivity}). The registry of names is built from the same {@code th_title_*}
 * string resources the Theorems section uses, in the CURRENT locale, so it matches the language the
 * solution is written in (English / Russian / Armenian).
 *
 * <p>Matching is a case-insensitive, non-overlapping, longest-name-first scan; only the first mention
 * of each theorem per text block is linked, so the card stays readable.
 */
public final class TheoremLinker {

    private static final class Entry {
        final String name;      // display name without the leading "N. " prefix, used for matching
        final int grade, topic;
        final String fullTitle; // full localized title (with number) passed to the theorem page header
        Entry(String name, int grade, int topic, String fullTitle) {
            this.name = name; this.grade = grade; this.topic = topic; this.fullTitle = fullTitle;
        }
    }

    private static List<Entry> cache;
    private static String cacheLang;

    /** Builds (and caches, per locale) the list of theorem names → (grade, topic). */
    private static List<Entry> registry(Context ctx) {
        String lang = currentLang(ctx);
        if (cache != null && lang.equals(cacheLang)) return cache;

        List<Entry> list = new ArrayList<>();
        for (int grade = 7; grade <= 12; grade++) {
            for (int topic = 1; topic <= 30; topic++) {
                int id = ctx.getResources().getIdentifier(
                        "th_title_" + grade + "_" + topic, "string", ctx.getPackageName());
                if (id == 0) continue;
                String full = ctx.getString(id);
                String name = normalizeName(full);
                // Skip very short names to avoid accidental matches on common words.
                if (name.length() >= 5) list.add(new Entry(name, grade, topic, full));

                // Optional alternate names / word-roots for this theorem, pipe-separated and localized
                // (th_alias_<grade>_<topic>). Catches "Pythagoras" for "Pythagorean Theorem", "cosine
                // rule" for "Law of Cosines", and inflected forms in Russian / Armenian.
                int aliasId = ctx.getResources().getIdentifier(
                        "th_alias_" + grade + "_" + topic, "string", ctx.getPackageName());
                if (aliasId != 0) {
                    for (String alias : ctx.getString(aliasId).split("\\|")) {
                        String a = alias.trim();
                        if (a.length() >= 5) list.add(new Entry(a, grade, topic, full));
                    }
                }
            }
        }
        // Longest names first so e.g. "Area of a Triangle" wins over a shorter overlapping name.
        Collections.sort(list, (a, b) -> b.name.length() - a.name.length());
        cache = list;
        cacheLang = lang;
        return list;
    }

    /**
     * "3. Similar Triangles (AA)" → "Similar Triangles": drops the leading "N. " and a trailing
     * qualifier in parentheses, so the bare theorem name is what gets matched in the solution text.
     */
    private static String normalizeName(String s) {
        String r = s.replaceFirst("^\\s*\\d+\\.\\s*", "").trim();
        r = r.replaceFirst("\\s*\\([^)]*\\)\\s*$", "").trim();
        return r;
    }

    private static String currentLang(Context ctx) {
        try {
            android.content.res.Configuration c = ctx.getResources().getConfiguration();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                return c.getLocales().get(0).toString();
            }
            return c.locale.toString();
        } catch (Exception e) {
            return "default";
        }
    }

    /**
     * Scans the TextView's current text for theorem names and turns each first mention into a tappable
     * link. Safe to call on any card; does nothing (and leaves the view untouched) if none are found.
     */
    public static void linkify(final Context ctx, TextView tv) {
        if (ctx == null || tv == null || tv.getText() == null) return;

        CharSequence current = tv.getText();
        SpannableStringBuilder sb = (current instanceof SpannableStringBuilder)
                ? (SpannableStringBuilder) current
                : new SpannableStringBuilder(current);

        String hay = sb.toString();
        String hayLower = hay.toLowerCase(Locale.getDefault());

        List<int[]> taken = new ArrayList<>();
        boolean linked = false;

        for (Entry e : registry(ctx)) {
            String needle = e.name.toLowerCase(Locale.getDefault());
            int idx = hayLower.indexOf(needle);
            if (idx < 0) continue;
            int end = idx + needle.length();
            if (overlaps(taken, idx, end)) continue;
            taken.add(new int[]{idx, end});

            final int grade = e.grade, topic = e.topic;
            final String fullTitle = e.fullTitle;
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Intent i = new Intent(ctx, GradeCurriculumActivity.class);
                    i.putExtra("GRADE", grade);
                    i.putExtra("TOPIC", topic);
                    i.putExtra("THEOREM_TITLE", fullTitle);
                    if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(ContextCompat.getColor(ctx, R.color.main_blue));
                    ds.setUnderlineText(true);
                    ds.setFakeBoldText(true);
                }
            }, idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            linked = true;
        }

        if (linked) {
            tv.setText(sb);
            // Let the spans receive taps; taps outside a span still scroll/propagate normally.
            tv.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private static boolean overlaps(List<int[]> taken, int start, int end) {
        for (int[] t : taken) if (start < t[1] && t[0] < end) return true;
        return false;
    }

    private TheoremLinker() {}
}
