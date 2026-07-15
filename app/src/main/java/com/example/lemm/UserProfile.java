package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Who a user actually is: student or teacher, which grade, which school.
 *
 * <p><b>This is not decoration — it is what makes the app findable and useful.</b>
 * <ul>
 *   <li><b>Friend discovery.</b> Search only ever worked if you already knew someone's exact
 *       username, which nobody does. Knowing a person's school and grade lets us suggest the
 *       classmates they actually want to add.</li>
 *   <li><b>Teaching.</b> A teacher gets the Classroom plan and invites a class; a student doesn't.</li>
 *   <li><b>The AI.</b> A grade-9 student and a grade-12 student need different explanations of the
 *       same figure.</li>
 * </ul>
 *
 * <p>Stored in {@code users_public/{uid}} — the same node the directory search reads. Everything here
 * is deliberately non-sensitive: no address, no age, no real name required. School is optional, and
 * a user can clear it at any time from their profile.
 */
public final class UserProfile {

    public static final String ROLE_STUDENT = "student";
    public static final String ROLE_TEACHER = "teacher";

    public static final int MIN_GRADE = 7;
    public static final int MAX_GRADE = 12;

    private static final String PREFS = "UserPrefs";
    private static final String K_ROLE = "profile_role";
    private static final String K_GRADE = "profile_grade";
    private static final String K_SCHOOL = "profile_school";
    private static final String K_DISPLAY = "profile_display";

    public String uid;
    public String username = "";
    public String displayName = "";
    public String role = ROLE_STUDENT;
    public String school = "";
    public int grade = 0;   // 0 = not set / not applicable (teachers)

    private UserProfile() {}

    public boolean isTeacher() { return ROLE_TEACHER.equals(role); }

    /** The name to show in lists — the friendly one if they gave us one, else the username. */
    public String label() {
        return (displayName != null && !displayName.trim().isEmpty()) ? displayName : username;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True until the user has told us whether they're a student or a teacher. */
    public static boolean needsSetup(Context c) {
        if (!Social.signedIn()) return false;                    // guests keep the app simple
        return prefs(c).getString(K_ROLE, "").isEmpty();
    }

    /** The signed-in user's own profile, read from local prefs (fast, no network). */
    public static UserProfile mine(Context c) {
        SharedPreferences p = prefs(c);
        UserProfile up = new UserProfile();
        up.uid = Social.uid();
        up.username = Social.myUsername(c);
        up.displayName = p.getString(K_DISPLAY, "");
        up.role = p.getString(K_ROLE, ROLE_STUDENT);
        up.school = p.getString(K_SCHOOL, "");
        up.grade = p.getInt(K_GRADE, 0);
        return up;
    }

    /**
     * Saves locally AND publishes the searchable fields to {@code users_public/{uid}}.
     *
     * <p>NOTE: the database rules whitelist exactly these field names ({@code $other: false}), so a
     * new field added here must be added to the rules too or the whole write is rejected.
     */
    public static void save(Context c, String displayName, String role, int grade, String school) {
        String uid = Social.uid();
        if (uid == null) return;

        String safeRole = ROLE_TEACHER.equals(role) ? ROLE_TEACHER : ROLE_STUDENT;
        String safeDisplay = clip(displayName, 40);
        String safeSchool = clip(school, 80);
        int safeGrade = ROLE_TEACHER.equals(safeRole) ? 0
                : Math.max(0, Math.min(MAX_GRADE, grade));

        prefs(c).edit()
                .putString(K_DISPLAY, safeDisplay)
                .putString(K_ROLE, safeRole)
                .putInt(K_GRADE, safeGrade)
                .putString(K_SCHOOL, safeSchool)
                .apply();

        String username = Social.myUsername(c);
        Map<String, Object> pub = new HashMap<>();
        pub.put("username", username);
        pub.put("usernameLower", username.toLowerCase(Locale.ROOT));
        pub.put("displayName", safeDisplay);
        pub.put("role", safeRole);
        pub.put("grade", safeGrade);
        pub.put("school", safeSchool);
        pub.put("schoolLower", safeSchool.toLowerCase(Locale.ROOT));

        FirebaseManager.getDatabase().getReference("users_public").child(uid).updateChildren(pub);
    }

    /** Pulls this account's profile from the cloud into local prefs (e.g. after signing in on a new phone). */
    public static void syncFromCloud(Context c, Runnable onDone) {
        String uid = Social.uid();
        if (uid == null) { if (onDone != null) onDone.run(); return; }

        FirebaseManager.getDatabase().getReference("users_public").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String role = snap.child("role").getValue(String.class);
                        if (role != null && !role.isEmpty()) {
                            Long g = snap.child("grade").getValue(Long.class);
                            prefs(c).edit()
                                    .putString(K_ROLE, role)
                                    .putInt(K_GRADE, g == null ? 0 : g.intValue())
                                    .putString(K_SCHOOL, str(snap.child("school").getValue(String.class)))
                                    .putString(K_DISPLAY, str(snap.child("displayName").getValue(String.class)))
                                    .apply();
                        }
                        if (onDone != null) onDone.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { if (onDone != null) onDone.run(); }
                });
    }

    /** Builds a profile from a directory row (used by search + classmate suggestions). */
    static UserProfile fromSnapshot(DataSnapshot s) {
        UserProfile up = new UserProfile();
        up.uid = s.getKey();
        up.username = str(s.child("username").getValue(String.class));
        up.displayName = str(s.child("displayName").getValue(String.class));
        up.role = str(s.child("role").getValue(String.class));
        up.school = str(s.child("school").getValue(String.class));
        Long g = s.child("grade").getValue(Long.class);
        up.grade = (g == null) ? 0 : g.intValue();
        return up;
    }

    /** "Grade 9 · Yerevan School 42" — the one-line subtitle shown under a name. */
    public String subtitle(Context c) {
        StringBuilder sb = new StringBuilder();
        if (isTeacher()) sb.append(c.getString(R.string.role_teacher));
        else if (grade >= MIN_GRADE) sb.append(c.getString(R.string.grade_n, grade));
        if (school != null && !school.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(school);
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String str(String s) { return s == null ? "" : s; }
}
