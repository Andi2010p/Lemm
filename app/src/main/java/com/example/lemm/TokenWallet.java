package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Calendar;
import java.util.Locale;

/**
 * A per-user AI-token wallet for the built-in Gemini (which the developer's key pays for).
 *
 * Two buckets:
 *  - {@code allowance} — the plan's monthly grant, RESET at the start of each calendar month.
 *  - {@code extra}     — tokens bought as top-up packs, which NEVER expire.
 * Spending draws the monthly allowance down first, then the purchased extras. Balance = allowance + extra.
 *
 * HONESTY NOTE: this is a CLIENT-side ledger. Like {@link ProStatusManager} it mirrors to
 * {@code users_info/{uid}} so it follows the account across devices, but it is NOT tamper-proof — a
 * determined user could reset it. Real pay-per-token enforcement needs a server proxy that counts
 * tokens from the provider's usage response (see the monetization plan). We only meter the app-owned
 * key; bring-your-own-key usage is never metered here (the user pays their own provider).
 */
public final class TokenWallet {
    private static final String PREFS = "TokenWallet";
    private static final String K_ALLOWANCE = "allowance_left"; // monthly bucket remaining
    private static final String K_EXTRA = "extra_tokens";       // purchased, non-expiring
    private static final String K_MONTH = "period";             // "yyyy-MM" of the last refill
    private static final String K_PLAN = "plan_for_period";     // which plan this period's grant was for
    private static final String K_USED = "lifetime_used";
    /** The uid whose cloud wallet has been pulled into these prefs. Guards against clobbering. */
    private static final String K_SYNCED_UID = "synced_uid";

    private static final String CLOUD_ALLOWANCE = "tok_allowance";
    private static final String CLOUD_EXTRA = "tok_extra";
    private static final String CLOUD_MONTH = "tok_period";

    private TokenWallet() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String currentPeriod() {
        Calendar cal = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
    }

    /**
     * Refills the monthly bucket when a new calendar month begins, AND when the plan changes
     * mid-month.
     *
     * <p>That second case is not cosmetic: without it a free user who buys Plus on the 5th keeps
     * their 20,000-token free grant until the 1st of next month — they pay and receive nothing.
     *
     * <p>The plan the current period was granted for is remembered in {@link #K_PLAN}, which makes
     * this <b>idempotent</b>. That matters: {@code BillingManager.checkPreviousPurchases()} calls
     * {@code ProStatusManager.grant()} on every launch for a Pro user, so a naive "top up when Pro"
     * would hand out a fresh 1,000,000 tokens every time the app started.
     */
    public static void ensureRefill(Context c) {
        SharedPreferences p = prefs(c);
        String period = currentPeriod();
        String plan = Entitlements.isPlus(c) ? "plus" : "free";
        long monthly = Entitlements.monthlyAllowance(c);

        // New month, or first ever run: top the monthly bucket back up. Purchased extras untouched.
        if (!period.equals(p.getString(K_MONTH, ""))) {
            p.edit().putLong(K_ALLOWANCE, monthly).putString(K_MONTH, period).putString(K_PLAN, plan).apply();
            pushToCloud(c);
            return;
        }

        if (!p.contains(K_ALLOWANCE)) {
            p.edit().putLong(K_ALLOWANCE, monthly).putString(K_PLAN, plan).apply();
            return;
        }

        // Same month, but the plan changed since this period's grant was made.
        if (!plan.equals(p.getString(K_PLAN, "free"))) {
            long current = p.getLong(K_ALLOWANCE, 0);
            long adjusted = "plus".equals(plan)
                    ? Math.max(current, monthly)   // upgraded: give them what they just paid for
                    : Math.min(current, monthly);  // downgraded: cap at the free grant
            p.edit().putLong(K_ALLOWANCE, adjusted).putString(K_PLAN, plan).apply();
            pushToCloud(c);
        }
    }

    public static long allowanceLeft(Context c) { ensureRefill(c); return prefs(c).getLong(K_ALLOWANCE, 0); }
    public static long extra(Context c) { return prefs(c).getLong(K_EXTRA, 0); }
    public static long balance(Context c) { return allowanceLeft(c) + extra(c); }

    /** True if there are enough tokens (allowance + purchased) to cover an estimated spend. */
    public static boolean canSpend(Context c, long need) {
        return balance(c) >= Math.max(1, need);
    }

