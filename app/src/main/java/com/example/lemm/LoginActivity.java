package com.example.lemm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
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

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button btnLogin, btnTestMode;
    private SignInButton btnGoogleLogin;
    private TextView tvSignUp;
    private DatabaseHelper dbHelper;

    private static final int RC_SIGN_IN = 100;
    private GoogleSignInClient mGoogleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        dbHelper = new DatabaseHelper(this);
        etUsername = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin);
        tvSignUp = findViewById(R.id.tvSignUp);
        btnTestMode = findViewById(R.id.btnTestMode);

        btnGoogleLogin.setSize(SignInButton.SIZE_WIDE);
        btnGoogleLogin.setColorScheme(SignInButton.COLOR_DARK);

        btnLogin.setOnClickListener(v -> {
            String user = etUsername.getText().toString().trim();
            String pass = etPassword.getText().toString().trim();

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show();
            } else {
                if (dbHelper.checkUser(user, pass)) {
                    saveSessionAndGoMain(user);
                } else {
                    Toast.makeText(this, "Invalid Credentials", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnGoogleLogin.setOnClickListener(v -> {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN);
        });

        tvSignUp.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
        });

        if (btnTestMode != null) {
            btnTestMode.setOnClickListener(v -> {
                // Login as a guest/test user without credentials
                saveSessionAndGoMain("GuestUser");
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null)
                    saveSessionAndGoMain(account.getEmail());
            } catch (ApiException e) {
                int statusCode = e.getStatusCode();
                String message;
                if (statusCode == 10)
                    message = "Config Error (10): Ensure SHA-1 is added to Google Console.";
                else if (statusCode == 12500)
                    message = "Sign-In Failed (12500): Check OAuth Consent Screen support email.";
                else
                    message = "Google Sign-In Failed: " + statusCode;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }
    }

    private void saveSessionAndGoMain(String username) {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        pref.edit().putString("username", username).apply();

        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
