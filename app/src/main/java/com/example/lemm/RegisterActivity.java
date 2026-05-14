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
            String user = etUsername.getText().toString().trim();
            String email = etEmail.getText().toString().trim();
            String pass = etPassword.getText().toString().trim();
            String repeatPass = etRepeatPassword.getText().toString().trim();

            if (user.isEmpty() || email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
            } else if (user.equalsIgnoreCase("GuestUser")) {
                Toast.makeText(this, getString(R.string.restricted_prefix), Toast.LENGTH_SHORT).show();
            } else if (!pass.equals(repeatPass)) {
                Toast.makeText(this, getString(R.string.passwords_mismatch), Toast.LENGTH_SHORT).show();
            } else if (pass.length() < 8) {
                Toast.makeText(this, getString(R.string.password_short), Toast.LENGTH_SHORT).show();
            } else {
                try {
                    if (dbHelper.addUser(user, email, pass)) {
                        EmailSender.sendEmail(email, "Welcome to Geometry AI",
                                "Hello " + user + ",\n\nYour account has been successfully created. Welcome to Geometry AI!\n\nBest regards,\nGeometry AI Team");

                        Toast.makeText(this, getString(R.string.registration_successful), Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, getString(R.string.user_exists), Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Database Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
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
            if (account != null) {
                SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                pref.edit().putString("username", account.getDisplayName()).apply();

                dbHelper.syncGoogleUser(account.getEmail(), account.getId());
                EmailSender.sendEmail(account.getEmail(), "Welcome to Geometry AI",
                        "Hello " + account.getDisplayName() + ",\n\nYour account via Google Sign-In has been successfully created.\n\nBest regards,\nGeometry AI Team");

                Toast.makeText(this, getString(R.string.welcome, account.getDisplayName()), Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, MainActivity.class));
                finish();
            }
        } catch (ApiException e) {
            Toast.makeText(this, getString(R.string.google_sign_in_failed) + ": " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}