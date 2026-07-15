package com.example.lemm;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The plans page — Lemma's paywall.
 *
 * <p>Three jobs, in order of importance:
 * <ol>
 *   <li><b>Make the money → credits → problems chain obvious.</b> Users don't think in tokens. The
 *       page leads with ONE number — how many problems you have left — and states the exchange rate
 *       plainly: 1 credit = 1 solved problem, a chat question is a quarter of one.</li>
 *   <li><b>Introduce the plans</b> with live prices straight from Play (never hard-coded), monthly or
 *       annual.</li>
 *   <li><b>Run a collective plan</b>: one person pays, then invites people by username. Each invitee
 *       accepts and gets their OWN account, their OWN progress and their OWN credits — nothing is
 *       shared except the payment.</li>
 * </ol>
 */
public class PlansActivity extends AppCompatActivity implements BillingManager.BillingListener {

    private BillingManager billing;
    private LinearLayout plansContainer, familyContainer, topupContainer;
    private TextView tvCurrentPlan, tvProblemsLeft, tvProblemsLeftLabel, tvFamilyLabel;
    private ProgressBar barCredits;
    private boolean annual;          // which base plan the user is looking at
    private boolean styleGlass;

    /** Whatever the server last told us. Null until getMyStatus() answers (or if it can't). */
    private Map<String, Object> status;
    private String myFamilyId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        styleGlass = StyleManager.isGlass(this);
        StyleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plans);

        plansContainer = findViewById(R.id.plansContainer);
        familyContainer = findViewById(R.id.familyContainer);
        topupContainer = findViewById(R.id.topupContainer);
        tvCurrentPlan = findViewById(R.id.tvCurrentPlan);
        tvProblemsLeft = findViewById(R.id.tvProblemsLeft);
        tvProblemsLeftLabel = findViewById(R.id.tvProblemsLeftLabel);
        tvFamilyLabel = findViewById(R.id.tvFamilyLabel);
        barCredits = findViewById(R.id.barCredits);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        MaterialButtonToggleGroup togglePeriod = findViewById(R.id.togglePeriod);
        togglePeriod.check(R.id.btnMonthly);
        togglePeriod.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            annual = (id == R.id.btnAnnual);
            renderPlans();
        });

        billing = new BillingManager(this, this);
        billing.startConnection();

        refreshStatus();
        renderWhy();
        renderPlans();
        renderTopups();
        listenForFamilyInvites();
    }

    // ---------- "why would I pay, when Gemini is free?" ----------

    /**
     * The comparison table. This exists because the honest objection to Lemma is <i>"a chatbot is
     * free and already on my phone"</i> — and it can't be answered with a feature list, only by
     * naming the things a chatbot cannot do at all.
     *
     * <p>The first row says a chatbot <b>can</b> answer a geometry question, with a tick in its
     * column. That's not a concession we're forced into — it's what makes the other eight rows
     * believable. A table where the competitor loses everything reads as advertising and gets
     * discounted entirely.
     */
    private void renderWhy() {
        LinearLayout box = findViewById(R.id.whyRows);
        if (box == null) return;
        box.removeAllViews();

        box.addView(whyHeader());
        for (String s : getResources().getStringArray(R.array.why_both)) box.addView(whyRow(s, true));
        for (String s : getResources().getStringArray(R.array.why_only_lemma)) box.addView(whyRow(s, false));
    }

    /** Column headings: (blank) | Chatbot | Lemma */
    private View whyHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(6));

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(spacer);

        row.addView(whyColumnLabel(getString(R.string.why_col_ai),
                ContextCompat.getColor(this, R.color.neon_text_dim)));
        row.addView(whyColumnLabel(getString(R.string.why_col_lemma),
                ContextCompat.getColor(this, R.color.neon_cyan)));
        return row;
    }

    private TextView whyColumnLabel(String text, int color) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(58), -2));
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setAllCaps(true);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(color);
        return tv;
    }

    private View whyRow(String label, boolean chatbotToo) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tv.setText(label);
        tv.setTextSize(14f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        row.addView(tv);

        row.addView(whyMark(chatbotToo ? "✓" : "✕", chatbotToo
                ? ContextCompat.getColor(this, R.color.neon_text)
                : ContextCompat.getColor(this, R.color.neon_text_dim)));
        row.addView(whyMark("✓", ContextCompat.getColor(this, R.color.neon_cyan)));

        // A screen reader would otherwise read "✓ ✕" with no idea which column is which.
        row.setContentDescription(getString(chatbotToo
                ? R.string.why_a11y_both : R.string.why_a11y_lemma_only, label));
        return row;
    }

    private TextView whyMark(String mark, int color) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(58), -2));
        tv.setText(mark);
        tv.setTextSize(16f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(color);
        return tv;
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
        refreshStatus();
    }

    // ---------- "how much do I have left" ----------

    /**
     * Asks the server what the user's plan and remaining credits are. The server is the only
     * authority — the client can read this but never write it.
     *
     * <p>If the backend isn't reachable (not deployed yet), we fall back to the local wallet so the
     * page still shows something honest rather than a spinner forever.
     */
    private void refreshStatus() {
        LemmaBackend.getMyStatus(new LemmaBackend.Callback<Map<String, Object>>() {
            @Override public void onSuccess(Map<String, Object> v) {
                if (isFinishing() || isDestroyed()) return;
                status = v;
                myFamilyId = (v.get("familyId") == null) ? null : String.valueOf(v.get("familyId"));
                showStatus(String.valueOf(v.get("plan")),
                        num(v.get("allowanceLeft")) + num(v.get("extraCredits")),
                        num(v.get("allowanceTotal")),
                        Boolean.TRUE.equals(v.get("perDay")));
                renderFamily();
                renderPlans();
            }
            @Override public void onError(String code, String message) {
                if (isFinishing() || isDestroyed()) return;
                showLocalStatus(); // backend not deployed — use the on-device wallet
            }
        });
    }

    /** Offline / pre-backend view: derive "problems left" from the local token wallet. */
    private void showLocalStatus() {
        boolean plus = Entitlements.isPlus(this);
        long problems = Entitlements.approxSolves(TokenWallet.balance(this));
        long total = Entitlements.approxSolves(Entitlements.monthlyAllowance(this));
        showStatus(plus ? "plus" : "free", problems, total, false);
    }

    private void showStatus(String plan, double left, double total, boolean perDay) {
        tvCurrentPlan.setText(planDisplayName(plan));
        tvProblemsLeft.setText(String.valueOf(Math.max(0, Math.round(left))));
        tvProblemsLeftLabel.setText(perDay
                ? getString(R.string.plans_problems_left_today)
                : getString(R.string.plans_problems_left));

        int pct = (total <= 0) ? 0 : (int) Math.max(0, Math.min(100, Math.round(left / total * 100)));
        barCredits.setProgress(pct);
    }

    private String planDisplayName(String plan) {
        if ("plus".equals(plan)) return getString(R.string.plan_plus_name);
        if ("family".equals(plan)) return getString(R.string.plan_family_name);
        if ("classroom".equals(plan)) return getString(R.string.plan_classroom_name);
        return getString(R.string.plan_free_name);
    }

    private String currentPlan() {
        if (status != null && status.get("plan") != null) return String.valueOf(status.get("plan"));
        return Entitlements.isPlus(this) ? "plus" : "free";
    }

    // ---------- the plan cards ----------

    private void renderPlans() {
        if (plansContainer == null) return;
        plansContainer.removeAllViews();

        plansContainer.addView(planCard(
                BillingManager.SUB_PLUS,
                getString(R.string.plan_plus_name),
                getString(R.string.plan_plus_tagline),
                new String[]{
                        getString(R.string.feat_500_problems),
                        getString(R.string.feat_smarter_model),
                        getString(R.string.feat_choose_model),
                        getString(R.string.feat_no_daily_limit),
                },
                true));

        plansContainer.addView(planCard(
                BillingManager.SUB_FAMILY,
                getString(R.string.plan_family_name),
                getString(R.string.plan_family_tagline),
                new String[]{
                        getString(R.string.feat_6_seats),
                        getString(R.string.feat_500_each),
                        getString(R.string.feat_own_progress),
                        getString(R.string.feat_one_payment),
                },
                false));

        plansContainer.addView(planCard(
                BillingManager.SUB_CLASSROOM,
                getString(R.string.plan_classroom_name),
                getString(R.string.plan_classroom_tagline),
                new String[]{
                        getString(R.string.feat_30_seats),
                        getString(R.string.feat_400_each),
                        getString(R.string.feat_teacher_invite),
                },
                false));
    }

    private View planCard(String productId, String name, String tagline, String[] features, boolean recommended) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        card.setRadius(dp(20));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeWidth(dp(recommended ? 2 : 1));
        card.setStrokeColor(recommended
                ? ContextCompat.getColor(this, R.color.neon_cyan)
                : StyleManager.color(this, R.attr.appCardStroke));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(18), dp(16));

        if (recommended) {
            TextView badge = new TextView(this);
            badge.setText(R.string.plans_most_popular);
            badge.setTextSize(11f);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setAllCaps(true);
            badge.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan));
            col.addView(badge);
        }

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(20f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        col.addView(tvName);

        TextView tvTag = new TextView(this);
        tvTag.setText(tagline);
        tvTag.setTextSize(13f);
        tvTag.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        col.addView(tvTag);

        // Live price from Play for the selected billing period.
        BillingManager.SubOffer offer = pickOffer(productId);
        TextView tvPrice = new TextView(this);
        tvPrice.setTextSize(26f);
        tvPrice.setTypeface(null, Typeface.BOLD);
        tvPrice.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        tvPrice.setPadding(0, dp(10), 0, 0);
        tvPrice.setText(offer != null
                ? offer.formattedPrice + (offer.isAnnual()
                        ? getString(R.string.plans_per_year) : getString(R.string.plans_per_month))
                : getString(R.string.plans_price_pending));
        col.addView(tvPrice);

        for (String f : features) {
            TextView row = new TextView(this);
            row.setText("✓  " + f);
            row.setTextSize(14f);
            row.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
            row.setPadding(0, dp(4), 0, 0);
            col.addView(row);
        }

        MaterialButton cta = new MaterialButton(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(50));
        blp.topMargin = dp(14);
        cta.setLayoutParams(blp);

        boolean isCurrent = productIdForPlan(currentPlan()).equals(productId);
        if (isCurrent) {
            cta.setText(R.string.plans_current);
            cta.setEnabled(false);
        } else {
            cta.setText(getString(R.string.plans_choose, name));
            final BillingManager.SubOffer chosen = offer;
            cta.setOnClickListener(v -> {
                Ux.tick(v);
                if (chosen == null) {
                    Toast.makeText(this, R.string.plan_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                billing.purchaseSubscription(chosen);
            });
        }
        col.addView(cta);

        card.addView(col);
        return card;
    }

    /** The base plan matching the monthly/annual toggle; falls back to whatever Play offers. */
    private BillingManager.SubOffer pickOffer(String productId) {
        List<BillingManager.SubOffer> offers = billing.offersFor(productId);
        if (offers.isEmpty()) return null;
        for (BillingManager.SubOffer o : offers) if (o.isAnnual() == annual) return o;
        return offers.get(0);
    }

    private String productIdForPlan(String plan) {
        if ("plus".equals(plan)) return BillingManager.SUB_PLUS;
        if ("family".equals(plan)) return BillingManager.SUB_FAMILY;
        if ("classroom".equals(plan)) return BillingManager.SUB_CLASSROOM;
        return "";
    }

    // ---------- credit top-ups ----------

    private void renderTopups() {
        topupContainer.removeAllViews();
        for (String pack : BillingManager.CREDIT_PACKS) {
            int credits = BillingManager.creditsForPack(pack);
            String price = billing.tokenPackPrice(pack);

            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            card.setLayoutParams(lp);
            card.setRadius(dp(16));
            card.setCardElevation(0f);
            card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));

            TextView label = new TextView(this);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            label.setText(getString(R.string.plans_pack_line, credits));
            label.setTextSize(15f);
            label.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
            row.addView(label);

            MaterialButton buy = new MaterialButton(this);
            buy.setText(price != null ? price : getString(R.string.plans_price_pending));
            buy.setOnClickListener(v -> { Ux.tick(v); billing.initiateTokenPurchase(pack); });
            row.addView(buy);

            card.addView(row);
            topupContainer.addView(card);
        }
    }

    // ---------- collective plan: one pays, invites the rest ----------

    private void renderFamily() {
        familyContainer.removeAllViews();

        String plan = currentPlan();
        boolean collective = "family".equals(plan) || "classroom".equals(plan);
        if (!collective || myFamilyId == null) {
            tvFamilyLabel.setVisibility(View.GONE);
            return;
        }
        tvFamilyLabel.setVisibility(View.VISIBLE);

        Social.familyRef(myFamilyId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                familyContainer.removeAllViews();

                Long seatLimit = snap.child("seatLimit").getValue(Long.class);
                String owner = snap.child("owner").getValue(String.class);
                DataSnapshot seats = snap.child("seats");
                long used = seats.getChildrenCount();

                TextView summary = new TextView(PlansActivity.this);
                summary.setText(getString(R.string.family_seats_used, used, seatLimit == null ? 0 : seatLimit));
                summary.setTextSize(14f);
                summary.setTextColor(ContextCompat.getColor(PlansActivity.this, R.color.neon_text_dim));
                summary.setPadding(0, 0, 0, dp(8));
                familyContainer.addView(summary);

                for (DataSnapshot seat : seats.getChildren()) {
                    String name = seat.getValue(String.class);
                    familyContainer.addView(seatRow(seat.getKey(), name, owner));
                }

                boolean iAmOwner = owner != null && owner.equals(Social.uid());
                boolean hasRoom = seatLimit != null && used < seatLimit;
                if (iAmOwner && hasRoom) {
                    MaterialButton invite = new MaterialButton(PlansActivity.this);
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(50));
                    blp.topMargin = dp(10);
                    invite.setLayoutParams(blp);
                    invite.setText(R.string.family_invite);
                    invite.setOnClickListener(v -> { Ux.tick(v); showInviteDialog(); });
                    familyContainer.addView(invite);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private View seatRow(String uid, String name, String ownerUid) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        card.setLayoutParams(lp);
        card.setRadius(dp(14));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        boolean isOwner = uid.equals(ownerUid);
        tv.setText(isOwner ? getString(R.string.family_seat_owner, name) : name);
        tv.setTextSize(15f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        row.addView(tv);

        // The owner can free a seat; a member can leave their own.
        boolean iAmOwner = ownerUid != null && ownerUid.equals(Social.uid());
        boolean isMe = uid.equals(Social.uid());
        if (!isOwner && (iAmOwner || isMe)) {
            MaterialButton remove = new MaterialButton(this);
            remove.setText(isMe ? R.string.leave_group : R.string.remove_friend);
            remove.setOnClickListener(v -> {
                Ux.tick(v);
                LemmaBackend.removeFromFamily(myFamilyId, uid, new LemmaBackend.Callback<Map<String, Object>>() {
                    @Override public void onSuccess(Map<String, Object> x) { refreshStatus(); }
                    @Override public void onError(String code, String message) {
                        Toast.makeText(PlansActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            });
            row.addView(remove);
        }

        card.addView(row);
        return card;
    }

    /** Invite by username. They must ACCEPT — nobody is silently enrolled into someone's plan. */
    private void showInviteDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.search_users_hint);
        input.setSingleLine(true);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.family_invite)
                .setMessage(R.string.family_invite_msg)
                .setView(box)
                .setPositiveButton(R.string.family_invite_send, (d, w) -> {
                    String username = input.getText().toString().trim();
                    if (username.isEmpty()) return;
                    LemmaBackend.inviteToFamily(username, new LemmaBackend.Callback<Map<String, Object>>() {
                        @Override public void onSuccess(Map<String, Object> v) {
                            Toast.makeText(PlansActivity.this,
                                    getString(R.string.family_invite_sent, username), Toast.LENGTH_LONG).show();
                        }
                        @Override public void onError(String code, String message) {
                            Toast.makeText(PlansActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** If someone invited me to their plan, offer to accept it. */
    private void listenForFamilyInvites() {
        if (!Social.signedIn()) return;
        Social.familyInvitesRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                for (DataSnapshot invite : snap.getChildren()) {
                    final String familyId = invite.getKey();
                    String from = invite.child("fromName").getValue(String.class);
                    new AlertDialog.Builder(PlansActivity.this)
                            .setTitle(R.string.family_invited_title)
                            .setMessage(getString(R.string.family_invited_msg, from == null ? "" : from))
                            .setPositiveButton(R.string.accept, (d, w) ->
                                    LemmaBackend.acceptFamilyInvite(familyId,
                                            new LemmaBackend.Callback<Map<String, Object>>() {
                                                @Override public void onSuccess(Map<String, Object> v) {
                                                    Toast.makeText(PlansActivity.this,
                                                            R.string.family_joined, Toast.LENGTH_LONG).show();
                                                    refreshStatus();
                                                }
                                                @Override public void onError(String code, String message) {
                                                    Toast.makeText(PlansActivity.this, message, Toast.LENGTH_LONG).show();
                                                }
                                            }))
                            .setNegativeButton(R.string.decline, null)
                            .show();
                    return; // one at a time
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ---------- BillingListener ----------

    @Override public void onBillingReady() { renderPlans(); renderTopups(); }
    @Override public void onPriceFetched(String price) { renderTopups(); }
    @Override public void onPurchaseSuccess() {
        Toast.makeText(this, R.string.plans_thanks, Toast.LENGTH_LONG).show();
        refreshStatus();
    }
    @Override public void onBillingError() { /* products not set up in Play Console yet */ }

    // ---------- utils ----------

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
