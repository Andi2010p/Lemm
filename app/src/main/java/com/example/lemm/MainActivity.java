package com.example.lemm;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.card.MaterialCardView;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.ai.client.generativeai.type.GenerateContentResponse;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final String TAG = "MainActivity";

    private TextView tvMainWelcome;
    private ImageButton btnSettings, btnProfile;

    private MaterialCardView cardNewProblem, cardScanProblem, cardDrawProblem, cardHistory, cardTheorems;

    private Uri photoUri;
    private String currentPhotoPath;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    processCapturedPhoto();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupUser();
        setupListeners();
    }

    private void initViews() {
        tvMainWelcome = findViewById(R.id.tvMainWelcome);
        btnSettings = findViewById(R.id.btnSettings);
        btnProfile = findViewById(R.id.btnProfile);

        cardNewProblem = findViewById(R.id.cardNewProblem);
        cardScanProblem = findViewById(R.id.cardScanProblem);
        cardDrawProblem = findViewById(R.id.cardDrawProblem);
        cardHistory = findViewById(R.id.cardHistory);
        cardTheorems = findViewById(R.id.cardTheorems);
    }

    private void setupUser() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        if (tvMainWelcome != null) {
            tvMainWelcome.setText(getString(R.string.welcome, username));
        }
    }

    private void setupListeners() {
        if (cardNewProblem != null) {
            cardNewProblem.setOnClickListener(v -> startActivity(new Intent(this, GeometryInputActivity.class)));
        }

        if (cardScanProblem != null) {
            cardScanProblem.setOnClickListener(v -> {
                if (checkCameraPermission()) dispatchTakePictureIntent();
                else requestCameraPermission();
            });
        }

        if (cardDrawProblem != null) {
            cardDrawProblem.setOnClickListener(v -> startActivity(new Intent(this, DrawingActivity.class)));
        }

        if (cardHistory != null) {
            cardHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        }

        if (cardTheorems != null) {
            cardTheorems.setOnClickListener(v -> startActivity(new Intent(this, TheoremsActivity.class)));
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }

        if (btnProfile != null) {
            btnProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = null;
        try { photoFile = createImageFile(); } catch (IOException ex) { Log.e(TAG, "Error creating file", ex); }
        if (photoFile != null) {
            photoUri = FileProvider.getUriForFile(this, "com.example.lemm.fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            cameraLauncher.launch(takePictureIntent);
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("JPEG_" + timeStamp + "_", ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private void processCapturedPhoto() {
        // --- STRICT AI SECURITY CHECK ---
        SharedPreferences apiPrefs = getSharedPreferences("AI_Settings", MODE_PRIVATE);
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        String username = userPrefs.getString("username", "");
        boolean isProUser = userPrefs.getBoolean("is_pro_user", false);
        String apiKey = "";

        if (username.equals("Admin_Teacher") || isProUser) {
            apiKey = BuildConfig.GEMINI_API_KEY;
        } else {
            apiKey = apiPrefs.getString("user_api_key", "");
        }

        // If the user is NOT pro and has NO custom key, block them!
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Access Denied: Please upgrade to Lemma Pro to use the AI Scanner.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        try {
            Bitmap originalBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri);
            int maxDim = 1024;
            float ratio = Math.min((float) maxDim / originalBitmap.getWidth(), (float) maxDim / originalBitmap.getHeight());
            Bitmap safeBitmap = Bitmap.createScaledBitmap(originalBitmap, (int)(originalBitmap.getWidth()*ratio), (int)(originalBitmap.getHeight()*ratio), true);

            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.scan_dialog_title))
                    .setMessage(getString(R.string.scan_dialog_message))
                    .setCancelable(false).show();

            GeminiAI geminiAI = new GeminiAI(apiKey);
            String prompt = "Transcribe math in this image. If not math, output: INVALID_IMAGE.";

            Futures.addCallback(geminiAI.extractTextFromImage(safeBitmap, prompt), new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        String text = result.getText();
                        if (text == null || text.contains("INVALID_IMAGE")) {
                            Toast.makeText(MainActivity.this, R.string.scan_error_no_text, Toast.LENGTH_LONG).show();
                        } else {
                            Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                            intent.putExtra("SCANNED_TEXT", text.trim());
                            startActivity(intent);
                        }
                    });
                }
                @Override
                public void onFailure(Throwable t) {
                    runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(MainActivity.this, "Scan Failed: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) { Toast.makeText(this, R.string.scan_error_loading_photo, Toast.LENGTH_SHORT).show(); }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            dispatchTakePictureIntent();
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}