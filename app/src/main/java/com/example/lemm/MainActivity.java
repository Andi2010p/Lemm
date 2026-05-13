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
import android.view.View;
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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

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
        // ONLY load the main layout
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
        tvMainWelcome.setText("Welcome Back, " + username);
    }

    private void setupListeners() {
        // Open Manual Input
        cardNewProblem.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, GeometryInputActivity.class));
        });

        // Open Scanner
        cardScanProblem.setOnClickListener(v -> {
            if (checkCameraPermission()) {
                dispatchTakePictureIntent();
            } else {
                requestCameraPermission();
            }
        });

        // OPEN THE CAD PAGE (This solves your errors)
        cardDrawProblem.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DrawingActivity.class);
            startActivity(intent);
        });

        // Open History
        cardHistory.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
        });

        btnProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    // --- CAMERA & OCR LOGIC ---
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
            // Around line 133 in MainActivity.java
            photoUri = FileProvider.getUriForFile(this, "com.example.lemm.fileprovider", photoFile);
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

    private void processCapturedPhoto() {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri);
            recognizeTextFromImage(bitmap);
        } catch (IOException e) {
            Log.e(TAG, "Error loading image", e);
        }
    }

    private void recognizeTextFromImage(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                    intent.putExtra("SCANNED_TEXT", visionText.getText());
                    startActivity(intent);
                })
                .addOnFailureListener(e -> Toast.makeText(this, "OCR Error", Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            dispatchTakePictureIntent();
        }
    }
}