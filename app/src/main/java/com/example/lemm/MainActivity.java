package com.example.lemm;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    
    private TextView tvMainWelcome;
    private ImageButton btnSettings, btnProfile;
    private MaterialCardView cardNewProblem, cardScanProblem, cardDrawProblem, cardHistory;
    private Button btnLogout;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bitmap photo = (Bitmap) result.getData().getExtras().get("data");
                    recognizeTextFromImage(photo);
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
        
        btnLogout = findViewById(R.id.btnLogout);
    }

    private void setupUser() {
        String username = getIntent().getStringExtra("LOGGED_IN_USERNAME");
        if (username == null) {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            username = pref.getString("username", "User");
        }
        tvMainWelcome.setText("Welcome, " + username);
    }

    private void setupListeners() {
        cardNewProblem.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
            startActivity(intent);
        });

        cardScanProblem.setOnClickListener(v -> {
            if (checkCameraPermission()) {
                openCamera();
            } else {
                requestCameraPermission();
            }
        });

        cardDrawProblem.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DrawingActivity.class);
            startActivity(intent);
        });

        cardHistory.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivity(intent);
        });

        btnProfile.setOnClickListener(v -> Toast.makeText(this, "Profile under development", Toast.LENGTH_SHORT).show());
        btnSettings.setOnClickListener(v -> Toast.makeText(this, "Settings under development", Toast.LENGTH_SHORT).show());
        
        btnLogout.setOnClickListener(v -> {
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            pref.edit().clear().apply();
            Intent logoutIntent = new Intent(MainActivity.this, LoginActivity.class);
            logoutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(logoutIntent);
            finish();
        });
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    private void openCamera() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cameraLauncher.launch(cameraIntent);
    }

    private void recognizeTextFromImage(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String resultText = visionText.getText();
                    if (resultText.isEmpty()) {
                        Toast.makeText(this, "No text detected in image", Toast.LENGTH_SHORT).show();
                    } else {
                        Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                        intent.putExtra("SCANNED_TEXT", resultText);
                        startActivity(intent);
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this, "OCR Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to scan", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
