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

    private MaterialCardView cardNewProblem, cardScanProblem, cardDrawProblem, cardHistory, cardTheorems, cardAskAI, cardFriends;

    private Uri photoUri;
    private String currentPhotoPath;
    private com.google.firebase.database.ValueEventListener apiKeyAutoSync;
    private com.google.firebase.database.ValueEventListener proAutoSync;
    private BillingManager proRestoreBilling;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    processCapturedPhoto();
                }
            }
    );

    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_main);
        styleGlass = StyleManager.isGlass(this);

        initViews();
        setupUser();
        setupListeners();
        maybeShowOnboarding();

        // Fetch this account's personal API keys from the cloud so they're ready to use on this
        // device (e.g. right after logging in on a new phone).
        ApiKeyStore.syncFromCloud(this, null);

        // Realtime autosync: whenever the same account's keys/toggle change on ANOTHER device,
        // the change lands in this device's local prefs in ~1s — no need to reopen Settings.
        apiKeyAutoSync = ApiKeyStore.attachRealtimeListener(this, null);

        // Subscription/Pro status follows the account too: pull it now and keep it live across devices.
        ProStatusManager.syncFromCloud(this, null);
        proAutoSync = ProStatusManager.attachRealtimeListener(this, null);

        // Restore Google Play purchases app-wide (previously this only happened when Settings opened),
        // so a paid user is recognised on launch — including a new device signed into the same Google account.
        proRestoreBilling = new BillingManager(this, new BillingManager.BillingListener() {
            @Override public void onBillingReady() {}
            @Override public void onPriceFetched(String price) {}
            @Override public void onPurchaseSuccess() {}
            @Override public void onBillingError() {}
        });
        proRestoreBilling.startConnection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass); // reflect a style change made in Settings
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ApiKeyStore.detachRealtimeListener(apiKeyAutoSync);
        ProStatusManager.detachRealtimeListener(proAutoSync);
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
        cardAskAI = findViewById(R.id.cardAskAI);
        cardFriends = findViewById(R.id.cardFriends);
    }

    /** Shows the animated how-to guide once, on the first launch after login. */
    private void maybeShowOnboarding() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (!pref.getBoolean(OnboardingActivity.PREF_DONE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
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
            cardNewProblem.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, GeometryInputActivity.class)); });
        }

        if (cardScanProblem != null) {
            cardScanProblem.setOnClickListener(v -> {
                Ux.tick(v);
                if (checkCameraPermission()) dispatchTakePictureIntent();
                else requestCameraPermission();
            });
        }

        if (cardDrawProblem != null) {
            cardDrawProblem.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, DrawingActivity.class)); });
        }

        if (cardHistory != null) {
            cardHistory.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, HistoryActivity.class)); });
        }

        if (cardTheorems != null) {
            cardTheorems.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, TheoremsActivity.class)); });
        }

        if (cardAskAI != null) {
            cardAskAI.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, ChatActivity.class)); });
        }

        if (cardFriends != null) {
            cardFriends.setOnClickListener(v -> { Ux.tick(v); startActivity(new Intent(this, FriendsActivity.class)); });
        }

        // Pull the token wallet BEFORE anything can spend from it. Until this lands, TokenWallet
        // refuses to push, so a fresh install can't overwrite purchased tokens with its local zero.
        TokenWallet.syncFromCloud(this, null);

        // Keep this account findable by username, and claim the name that proves we own our cloud
        // history. If another account already holds it, this account's work will NOT sync — say so.
        Social.publishDirectoryEntry(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.username_taken_title)
                    .setMessage(R.string.username_taken_msg)
                    .setPositiveButton(R.string.username_taken_fix,
                            (d, w) -> startActivity(new Intent(this, ProfileActivity.class)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

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
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
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
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        String username = userPrefs.getString("username", "");
        boolean isProUser = userPrefs.getBoolean("is_pro_user", false);
        String apiKey = "";

        if (username.equals("Admin_Teacher") || isProUser) {
            apiKey = BuildConfig.GEMINI_API_KEY;
        } else if (ApiKeyStore.hasUsableKeys(this)) {
            apiKey = ApiKeyStore.getKeys(this).get(0);
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
            String prompt = "You are the scanner for a math/geometry tutoring app. Examine the image, which may be a photo "
                    + "of a handwritten or printed math problem AND/OR a hand-drawn geometry figure. "
                    + "If it contains ANY math — geometry, trigonometry, mensuration, algebra, equations, coordinates, a "
                    + "proof/construction, a labelled figure, or a math word problem (with or without a figure) — do BOTH:\n"
                    + "1) Transcribe any problem text EXACTLY as written, preserving numbers, symbols and labels.\n"
                    + "2) If there is a figure or drawing, describe it precisely under a line that starts with 'FIGURE:' — name "
                    + "the shapes, list every labelled point/side/angle, all given measurements, and every marked relationship "
                    + "(right angles, equal or parallel marks, tangents, midpoints, etc.).\n"
                    + "Read handwriting and slightly blurry marks as best you can. "
                    + "ONLY if the image clearly has NO math at all (e.g. a photo of a person/object/scene, a non-math screenshot, "
                    + "or it is completely unreadable) output EXACTLY this single token and nothing else: INVALID_IMAGE";

            Futures.addCallback(geminiAI.extractTextFromImage(safeBitmap, prompt), new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        String text = (result != null) ? result.getText() : null;
                        if (text == null || text.trim().isEmpty() || text.contains("INVALID_IMAGE")) {
                            // Not a geometry problem: clearly notify the user instead of opening the solver.
                            new android.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.scan_not_geometry_title)
                                    .setMessage(R.string.scan_not_geometry_msg)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        } else {
                            Intent intent = new Intent(MainActivity.this, GeometryInputActivity.class);
                            intent.putExtra("SCANNED_TEXT", text.trim());
                            // Also carry the actual drawing so the solver can SEE it (Gemini vision),
                            // not just the transcription — the figure often holds what the text can't.
                            if (currentPhotoPath != null) intent.putExtra("SCANNED_IMAGE_PATH", currentPhotoPath);
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