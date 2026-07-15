package com.example.lemm;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * In-app reporting of offensive or wrong AI output.
 *
 * REQUIRED BY GOOGLE PLAY. Play's "AI-Generated Content" policy says an app whose users generate AI
 * content must let them report or flag offensive AI output *without leaving the app*, and must feed
 * those reports back into moderation. An app that generates AI text and offers no reporting path gets
 * rejected. Reports land in Realtime DB under {@code ai_reports/} for the developer to review.
 *
 * Attach it wherever AI text is shown: the chat bubbles and the solver's step cards.
 */
public final class AiReports {
    private AiReports() {}

    private static final int MAX_CONTENT = 4000; // don't ship a whole novel to the DB

    /** Reason picker → submit. Pass the exact AI text the user is complaining about. */
    public static void showReportDialog(Context c, String aiText) {
        // The database rules require an authenticated writer, so a guest's report would be silently
        // rejected. Say so instead of thanking them for a report that went nowhere.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            new AlertDialog.Builder(c)
                    .setTitle(R.string.report_ai_title)
                    .setMessage(R.string.report_needs_signin)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final int[] reasonIds = {
                R.string.report_reason_offensive,
                R.string.report_reason_wrong,
                R.string.report_reason_nonsense,
                R.string.report_reason_other,
        };
        CharSequence[] labels = new CharSequence[reasonIds.length];
        for (int i = 0; i < reasonIds.length; i++) labels[i] = c.getString(reasonIds[i]);

        new AlertDialog.Builder(c)
                .setTitle(R.string.report_ai_title)
                .setItems(labels, (d, which) -> submit(c, aiText, c.getString(reasonIds[which])))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Writes the report. Realtime DB queues it offline and flushes on reconnect, so the confirmation
     * is optimistic — but if the server actively <em>rejects</em> it (rules, no auth) we correct the
     * user rather than leaving them believing it was filed.
     */
    public static void submit(Context c, String aiText, String reason) {
        String content = (aiText == null) ? "" : aiText;
        if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            Toast.makeText(c, R.string.report_needs_signin, Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, Object> report = new HashMap<>();
        report.put("uid", u.getUid());
        report.put("reason", reason);
        report.put("content", content);
        report.put("provider", AiConfig.provider(c).name());
        report.put("appVersion", BuildConfig.VERSION_NAME);
        report.put("ts", ServerValue.TIMESTAMP);

        try {
            DatabaseReference ref = FirebaseManager.getDatabase().getReference("ai_reports").push();
            ref.setValue(report).addOnFailureListener(e ->
                    Toast.makeText(c, R.string.report_failed, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Toast.makeText(c, R.string.report_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, R.string.report_sent, Toast.LENGTH_LONG).show();
    }
}
