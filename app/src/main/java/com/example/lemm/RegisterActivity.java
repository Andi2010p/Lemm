package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.textfield.TextInputEditText;

public class RegisterActivity extends AppCompatActivity {

    private TextInputEditText etUsername, etEmail, etPassword, etRepeatPassword;
    private Button btnRegister, btnBackToLogin;
    private SignInButton btnGoogleRegister;
    private DatabaseHelper dbHelper;
    private GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        dbHelper = new DatabaseHelper(this);

        etUsername = findViewById(R.id.etUsername);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etRepeatPassword = findViewById(R.id.etRepeatPassword);
        btnRegister = findViewById(R.id.btnRegister);
        btnBackToLogin = findViewById(R.id.btnBackToLogin);
        btnGoogleRegister = findViewById(R.id.btnGoogleRegister);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id)) // Required for Firebase
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        if (btnGoogleRegister != null) {
            btnGoogleRegister.setSize(SignInButton.SIZE_WIDE);
            btnGoogleRegister.setOnClickListener(v -> {
                Intent signInIntent = mGoogleSignInClient.getSignInIntent();
                startActivityForResult(signInIntent, RC_SIGN_IN);
            });
        }

        btnRegister.setOnClickListener(v -> {
            try {
                String user = etUsername.getText().toString().trim();
                String email = etEmail.getText().toString().trim();
                String pass = etPassword.getText().toString().trim();
                String repeatPass = etRepeatPassword.getText().toString().trim();

                if (user.isEmpty() || email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (user.toLowerCase().startsWith("guestuser") || user.toLowerCase().equals("admin_teacher")) {
                    Toast.makeText(this, getString(R.string.restricted_prefix), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pass.equals(repeatPass)) {
                    Toast.makeText(this, getString(R.string.passwords_mismatch), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pass.length() < 8) {
                    Toast.makeText(this, getString(R.string.password_short), Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(this, "Connecting to Cloud...", Toast.LENGTH_SHORT).show();

                // 1. CREATE ACCOUNT IN FIREBASE AUTHENTICATION
                com.google.firebase.auth.FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                try {
                                    String uid = task.getResult().getUser().getUid();

                                    // 2. SAVE USERNAME TO FIREBASE REALTIME DATABASE
                                    com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users_info")
                                            .child(uid).child("username").setValue(user);

                                    // 3. SAVE TO LOCAL SQLITE
                                    dbHelper.addUser(user, email, pass);

                                    // 4. SEND BEAUTIFUL HTML EMAIL (Standard Registration)
                                    String subject = "Welcome to Lemma!";
                                    String headline = "Account Created Successfully";
                                    String body = "Hello <b>" + user + "</b>,\n\n" +
                                            "Welcome to your new AI-powered geometry tutor!\n\n" +
                                            "<b>Your Account Details:</b>\n" +
                                            "Username: " + user + "\n" +
                                            "Email: " + email + "\n\n" +
                                            "You can now scan math problems, draw 3D shapes, and sync your solutions securely to the cloud.";
                                    EmailSender.sendOfficialEmail(email, subject, headline, body);

                                    Toast.makeText(this, "Registration Successful!", Toast.LENGTH_LONG).show();
                                    finish();
                                } catch (Exception e) {
                                    Toast.makeText(this, "Database Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            } else {
                                Toast.makeText(this, "Cloud Error: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });

            } catch (Exception e) {
                Toast.makeText(this, "App Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnBackToLogin.setOnClickListener(v -> finish());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            handleSignInResult(task);
        }
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            if (account != null && account.getIdToken() != null) {

                // Connect to Firebase Auth
                com.google.firebase.auth.AuthCredential credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(account.getIdToken(), null);
                com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnCompleteListener(this, task -> {
                            if (task.isSuccessful()) {
                                String email = account.getEmail();
                                String rawName = (account.getDisplayName() != null) ? account.getDisplayName() : email.split("@")[0];
                                String safeUsername = rawName.replaceAll("[^a-zA-Z0-9_]", "_");

                                // Save Cloud mapping and Local DB
                                com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                                com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users_info")
                                        .child(user.getUid()).child("username").setValue(safeUsername);

                                dbHelper.syncGoogleUser(safeUsername, email, account.getId());

                                // 4. SEND BEAUTIFUL HTML EMAIL (Google Registration)
                                if (email != null && !email.isEmpty()) {
                                    String subject = "Welcome to Lemma!";
                                    String headline = "Account Linked Successfully";
                                    String body = "Hello <b>" + safeUsername + "</b>,\n\n" +
                                            "Your account via Google Sign-In has been successfully registered!\n\n" +
                                            "<b>Your Account Details:</b>\n" +
                                            "Username: " + safeUsername + "\n" +
                                            "Email: " + email + "\n\n" +
                                            "You can now scan math problems, draw 2D shapes, and sync your solutions securely to the cloud.";
                                    EmailSender.sendOfficialEmail(email, subject, headline, body);
                                }

                                // Log them in
                                SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                                pref.edit().putString("username", safeUsername).putBoolean("is_guest", false).apply();

                                Toast.makeText(this, getString(R.string.welcome, safeUsername), Toast.LENGTH_SHORT).show();
                                startActivity(new Intent(this, MainActivity.class));
                                finish();
                            } else {
                                Toast.makeText(this, "Google Registration Failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
            }
        } catch (ApiException e) {
            Toast.makeText(this, "Google UI Error: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}