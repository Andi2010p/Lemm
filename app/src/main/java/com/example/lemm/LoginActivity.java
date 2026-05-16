package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
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
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import java.util.Random;
// Add this with your other variables at the top of LoginActivity

public class LoginActivity extends AppCompatActivity {
    private com.google.firebase.auth.FirebaseAuth mAuth;

    private EditText etUsername, etPassword;
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

        mAuth = com.google.firebase.auth.FirebaseAuth.getInstance();

        // Notice the new .requestIdToken() line! This is required for Firebase.
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("481743722691-874nj07keqhs20s6bolgram4bp4ghfdr.apps.googleusercontent.com")                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        dbHelper = new DatabaseHelper(this);
        etUsername = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin);
        tvSignUp = findViewById(R.id.tvSignUp);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);


        btnLogin.setOnClickListener(v -> {
            String user = etUsername.getText().toString().trim();
            String pass = etPassword.getText().toString().trim();

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
            } else if (user.startsWith("GuestUser_") || user.equals("Admin_Teacher")) {
                Toast.makeText(this, getString(R.string.restricted_prefix), Toast.LENGTH_SHORT).show();
            } else {
                if (dbHelper.checkUser(user, pass)) {
                    saveSessionAndGoMain(user, false);
                } else {
                    Toast.makeText(this, getString(R.string.invalid_credentials), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnGoogleLogin.setOnClickListener(v -> {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN);
        });

        tvSignUp.setOnClickListener(v -> startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));

        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void showForgotPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.reset_password));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);

        final EditText inputIdentifier = new EditText(this);
        inputIdentifier.setHint(getString(R.string.email_username));
        layout.addView(inputIdentifier);

        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.send_otp), (dialog, which) -> {
            String identifier = inputIdentifier.getText().toString().trim();
            if (identifier.isEmpty()) {
                Toast.makeText(this, "Please enter email or username", Toast.LENGTH_SHORT).show();
                return;
            }

            // Change "email" to "targetEmail" to avoid the duplicate name error
            String targetEmail = dbHelper.getUserEmail(identifier);

            if (targetEmail != null && !targetEmail.isEmpty()) {
                String otp = String.format("%06d", new java.util.Random().nextInt(999999));

                String otpBody = "Your Lemma Password Reset Code is: " + otp;

                // Use targetEmail here
                EmailSender.sendEmail(targetEmail, "Password Reset OTP", otpBody);

                Toast.makeText(this, "OTP sent to your email", Toast.LENGTH_SHORT).show();
                showOTPDialog(identifier, otp);
            } else {
                Toast.makeText(this, "User not found or no email associated", Toast.LENGTH_SHORT).show();
            }
        });        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void showOTPDialog(String identifier, String correctOtp) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.enter_otp));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);

        final EditText inputOtp = new EditText(this);
        inputOtp.setHint(getString(R.string.otp_hint));
        inputOtp.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(inputOtp);

        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.verify), (dialog, which) -> {
            String enteredOtp = inputOtp.getText().toString().trim();
            if (enteredOtp.equals(correctOtp)) {
                showNewPasswordDialog(identifier);
            } else {
                Toast.makeText(this, "Invalid OTP", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void showNewPasswordDialog(String identifier) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.reset_password));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);

        final EditText inputNewPass = new EditText(this);
        inputNewPass.setHint(getString(R.string.new_password));
        inputNewPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(inputNewPass);

        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            String newPass = inputNewPass.getText().toString().trim();
            if (newPass.length() < 8) {
                Toast.makeText(this, getString(R.string.password_short), Toast.LENGTH_SHORT).show();
            } else {
                if (dbHelper.updatePassword(identifier, newPass)) {
                    Toast.makeText(this, "Password updated successfully!", Toast.LENGTH_SHORT).show();
                    String email = dbHelper.getUserEmail(identifier);
                    if (email != null) {
                        EmailSender.sendEmail(email, "Password Changed", "Your Lemma password has been successfully reset.");
                    }
                } else {
                    Toast.makeText(this, "Error updating password", Toast.LENGTH_SHORT).show();
                }
            }
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
                if (account != null) {
                    // WE GOT THE GOOGLE ACCOUNT! Now send it to Firebase.
                    firebaseAuthWithGoogle(account.getIdToken(), account);
                }
            } catch (ApiException e) {
                Toast.makeText(this, "Google Sign-In failed: " + e.getStatusCode(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void firebaseAuthWithGoogle(String idToken, GoogleSignInAccount account) {
        com.google.firebase.auth.AuthCredential credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null);

        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Sign in success!
                        String email = account.getEmail();
                        String name = account.getDisplayName();

                        // Save to your local SQLite database so history works
                        dbHelper.syncGoogleUser(email, account.getId());

                        Toast.makeText(this, getString(R.string.welcome, name), Toast.LENGTH_SHORT).show();
                        saveSessionAndGoMain(email, false);
                    } else {
                        // If sign in fails, display a message to the user.
                        Toast.makeText(LoginActivity.this, "Firebase Authentication Failed.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void saveSessionAndGoMain(String username, boolean isGuest) {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        pref.edit()
                .putString("username", username)
                .putBoolean("is_guest", isGuest)
                .apply();

        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}