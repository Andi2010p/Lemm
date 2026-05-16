package com.example.lemm;

import android.content.Context;
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

        btnChangePass.setOnClickListener(v -> {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String username = pref.getString("username", "");
            boolean isGuest = pref.getBoolean("is_guest", false);

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
        builder.setTitle(getString(R.string.change_password));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);

        final EditText inputCurrentPass = new EditText(this);
        inputCurrentPass.setHint(getString(R.string.current_password));
        inputCurrentPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(inputCurrentPass);

        final EditText inputNewPass = new EditText(this);
        inputNewPass.setHint(getString(R.string.new_password));
        inputNewPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 20, 0, 0);
        inputNewPass.setLayoutParams(lp);
        layout.addView(inputNewPass);

        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            String currentPass = inputCurrentPass.getText().toString().trim();
            String newPass = inputNewPass.getText().toString().trim();
// Inside showChangePasswordDialog -> builder.setPositiveButton success block
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String username = pref.getString("username", "");
            if (dbHelper.updatePassword(username, newPass)) {
                Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show();

                String email = dbHelper.getUserEmail(username);
                if (email != null) {
                    String changeBody = "Hello,\n\n" +
                            "This is a confirmation that your Lemma account password has been changed.\n" +
                            "If you did not perform this action, please contact support.";

                    EmailSender.sendEmail(email, "Security Alert: Password Changed", changeBody);
                }
            }
           if (currentPass.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
                return;
            }


            if (dbHelper.checkUser(username, currentPass)) {
                if (newPass.length() < 8) {
                    Toast.makeText(this, getString(R.string.password_short), Toast.LENGTH_SHORT).show();
                    return;
                }

                dbHelper.updatePassword(username, newPass);
                Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show();

                String email = dbHelper.getUserEmail(username);
                if (email != null && !email.isEmpty()) {
                    EmailSender.sendEmail(email, "Password Changed", "Your Lemma password has been successfully changed.");
                }
            } else {
                Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(getString(R.string.cancel), null);
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

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}