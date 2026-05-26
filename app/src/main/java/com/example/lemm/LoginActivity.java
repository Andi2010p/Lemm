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
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
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

                                        sendLoginAlert(identifier, localUsername);
                                        Toast.makeText(LoginActivity.this, "Welcome " + localUsername + "!", Toast.LENGTH_SHORT).show();
                                        saveSessionAndGoMain(localUsername, false);
                                    } else {
                                        // BRAND NEW DEVICE: FETCH FROM CLOUD WITH A 7-SECOND TIMEOUT
                                        final boolean[] hasResponded = {false};

                                        // Start a 7-second timer
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                            if (!hasResponded[0]) {
                                                hasResponded[0] = true; // Block the cloud from triggering later
                                                String fallback = identifier.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
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
                                                            cloudUsername = identifier.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
                                                            try {
                                                                FirebaseManager.getDatabase().getReference("users_info")
                                                                        .child(uid).child("username").setValue(cloudUsername);
                                                            } catch (Exception ignored) {}
                                                        }

                                                        try { dbHelper.addUser(cloudUsername, identifier, pass); } catch (Exception ignored) {}

                                                        // Sync offline data using correct cloud name
                                                        CloudSyncManager.syncLocalToCloud(dbHelper, cloudUsername);

                                                        sendLoginAlert(identifier, cloudUsername);
                                                        Toast.makeText(LoginActivity.this, "Welcome " + cloudUsername + "!", Toast.LENGTH_SHORT).show();
                                                        saveSessionAndGoMain(cloudUsername, false);
                                                    }

                                                    @Override
                                                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                                                        if (hasResponded[0]) return;
                                                        hasResponded[0] = true;
                                                        String fallback = identifier.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");

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

                                    sendLoginAlert(linkedEmail, identifier);
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
        btnGoogleLogin.setOnClickListener(v -> {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN);
        });

        tvSignUp.setOnClickListener(v -> startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void sendLoginAlert(String email, String username) {
        String subject = "New Login Detected";
        String headline = "Security Alert";
        String body = "Hello <b>" + username + "</b>,\n\n" +
                "A successful login to your <b>Lemma</b> account was detected on <b>" + FirebaseManager.getCurrentDate() + "</b>.\n\n" +
                "If this was you, you can safely ignore this email.\n\n" +
                "If you did not authorize this login, please reset your password immediately inside the app.";
        EmailSender.sendOfficialEmail(email, subject, headline, body);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

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
                Toast.makeText(this, googleSignInError(e.getStatusCode()), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Human-readable explanation for the most common Google Sign-In status codes. */
    private String googleSignInError(int code) {
        switch (code) {
            case 10: // DEVELOPER_ERROR
                return "Google Sign-In failed (10): this build's SHA-1 or the Web client ID isn't registered for this Firebase project. "
                        + "Add your SHA-1 in Firebase Console, re-download google-services.json, then uninstall & reinstall.";
            case 12500: // SIGN_IN_FAILED
                return "Google Sign-In failed (12500): update Google Play Services and make sure a Google account is added on this device.";
            case 12501: // SIGN_IN_CANCELLED
                return "Google Sign-In was canceled.";
            case 12502: // SIGN_IN_CURRENTLY_IN_PROGRESS
                return "A Google Sign-In is already in progress.";
            case 7:     // NETWORK_ERROR
                return "Network error during Google Sign-In. Check your connection.";
            default:
                return "Google Sign-In failed (code " + code + "): "
                        + com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.getStatusCodeString(code);
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
                                        String finalUsername = snapshot.getValue(String.class);
                                        if (finalUsername == null || finalUsername.isEmpty()) {
                                            String rawName = (account.getDisplayName() != null) ? account.getDisplayName() : email.split("@")[0];
                                            finalUsername = rawName.replaceAll("[^a-zA-Z0-9_]", "_");
                                            FirebaseManager.getDatabase().getReference("users_info")
                                                    .child(uid).child("username").setValue(finalUsername);
                                        }

                                        try {
                                            dbHelper.syncGoogleUser(finalUsername, email, account.getId());
                                            CloudSyncManager.syncLocalToCloud(dbHelper, finalUsername);
                                        } catch (Exception ignored) {}

                                        if (email != null && !email.isEmpty()) {
                                            String subject = "Welcome to Lemma!";
                                            String headline = "Account Linked Successfully";
                                            String body = "Hello <b>" + finalUsername + "</b>,\n\n" +
                                                    "Your account via Google Sign-In has been successfully registered!\n\n" +
                                                    "<b>Your Account Details:</b>\n" +
                                                    "Username: " + finalUsername + "\n" +
                                                    "Email: " + email + "\n\n" +
                                                    "You can now scan math problems, draw 2D shapes, and sync your solutions securely to the cloud.";
                                            EmailSender.sendOfficialEmail(email, subject, headline, body);
                                        }

                                        Toast.makeText(LoginActivity.this, getString(R.string.welcome, finalUsername), Toast.LENGTH_SHORT).show();
                                        saveSessionAndGoMain(finalUsername, false);
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

    private void saveSessionAndGoMain(String username, boolean isGuest) {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        pref.edit().putString("username", username).putBoolean("is_guest", isGuest).apply();

        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}