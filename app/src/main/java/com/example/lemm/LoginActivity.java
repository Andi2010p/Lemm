package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;

public class LoginActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    private EditText etIdentifier, etPassword;
    private Button btnLogin;
    private com.google.android.material.button.MaterialButton btnGoogleLogin;
    private TextView tvSignUp, tvForgotPassword;
    private DatabaseHelper dbHelper;

    private static final int RC_SIGN_IN = 100;
    private static final int RC_PLAY_SERVICES = 101;
    private GoogleSignInClient mGoogleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        dbHelper = new DatabaseHelper(this);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        etIdentifier = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin);
        tvSignUp = findViewById(R.id.tvSignUp);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);

        // "Guest mode" from the welcome screen passes credentials in as extras — pre-fill them
        // so the user just has to tap Login.
        String prefillEmail = getIntent().getStringExtra("PREFILL_EMAIL");
        String prefillPassword = getIntent().getStringExtra("PREFILL_PASSWORD");
        if (prefillEmail != null && !prefillEmail.isEmpty()) etIdentifier.setText(prefillEmail);
        if (prefillPassword != null && !prefillPassword.isEmpty()) etPassword.setText(prefillPassword);

        // --- MAIN LOGIN BUTTON LOGIC ---
        btnLogin.setOnClickListener(v -> {
            try {
                String identifier = etIdentifier.getText().toString().trim();
                String pass = etPassword.getText().toString().trim();

                if (identifier.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
                    return;
                }

                btnLogin.setText("Connecting...");
                btnLogin.setEnabled(false);

                if (identifier.contains("@")) {
                    // 1. LOG INTO FIREBASE WITH EMAIL
                    mAuth.signInWithEmailAndPassword(identifier, pass)
                            .addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    String uid = task.getResult().getUser().getUid();

                                    // CHECK LOCAL DB FIRST (Prevents "Welcome [email]" bug)
                                    String localUsername = dbHelper.authenticateUser(identifier, pass);
                                    if (localUsername != null && !localUsername.contains("@")) {
                                        try {
                                            FirebaseManager.getDatabase().getReference("users_info")
                                                    .child(uid).child("username").setValue(localUsername);
                                        } catch (Exception ignored) {}

                                        // Sync offline local data to cloud on login
                                        CloudSyncManager.syncLocalToCloud(dbHelper, localUsername);

                                        AuthManager.sendLoginAlert(identifier, localUsername);
                                        Toast.makeText(LoginActivity.this, "Welcome " + localUsername + "!", Toast.LENGTH_SHORT).show();
                                        saveSessionAndGoMain(localUsername, false);
                                    } else {
                                        // BRAND NEW DEVICE: FETCH FROM CLOUD WITH A 7-SECOND TIMEOUT
                                        final boolean[] hasResponded = {false};

                                        // Start a 7-second timer
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                            if (!hasResponded[0]) {
                                                hasResponded[0] = true; // Block the cloud from triggering later
                                                String fallback = AuthManager.fallbackUsernameFromEmail(identifier);
                                                try { dbHelper.addUser(fallback, identifier, pass); } catch (Exception ignored) {}

                                                // Sync offline data using fallback name
                                                CloudSyncManager.syncLocalToCloud(dbHelper, fallback);

                                                Toast.makeText(LoginActivity.this, "Network slow. Logged in offline!", Toast.LENGTH_SHORT).show();
                                                saveSessionAndGoMain(fallback, false);
                                            }
                                        }, 4000); // fallback after 4s if the cloud username is slow

                                        // Ask the Cloud
                                        FirebaseManager.getDatabase().getReference("users_info")
                                                .child(uid).child("username")
                                                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                                    @Override
                                                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                                                        if (hasResponded[0]) return; // Stop if timeout already happened
                                                        hasResponded[0] = true;

                                                        String cloudUsername = snapshot.getValue(String.class);
                                                        if (cloudUsername == null || cloudUsername.isEmpty() || cloudUsername.contains("@")) {
                                                            cloudUsername = AuthManager.fallbackUsernameFromEmail(identifier);
                                                            try {
                                                                FirebaseManager.getDatabase().getReference("users_info")
                                                                        .child(uid).child("username").setValue(cloudUsername);
                                                            } catch (Exception ignored) {}
                                                        }

                                                        try { dbHelper.addUser(cloudUsername, identifier, pass); } catch (Exception ignored) {}

                                                        // Sync offline data using correct cloud name
                                                        CloudSyncManager.syncLocalToCloud(dbHelper, cloudUsername);

                                                        AuthManager.sendLoginAlert(identifier, cloudUsername);
                                                        Toast.makeText(LoginActivity.this, "Welcome " + cloudUsername + "!", Toast.LENGTH_SHORT).show();
                                                        saveSessionAndGoMain(cloudUsername, false);
                                                    }

                                                    @Override
                                                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                                                        if (hasResponded[0]) return;
                                                        hasResponded[0] = true;
                                                        String fallback = AuthManager.fallbackUsernameFromEmail(identifier);

                                                        // Sync offline data using fallback name
                                                        CloudSyncManager.syncLocalToCloud(dbHelper, fallback);

                                                        saveSessionAndGoMain(fallback, false);
                                                    }
                                                });
                                    }
                                } else {
                                    // FIREBASE FAILED
                                    btnLogin.setText(getString(R.string.login));
                                    btnLogin.setEnabled(true);
                                    String errorMsg = (task.getException() != null) ? task.getException().getMessage() : "Invalid credentials";
                                    Toast.makeText(LoginActivity.this, "Login Failed: " + errorMsg, Toast.LENGTH_LONG).show();
                                }
                            });
                } else {
                    // USERNAME LOGIN
                    String localUsername = dbHelper.authenticateUser(identifier, pass);
                    if (localUsername != null) {
                        // Sync offline local data to cloud on login
                        CloudSyncManager.syncLocalToCloud(dbHelper, localUsername);
                        saveSessionAndGoMain(localUsername, false);
                    } else {
                        String linkedEmail = dbHelper.getUserEmail(identifier);
                        if (linkedEmail != null && !linkedEmail.isEmpty()) {
                            mAuth.signInWithEmailAndPassword(linkedEmail, pass).addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    dbHelper.updatePassword(identifier, pass);

                                    // Sync offline local data to cloud on login
                                    CloudSyncManager.syncLocalToCloud(dbHelper, identifier);

                                    AuthManager.sendLoginAlert(linkedEmail, identifier);
                                    Toast.makeText(LoginActivity.this, "Password synced! Welcome back.", Toast.LENGTH_SHORT).show();
                                    saveSessionAndGoMain(identifier, false);
                                } else {
                                    btnLogin.setText(getString(R.string.login));
                                    btnLogin.setEnabled(true);
                                    Toast.makeText(LoginActivity.this, "Login Failed: Invalid credentials.", Toast.LENGTH_LONG).show();
                                }
                            });
                        } else {
                            btnLogin.setText(getString(R.string.login));
                            btnLogin.setEnabled(true);
                            Toast.makeText(this, "Login Failed. Try using your Email Address.", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            } catch (Exception e) {
                btnLogin.setText(getString(R.string.login));
                btnLogin.setEnabled(true);
                Toast.makeText(this, "App Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        // --- GOOGLE LOGIN ---
        btnGoogleLogin.setOnClickListener(v -> startGoogleSignIn());

        tvSignUp.setOnClickListener(v -> startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void showForgotPasswordDialog() {
        String currentInput = etIdentifier.getText().toString().trim();

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Reset Password");
        builder.setMessage("Enter your registered email address. We will send you a secure link to reset your password.");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 0);

        com.google.android.material.textfield.TextInputLayout textInputLayout =
                new com.google.android.material.textfield.TextInputLayout(this, null,
                        com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        textInputLayout.setHint(getString(R.string.email));

        final com.google.android.material.textfield.TextInputEditText inputEmail =
                new com.google.android.material.textfield.TextInputEditText(textInputLayout.getContext());
        inputEmail.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        if (currentInput.contains("@")) {
            inputEmail.setText(currentInput);
        }

        textInputLayout.addView(inputEmail);
        layout.addView(textInputLayout);
        builder.setView(layout);

        builder.setPositiveButton("Send Link", (dialog, which) -> {
            String email = inputEmail.getText().toString().trim();
            if (email.isEmpty() || !email.contains("@")) {
                Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "Sending link...", Toast.LENGTH_SHORT).show();

            mAuth.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(this, "✅ Reset link sent! Check your inbox & spam folder.", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        throw task.getException();
                    } catch (com.google.firebase.auth.FirebaseAuthInvalidUserException e) {
                        Toast.makeText(this, "❌ Error: No account exists with this email.", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        });

        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    /**
     * Launches Google Sign-In, but first verifies Google Play Services is present and current.
     * A missing or outdated Play Services is the usual reason the button seems to "do nothing":
     * the sign-in intent cancels instantly with no account chooser and no error. Surfacing it as a
     * resolvable dialog (or a clear toast) turns a silent no-op into something the user can act on.
     */
    private void startGoogleSignIn() {
        GoogleApiAvailability avail = GoogleApiAvailability.getInstance();
        int status = avail.isGooglePlayServicesAvailable(this);
        if (status != ConnectionResult.SUCCESS) {
            android.util.Log.e("GoogleSignIn", "Google Play Services unavailable, status=" + status);
            if (avail.isUserResolvableError(status)) {
                android.app.Dialog d = avail.getErrorDialog(this, status, RC_PLAY_SERVICES);
                if (d != null) d.show();
            } else {
                Toast.makeText(this, "This device has no usable Google Play Services, so Google Sign-In can't run here. Use email login instead.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            startActivityForResult(mGoogleSignInClient.getSignInIntent(), RC_SIGN_IN);
        } catch (Exception e) {
            android.util.Log.e("GoogleSignIn", "Failed to launch the sign-in intent", e);
            Toast.makeText(this, "Couldn't open Google Sign-In: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        android.util.Log.d("GoogleSignIn", "onActivityResult req=" + requestCode
                + " resultCode=" + resultCode + " hasData=" + (data != null));

        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account == null || account.getIdToken() == null) {
                    // No ID token means requestIdToken() got the wrong/empty Web client ID.
                    android.util.Log.e("GoogleSignIn", "No ID token in the returned account.");
                    Toast.makeText(this, "Google Sign-In failed: no ID token returned. The Web client ID is wrong or missing.", Toast.LENGTH_LONG).show();
                    return;
                }
                firebaseAuthWithGoogle(account.getIdToken(), account);
            } catch (ApiException e) {
                android.util.Log.e("GoogleSignIn", "ApiException code=" + e.getStatusCode() + " "
                        + com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.getStatusCodeString(e.getStatusCode()), e);

                if (e.getStatusCode() == com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR) {
                    // Status 10. Google is saying "no OAuth client exists for the package + signing
                    // certificate this APK actually has". Re-pasting the SHA-1 you THINK you have is
                    // the standard response and it fixes nothing, because the fingerprint that
                    // matters is the one below — read off the installed app, not off the build
                    // machine. Show it, so the guessing stops.
                    String report = AuthDiagnostics.report(this);
                    android.util.Log.e("GoogleSignIn", "DEVELOPER_ERROR (10). Config as the phone sees it:\n" + report);
                    new AlertDialog.Builder(this)
                            .setTitle("Google Sign-In misconfigured")
                            .setMessage(report)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                Toast.makeText(this, AuthManager.googleSignInError(e.getStatusCode()), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void firebaseAuthWithGoogle(String idToken, GoogleSignInAccount account) {
        com.google.firebase.auth.AuthCredential credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null);

        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        String email = account.getEmail();
                        String uid = task.getResult().getUser().getUid();

                        // FIX: Check if they already have an established username in users_info first!
                        FirebaseManager.getDatabase().getReference("users_info")
                                .child(uid).child("username")
                                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                    @Override
                                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                                        String existing = snapshot.getValue(String.class);
                                        if (existing == null || existing.isEmpty()) {
                                            // First Google sign-in for this account: let them CHOOSE a
                                            // username rather than derive one from their email.
                                            String suggestion = (account.getDisplayName() != null)
                                                    ? account.getDisplayName()
                                                    : (email != null ? email.split("@")[0] : "");
                                            UsernamePrompt.choose(LoginActivity.this, suggestion,
                                                    chosen -> {
                                                        FirebaseManager.getDatabase().getReference("users_info")
                                                                .child(uid).child("username").setValue(chosen);
                                                        Social.writeDirectory(uid, chosen);
                                                        Social.claimUsername(chosen, null);
                                                        finishGoogleLogin(account, email, chosen);
                                                    });
                                        } else {
                                            // Returning user — make sure they're in the directory, then continue.
                                            Social.writeDirectory(uid, existing);
                                            finishGoogleLogin(account, email, existing);
                                        }
                                    }

                                    @Override
                                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                                        Toast.makeText(LoginActivity.this, "Google Login Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        String em = (task.getException() != null) ? task.getException().getMessage() : "unknown error";
                        android.util.Log.e("GoogleSignIn", "Firebase signInWithCredential failed: " + em, task.getException());
                        Toast.makeText(LoginActivity.this,
                                "Firebase rejected the Google login: " + em
                                        + "\n(If this mentions the provider being disabled, enable Google in Firebase Console → Authentication → Sign-in method.)",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Finishes a Google login once we have the account's final username. */
    private void finishGoogleLogin(GoogleSignInAccount account, String email, String username) {
        try {
            dbHelper.syncGoogleUser(username, email, account.getId());
            CloudSyncManager.syncLocalToCloud(dbHelper, username);
        } catch (Exception ignored) {}

        AuthManager.sendGoogleWelcome(email, username);
        Toast.makeText(LoginActivity.this, getString(R.string.welcome, username), Toast.LENGTH_SHORT).show();
        saveSessionAndGoMain(username, false);
    }

    private void saveSessionAndGoMain(String username, boolean isGuest) {
        AuthManager.saveSession(this, username, isGuest);
        startActivity(new Intent(LoginActivity.this, MainActivity.class));
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}