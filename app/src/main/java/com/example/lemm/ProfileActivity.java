package com.example.lemm;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;

public class ProfileActivity extends AppCompatActivity {

    private TextView tvUsername, tvHistoryCount, tvDrawingCount;
    private ImageButton btnBack;
    private MaterialButton btnLogout, btnChangePass, btnDeleteAccount;
    private DatabaseHelper dbHelper;
    private ImageView imgProfileAvatar;
    private MaterialCardView cardProfileAvatar;

    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> requestCameraPermissionLauncher;

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
        btnDeleteAccount = findViewById(R.id.btnDeleteAccount);
        imgProfileAvatar = findViewById(R.id.imgProfileAvatar);
        cardProfileAvatar = findViewById(R.id.cardProfileAvatar);

        setupImagePickers();

        // NOTE: setupUserData() is no longer here! It is now inside onResume below.

        btnBack.setOnClickListener(v -> finish());

        cardProfileAvatar.setOnClickListener(v -> showImageSourceDialog());

        btnChangePass.setOnClickListener(v -> {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String username = pref.getString("username", "");
            if (pref.getBoolean("is_guest", false) || username.startsWith("GuestUser_") || username.equals("Admin_Teacher")) {
                Toast.makeText(this, "You cannot change the password in Guest or Admin mode.", Toast.LENGTH_LONG).show();
                return;
            }
            showChangePasswordDialog(username);
        });

        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.logout))
                    .setMessage(getString(R.string.logout_confirm))
                    .setPositiveButton(getString(R.string.logout), (dialog, which) -> {
                        getSharedPreferences("UserPrefs", MODE_PRIVATE).edit().clear().apply();
                        Intent intent = new Intent(ProfileActivity.this, StartActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    })
                    .setNegativeButton(getString(R.string.cancel), null).show();
        });

        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(v -> {
                SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
                String username = pref.getString("username", "");

                if (pref.getBoolean("is_guest", false) || username.startsWith("GuestUser_") || username.equals("Admin_Teacher")) {
                    Toast.makeText(this, "You cannot delete Guest or Admin accounts.", Toast.LENGTH_SHORT).show();
                    return;
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
                builder.setTitle("Delete Account");
                builder.setMessage("This action cannot be undone. All your drawings, solutions, and cloud data will be permanently wiped.\n\nPlease type DELETE below to confirm.");

                LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(60, 20, 60, 0);

                final EditText inputVerify = new EditText(this);
                inputVerify.setHint("DELETE");
                inputVerify.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                layout.addView(inputVerify);
                builder.setView(layout);

                builder.setPositiveButton("Delete Forever", null);
                builder.setNegativeButton("Cancel", null);

                androidx.appcompat.app.AlertDialog dialog = builder.create();
                dialog.show();

                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(android.graphics.Color.parseColor("#D32F2F"));

                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String verification = inputVerify.getText().toString().trim();

                    if (verification.equals("DELETE")) {
                        dialog.dismiss();
                        Toast.makeText(this, "Deleting account...", Toast.LENGTH_SHORT).show();
                        performAccountDeletion(username);
                    } else {
                        inputVerify.setError("You must type DELETE in all caps.");
                    }
                });
            });
        }
    }

    // --- HERE IS ONRESUME! ---
    // It sits perfectly between the closing of onCreate and the rest of the class.
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh all user data, avatar, and PRO status every time you look at the screen!
        setupUserData();
    }

    // --- AVATAR LOGIC ---
    private void setupImagePickers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                try {
                    Uri imageUri = result.getData().getData();
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                    saveAndSetAvatar(bitmap);
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                }
            }
        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Bundle extras = result.getData().getExtras();
                if (extras != null && extras.get("data") != null) {
                    Bitmap bitmap = (Bitmap) extras.get("data");
                    saveAndSetAvatar(bitmap);
                }
            }
        });

        requestCameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                cameraLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            } else {
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showImageSourceDialog() {
        String[] options = {
                getString(R.string.scan_ocr),
                "Gallery",
                getString(R.string.delete)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.user_profile))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        galleryLauncher.launch(intent);
                    } else if (which == 2) {
                        removeAvatar();
                    }
                }).show();
    }

    private void saveAndSetAvatar(Bitmap bitmap) {
        if (bitmap == null) return;

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 300, 300, true);
        imgProfileAvatar.setImageTintList(null);
        imgProfileAvatar.setImageBitmap(scaled);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] b = baos.toByteArray();
        String encodedImage = Base64.encodeToString(b, Base64.DEFAULT);

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "");
        pref.edit().putString("avatar_" + username, encodedImage).apply();

        com.google.firebase.auth.FirebaseUser fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser != null) {
            FirebaseManager.getDatabase().getReference("users_info")
                    .child(fbUser.getUid()).child("avatar").setValue(encodedImage);
            Toast.makeText(this, "Profile picture updated!", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeAvatar() {
        imgProfileAvatar.setImageResource(android.R.drawable.ic_menu_camera);
        imgProfileAvatar.setImageTintList(ColorStateList.valueOf(Color.parseColor("#0C3D6A")));

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "");

        pref.edit().remove("avatar_" + username).apply();

        com.google.firebase.auth.FirebaseUser fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser != null) {
            FirebaseManager.getDatabase().getReference("users_info")
                    .child(fbUser.getUid()).child("avatar").removeValue();
            Toast.makeText(this, "Profile picture removed", Toast.LENGTH_SHORT).show();
        }
    }

    // --- SETUP USER DATA ---
    private void setupUserData() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        boolean isGuest = pref.getBoolean("is_guest", false);
        boolean isPro = pref.getBoolean("is_pro_user", false);

        tvUsername.setText(username);
        TextView tvEmail = findViewById(R.id.tvProfileEmail);
        TextView tvTier = findViewById(R.id.tvProfileTier);

        String email = "No Email Provided";
        com.google.firebase.auth.FirebaseUser fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();

        if (fbUser != null && fbUser.getEmail() != null) {
            email = fbUser.getEmail();
        } else {
            String localEmail = dbHelper.getUserEmail(username);
            if (localEmail != null && !localEmail.isEmpty()) email = localEmail;
        }

        if (isGuest || username.startsWith("GuestUser_") || username.equals("Admin_Teacher")) {
            if(tvEmail != null) tvEmail.setText("Not logged in");
        } else {
            if(tvEmail != null) tvEmail.setText(email);
        }

        if (tvTier != null) {
            if (isPro) {
                tvTier.setText("PRO MEMBER \u2B50");
                tvTier.setTextColor(android.graphics.Color.parseColor("#F57F17"));
                tvTier.setBackgroundColor(android.graphics.Color.parseColor("#FFFDE7"));
            } else {
                tvTier.setText("FREE PLAN");
                tvTier.setTextColor(android.graphics.Color.parseColor("#2E7D32"));
                tvTier.setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"));
            }
        }

        // LOAD AVATAR
        String encodedImage = pref.getString("avatar_" + username, "");
        if (!encodedImage.isEmpty()) {
            byte[] b = Base64.decode(encodedImage, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            imgProfileAvatar.setImageTintList(null);
            imgProfileAvatar.setImageBitmap(bmp);
        } else if (fbUser != null) {
            FirebaseManager.getDatabase().getReference("users_info")
                    .child(fbUser.getUid()).child("avatar").addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snap) {
                            String cloudAvatar = snap.getValue(String.class);
                            if (cloudAvatar != null && !cloudAvatar.isEmpty()) {
                                pref.edit().putString("avatar_" + username, cloudAvatar).apply();
                                byte[] b = Base64.decode(cloudAvatar, Base64.DEFAULT);
                                Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                                imgProfileAvatar.setImageTintList(null);
                                imgProfileAvatar.setImageBitmap(bmp);
                            }
                        }
                        @Override public void onCancelled(DatabaseError err) {}
                    });
        }

        // Load Counts
        // Single-value events: setupUserData() runs on every onResume, so persistent
        // listeners would accumulate (leak). One-shot reads refresh the counts each time.
        com.google.firebase.database.DatabaseReference userRef = FirebaseManager.getUserRef(username);
        userRef.child("history").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { tvHistoryCount.setText(String.valueOf(snapshot.getChildrenCount())); }
            @Override public void onCancelled(DatabaseError error) {}
        });

        userRef.child("drawings").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { tvDrawingCount.setText(String.valueOf(snapshot.getChildrenCount())); }
            @Override public void onCancelled(DatabaseError error) {}
        });
    }

    private void showChangePasswordDialog(String username) {
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

            if (currentPass.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_all_fields), Toast.LENGTH_SHORT).show();
                return;
            }

            if (dbHelper.checkUser(username, currentPass)) {
                if (newPass.length() < 8) {
                    Toast.makeText(this, getString(R.string.password_short), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (dbHelper.updatePassword(username, newPass)) {
                    Toast.makeText(this, "Password changed successfully", Toast.LENGTH_SHORT).show();

                    com.google.firebase.auth.FirebaseUser fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                    if (fbUser != null) { fbUser.updatePassword(newPass); }

                    String email = dbHelper.getUserEmail(username);
                    if (email != null && !email.isEmpty()) {
                        String subject = "Security Alert: Password Changed";
                        String headline = "Password Successfully Changed";
                        String body = "Hello <b>" + username + "</b>,\n\n" +
                                "This is a confirmation that your <b>Lemma</b> account password was recently changed from your Profile Settings.\n\n" +
                                "If you made this change, you can safely ignore this email.\n\n" +
                                "If you <b>DID NOT</b> perform this action, please log out and reset your password immediately from the Login screen.";
                        EmailSender.sendOfficialEmail(email, subject, headline, body);
                    }
                }
            } else {
                Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void performAccountDeletion(String username) {
        com.google.firebase.auth.FirebaseUser currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        com.google.firebase.database.DatabaseReference dbRef = FirebaseManager.getDatabase().getReference();

        if (currentUser != null) {
            dbRef.child("users").child(FirebaseManager.sanitizeUser(username)).removeValue();
            dbRef.child("users_info").child(currentUser.getUid()).removeValue();

            currentUser.delete().addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    completeLocalWipe(username);
                } else {
                    Toast.makeText(this, "Action requires recent login. Please log out, log back in, and try again.", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            completeLocalWipe(username);
        }
    }

    private void completeLocalWipe(String username) {
        dbHelper.clearUserHistory(username);
        dbHelper.deleteUser(username);

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        pref.edit().clear().apply();

        Toast.makeText(this, "Account and all data completely deleted.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(ProfileActivity.this, StartActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}