    /** Deducts tokens: monthly allowance first, then purchased extras. */
    public static void spend(Context c, long tokens) {
        if (tokens <= 0) return;
        ensureRefill(c);
        SharedPreferences p = prefs(c);
        long allowance = p.getLong(K_ALLOWANCE, 0);
        long ex = p.getLong(K_EXTRA, 0);
        long fromAllowance = Math.min(allowance, tokens);
        long fromExtra = Math.min(ex, tokens - fromAllowance);
        p.edit()
                .putLong(K_ALLOWANCE, allowance - fromAllowance)
                .putLong(K_EXTRA, ex - fromExtra)
                .putLong(K_USED, p.getLong(K_USED, 0) + tokens)
                .apply();
        pushToCloud(c);
    }

    /** Credits purchased top-up tokens (called after a consumable pack is bought). */
    public static void addExtra(Context c, long tokens) {
        if (tokens <= 0) return;
        SharedPreferences p = prefs(c);
        p.edit().putLong(K_EXTRA, p.getLong(K_EXTRA, 0) + tokens).apply();
        pushToCloud(c);
    }

    /** Rough token estimate for a batch of texts (~4 chars/token) plus padding for the unseen reply. */
    public static long estimateTokens(String... texts) {
        long chars = 0;
        if (texts != null) for (String t : texts) if (t != null) chars += t.length();
        return (chars / 4) + 400;
    }

    // ---- cross-device cloud mirror (mirrors ProStatusManager's pattern) ----

    private static DatabaseReference cloudRef() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null; // guests stay local-only
        return FirebaseManager.getDatabase().getReference("users_info").child(u.getUid());
    }

    private static String currentUid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return (u == null) ? null : u.getUid();
    }

    /** True once this device has actually PULLED this account's wallet at least once. */
    private static boolean syncedForCurrentUser(Context c) {
        String uid = currentUid();
        return uid != null && uid.equals(prefs(c).getString(K_SYNCED_UID, null));
    }

    /**
     * Pushes the wallet up.
     *
     * <p><b>Refuses to write until this device has pulled the account's wallet at least once.</b>
     * Without that guard, a fresh install (or a second device) would write its local defaults —
     * {@code extra = 0} — straight over the account's real, purchased, non-expiring token balance:
     * the very first solve calls {@code ensureRefill()} long before Settings ever calls
     * {@link #syncFromCloud}. That destroyed tokens the user had paid real money for.
     */
    public static void pushToCloud(Context c) {
        DatabaseReference ref = cloudRef();
        if (ref == null) return;
        if (!syncedForCurrentUser(c)) return; // never clobber the cloud with un-synced local defaults
        SharedPreferences p = prefs(c);
        ref.child(CLOUD_ALLOWANCE).setValue(p.getLong(K_ALLOWANCE, 0));
        ref.child(CLOUD_EXTRA).setValue(p.getLong(K_EXTRA, 0));
        ref.child(CLOUD_MONTH).setValue(p.getString(K_MONTH, currentPeriod()));
    }

    /**
     * Pulls the account's wallet into local prefs. <b>Call this at app start</b> (MainActivity), not
     * just when Settings opens — until it has run once, {@link #pushToCloud} refuses to write, so an
     * un-synced device can neither corrupt nor be trusted to publish the balance.
     */
    public static void syncFromCloud(Context c, Runnable onDone) {
        DatabaseReference ref = cloudRef();
        if (ref == null) { ensureRefill(c); if (onDone != null) onDone.run(); return; }
        final String uid = currentUid();
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long allowance = snap.child(CLOUD_ALLOWANCE).getValue(Long.class);
                Long ex = snap.child(CLOUD_EXTRA).getValue(Long.class);
                String period = snap.child(CLOUD_MONTH).getValue(String.class);

                // The pull happened: from here this device is allowed to publish the wallet.
                prefs(c).edit().putString(K_SYNCED_UID, uid).apply();

                if (allowance != null || ex != null) {
                    SharedPreferences.Editor e = prefs(c).edit();
                    if (allowance != null) e.putLong(K_ALLOWANCE, allowance);
                    if (ex != null) e.putLong(K_EXTRA, ex);
                    if (period != null) e.putString(K_MONTH, period);
                    e.apply();
                    ensureRefill(c); // a new month since the cloud value was written still refills
                } else {
                    ensureRefill(c);
                    pushToCloud(c); // never set → seed the cloud from this device
                }
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                ensureRefill(c);
                if (onDone != null) onDone.run();
            }
        });
    }
}
