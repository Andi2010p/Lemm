package com.example.lemm;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Reporting abusive messages and people.
 *
 * REQUIRED. Once an app lets users send each other content it falls under Google Play's
 * User Generated Content policy (and Apple's Guideline 1.2): there must be an in-app way to report
 * objectionable content <em>and</em> the user who sent it, plus a way to block that user. Reports
 * land in {@code user_reports/} (append-only, unreadable from any client) for you to action.
 *
 * <p>The report carries the message's <b>plaintext</b>, captured on the reporter's device. That is
 * deliberate: it is the same design end-to-end-encrypted messengers use, so moderation keeps working
 * even if the thread itself is later encrypted.
 *
 * @see AiReports for the parallel sink covering AI-generated output.
 */
public final class UserReports {
    private UserReports() {}

    private static final int MAX_CONTENT = 4000;

    /** Reason picker, then submit. {@code m} is the message being reported. */
    public static void showReportDialog(Context c, Social.Thread t, Social.Message m) {
        final int[] reasonIds = {
                R.string.report_reason_abuse,
                R.string.report_reason_bullying,
                R.string.report_reason_spam,
                R.string.report_reason_other,
        };
        CharSequence[] labels = new CharSequence[reasonIds.length];
        for (int i = 0; i < reasonIds.length; i++) labels[i] = c.getString(reasonIds[i]);

        new AlertDialog.Builder(c)
                .setTitle(R.string.report_message_title)
                .setItems(labels, (d, which) -> submit(c, t, m, c.getString(reasonIds[which])))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void submit(Context c, Social.Thread t, Social.Message m, String reason) {
        Map<String, Object> report = new HashMap<>();
        report.put("reporter", Social.uid());
        report.put("reportedUid", m.from == null ? "" : m.from);
        report.put("reportedName", m.fromName == null ? "" : m.fromName);
        report.put("thread", t.isGroup() ? "gm:" + t.groupId : "dm:" + Social.chatId(Social.uid(), t.peerUid));
        report.put("messageId", m.id == null ? "" : m.id);
        report.put("content", plaintextOf(m));
        report.put("reason", reason);
        report.put("ts", ServerValue.TIMESTAMP);

        try {
            // Optimistic: RTDB queues offline writes. But a server-side REJECTION must be surfaced,
            // or the reporter believes an abuse report was filed when it never was.
            Social.userReportsRef().push().setValue(report).addOnFailureListener(e ->
                    Toast.makeText(c, R.string.report_failed, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Toast.makeText(c, R.string.report_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, R.string.report_sent, Toast.LENGTH_LONG).show();
    }

    /** What the reporter actually saw, so a moderator can judge it without the thread. */
    private static String plaintextOf(Social.Message m) {
        String s;
        if (Social.TYPE_SOLUTION.equals(m.type)) {
            s = "[solution] " + safe(m.title) + "\n" + safe(m.problem);
        } else if (Social.TYPE_DRAWING.equals(m.type)) {
            s = "[drawing] " + safe(m.title);
        } else {
            s = safe(m.text);
        }
        return s.length() > MAX_CONTENT ? s.substring(0, MAX_CONTENT) : s;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
