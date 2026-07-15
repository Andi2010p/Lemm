package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The social layer: user directory, friend graph, group chats, and messages.
 *
 * <h3>Cloud shape</h3>
 * <pre>
 * users_public/{uid}   = { username, usernameLower }   PUBLIC - the searchable directory
 * users_info/{uid}     = { ai_keys, is_pro, tok_* }    PRIVATE - owner-only, never readable by peers
 * usernames/{sanitized}= uid                           claim table: proves who owns users/{username}
 *
 * friends/{uid}/{peerUid}           = peerUsername
 * friend_requests/{toUid}/{fromUid} = fromUsername
 *
 * groups/{gid}/meta                 = { name, owner, createdAt }
 * groups/{gid}/members/{uid}        = username           (2..40 members)
 * user_groups/{uid}/{gid}           = groupName          (so I can list my groups)
 *
 * dm/{chatId}/{msgId} = message      chatId = the two uids sorted+joined
 * gm/{gid}/{msgId}    = message
 * </pre>
 *
 * <p><b>Why the directory is a separate node.</b> {@code users_info} holds the user's own AI API keys
 * and purchase state. Username search needs a node every signed-in user can read, so the searchable
 * fields live in {@code users_public} and {@code users_info} stays owner-only. Never move username
 * search back onto {@code users_info}.
 *
 * <p>{@code chatId} is derived from the two uids, so membership is provable from the key alone and no
 * shared chat index has to be stored. Identity is the Firebase Auth uid: <b>guests cannot chat</b>.
 *
 * <p>Deploy the matching rules from {@code docs/database.rules.json}.
 */
public final class Social {

    private Social() {}

    private static final String USERS_PUBLIC = "users_public";
    private static final String USERNAMES = "usernames";
    private static final String FRIENDS = "friends";
    private static final String REQUESTS = "friend_requests";
    private static final String DM = "dm";
    private static final String GROUPS = "groups";
    private static final String USER_GROUPS = "user_groups";
    private static final String GM = "gm";
    private static final String BLOCKED = "blocked";
    private static final String USER_REPORTS = "user_reports";

    /** Highest code point Firebase orders on: [q, q+PREFIX_MAX] is a prefix range. */
    private static final char PREFIX_MAX = (char) 0xF8FF;

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_SOLUTION = "solution";
    public static final String TYPE_DRAWING = "drawing";

    /** A group is at least the creator + 1, and never more than 40 (mirrored in the DB rules). */
    public static final int MIN_GROUP_MEMBERS = 2;
    public static final int MAX_GROUP_MEMBERS = 40;

    // Size caps mirrored by .validate in the rules. Exceed one and the SERVER rejects the whole
    // write, so the client must check them or the send fails silently.
    public static final int MAX_TEXT = 4000;
    public static final int MAX_TITLE = 120;
    public static final int MAX_PROBLEM = 4000;
    public static final int MAX_RAW = 200_000;
    public static final int MAX_DATA = 300_000;
    public static final int MAX_GROUP_NAME = 60;

    // ---------- identity ----------

