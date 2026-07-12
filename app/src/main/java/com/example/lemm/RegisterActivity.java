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

                if (email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
                    return;
                }
                Integer userErr = UsernameRules.validate(user);
                if (userErr != null) {
                    Toast.makeText(this, getString(userErr), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (dbHelper.checkUsernameExists(user)) {
                    Toast.makeText(this, getString(R.string.username_taken), Toast.LENGTH_SHORT).show();
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
                                // 2. CLAIM THE USERNAME ACCOUNT-WIDE.
                                // checkUsernameExists() only consults this device's SQLite, so two
                                // phones could otherwise register the same name — and since the cloud
                                // node is users/{lowercased name}, they would silently share one
                                // person's history. The claim table is the only global arbiter, so if
                                // we lose the race we must undo the account we just created.
                                Social.claimUsername(user, owned -> {
                                    if (!owned) {
                                        com.google.firebase.auth.FirebaseUser fresh =
                                                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                                        if (fresh != null) fresh.delete();
                                        Toast.makeText(this, R.string.username_taken_title, Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    finishRegistration(task.getResult().getUser().getUid(), user, email, pass);
                                });
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

    /** Runs only once the username has been claimed account-wide, so the cloud node is really ours. */
    private void finishRegistration(String uid, String user, String email, String pass) {
        try {
            FirebaseManager.getDatabase().getReference("users_info")
                    .child(uid).child("username").setValue(user);
            // Make them findable in search right away, not only after they reach the home screen.
            Social.writeDirectory(uid, user);

            dbHelper.addUser(user, email, pass);

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
    }

    /** Runs after the user has picked a username for their new Google account. */
    private void finishGoogleRegistration(GoogleSignInAccount account, String email, String username) {
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();

        FirebaseManager.getDatabase().getReference("users_info").child(uid).child("username").setValue(username);
        // Publish to the searchable directory NOW, so friends can find them immediately.
        Social.writeDirectory(uid, username);
        Social.claimUsername(username, null);

        dbHelper.syncGoogleUser(username, email, account.getId());

        if (email != null && !email.isEmpty()) {
            String body = "Hello <b>" + username + "</b>,\n\n" +
                    "Your account via Google Sign-In has been successfully registered!\n\n" +
                    "<b>Your Account Details:</b>\n" +
                    "Username: " + username + "\n" +
                    "Email: " + email + "\n\n" +
                    "You can now scan math problems, draw shapes, and sync your solutions securely to the cloud.";
            EmailSender.sendOfficialEmail(email, "Welcome to Lemma!", "Account Linked Successfully", body);
        }

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        pref.edit().putString("username", username).putBoolean("is_guest", false).apply();

        Toast.makeText(this, getString(R.string.welcome, username), Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class));
        finish();
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
                                // Let the user CHOOSE their username instead of auto-naming them from
                                // their email. Pre-fill a suggestion from their Google display name.
                                String suggestion = (account.getDisplayName() != null)
                                        ? account.getDisplayName()
                                        : (email != null ? email.split("@")[0] : "");
                                UsernamePrompt.choose(this, suggestion,
                                        chosen -> finishGoogleRegistration(account, email, chosen));
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