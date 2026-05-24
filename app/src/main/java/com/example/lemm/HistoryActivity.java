package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = new DatabaseHelper(this);
        currentUsername = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");

        rvHistory = findViewById(R.id.rvHistory);
        btnShowSolutions = findViewById(R.id.btnShowSolutions);
        btnShowDrawings = findViewById(R.id.btnShowDrawings);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));

        adapter = new GenericAdapter(displayList, new GenericAdapter.OnItemActionListener() {
            @Override public void onItemClick(GenericItem item) { openItem(item); }
            @Override public void onEditClick(GenericItem item) { editItem(item); }
            @Override public void onRenameClick(GenericItem item) { showRenameDialog(item); }
            @Override public void onDeleteClick(GenericItem item) { confirmDelete(item); }
            @Override public void onDownloadClick(GenericItem item) {
                if (!showingSolutions) showDownloadFormatDialog(item);
            }
        });

        rvHistory.setAdapter(adapter);

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
        displayList.clear();
        Cursor cursor = showingSolutions ? dbHelper.getHistory(currentUsername) : dbHelper.getDrawings(currentUsername);

        if (cursor != null && cursor.moveToFirst()) {
            do {
                if (showingSolutions) {
                    String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("hist_id")));
                    String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                    String prob = cursor.getString(cursor.getColumnIndexOrThrow("problem"));
                    String raw = cursor.getString(cursor.getColumnIndexOrThrow("raw_response"));
                    String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                    displayList.add(new GenericItem(id, title, prob, raw, date));
                } else {
                    String id = String.valueOf(cursor.getInt(cursor.getColumnIndexOrThrow("drw_id")));
                    String title = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                    String data = cursor.getString(cursor.getColumnIndexOrThrow("data"));
                    String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                    displayList.add(new GenericItem(id, title, "Date: " + date, data, date));
                }
            } while (cursor.moveToNext());
            cursor.close();
        }

        Collections.sort(displayList, (o1, o2) -> o2.date.compareTo(o1.date));
        adapter.notifyDataSetChanged();
    }

    // REAL-TIME CLOUD LISTENER
    private void listenToCloudAndMerge() {
        if (cloudRef != null && cloudListener != null) {
            cloudRef.removeEventListener(cloudListener);
        }

        // Guests don't use cloud sync
        if (currentUsername.startsWith("GuestUser_")) {
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
        HashSet<String> localDates = new HashSet<>();

        // 1. Map everything we already have locally
        Cursor cursor = showingSolutions ? dbHelper.getHistory(currentUsername) : dbHelper.getDrawings(currentUsername);
        if (cursor != null && cursor.moveToFirst()) {
            do {
                localDates.add(cursor.getString(cursor.getColumnIndexOrThrow("date")));
            } while (cursor.moveToNext());
            cursor.close();
        }

        boolean downloadedNewData = false;

        // 2. Download anything from cloud that we are missing locally
        if (cloudSnapshot != null) {
            for (DataSnapshot child : cloudSnapshot.getChildren()) {
                String date = child.child("date").getValue(String.class);

                // If it lacks a date or we already have it locally, ignore it
                if (date == null || localDates.contains(date)) continue;

                String title = child.child("title").getValue(String.class);
                if (title == null) title = "Synced Item";

                if (showingSolutions) {
                    String prob = child.child("problem").getValue(String.class);
                    String raw = child.child("raw_response").getValue(String.class);
                    dbHelper.addHistoryWithDate(currentUsername, title, prob == null ? "" : prob, "", raw == null ? "" : raw, date);
                } else {
                    String data = child.child("data").getValue(String.class);
                    dbHelper.addDrawingWithDate(currentUsername, title, data == null ? "{}" : data, date);
                }
                downloadedNewData = true;
            }
        }

        // 3. If we downloaded things from the cloud, reload our list so they appear!
        if (downloadedNewData) {
            loadLocalHistoryOnly();
        }
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
                if (!currentUsername.startsWith("GuestUser_")) {
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
                    if (!currentUsername.startsWith("GuestUser_")) {
                        String cloudKey = item.date.replaceAll("[^a-zA-Z0-9]", "");
                        FirebaseManager.getUserRef(currentUsername).child(showingSolutions ? "history" : "drawings").child(cloudKey).removeValue();
                    }

                    Toast.makeText(HistoryActivity.this, getString(R.string.deleted_toast), Toast.LENGTH_SHORT).show();
                    loadLocalHistoryOnly(); // Refresh screen
                })
                .setNegativeButton(getString(R.string.cancel), null).show();
    }

    private void openItem(GenericItem item) {
        if (showingSolutions) {
            Intent intent = new Intent(this, GeometryInputActivity.class);
            intent.putExtra("SAVED_RAW", item.data);
            intent.putExtra("SAVED_PROBLEM", item.subtext);
            intent.putExtra("SAVED_NAME", item.title);
            intent.putExtra("SAVED_DATE", item.date);
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

    private void showDownloadFormatDialog(GenericItem item) {
        String[] formats = {"PNG Image", "JPEG Image", "PDF Document"};
        new AlertDialog.Builder(this).setTitle("Download Format").setItems(formats, (dialog, which) -> {
            if (which == 2) saveDrawingAsPdf(item);
            else saveDrawingToGallery(item, (which == 0) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, (which == 0) ? ".png" : ".jpg", (which == 0) ? "image/png" : "image/jpeg");
        }).show();
    }

    private void saveDrawingToGallery(GenericItem item, Bitmap.CompressFormat format, String ext, String mimeType) {
        try {
            Bitmap bitmap = renderCadToBitmap(item.data);
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
            Bitmap bitmap = renderCadToBitmap(item.data);
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

    private Bitmap renderCadToBitmap(String jsonData) throws Exception {
        int size = 2000;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

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
        CadGeometryCanvas tempView = new CadGeometryCanvas(this, null);
        tempView.setEngine(tempEngine);
        tempView.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        tempView.layout(0, 0, size, size);
        tempView.pan(size/2f, size/2f);
        tempView.draw(canvas);
        return bitmap;
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

        interface OnItemActionListener {
            void onItemClick(GenericItem item); void onEditClick(GenericItem item);
            void onRenameClick(GenericItem item); void onDeleteClick(GenericItem item); void onDownloadClick(GenericItem item);
        }

        GenericAdapter(List<GenericItem> items, OnItemActionListener listener) { this.items = items; this.listener = listener; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false)); }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GenericItem item = items.get(position);
            holder.tvTitle.setText(item.title); holder.tvSub.setText(item.subtext); holder.tvDate.setText(item.date);

            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
            holder.btnDelete.setOnClickListener(v -> listener.onDeleteClick(item));
            holder.btnRename.setOnClickListener(v -> listener.onRenameClick(item));
            holder.btnDownload.setOnClickListener(v -> listener.onDownloadClick(item));
            holder.btnEdit.setOnClickListener(v -> listener.onEditClick(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvSub, tvDate; Button btnEdit; ImageButton btnDelete, btnRename, btnDownload;
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvHistoryName); tvSub = itemView.findViewById(R.id.tvHistoryProblem); tvDate = itemView.findViewById(R.id.tvHistoryDate);
                btnEdit = itemView.findViewById(R.id.btnEditHistory); btnDelete = itemView.findViewById(R.id.btnDeleteHistory); btnRename = itemView.findViewById(R.id.btnRenameHistory); btnDownload = itemView.findViewById(R.id.btnDownloadHistory);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cloudRef != null && cloudListener != null) cloudRef.removeEventListener(cloudListener);
    }

    @Override protected void attachBaseContext(Context newBase) { super.attachBaseContext(LocaleHelper.onAttach(newBase)); }
}