    /** The signed-in Firebase uid, or null for guests / username-only accounts. */
    public static String uid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return (u == null) ? null : u.getUid();
    }

    public static boolean signedIn() { return uid() != null; }

    public static String myUsername(Context c) {
        return c.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE).getString("username", "");
    }

    private static DatabaseReference db(String path) {
        return FirebaseManager.getDatabase().getReference(path);
    }

    /**
     * Publishes the two things the rest of the system needs:
     * <ul>
     *   <li>{@code users_public/{uid}} so other people can find me by username;</li>
     *   <li>{@code usernames/{sanitized} = uid}, the claim table that lets the rules prove I own
     *       {@code users/{sanitized}} — the node holding my history and drawings. Without it that
     *       node cannot be secured at all, because it is keyed by username rather than by uid.</li>
     * </ul>
     * Claiming is first-come and enforced by the rules, so this silently no-ops if the name is taken.
     */
    public static void publishDirectoryEntry(Context c) {
        publishDirectoryEntry(c, null);
    }

    /**
     * @param onNameTaken runs (on the main thread) when this username is already claimed by a
     *   DIFFERENT account. That is not cosmetic: the rules key {@code users/{name}} — the node
     *   holding this person's history and drawings — off the claim, so a loser of the race silently
     *   stops syncing. They have to be told to rename.
     */
    public static void publishDirectoryEntry(Context c, Runnable onNameTaken) {
        String uid = uid();
        String name = myUsername(c);
        if (uid == null || name.isEmpty() || name.startsWith("GuestUser")) return;

        writeDirectory(uid, name);
        claimUsername(name, owned -> { if (!owned && onNameTaken != null) onNameTaken.run(); });
    }

    /**
     * Writes the searchable directory row {@code users_public/{uid} = {username, usernameLower}}.
     *
     * <p>Call this the moment an account gets a username — at REGISTRATION, not only when the user
     * later reaches the home screen. Search reads this node, so a user who registered but hadn't yet
     * opened MainActivity was simply invisible to their friends. Cheap and idempotent.
     */
    public static void writeDirectory(String uid, String username) {
        if (uid == null || username == null || username.isEmpty() || username.startsWith("GuestUser")) return;
        Map<String, Object> pub = new HashMap<>();
        pub.put("username", username);
        pub.put("usernameLower", username.toLowerCase(Locale.ROOT));
        db(USERS_PUBLIC).child(uid).updateChildren(pub);
    }

    public interface ClaimCallback { void onResult(boolean owned); }

    /**
     * Claims {@code usernames/{sanitized} = uid}. The rules accept it only when the name is unclaimed
     * or already ours, so a rejection means somebody else owns it.
     *
     * <p>This claim is what proves ownership of {@code users/{sanitized}} — the node holding this
     * person's history and drawings. <b>Anything that writes to that node must claim first</b>, or the
     * write is denied and the data is silently lost.
     */
    public static void claimUsername(String username, ClaimCallback cb) {
        String uid = uid();
        if (uid == null || username == null || username.isEmpty() || username.startsWith("GuestUser")) {
            if (cb != null) cb.onResult(false);
            return;
        }
        // `usernames/` is server-only. It has to be: when clients could write it, anyone could read
        // the public directory, spot a user who had not yet claimed their own name, claim it, and
        // thereby seize that user's entire history node. The function claims it atomically.
        //
        // Only a GENUINE conflict ("already-exists") means the name isn't ours. Any other error —
        // the backend not being deployed yet, no network, App Check — must NOT be reported as
        // "taken", or the user gets nagged to rename on every launch over a transient failure.
        LemmaBackend.claimUsername(username, new LemmaBackend.Callback<java.util.Map<String, Object>>() {
            @Override public void onSuccess(java.util.Map<String, Object> v) { if (cb != null) cb.onResult(true); }
            @Override public void onError(String code, String message) {
                if (cb != null) cb.onResult(!"already-exists".equals(code));
            }
        });
    }

    /**
     * Releasing a username on rename/delete is now done by the backend as part of those flows.
     * Kept as a no-op so callers don't need to branch; the claim table is server-only.
     *
     * @deprecated the server releases the name; see functions/lib/social.js#releaseUsername.
     */
    @Deprecated
    public static void releaseUsername(String username) {
        // Intentionally empty: clients cannot write `usernames/` any more.
    }

    // ---------- directory search ----------

    public static final class UserEntry {
        public final String uid, username;
        UserEntry(String uid, String username) { this.uid = uid; this.username = username; }
    }

    public interface UsersCallback { void onUsers(List<UserEntry> users); }

    private static final int SEARCH_SCAN_LIMIT = 500;  // how much of the directory a search examines
    private static final int SEARCH_RESULTS = 25;

    /**
     * Finds people by username, the way a social app does — a <b>substring</b> match, not just
     * "starts with". The old prefix-only query meant that unless you typed the exact beginning of a
     * username (which, when usernames were auto-generated, nobody knew), you found nobody.
     *
     * <p>Realtime Database has no server-side "contains", so we pull a bounded window of the directory
     * and filter it on the device. Prefix matches are surfaced first because that is what a searcher
     * expects at the top. For the app's current scale this reads the whole directory; if it ever grows
     * past {@link #SEARCH_SCAN_LIMIT} users, search moves to the backend. Excludes yourself.
     *
     * <p>Deliberately NOT {@code orderByChild("usernameLower")}: that query <b>fails outright</b> —
     * {@code onCancelled}, empty results — unless the deployed rules declare
     * {@code ".indexOn": ["usernameLower"]} on {@code users_public}. A missing index there is a
     * classic silent "search finds nobody". Ordering by key needs no index and we re-rank locally
     * anyway, so this can never fail for want of an index.
     */
    public static void searchUsers(String rawQuery, UsersCallback cb) {
        final String q = (rawQuery == null) ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { cb.onUsers(new ArrayList<>()); return; }
        final String me = uid();

        Query query = db(USERS_PUBLIC).limitToFirst(SEARCH_SCAN_LIMIT);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<UserEntry> starts = new ArrayList<>();
                List<UserEntry> contains = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    String uid = child.getKey();
                    String name = child.child("username").getValue(String.class);
                    if (uid == null || name == null) continue;
                    if (uid.equals(me)) continue; // never offer to friend yourself

                    String lower = child.child("usernameLower").getValue(String.class);
                    if (lower == null) lower = name.toLowerCase(Locale.ROOT);

                    if (lower.startsWith(q)) starts.add(new UserEntry(uid, name));
                    else if (lower.contains(q)) contains.add(new UserEntry(uid, name));
                }
                starts.addAll(contains); // "starts with" ranked above "contains"
                cb.onUsers(new ArrayList<>(starts.subList(0, Math.min(starts.size(), SEARCH_RESULTS))));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.onUsers(new ArrayList<>()); }
        });
    }

    public interface ProfilesCallback { void onUsers(List<UserProfile> users); }

    /**
     * Suggests people worth adding: <b>your classmates</b>.
     *
     * <p>This exists because plain username search is useless in practice — you can only find someone
     * if you already know the exact name they picked, and nobody does. Matching on school (and then
     * ranking same-grade first) surfaces the people a student actually wants to add, without them
     * having to know anything at all.
     *
     * <p>Same bounded client-side scan as {@link #searchUsers}: no {@code orderByChild}, so it cannot
     * fail because an index is missing from the deployed rules.
     */
    public static void suggestClassmates(Context c, ProfilesCallback cb) {
        final String me = uid();
        final UserProfile mine = UserProfile.mine(c);
        final String school = mine.school == null ? "" : mine.school.trim().toLowerCase(Locale.ROOT);
        if (me == null || school.isEmpty()) { cb.onUsers(new ArrayList<>()); return; }

        db(USERS_PUBLIC).limitToFirst(SEARCH_SCAN_LIMIT)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<UserProfile> sameGrade = new ArrayList<>();
                        List<UserProfile> sameSchool = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            String uid = child.getKey();
                            if (uid == null || uid.equals(me)) continue;

                            UserProfile p = UserProfile.fromSnapshot(child);
                            if (p.username.isEmpty()) continue;

                            String theirs = p.school == null ? "" : p.school.trim().toLowerCase(Locale.ROOT);
                            if (theirs.isEmpty() || !theirs.equals(school)) continue;

                            // Teachers and same-grade students float to the top; other grades below.
                            if (p.isTeacher() || (mine.grade > 0 && p.grade == mine.grade)) sameGrade.add(p);
                            else sameSchool.add(p);
                        }
                        sameGrade.addAll(sameSchool);
                        cb.onUsers(new ArrayList<>(sameGrade.subList(0, Math.min(sameGrade.size(), SEARCH_RESULTS))));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onUsers(new ArrayList<>()); }
                });
    }

    // ---------- friends ----------

    public static DatabaseReference friendsRef() { return db(FRIENDS).child(uid()); }
    public static DatabaseReference requestsRef() { return db(REQUESTS).child(uid()); }

    /** Ask {@code toUid} to be friends. Lands in their friend_requests inbox. */
    public static void sendFriendRequest(Context c, String toUid) {
        String me = uid();
        if (me == null) return;
        db(REQUESTS).child(toUid).child(me).setValue(myUsername(c));
    }

    /**
     * Accept. A friend edge means "both people agreed", and only the server can verify that a
     * pending request actually exists — so `friends/` is server-only and this is a callable.
     * That is what stops a stranger writing themselves into your friend list.
     */
    public static void acceptFriendRequest(Context c, String fromUid, String fromName) {
        if (uid() == null) return;
        LemmaBackend.acceptFriendRequest(fromUid, null);
    }

    /** Declining is just deleting my own inbox entry, which the rules already allow. */
    public static void declineFriendRequest(String fromUid) {
        String me = uid();
        if (me == null) return;
        db(REQUESTS).child(me).child(fromUid).removeValue();
    }

    /** Unfriend both directions. The message history is left intact. */
    public static void removeFriend(String peerUid) {
        if (uid() == null) return;
        LemmaBackend.removeFriend(peerUid, null);
    }

    // ---------- groups ----------

    public static DatabaseReference myGroupsRef() { return db(USER_GROUPS).child(uid()); }
    public static DatabaseReference groupMembersRef(String gid) {
        return db(GROUPS).child(gid).child("members");
    }

    public interface GroupCallback {
        void onCreated(String groupId);
        void onError(String message);
    }

    /**
     * Creates a group with me as owner. {@code others} must not include me.
     *
     * <p>Server-side, because two things have to be true and only a server can check either:
     * <ul>
     *   <li><b>Consent</b> — every member must already be a mutual friend, and friendship required
     *       their explicit accept. Rules cannot ask "is X a friend of the caller".</li>
     *   <li><b>The 2..40 cap</b> — a real count, not a guard-rail. Realtime Database rules cannot
     *       count children (there is no {@code numChildren()}; that's Firestore's {@code size()}),
     *       so the old client-side counter could be desynchronised by any member.</li>
     * </ul>
     */
    public static void createGroup(Context c, String name, List<UserEntry> others, GroupCallback cb) {
        if (uid() == null) { cb.onError("not signed in"); return; }

        List<String> memberUids = new ArrayList<>();
        for (UserEntry u : others) memberUids.add(u.uid);

        LemmaBackend.createGroup(clip(name, MAX_GROUP_NAME), memberUids,
                new LemmaBackend.Callback<Map<String, Object>>() {
                    @Override public void onSuccess(Map<String, Object> res) {
                        Object gid = res.get("groupId");
                        if (gid == null) cb.onError("no group id");
                        else cb.onCreated(String.valueOf(gid));
                    }
                    @Override public void onError(String code, String message) { cb.onError(message); }
                });
    }

    /** Adds one friend. The server enforces mutual friendship and the hard 40-member cap. */
    public static void addToGroup(String gid, String groupName, UserEntry u) {
        if (uid() == null) return;
        LemmaBackend.addToGroup(gid, u.uid, null);
    }

    /** Leaving always succeeds: the server recomputes the count from the actual member list. */
    public static void leaveGroup(String gid) {
        if (uid() == null) return;
        LemmaBackend.leaveGroup(gid, null);
    }

    // ---------- collective (family / classroom) plans ----------

    /** The plan I belong to. Readable only by its owner and seat-holders; written only by the server. */
    public static DatabaseReference familyRef(String familyId) {
        return db("families").child(familyId);
    }

    /** Invitations addressed to me. Accepting one is a server call — nobody is enrolled silently. */
    public static DatabaseReference familyInvitesRef() {
        return db("family_invites").child(uid());
    }

    // ---------- blocking ----------

    /** {@code blocked/{me}/{peerUid} = peerUsername} — the value is only there so the UI can name them. */
    public static DatabaseReference blockedRef() { return db(BLOCKED).child(uid()); }

    /**
     * Blocks someone: they disappear from your lists, their messages are filtered out, and the
     * database rules stop them writing into your DM or sending you a friend request. Blocking also
     * unfriends them — a block you have to "un-friend to make stick" isn't a block.
     */
    /**
     * Blocking must sever the friendship and BOTH pending requests atomically — otherwise a request
     * sent before the block survives in the inbox and can still be accepted. Friend edges are
     * server-only, so the whole operation is one callable, one multi-path write.
     */
    public static void blockUser(String peerUid, String peerUsername) {
        if (uid() == null) return;
        LemmaBackend.blockUser(peerUid, null);
    }

    public static void unblockUser(String peerUid) {
        String me = uid();
        if (me == null) return;
        db(BLOCKED).child(me).child(peerUid).removeValue();
    }

    /** Append-only sink for "this user / this message is abusive" reports. Read it in the console. */
    public static DatabaseReference userReportsRef() { return db(USER_REPORTS); }

    // ---------- threads (a DM or a group) ----------

    /** Both participants derive the same id, so no shared index has to be stored anywhere. */
    public static String chatId(String a, String b) {
        return (a.compareTo(b) < 0) ? a + "_" + b : b + "_" + a;
    }

    /** A conversation: either a 1:1 DM with {@code peerUid}, or the group {@code groupId}. */
    public static final class Thread {
        public final String peerUid;  // null for a group
        public final String groupId;  // null for a DM
        public final String title;

        private Thread(String peerUid, String groupId, String title) {
            this.peerUid = peerUid; this.groupId = groupId; this.title = title;
        }
        public static Thread dm(String peerUid, String title) { return new Thread(peerUid, null, title); }
        public static Thread group(String groupId, String title) { return new Thread(null, groupId, title); }

        public boolean isGroup() { return groupId != null; }
        /** Stable key for the local "last seen" bookkeeping. */
        public String seenKey() { return isGroup() ? "g:" + groupId : "u:" + peerUid; }
    }

    public static DatabaseReference threadRef(Thread t) {
        return t.isGroup() ? db(GM).child(t.groupId) : db(DM).child(chatId(uid(), t.peerUid));
    }

    /** One message. Only the fields its {@code type} uses are populated. */
    public static final class Message {
        public String id, from, fromName, type, text, title, problem, raw, data;
        public long ts;
        public boolean mine(String me) { return me != null && me.equals(from); }

        static Message from(DataSnapshot s) {
            Message m = new Message();
            m.id = s.getKey();
            m.from = s.child("from").getValue(String.class);
            m.fromName = s.child("fromName").getValue(String.class);
            m.type = s.child("type").getValue(String.class);
            m.text = s.child("text").getValue(String.class);
            m.title = s.child("title").getValue(String.class);
            m.problem = s.child("problem").getValue(String.class);
            m.raw = s.child("raw").getValue(String.class);
            m.data = s.child("data").getValue(String.class);
            Long ts = s.child("ts").getValue(Long.class);
            m.ts = (ts == null) ? 0L : ts;
            if (m.type == null) m.type = TYPE_TEXT;
            return m;
        }
    }

    private static Map<String, Object> base(Context c, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("from", uid());
        m.put("fromName", myUsername(c));
        m.put("type", type);
        m.put("ts", ServerValue.TIMESTAMP);
        return m;
    }

    public static void sendText(Context c, Thread t, String text) {
        if (text == null) return;
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        Map<String, Object> m = base(c, TYPE_TEXT);
        m.put("text", text);
        threadRef(t).push().setValue(m);
    }

    /** Clips a string to a cap the rules enforce, so the write is never rejected for length. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Shares a solved problem: the recipient can open it in the solver, figure and all.
     * @return false if the solution is too large to send (the caller should tell the user).
     */
    public static boolean sendSolution(Context c, Thread t, String title, String problem, String raw) {
        if (raw != null && raw.length() > MAX_RAW) return false; // the rules would reject it
        Map<String, Object> m = base(c, TYPE_SOLUTION);
        m.put("title", clip(title, MAX_TITLE));
        m.put("problem", clip(problem, MAX_PROBLEM));
        m.put("raw", raw == null ? "" : raw);
        threadRef(t).push().setValue(m);
        return true;
    }

    /**
     * Shares a drawing (2-D or 3-D — the payload decides which editor opens it).
     * @return false if the drawing is too large to send.
     */
    public static boolean sendDrawing(Context c, Thread t, String title, String data) {
        if (data != null && data.length() > MAX_DATA) return false; // the rules would reject it
        Map<String, Object> m = base(c, TYPE_DRAWING);
        m.put("title", clip(title, MAX_TITLE));
        m.put("data", data == null ? "" : data);
        threadRef(t).push().setValue(m);
        return true;
    }

    // ---------- unread badge (local, no server writes) ----------

    private static SharedPreferences seen(Context c) {
        return c.getSharedPreferences("ChatSeen", Context.MODE_PRIVATE);
    }

    public static void markSeen(Context c, Thread t, long ts) {
        String k = t.seenKey();
        seen(c).edit().putLong(k, Math.max(ts, seen(c).getLong(k, 0))).apply();
    }

    public static long lastSeen(Context c, Thread t) {
        return seen(c).getLong(t.seenKey(), 0);
    }
}
