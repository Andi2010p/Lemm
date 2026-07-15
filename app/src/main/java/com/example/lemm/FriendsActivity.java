package com.example.lemm;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The social hub: search for people, answer friend requests, and open a chat with a friend.
 *
 * The friends list doubles as the chat list — each row lazily loads the last message of that thread
 * and shows an unread dot when it is newer than the last time this device opened the thread.
 */
public class FriendsActivity extends AppCompatActivity {

    private LinearLayout searchResults, requestsContainer, friendsContainer, groupsContainer,
            blockedContainer, suggestContainer;
    private TextView tvSearchLabel, tvRequestsLabel, tvGroupsLabel, tvBlockedLabel, tvEmpty, tvSuggestLabel;
    private EditText etSearch;

    private ValueEventListener friendsListener, requestsListener, groupsListener, blockedListener;
    private final List<String> friendUids = new ArrayList<>();
    /** uids this user has blocked — hidden from search, requests and chats. */
    private final Set<String> blocked = new HashSet<>();
    /** Latest friend-request snapshot, re-rendered whenever either it or {@link #blocked} changes. */
    private final List<Social.UserEntry> pendingRequests = new ArrayList<>();
    /** Friends in list order, so the group-picker checkboxes map back to real users. */
    private final List<Social.UserEntry> friendList = new ArrayList<>();
    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        styleGlass = StyleManager.isGlass(this);
        StyleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friends);

        searchResults = findViewById(R.id.searchResults);
        requestsContainer = findViewById(R.id.requestsContainer);
        friendsContainer = findViewById(R.id.friendsContainer);
        groupsContainer = findViewById(R.id.groupsContainer);
        blockedContainer = findViewById(R.id.blockedContainer);
        tvSearchLabel = findViewById(R.id.tvSearchLabel);
        tvRequestsLabel = findViewById(R.id.tvRequestsLabel);
        tvGroupsLabel = findViewById(R.id.tvGroupsLabel);
        tvBlockedLabel = findViewById(R.id.tvBlockedLabel);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);

        suggestContainer = findViewById(R.id.suggestContainer);
        tvSuggestLabel = findViewById(R.id.tvSuggestLabel);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnNewGroup).setOnClickListener(v -> showCreateGroupDialog());
        findViewById(R.id.btnInvite).setOnClickListener(v -> shareInvite());

        // Chatting needs a stable cloud identity, which guests don't have.
        if (!Social.signedIn()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.sign_in_to_chat)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        Social.publishDirectoryEntry(this); // make me findable / backfill usernameLower

        ImageButton btnSearch = findViewById(R.id.btnSearch);
        btnSearch.setOnClickListener(v -> runSearch());
        etSearch.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); return true; }
            return false;
        });

        attachListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
        if (Social.signedIn()) renderFriendsFromCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // If the user signed out while this screen was open, uid() is null and building the ref
        // would throw — the listeners die with the sign-out anyway.
        if (!Social.signedIn()) return;
        if (friendsListener != null) Social.friendsRef().removeEventListener(friendsListener);
        if (requestsListener != null) Social.requestsRef().removeEventListener(requestsListener);
        if (groupsListener != null) Social.myGroupsRef().removeEventListener(groupsListener);
        if (blockedListener != null) Social.blockedRef().removeEventListener(blockedListener);
    }

    // ---------- live lists ----------

    private void attachListeners() {
        friendsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                friendsContainer.removeAllViews();
                friendUids.clear();
                friendList.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    String uid = child.getKey();
                    String name = child.getValue(String.class);
                    if (uid == null || name == null) continue;
                    friendUids.add(uid);
                    friendList.add(new Social.UserEntry(uid, name));
                    friendsContainer.addView(friendRow(uid, name));
                }
                tvEmpty.setVisibility(friendUids.isEmpty() ? View.VISIBLE : View.GONE);
                renderSuggestions(); // re-filter: never suggest someone who's already a friend
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        Social.friendsRef().addValueEventListener(friendsListener);

        groupsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                groupsContainer.removeAllViews();
                int count = 0;
                for (DataSnapshot child : snap.getChildren()) {
                    String gid = child.getKey();
                    String name = child.getValue(String.class);
                    if (gid == null || name == null) continue;
                    groupsContainer.addView(groupRow(gid, name));
                    count++;
                }
                tvGroupsLabel.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        Social.myGroupsRef().addValueEventListener(groupsListener);

        // Requests and blocks arrive on two independent listeners in a non-deterministic order, so
        // neither may render alone: the requests snapshot would paint a blocked user's request before
        // the block list had loaded, and it would stay there (and stay acceptable) all session.
        // Both listeners therefore just update state and re-run one renderer.
        requestsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                pendingRequests.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    String uid = child.getKey();
                    String name = child.getValue(String.class);
                    if (uid == null || name == null) continue;
                    pendingRequests.add(new Social.UserEntry(uid, name));
                }
                renderRequests();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        Social.requestsRef().addValueEventListener(requestsListener);

        blockedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                blockedContainer.removeAllViews();
                blocked.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    String uid = child.getKey();
                    if (uid == null) continue;
                    String name = child.getValue(String.class);
                    blocked.add(uid);
                    blockedContainer.addView(blockedRow(uid, name == null || name.isEmpty()
                            ? getString(R.string.this_user) : name));
                }
                tvBlockedLabel.setVisibility(blocked.isEmpty() ? View.GONE : View.VISIBLE);
                renderRequests(); // the block set changed — re-filter the requests
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        Social.blockedRef().addValueEventListener(blockedListener);
    }

    /** Single renderer for the requests section, filtered by the (possibly late-loading) block set. */
    private void renderRequests() {
        requestsContainer.removeAllViews();
        int count = 0;
        for (Social.UserEntry u : pendingRequests) {
            if (blocked.contains(u.uid)) continue; // a blocked user can't reach you
            requestsContainer.addView(requestRow(u.uid, u.username));
            count++;
        }
        tvRequestsLabel.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    /** Refreshes previews + unread dots after coming back from a thread. */
    private void renderFriendsFromCache() {
        refreshAllIn(friendsContainer);
        refreshAllIn(groupsContainer);
    }

    private void refreshAllIn(LinearLayout parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View row = parent.getChildAt(i);
            Object tag = row.getTag();
            if (tag instanceof Social.Thread) refreshPreview(row, (Social.Thread) tag);
        }
    }

    // ---------- rows ----------

    private MaterialCardView shell() {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        card.setLayoutParams(lp);
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        return card;
    }

    /** Round monogram avatar — the first letter of the username. */
    private TextView avatar(String name) {
        TextView av = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.rightMargin = dp(12);
        av.setLayoutParams(lp);
        av.setGravity(Gravity.CENTER);
        av.setTextColor(Color.WHITE);
        av.setTypeface(null, Typeface.BOLD);
        av.setBackgroundResource(R.drawable.bg_circle_primary);
        av.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        return av;
    }

    private LinearLayout rowBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        return body;
    }

    /** A friend: tap to chat. Subtitle shows the last message; a dot marks it unread. */
    private View friendRow(String uid, String name) {
        MaterialCardView card = shell();
        card.setTag(Social.Thread.dm(uid, name));
        LinearLayout body = rowBody();
        body.addView(avatar(name));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));

        TextView tvPreview = new TextView(this);
        tvPreview.setTextSize(13f);
        tvPreview.setMaxLines(1);
        tvPreview.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        tvPreview.setTag("preview");

        col.addView(tvName);
        col.addView(tvPreview);
        body.addView(col);

        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dot.setLayoutParams(dlp);
        dot.setBackgroundResource(R.drawable.bg_neon_dot);
        dot.setVisibility(View.GONE);
        dot.setTag("dot");
        body.addView(dot);

        card.addView(body);
        card.setOnClickListener(v -> openThread(Social.Thread.dm(uid, name)));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.remove_friend_confirm, name))
                    .setPositiveButton(R.string.remove_friend, (d, w) -> Social.removeFriend(uid))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });

        refreshPreview(card, Social.Thread.dm(uid, name));
        return card;
    }

    /** A group thread. Long-press to leave. */
    private View groupRow(String gid, String name) {
        MaterialCardView card = shell();
        card.setTag(Social.Thread.group(gid, name));
        LinearLayout body = rowBody();
        body.addView(avatar(name));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));

        TextView tvPreview = new TextView(this);
        tvPreview.setTextSize(13f);
        tvPreview.setMaxLines(1);
        tvPreview.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        tvPreview.setTag("preview");

        col.addView(tvName);
        col.addView(tvPreview);
        body.addView(col);

        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        dot.setBackgroundResource(R.drawable.bg_neon_dot);
        dot.setVisibility(View.GONE);
        dot.setTag("dot");
        body.addView(dot);

        card.addView(body);
        card.setOnClickListener(v -> openThread(Social.Thread.group(gid, name)));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.leave_group_confirm, name))
                    .setPositiveButton(R.string.leave_group, (d, w) -> Social.leaveGroup(gid))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });

        refreshPreview(card, Social.Thread.group(gid, name));
        return card;
    }

    private void openThread(Social.Thread t) {
        Intent i = new Intent(this, MessagesActivity.class);
        if (t.isGroup()) {
            i.putExtra(MessagesActivity.EXTRA_GROUP_ID, t.groupId);
            i.putExtra(MessagesActivity.EXTRA_GROUP_NAME, t.title);
        } else {
            i.putExtra(MessagesActivity.EXTRA_PEER_UID, t.peerUid);
            i.putExtra(MessagesActivity.EXTRA_PEER_NAME, t.title);
        }
        startActivity(i);
    }

    /** Pulls the newest message of this thread for the preview line + unread dot. */
    private void refreshPreview(View row, Social.Thread t) {
        final TextView preview = row.findViewWithTag("preview");
        final View dot = row.findViewWithTag("dot");
        if (preview == null) return;

        Social.threadRef(t).limitToLast(1).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot child : snap.getChildren()) {
                    Social.Message m = Social.Message.from(child);
                    preview.setText(previewOf(m, t));
                    boolean unread = m.ts > Social.lastSeen(FriendsActivity.this, t)
                            && !m.mine(Social.uid());
                    if (dot != null) dot.setVisibility(unread ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private String previewOf(Social.Message m, Social.Thread t) {
        String body;
        if (Social.TYPE_SOLUTION.equals(m.type)) body = getString(R.string.preview_solution);
        else if (Social.TYPE_DRAWING.equals(m.type)) body = getString(R.string.preview_drawing);
        else body = m.text == null ? "" : m.text;
        // In a group it matters who said it.
        if (t.isGroup() && m.fromName != null && !m.fromName.isEmpty()) return m.fromName + ": " + body;
        return body;
    }

    /** A blocked user, with the way back out. */
    private View blockedRow(String uid, String name) {
        MaterialCardView card = shell();
        LinearLayout body = rowBody();
        body.addView(avatar(name));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        body.addView(tvName);

        MaterialButton unblock = new MaterialButton(this);
        unblock.setText(R.string.unblock);
        unblock.setOnClickListener(v -> Social.unblockUser(uid));
        body.addView(unblock);

        card.addView(body);
        return card;
    }

    /** An incoming friend request: accept or decline. */
    private View requestRow(String uid, String name) {
        MaterialCardView card = shell();
        LinearLayout body = rowBody();
        body.addView(avatar(name));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        body.addView(tvName);

        MaterialButton accept = new MaterialButton(this);
        accept.setText(R.string.accept);
        accept.setOnClickListener(v -> {
            Social.acceptFriendRequest(this, uid, name);
            Toast.makeText(this, getString(R.string.friend_added, name), Toast.LENGTH_SHORT).show();
        });
        body.addView(accept);

        MaterialButton decline = new MaterialButton(this);
        decline.setText(R.string.decline);
        decline.setOnClickListener(v -> Social.declineFriendRequest(uid));
        body.addView(decline);

        card.addView(body);
        return card;
    }

    // ---------- classmate suggestions: the real fix for "I can't find my friend" ----------

    /**
     * Username search only ever worked if you already knew the exact name someone picked — and
     * nobody does. This surfaces the people you actually want: others from your school, same grade
     * first. It needs no knowledge and no typing.
     */
    private void renderSuggestions() {
        if (suggestContainer == null) return;
        Social.suggestClassmates(this, people -> {
            if (isFinishing() || isDestroyed()) return;
            suggestContainer.removeAllViews();

            int shown = 0;
            for (UserProfile p : people) {
                if (friendUids.contains(p.uid) || blocked.contains(p.uid)) continue; // already sorted
                suggestContainer.addView(suggestRow(p));
                shown++;
            }
            tvSuggestLabel.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        });
    }

    private View suggestRow(UserProfile p) {
        MaterialCardView card = shell();
        LinearLayout body = rowBody();
        body.addView(avatar(p.label()));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(p.label());
        tvName.setTextSize(16f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        col.addView(tvName);

        // "Grade 9 · Yerevan School 42" — tells you WHY they're being suggested.
        String sub = p.subtitle(this);
        if (!sub.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(sub);
            tvSub.setTextSize(13f);
            tvSub.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
            col.addView(tvSub);
        }
        body.addView(col);

        MaterialButton add = new MaterialButton(this);
        add.setText(R.string.add_friend);
        add.setOnClickListener(v -> {
            Ux.tick(v);
            Social.sendFriendRequest(this, p.uid);
            add.setEnabled(false);
            add.setText(R.string.requested);
        });
        body.addView(add);

        card.addView(body);
        return card;
    }

    /**
     * Share an invite. The plain truth is that a friend can't find you unless they know your
     * username, so hand them the username directly through whatever app they already use.
     */
    private void shareInvite() {
        String username = Social.myUsername(this);
        String text = getString(R.string.invite_text, username, "https://andi2010p.github.io/Lemm/");

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, getString(R.string.invite_friends)));
    }

    // ---------- search ----------

    private void runSearch() {
        String q = etSearch.getText().toString().trim();
        searchResults.removeAllViews();
        if (q.isEmpty()) { tvSearchLabel.setVisibility(View.GONE); return; }
        tvSearchLabel.setVisibility(View.VISIBLE);

        Social.searchUsers(q, allUsers -> {
            searchResults.removeAllViews();
            // Someone you blocked shouldn't resurface in search.
            List<Social.UserEntry> users = new ArrayList<>();
            for (Social.UserEntry u : allUsers) if (!blocked.contains(u.uid)) users.add(u);
            if (users.isEmpty()) {
                TextView none = new TextView(this);
                none.setText(R.string.no_users_found);
                none.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
                searchResults.addView(none);
                return;
            }
            for (Social.UserEntry u : users) searchResults.addView(searchRow(u));
        });
    }

    private View searchRow(Social.UserEntry u) {
        MaterialCardView card = shell();
        LinearLayout body = rowBody();
        body.addView(avatar(u.username));

        TextView tvName = new TextView(this);
        tvName.setText(u.username);
        tvName.setTextSize(16f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tvName.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        body.addView(tvName);

        MaterialButton action = new MaterialButton(this);
        if (friendUids.contains(u.uid)) {
            action.setText(R.string.message);
            action.setOnClickListener(v -> openThread(Social.Thread.dm(u.uid, u.username)));
        } else {
            action.setText(R.string.add_friend);
            action.setOnClickListener(v -> {
                Social.sendFriendRequest(this, u.uid);
                action.setEnabled(false);
                action.setText(R.string.requested);
                Toast.makeText(this, R.string.request_sent, Toast.LENGTH_SHORT).show();
            });
        }
        body.addView(action);

        card.addView(body);
        return card;
    }

    // ---------- creating a group ----------

    /**
     * Name + a checklist of friends. A group is the creator plus 1..39 others, so the total stays
     * inside {@link Social#MAX_GROUP_MEMBERS}. The same cap is enforced by the database rules.
     */
    private void showCreateGroupDialog() {
        if (friendList.isEmpty()) {
            Toast.makeText(this, R.string.group_needs_friends, Toast.LENGTH_LONG).show();
            return;
        }

        final EditText etName = new EditText(this);
        etName.setHint(R.string.group_name_hint);
        etName.setSingleLine(true);

        // SNAPSHOT the friends. `friendList` is cleared and rebuilt by a live ValueEventListener, so
        // if a friend unfriends us (or renames) while this dialog is open, indexing back into the
        // live list would crash with IndexOutOfBounds — or, worse, silently add the wrong people.
        final List<Social.UserEntry> candidates = new ArrayList<>(friendList);
        final boolean[] checked = new boolean[candidates.size()];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), 0);
        root.addView(etName);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.group_pick_members, Social.MAX_GROUP_MEMBERS));
        hint.setTextSize(12f);
        hint.setPadding(0, dp(12), 0, dp(4));
        hint.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        root.addView(hint);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < candidates.size(); i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(this);
            cb.setText(candidates.get(i).username);
            cb.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
            cb.setOnCheckedChangeListener((b, isChecked) -> checked[idx] = isChecked);
            list.addView(cb);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(260)));
        root.addView(scroll);

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_group)
                .setView(root)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.new_group);

                    List<Social.UserEntry> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(candidates.get(i));

                    // +1 for me, the creator.
                    if (picked.isEmpty()) {
                        Toast.makeText(this, R.string.group_pick_someone, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (picked.size() + 1 > Social.MAX_GROUP_MEMBERS) {
                        Toast.makeText(this, getString(R.string.group_too_big, Social.MAX_GROUP_MEMBERS),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    final String groupName = name;
                    Social.createGroup(this, groupName, picked, new Social.GroupCallback() {
                        @Override public void onCreated(String groupId) {
                            Toast.makeText(FriendsActivity.this, R.string.group_created, Toast.LENGTH_SHORT).show();
                            openThread(Social.Thread.group(groupId, groupName));
                        }
                        @Override public void onError(String message) {
                            Toast.makeText(FriendsActivity.this,
                                    getString(R.string.group_create_failed, message), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
