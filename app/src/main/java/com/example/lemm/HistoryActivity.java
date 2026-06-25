package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private Button btnShowSolutions, btnShowDrawings;

    private List<GenericItem> displayList = new ArrayList<>();
    private GenericAdapter adapter;
    private boolean showingSolutions = true;
    private DatabaseHelper dbHelper;
    private String currentUsername;

    private DatabaseReference cloudRef;
    private ValueEventListener cloudListener;

    // Serializes all DB writes/reads off the UI thread so the cloud merge never freezes the list.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = new DatabaseHelper(this);
        currentUsername = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");

        // Push local history up to the cloud (real accounts only; guests don't sync).
        if (!currentUsername.startsWith("GuestUser")) {
            CloudSyncManager.syncLocalToCloud(dbHelper, currentUsername);
        }

        rvHistory = findViewById(R.id.rvHistory);
        btnShowSolutions = findViewById(R.id.btnShowSolutions);
        btnShowDrawings = findViewById(R.id.btnShowDrawings);

        View backBtn = findViewById(R.id.btnBack);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        rvHistory.setLayoutManager(new LinearLayoutManager(this));

        adapter = new GenericAdapter(displayList, ioExecutor, new GenericAdapter.OnItemActionListener() {
            @Override public void onItemClick(GenericItem item) { openItem(item); }
            @Override public void onEditClick(GenericItem item) { editItem(item); }
            @Override public void onRenameClick(GenericItem item) { showRenameDialog(item); }
            @Override public void onDeleteClick(GenericItem item) { confirmDelete(item); }
            @Override public void onDownloadClick(GenericItem item) {
                if (showingSolutions) exportSolutionImage(item);
                else showDownloadFormatDialog(item);
            }
        });

        rvHistory.setAdapter(adapter);

        attachSwipeToDelete();
        maybeShowSwipeHint();

        btnShowSolutions.setOnClickListener(v -> {
            showingSolutions = true;
            updateTabStyles();
            listenToCloudAndMerge();
        });

        btnShowDrawings.setOnClickListener(v -> {
            showingSolutions = false;
            updateTabStyles();
            listenToCloudAndMerge();
        });

        updateTabStyles();
        listenToCloudAndMerge();
    }

    private void updateTabStyles() {
        if (showingSolutions) {
            btnShowSolutions.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0C3D6A));
            btnShowSolutions.setTextColor(Color.WHITE);
            btnShowDrawings.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnShowDrawings.setTextColor(0xFF0C3D6A);
        } else {
            btnShowDrawings.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0C3D6A));
            btnShowDrawings.setTextColor(Color.WHITE);
            btnShowSolutions.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnShowSolutions.setTextColor(0xFF0C3D6A);
        }
    }

    private void loadLocalHistoryOnly() {
        List<GenericItem> fresh = queryLocalHistory(showingSolutions);
        displayList.clear();
        displayList.addAll(fresh);
        adapter.setSolutions(showingSolutions);
        adapter.notifyDataSetChanged();
    }

    /** Reads the local DB into a sorted list. Safe to call from a background thread. */
    private List<GenericItem> queryLocalHistory(boolean solutions) {
        List<GenericItem> out = new ArrayList<>();
        Cursor cursor = solutions ? dbHelper.getHistory(currentUsername) : dbHelper.getDrawings(currentUsername);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    if (solutions) {
                        String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("hist_id")));
                        String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                        String prob = cursor.getString(cursor.getColumnIndexOrThrow("problem"));
                        String raw = cursor.getString(cursor.getColumnIndexOrThrow("raw_response"));
                        String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                        out.add(new GenericItem(id, title, prob, raw, date));
                    } else {
                        String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("drw_id")));
                        String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                        String data = cursor.getString(cursor.getColumnIndexOrThrow("data"));
                        String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                        out.add(new GenericItem(id, title, "Date: " + date, data, date));
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        Collections.sort(out, (o1, o2) -> o2.date.compareTo(o1.date));
        return out;
    }

    // REAL-TIME CLOUD LISTENER
    private void listenToCloudAndMerge() {
        if (cloudRef != null && cloudListener != null) {
            cloudRef.removeEventListener(cloudListener);
        }

        // Guests don't use cloud sync
        if (currentUsername.startsWith("GuestUser")) {
            loadLocalHistoryOnly();
            return;
        }

        String node = showingSolutions ? "history" : "drawings";
        cloudRef = FirebaseManager.getUserRef(currentUsername).child(node);

        cloudListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                mergeData(snapshot);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(HistoryActivity.this, "Cloud Sync Blocked: Check Firebase Rules", Toast.LENGTH_LONG).show();
                Log.e("FirebaseSync", "Cloud sync blocked: " + error.getMessage());
                loadLocalHistoryOnly(); // Fallback to offline
            }
        };
        cloudRef.addValueEventListener(cloudListener);

        // Show offline items immediately while waiting for cloud
        loadLocalHistoryOnly();
    }

    private void mergeData(DataSnapshot cloudSnapshot) {
        final boolean solutions = showingSolutions;

        // Pull the snapshot into a lightweight in-memory list here (cheap, no disk I/O). We never
        // blanket-delete: with Firebase offline persistence the first snapshot can arrive empty/cached,
        // so a blanket delete would wipe good local rows. Each cloud item replaces its local namesake.
        final List<ContentValues> rows = new ArrayList<>();
        if (cloudSnapshot != null && cloudSnapshot.exists()) {
            for (DataSnapshot child : cloudSnapshot.getChildren()) {
                String date = child.child("date").getValue(String.class);
                if (date == null) continue;
                String title = child.child("title").getValue(String.class);
                if (title == null) title = "Synced Item";

                ContentValues v = new ContentValues();
                v.put("username", currentUsername);
                v.put("name", title);
                v.put("date", date);
                v.put("synced", 1); // mirrored from cloud => already synced
                if (solutions) {
                    String prob = child.child("problem").getValue(String.class);
                    String raw = child.child("raw_response").getValue(String.class);
                    v.put("problem", prob != null ? prob : "");
                    v.put("solution", "");
                    v.put("raw_response", raw != null ? raw : "");
                } else {
                    String data = child.child("data").getValue(String.class);
                    v.put("data", data != null ? data : "{}");
                }
                rows.add(v);
            }
        }

        // Do every write in ONE transaction on a background thread (autocommit fsync-per-row was the
        // freeze), then reload the list off-thread and hand the finished result to the UI.
        final String table = solutions ? "history" : "drawings";
        ioExecutor.execute(() -> {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    for (ContentValues v : rows) {
                        db.delete(table, "username = ? AND date = ?",
                                new String[]{currentUsername, v.getAsString("date")});
                        db.insert(table, null, v);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Exception e) {
                Log.e("HistorySync", "Error mirroring data: " + e.getMessage());
            }

            final List<GenericItem> fresh = queryLocalHistory(solutions);
            runOnUiThread(() -> {
                if (showingSolutions != solutions) return; // user switched tabs while we worked
                displayList.clear();
                displayList.addAll(fresh);
                adapter.notifyDataSetChanged();
            });
        });
    }
    private void showRenameDialog(GenericItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Item");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(item.title);
        input.setSelectAllOnFocus(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                int id = Integer.parseInt(item.id);
                if (showingSolutions) dbHelper.renameHistory(id, newName);
                else dbHelper.renameDrawing(id, newName);

                // Push rename to cloud immediately
                if (!currentUsername.startsWith("GuestUser")) {
                    String cloudKey = item.date.replaceAll("[^a-zA-Z0-9]", "");
                    FirebaseManager.getUserRef(currentUsername)
                            .child(showingSolutions ? "history" : "drawings")
                            .child(cloudKey).child("title").setValue(newName);
                }

                Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show();
                loadLocalHistoryOnly(); // Refresh screen
            }
        });
        builder.setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(GenericItem item) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_item_title))
                .setMessage(getString(R.string.delete_item_msg))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    int id = Integer.parseInt(item.id);
                    if (showingSolutions) dbHelper.deleteHistory(id);
                    else dbHelper.deleteDrawing(id);

                    // Push deletion to cloud immediately
                    if (!currentUsername.startsWith("GuestUser")) {
                        String cloudKey = item.date.replaceAll("[^a-zA-Z0-9]", "");
                        FirebaseManager.getUserRef(currentUsername).child(showingSolutions ? "history" : "drawings").child(cloudKey).removeValue();
                    }

                    Toast.makeText(HistoryActivity.this, getString(R.string.deleted_toast), Toast.LENGTH_SHORT).show();
                    loadLocalHistoryOnly(); // Refresh screen
                })
                .setNegativeButton(getString(R.string.cancel), null).show();
    }

    private void attachSwipeToDelete() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            private final ColorDrawable background = new ColorDrawable(Color.parseColor("#D32F2F"));
            private final Drawable icon = ContextCompat.getDrawable(HistoryActivity.this, android.R.drawable.ic_menu_delete);

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= displayList.size()) { adapter.notifyDataSetChanged(); return; }

                final GenericItem item = displayList.get(pos);
                final boolean wasSolutions = showingSolutions;

                // Remove from the list and the stores right away so a cloud refresh can't resurrect it,
                // then offer UNDO (Gmail-style) to restore everything if it was a mistake.
                displayList.remove(pos);
                adapter.notifyItemRemoved(pos);
                deleteItemFromStores(item, wasSolutions);

                Snackbar.make(rvHistory, getString(R.string.deleted_toast), Snackbar.LENGTH_LONG)
                        .setAction("UNDO", v -> restoreItemToStores(item, wasSolutions))
                        .show();
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View row = vh.itemView;
                if (dX > 0) {
                    background.setBounds(row.getLeft(), row.getTop(), row.getLeft() + (int) dX, row.getBottom());
                } else if (dX < 0) {
                    background.setBounds(row.getRight() + (int) dX, row.getTop(), row.getRight(), row.getBottom());
                } else {
                    background.setBounds(0, 0, 0, 0);
                }
                background.draw(c);

                if (icon != null && dX != 0) {
                    int iw = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
                    int margin = (row.getHeight() - ih) / 2;
                    int top = row.getTop() + margin, bottom = top + ih;
                    if (dX > 0) {
                        int left = row.getLeft() + margin;
                        icon.setBounds(left, top, left + iw, bottom);
                    } else {
                        int right = row.getRight() - margin;
                        icon.setBounds(right - iw, top, right, bottom);
                    }
                    icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                    icon.draw(c);
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(rvHistory);
    }

    private void maybeShowSwipeHint() {
        android.content.SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        if (!pref.getBoolean("swipe_hint_shown", false)) {
            Toast.makeText(this, "Tip: swipe a card left or right to delete", Toast.LENGTH_LONG).show();
            pref.edit().putBoolean("swipe_hint_shown", true).apply();
        }
    }

    private void deleteItemFromStores(GenericItem item, boolean solutions) {
        try {
            int id = Integer.parseInt(item.id);
            if (solutions) dbHelper.deleteHistory(id); else dbHelper.deleteDrawing(id);
        } catch (Exception ignored) {}
        if (!currentUsername.startsWith("GuestUser") && item.date != null) {
            String cloudKey = item.date.replaceAll("[^a-zA-Z0-9]", "");
            FirebaseManager.getUserRef(currentUsername).child(solutions ? "history" : "drawings").child(cloudKey).removeValue();
        }
    }

    private void restoreItemToStores(GenericItem item, boolean solutions) {
        if (solutions) {
            dbHelper.addHistoryWithDate(currentUsername, item.title, item.subtext, "", item.data, item.date);
        } else {
            dbHelper.addDrawingWithDate(currentUsername, item.title, item.data, item.date);
        }
        if (!currentUsername.startsWith("GuestUser") && item.date != null) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("title", item.title);
            map.put("date", item.date);
            if (solutions) { map.put("problem", item.subtext); map.put("raw_response", item.data); }
            else { map.put("data", item.data); }
            String cloudKey = item.date.replaceAll("[^a-zA-Z0-9]", "");
            FirebaseManager.getUserRef(currentUsername).child(solutions ? "history" : "drawings").child(cloudKey).setValue(map);
        }
        loadLocalHistoryOnly();
    }

    private void openItem(GenericItem item) {
        if (showingSolutions) {
            Intent intent = new Intent(this, GeometryInputActivity.class);
            intent.putExtra("SAVED_RAW", item.data);
            intent.putExtra("SAVED_PROBLEM", item.subtext);
            intent.putExtra("SAVED_NAME", item.title);
            intent.putExtra("SAVED_DATE", item.date);
            startActivity(intent);
        } else if (GeometryCanvas3D.isJson3d(item.data)) {
            Intent intent = new Intent(this, Drawing3DActivity.class);
            intent.putExtra("LOAD_3D_DATA", item.data);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, DrawingActivity.class);
            intent.putExtra("LOAD_DRAWING_DATA", item.data);
            intent.putExtra("IS_VIEW_ONLY", true);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
            intent.putExtra("SAVED_DATE", item.date);
            startActivity(intent);
        }
    }

    private void editItem(GenericItem item) {
        if (showingSolutions) {
            Intent intent = new Intent(this, GeometryInputActivity.class);
            intent.putExtra("EDIT_MODE", true);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_PROBLEM", item.subtext);
            intent.putExtra("SAVED_NAME", item.title);
            intent.putExtra("SAVED_RAW", item.data);
            intent.putExtra("SAVED_DATE", item.date);
            startActivity(intent);
        } else if (GeometryCanvas3D.isJson3d(item.data)) {
            Intent intent = new Intent(this, Drawing3DActivity.class);
            intent.putExtra("LOAD_3D_DATA", item.data);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, DrawingActivity.class);
            intent.putExtra("LOAD_DRAWING_DATA", item.data);
            intent.putExtra("IS_VIEW_ONLY", false);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
            intent.putExtra("SAVED_DATE", item.date);
            startActivity(intent);
        }
    }

    /** Renders the saved solution's 3D figure offscreen, then offers Image / PDF export. */
    private void exportSolutionImage(GenericItem item) {
        Bitmap figure = null;
        try {
            GeometryCanvas3D cv = new GeometryCanvas3D(this, null);
            cv.loadFromSolution(item.data);
            if (!cv.isEmpty()) {
                int size = 1200;
                cv.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
                cv.layout(0, 0, size, size);
                figure = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                cv.draw(new Canvas(figure));
            }
        } catch (Exception e) {
            Log.e("HistoryExport", "Figure render failed: " + e.getMessage());
        }
        SolutionExporter.showExportDialog(this, figure, SolutionExporter.cleanSolutionText(item.data), item.title);
    }

    private void showDownloadFormatDialog(GenericItem item) {
        String[] formats = {"PNG Image", "JPEG Image", "PDF Document"};
        new AlertDialog.Builder(this).setTitle("Download Format").setItems(formats, (dialog, which) -> {
            if (which == 2) saveDrawingAsPdf(item);
            else saveDrawingToGallery(item, (which == 0) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, (which == 0) ? ".png" : ".jpg", (which == 0) ? "image/png" : "image/jpeg");
        }).show();
    }

    private void saveDrawingToGallery(GenericItem item, Bitmap.CompressFormat format, String ext, String mimeType) {
        try {
            Bitmap bitmap = renderCadToBitmap(item.data, 2000);
            String fileName = item.title.replaceAll("[^a-zA-Z0-9]", "_") + "_" + System.currentTimeMillis() + ext;
            OutputStream fos;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LemmCAD");
                Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                fos = getContentResolver().openOutputStream(imageUri);
            } else {
                String imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
                java.io.File image = new java.io.File(imagesDir, fileName);
                fos = new java.io.FileOutputStream(image);
            }
            bitmap.compress(format, 100, fos); fos.flush(); fos.close();
            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Export Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void saveDrawingAsPdf(GenericItem item) {
        try {
            Bitmap bitmap = renderCadToBitmap(item.data, 2000);
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
            document.finishPage(page);

            String fileName = item.title.replaceAll("[^a-zA-Z0-9]", "_") + "_" + System.currentTimeMillis() + ".pdf";
            OutputStream fos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/LemmCAD");
                Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), contentValues);
                fos = getContentResolver().openOutputStream(uri);
            } else {
                java.io.File file = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName);
                fos = new java.io.FileOutputStream(file);
            }
            document.writeTo(fos); document.close(); fos.close();
            Toast.makeText(this, "PDF Saved to Documents", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "PDF Export Failed", Toast.LENGTH_SHORT).show(); }
    }

    static Bitmap renderCadToBitmap(String jsonData, int size) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // 1. Draw a clean engineering-white paper background
        canvas.drawColor(Color.WHITE);

        // 2. Deserialize the CAD data
        CadEngine2d tempEngine = new CadEngine2d();
        org.locationtech.jts.io.WKTReader reader = new org.locationtech.jts.io.WKTReader();
        List<org.locationtech.jts.geom.Geometry> geometries = new ArrayList<>();
        List<CadEngine2d.NamedPoint> points = new ArrayList<>();

        if (jsonData.trim().startsWith("[")) {
            org.json.JSONArray array = new org.json.JSONArray(jsonData);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                org.locationtech.jts.geom.Geometry g = reader.read(obj.getString("wkt"));
                if (obj.has("userData")) g.setUserData(obj.getString("userData"));
                geometries.add(g);
            }
        } else {
            org.json.JSONObject root = new org.json.JSONObject(jsonData);
            org.json.JSONArray geoArray = root.getJSONArray("geometries");
            for (int i = 0; i < geoArray.length(); i++) {
                org.json.JSONObject obj = geoArray.getJSONObject(i);
                org.locationtech.jts.geom.Geometry g = reader.read(obj.getString("wkt"));
                if (obj.has("userData")) g.setUserData(obj.getString("userData"));
                geometries.add(g);
            }
            org.json.JSONArray ptsArray = root.getJSONArray("points");
            for (int i = 0; i < ptsArray.length(); i++) {
                org.json.JSONObject obj = ptsArray.getJSONObject(i);
                points.add(new CadEngine2d.NamedPoint(obj.getDouble("x"), obj.getDouble("y"), obj.getString("label")));
            }
        }
        tempEngine.setGeometriesAndPoints(geometries, points);

        // 3. AUTO-ZOOM-TO-FIT: Find boundaries of the drawing
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (org.locationtech.jts.geom.Geometry g : geometries) {
            for (org.locationtech.jts.geom.Coordinate c : g.getCoordinates()) {
                if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
                if (c.y < minY) minY = c.y; if (c.y > maxY) maxY = c.y;
            }
        }

        // Handle empty drawings gracefully
        if (geometries.isEmpty()) return bitmap;

        double drawW = maxX - minX;
        double drawH = maxY - minY;
        if (drawW <= 0) drawW = 100; if (drawH <= 0) drawH = 100;

        // All sizes below are tuned for a 2000px canvas; scale them by k so thumbnails look right too.
        float k = size / 2000f;

        // Apply a safe padding around the drawings (scaled)
        float padding = 250f * k;
        float scale = (float) Math.min((size - 2 * padding) / drawW, (size - 2 * padding) / drawH);

        // Center the scaled drawing on the canvas
        float offsetX = (float) (padding + (size - 2 * padding - drawW * scale) / 2 - minX * scale);
        float offsetY = (float) (padding + (size - 2 * padding - drawH * scale) / 2 - minY * scale);

        // 4. Setup high-contrast paints
        android.graphics.Paint linePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#1A237E")); // Deep blueprints blue
        linePaint.setStyle(android.graphics.Paint.Style.STROKE);
        linePaint.setStrokeWidth(8f * k);

        android.graphics.Paint vertexPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        vertexPaint.setColor(Color.parseColor("#D32F2F")); // Bright red vertex dots
        vertexPaint.setStyle(android.graphics.Paint.Style.FILL);

        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#0C3D6A"));
        textPaint.setTextSize(48f * k);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);

        // 5. Draw the vectors directly on the high-res Canvas
        for (org.locationtech.jts.geom.Geometry geo : geometries) {
            org.locationtech.jts.geom.Coordinate[] coords = geo.getCoordinates();
            android.graphics.Path path = new android.graphics.Path();

            float startX = (float) (coords[0].x * scale + offsetX);
            float startY = (float) (coords[0].y * scale + offsetY);
            path.moveTo(startX, startY);

            for (int i = 1; i < coords.length; i++) {
                float px = (float) (coords[i].x * scale + offsetX);
                float py = (float) (coords[i].y * scale + offsetY);
                path.lineTo(px, py);
            }
            if (geo instanceof org.locationtech.jts.geom.Polygon) {
                path.close();
            }
            canvas.drawPath(path, linePaint);

            // Draw vertex points
            for (org.locationtech.jts.geom.Coordinate c : coords) {
                float vx = (float) (c.x * scale + offsetX);
                float vy = (float) (c.y * scale + offsetY);
                canvas.drawCircle(vx, vy, 12f * k, vertexPaint);
            }

            // Draw Dimension labels
            if (geo.getUserData() != null) {
                String label = geo.getUserData().toString();
                org.locationtech.jts.geom.Coordinate centroid = geo.getCentroid().getCoordinate();
                float cx = (float) (centroid.x * scale + offsetX);
                float cy = (float) (centroid.y * scale + offsetY);

                // Add a small background highlight behind text for readability
                android.graphics.Paint bgPaint = new android.graphics.Paint();
                bgPaint.setColor(Color.WHITE);
                canvas.drawRect(cx - 120 * k, cy - 35 * k, cx + 120 * k, cy + 20 * k, bgPaint);

                canvas.drawText(label, cx, cy, textPaint);
            }
        }

        // 6. Draw point labels (A, B, C...)
        for (CadEngine2d.NamedPoint np : points) {
            float px = (float) (np.x * scale + offsetX);
            float py = (float) (np.y * scale + offsetY);
            canvas.drawText(np.label, px + 20 * k, py - 20 * k, textPaint);
        }

        return bitmap;
    }
    /** Renders a small preview of a saved 3D drawing (the editable model JSON), or null on failure. */
    static Bitmap renderDrawing3DThumb(Context ctx, String json, int size) {
        try {
            GeometryCanvas3D cv = new GeometryCanvas3D(ctx, null);
            cv.loadFromJson(json);
            if (cv.isEmpty()) return null;
            cv.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cv.layout(0, 0, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cv.draw(new Canvas(bmp));
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** Renders a small preview of a saved solution's 3D figure, or null if it has no figure. */
    static Bitmap renderSolutionThumb(Context ctx, String rawText, int size) {
        try {
            GeometryCanvas3D cv = new GeometryCanvas3D(ctx, null);
            cv.loadFromSolution(rawText);
            if (cv.isEmpty()) return null;
            cv.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cv.layout(0, 0, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cv.draw(new Canvas(bmp));
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private static class GenericItem {
        String id;
        String title, subtext, data, date;
        GenericItem(String id, String t, String s, String d, String dt) {
            this.id = id; this.title = t; this.subtext = s; this.data = d; this.date = dt;
        }
    }

    private static class GenericAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> {
        private List<GenericItem> items;
        private OnItemActionListener listener;
        private final java.util.concurrent.Executor bgExec;
        private boolean solutions = true;

        // Thumbnails are generated once per item and cached so scrolling stays smooth.
        private static final int THUMB_PX = 220;
        private static final android.util.LruCache<String, Bitmap> THUMB_CACHE = new android.util.LruCache<>(40);
        private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

        interface OnItemActionListener {
            void onItemClick(GenericItem item); void onEditClick(GenericItem item);
            void onRenameClick(GenericItem item); void onDeleteClick(GenericItem item); void onDownloadClick(GenericItem item);
        }

        GenericAdapter(List<GenericItem> items, java.util.concurrent.Executor bgExec, OnItemActionListener listener) {
            this.items = items; this.bgExec = bgExec; this.listener = listener;
        }

        void setSolutions(boolean s) { this.solutions = s; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false)); }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GenericItem item = items.get(position);
            holder.tvTitle.setText(item.title); holder.tvSub.setText(item.subtext); holder.tvDate.setText(item.date);

            bindThumbnail(holder, item);

            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
            holder.btnDelete.setVisibility(View.GONE); // deletion is now done by swiping the card
            holder.btnRename.setOnClickListener(v -> listener.onRenameClick(item));
            holder.btnDownload.setOnClickListener(v -> listener.onDownloadClick(item));
            holder.btnEdit.setOnClickListener(v -> listener.onEditClick(item));
        }

        /** Shows a cached thumbnail immediately, or generates one off the UI thread (re-rendered from the
         *  saved DB record: the 3D figure for solutions, the 2D sketch for drawings). */
        private void bindThumbnail(ViewHolder holder, GenericItem item) {
            final boolean sol = solutions;
            final String key = (sol ? "S" : "D") + item.id + "|" + item.date;
            holder.ivThumb.setTag(key);

            Bitmap cached = THUMB_CACHE.get(key);
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached);
                return;
            }

            holder.ivThumb.setImageResource(sol ? android.R.drawable.ic_menu_gallery : android.R.drawable.ic_menu_edit);

            final String data = item.data;
            final Context ctx = holder.itemView.getContext().getApplicationContext();
            bgExec.execute(() -> {
                Bitmap bmp = null;
                try {
                    if (sol) bmp = renderSolutionThumb(ctx, data, THUMB_PX);
                    else if (GeometryCanvas3D.isJson3d(data)) bmp = renderDrawing3DThumb(ctx, data, THUMB_PX);
                    else bmp = renderCadToBitmap(data, THUMB_PX);
                } catch (Exception ignored) { }
                final Bitmap result = bmp;
                if (result != null) THUMB_CACHE.put(key, result);
                MAIN.post(() -> {
                    if (result != null && key.equals(holder.ivThumb.getTag())) {
                        holder.ivThumb.setImageBitmap(result);
                    }
                });
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvSub, tvDate; Button btnEdit; ImageButton btnDelete, btnRename, btnDownload; ImageView ivThumb;
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvHistoryName); tvSub = itemView.findViewById(R.id.tvHistoryProblem); tvDate = itemView.findViewById(R.id.tvHistoryDate);
                btnEdit = itemView.findViewById(R.id.btnEditHistory); btnDelete = itemView.findViewById(R.id.btnDeleteHistory); btnRename = itemView.findViewById(R.id.btnRenameHistory); btnDownload = itemView.findViewById(R.id.btnDownloadHistory);
                ivThumb = itemView.findViewById(R.id.ivHistoryThumb);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cloudRef != null && cloudListener != null) cloudRef.removeEventListener(cloudListener);
        ioExecutor.shutdown();
    }

    @Override protected void attachBaseContext(Context newBase) { super.attachBaseContext(LocaleHelper.onAttach(newBase)); }
}