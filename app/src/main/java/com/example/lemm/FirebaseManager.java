package com.example.lemm;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FirebaseManager {

    // The Realtime Database lives in europe-west1. getInstance() without this explicit URL can
    // silently fail to reach a non-us-central1 database, so writes queue forever and never commit.
    private static final String DB_URL = "https://lemma-37061-default-rtdb.europe-west1.firebasedatabase.app";

    public static FirebaseDatabase getDatabase() {
        return FirebaseDatabase.getInstance(DB_URL);
    }

    /** Forces lowercase so "Andi" and "andi" always resolve to the same legacy folder. */
    public static String sanitizeUser(String user) {
        if (user == null) return "guestuser";
        return user.replaceAll("[.#$\\[\\]]", "_").toLowerCase();
    }

    /** The signed-in account's uid, or null for guests / not-yet-signed-in. */
    public static String uid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getUid();
    }

    /**
     * The node holding this account's synced history and drawings: {@code users/{uid}}.
     *
     * <p><b>This used to be keyed by username, and that lost people's data.</b> The rules could not
     * prove that {@code users/{some-name}} belonged to you from the request alone, so ownership was
     * proven through a claim table, {@code usernames/{name} = uid}, that only a Cloud Function may
     * write. Until that backend is deployed the table is empty, so every read and write of every
     * user's history was denied — silently, because the failures were only logged. The local SQLite
     * copy was then the sole copy of the data, and uninstalling the app destroyed it.
     *
     * <p>Keyed by uid, ownership is <b>self-proving</b>: the rule is {@code $key === auth.uid} and
     * needs no table, no backend and no network round-trip to establish. It also means renaming your
     * username no longer moves (or orphans) your data.
     *
     * @return the node, or {@code null} when nobody is signed in — callers must skip cloud work
     *         rather than fall back to a path the server will reject.
     */
    public static DatabaseReference getUserRef() {
        String uid = uid();
        if (uid == null) return null;
        return getDatabase().getReference("users").child(uid);
    }

    /**
     * @deprecated the username is ignored — the node is keyed by uid. Kept so existing call sites
     *     keep compiling and keep pointing at the <i>right</i> node. Use {@link #getUserRef()}.
     */
    @Deprecated
    public static DatabaseReference getUserRef(String username) {
        return getUserRef();
    }

    /**
     * The old username-keyed node, {@code users/{sanitized}}. The client cannot read it — the rules
     * gate it behind the (empty) claim table — so recovering anything left there is the job of
     * {@code tools/migrate-history-to-uid.js}, which runs with admin credentials.
     */
    public static DatabaseReference legacyUserRef(String username) {
        return getDatabase().getReference("users").child(sanitizeUser(username));
    }

    public static String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}
