package com.example.lemm;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class ProfileActivity extends AppCompatActivity {

    private TextView tvUsername, tvHistoryCount, tvDrawingCount;
    private ImageButton btnBack;
    private MaterialButton btnLogout, btnChangePass;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        dbHelper = new DatabaseHelper(this);

        tvUsername = findViewById(R.id.tvProfileUsername);
        tvHistoryCount = findViewById(R.id.tvHistoryCount);
        tvDrawingCount = findViewById(R.id.tvDrawingCount);
        btnBack = findViewById(R.id.btnBack);
        btnLogout = findViewById(R.id.btnProfileLogout);
        btnChangePass = findViewById(R.id.btnChangePassword);

        setupUserData();

        btnBack.setOnClickListener(v -> finish());

        // Open Dialog Window for Password Change
        btnChangePass.setOnClickListener(v -> {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String username = pref.getString("username", "");
            boolean isGuest = pref.getBoolean("is_guest", false);

            // Restrict password change for Guest and Admin modes
            if (isGuest || username.startsWith("GuestUser_") || username.equals("Admin_Teacher")) {
                Toast.makeText(this, "You cannot change the password in Guest or Admin mode.", Toast.LENGTH_LONG).show();
                return;
            }

            showChangePasswordDialog();
        });

        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.logout))
                    .setMessage(getString(R.string.logout_confirm))
                    .setPositiveButton(getString(R.string.logout), (dialog, which) -> {
                        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                        pref.edit().clear().apply();

                        Intent intent = new Intent(ProfileActivity.this, StartActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        });
    }

    private void showChangePasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Change Password");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);

        final EditText inputCurrentPass = new EditText(this);
        inputCurrentPass.setHint("Current Password");
        inputCurrentPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(inputCurrentPass);

        final EditText inputNewPass = new EditText(this);
        inputNewPass.setHint("New Password (min 8 chars)");
        inputNewPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Add spacing between the two inputs
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 20, 0, 0);
        inputNewPass.setLayoutParams(lp);
        layout.addView(inputNewPass);

        builder.setView(layout);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String currentPass = inputCurrentPass.getText().toString().trim();
            String newPass = inputNewPass.getText().toString().trim();

            if (currentPass.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String username = pref.getString("username", "");

            if (dbHelper.checkUser(username, currentPass)) {
                if (newPass.length() < 8) {
                    Toast.makeText(this, "New password must be at least 8 characters", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Update in database
                dbHelper.updatePassword(username, newPass);
                Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show();

                // Send Email Notification
                String email = dbHelper.getUserEmail(username);
                if (email != null && !email.isEmpty()) {
                    EmailSender.sendEmail(email, "Password Changed", "Your Geometry AI password has been successfully changed.");
                }
            } else {
                Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void setupUserData() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        tvUsername.setText(username);

        int historyCount = getCountFromCursor(dbHelper.getHistory(username));
        int drawingCount = getCountFromCursor(dbHelper.getDrawings(username));

        tvHistoryCount.setText(String.valueOf(historyCount));
        tvDrawingCount.setText(String.valueOf(drawingCount));
    }

    private int getCountFromCursor(Cursor cursor) {
        if (cursor != null) {
            int count = cursor.getCount();
            cursor.close();
            return count;
        }
        return 0;
    }
}