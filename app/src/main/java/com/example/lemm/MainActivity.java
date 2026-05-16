package com.example.lemm;

import android.Manifest;
import android.content.Context;
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
    private static final String TAG = "Scanner";

    private TextView tvMainWelcome;
    private ImageButton btnSettings, btnProfile;
    private MaterialCardView cardNewProblem, cardScanProblem, cardDrawProblem, cardHistory;

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
    }

    private void setupUser() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        tvMainWelcome.setText(getString(R.string.welcome, username));
    }

    private void setupListeners() {
        cardNewProblem.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, GeometryInputActivity.class)));

        cardScanProblem.setOnClickListener(v -> {
            if (checkCameraPermission()) {
                dispatchTakePictureIntent();
            } else {
                requestCameraPermission();
            }
        });

        cardDrawProblem.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, DrawingActivity.class)));
        cardHistory.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
        btnProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
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
        try {
            photoFile = createImageFile();
        } catch (IOException ex) {
            Log.e(TAG, "Error creating file", ex);
        }
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

    // --- GEMINI AI OCR SCANNER ---
// --- GEMINI AI OCR SCANNER ---
// --- GEMINI AI OCR SCANNER ---
    private void processCapturedPhoto() {
        try {
            Bitmap originalBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri);

            // 1. HEAVY COMPRESSION: Scale down to 640px to prevent "Unexpected Response"
            int maxDimension = 640;
            int width = originalBitmap.getWidth();
            int height = originalBitmap.getHeight();

            if (width > maxDimension || height > maxDimension) {
                float ratio = Math.min((float) maxDimension / width, (float) maxDimension / height);
                originalBitmap = Bitmap.createScaledBitmap(originalBitmap, (int) (width * ratio), (int) (height * ratio), true);
            }

            // 2. FORMAT FIX: Convert to standard ARGB_8888 so Gemini doesn't reject weird camera pixel formats
            Bitmap safeBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);

            // Show Loading Dialog (NOW USING TRANSLATIONS)
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.scan_dialog_title))
                    .setMessage(getString(R.string.scan_dialog_message))
                    .setCancelable(false)
                    .show();

            GeminiAI geminiAI = new GeminiAI(BuildConfig.GEMINI_API_KEY);

            // Simple, strict prompt
            String prompt = "Read all the text and math equations in this image. Return ONLY the extracted text. Do not solve the problem or add any commentary.";

            Futures.addCallback(
                    geminiAI.extractTextFromImage(safeBitmap, prompt),
                    new FutureCallback<GenerateContentResponse>() {
                        @Override
                        public void onSuccess(GenerateContentResponse result) {
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                String scannedText = result.getText();

                                if (scannedText == null || scannedText.isEmpty()) {
                                    Toast.makeText(MainActivity.this, getString(R.string.scan_error_no_text), Toast.LENGTH_LONG).show();
                                    return;
                                }

                                // Send the scanned text to the Input screen
                                Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                                intent.putExtra("SCANNED_TEXT", scannedText.trim());
                                startActivity(intent);
                            });
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                Log.e("ScannerError", "Failed to scan", t);
                                Toast.makeText(MainActivity.this, getString(R.string.scan_error_failed) + t.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    },
                    ContextCompat.getMainExecutor(this)
            );

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.scan_error_loading_photo), Toast.LENGTH_SHORT).show();
        }
    }    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            dispatchTakePictureIntent();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}