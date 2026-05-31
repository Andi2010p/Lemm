package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import java.util.UUID;

public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (pref.contains("username")) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_start);

        setupLanguagePicker();

        Button btnLogin = findViewById(R.id.btnGoToLogin);
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        }

        Button btnRegister = findViewById(R.id.btnGoToRegister);
        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        }

        Button btnGuest = findViewById(R.id.btnGuestMode);
        if (btnGuest != null) {
            btnGuest.setOnClickListener(v -> autoLoginAsDemoAccount());
        }
/* admin mode
        Button btnQuickStart = findViewById(R.id.btnQuickStart);
        if (btnQuickStart != null) {
            btnQuickStart.setOnClickListener(v -> {
                String uniqueGuestId = "GuestUser_" + UUID.randomUUID().toString().substring(0, 8);
                SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                userPrefs.edit()
                        .putString("username", uniqueGuestId)
                        .putBoolean("is_guest", true)
                        .apply();

                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }

        Button btnAdminMode = findViewById(R.id.btnAdminMode);
        if (btnAdminMode != null) {
            btnAdminMode.setOnClickListener(v -> {
                SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                userPrefs.edit()
                        .putString("username", "Admin_Teacher")
                        .putBoolean("is_guest", false)
                        .apply();

                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }*/
    }

    private void setupLanguagePicker() {
        View card = findViewById(R.id.cardLanguage);
        TextView tvLang = findViewById(R.id.tvCurrentLang);
        if (card == null || tvLang == null) return;

        final String current = getSharedPreferences("Settings", MODE_PRIVATE)
                .getString("Locale.Helper.Selected.Language", "en");
        tvLang.setText(codeToLabel(current));

        card.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, card);
            menu.getMenu().add(0, 0, 0, getString(R.string.language_en));
            menu.getMenu().add(0, 1, 1, getString(R.string.language_ru));
            menu.getMenu().add(0, 2, 2, getString(R.string.language_hy));
            menu.setOnMenuItemClickListener(item -> {
                String code = "en";
                if (item.getItemId() == 1) code = "ru";
                else if (item.getItemId() == 2) code = "hy";
                if (!code.equals(current)) {
                    LocaleHelper.setLocale(this, code);
                    recreate(); // reload the welcome screen in the chosen language
                }
                return true;
            });
            menu.show();
        });
    }

    private String codeToLabel(String code) {
        switch (code) {
            case "ru": return "RU";
            case "hy": return "HY";
            default:   return "EN";
        }
    }

    /**
     * "Guest mode" shortcut — signs in as the shared judge/demo account so the user lands directly
     * in the main page with all of that account's history available. Credentials are read from
     * BuildConfig (gitignored local.properties). This is NOT a true anonymous guest — it's a
     * shared demo account, so cloud sync is enabled (history is the demo account's history).
     */
    private void autoLoginAsDemoAccount() {
        String email = BuildConfig.GUEST_EMAIL;
        String pass  = BuildConfig.GUEST_PASSWORD;
        if (email == null || email.isEmpty() || pass == null || pass.isEmpty()) {
            Toast.makeText(this, "Guest credentials aren't configured.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Signing in…", Toast.LENGTH_SHORT).show();
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        String msg = (task.getException() != null) ? task.getException().getMessage() : "Unknown";
                        Toast.makeText(this, "Guest login failed: " + msg, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String uid = task.getResult().getUser().getUid();
                    // Fetch the demo account's username from the cloud (so History keys to its data),
                    // with a 4-second fallback to the email prefix if Firebase is slow.
                    final boolean[] done = {false};
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (done[0]) return;
                        done[0] = true;
                        finishGuestLogin(email.split("@")[0], email, pass);
                    }, 4000);
                    FirebaseManager.getDatabase().getReference("users_info").child(uid).child("username")
                            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                                    if (done[0]) return; done[0] = true;
                                    String name = snap.getValue(String.class);
                                    if (name == null || name.isEmpty() || name.contains("@")) name = email.split("@")[0];
                                    finishGuestLogin(name, email, pass);
                                }
                                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                                    if (done[0]) return; done[0] = true;
                                    finishGuestLogin(email.split("@")[0], email, pass);
                                }
                            });
                });
    }

    private void finishGuestLogin(String username, String email, String pass) {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        // is_guest = false on purpose — this is a shared signed-in account, NOT a local-only guest,
        // so cloud sync is enabled and the demo account's history shows up.
        pref.edit().putString("username", username).putBoolean("is_guest", false).apply();
        try {
            DatabaseHelper db = new DatabaseHelper(this);
            db.addUser(username, email, pass);
            CloudSyncManager.syncLocalToCloud(db, username);
        } catch (Exception ignored) {}
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}