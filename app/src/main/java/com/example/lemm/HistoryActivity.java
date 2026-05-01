package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private TextView tvTestModeWarning;
    private Button btnShowSolutions, btnShowDrawings;
    private DatabaseHelper dbHelper;
    private List<GenericItem> displayList = new ArrayList<>();
    private boolean showingSolutions = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        rvHistory = findViewById(R.id.rvHistory);
        tvTestModeWarning = findViewById(R.id.tvTestModeWarning);
        btnShowSolutions = findViewById(R.id.btnShowSolutions);
        btnShowDrawings = findViewById(R.id.btnShowDrawings);
        
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        dbHelper = new DatabaseHelper(this);

        btnShowSolutions.setOnClickListener(v -> {
            showingSolutions = true;
            updateTabStyles();
            loadHistory();
        });

        btnShowDrawings.setOnClickListener(v -> {
            showingSolutions = false;
            updateTabStyles();
            loadHistory();
        });

        checkTestMode();
        updateTabStyles();
        loadHistory();
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

    private void checkTestMode() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean isTestMode = pref.getBoolean("is_test_mode", false);
        tvTestModeWarning.setVisibility(isTestMode ? View.VISIBLE : View.GONE);
    }

    private void loadHistory() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "GuestUser");
        boolean isTestMode = pref.getBoolean("is_test_mode", false);

        displayList.clear();
        
        if (showingSolutions) {
            loadSolutions(username, isTestMode);
        } else {
            loadDrawings(username, isTestMode);
        }

        rvHistory.setAdapter(new GenericAdapter(displayList, new GenericAdapter.OnItemActionListener() {
            @Override
            public void onItemClick(GenericItem item) {
                openItem(item);
            }

            @Override
            public void onEditClick(GenericItem item) {
                editItem(item);
            }

            @Override
            public void onRenameClick(GenericItem item) {
                showRenameDialog(item);
            }

            @Override
            public void onDeleteClick(GenericItem item) {
                confirmDelete(item);
            }

            @Override
            public void onDownloadClick(GenericItem item) {
                if (!showingSolutions) {
                    showDownloadFormatDialog(item);
                }
            }
        }, true)); 
    }

    private void showDownloadFormatDialog(GenericItem item) {
        String[] formats = {"PNG Image", "JPEG Image", "PDF Document"};
        new AlertDialog.Builder(this)
                .setTitle("Download Format")
                .setItems(formats, (dialog, which) -> {
                    if (which == 2) {
                        saveDrawingAsPdf(item);
                    } else {
                        Bitmap.CompressFormat format = (which == 0) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                        String ext = (which == 0) ? ".png" : ".jpg";
                        String mime = (which == 0) ? "image/png" : "image/jpeg";
                        saveDrawingToGallery(item, format, ext, mime);
                    }
                })
                .show();
    }

    private void saveDrawingToGallery(GenericItem item, Bitmap.CompressFormat format, String ext, String mimeType) {
        GeometryCanvas tempCanvas = new GeometryCanvas(this, null);
        tempCanvas.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY));
        tempCanvas.layout(0, 0, 1440, 1440);
        tempCanvas.setDrawingData(item.data);
        
        try {
            Bitmap bitmap = Bitmap.createBitmap(1440, 1440, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            tempCanvas.draw(canvas);

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

            bitmap.compress(format, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save drawing", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void saveDrawingAsPdf(GenericItem item) {
        GeometryCanvas tempCanvas = new GeometryCanvas(this, null);
        tempCanvas.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(2036, View.MeasureSpec.EXACTLY)); // A4-like aspect
        tempCanvas.layout(0, 0, 1440, 2036);
        tempCanvas.setDrawingData(item.data);

        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(1440, 2036, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        tempCanvas.draw(page.getCanvas());
        document.finishPage(page);

        try {
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
            document.writeTo(fos);
            document.close();
            fos.close();
            Toast.makeText(this, "PDF Saved to Documents", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "PDF Export Failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
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
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                if (showingSolutions) {
                    dbHelper.renameHistory(item.id, newName);
                } else {
                    dbHelper.renameDrawing(item.id, newName);
                }
                loadHistory();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmDelete(GenericItem item) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_item_title))
                .setMessage(getString(R.string.delete_item_msg))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    if (showingSolutions) {
                        dbHelper.deleteHistory(item.id);
                    } else {
                        dbHelper.deleteDrawing(item.id);
                    }
                    Toast.makeText(this, getString(R.string.deleted_toast), Toast.LENGTH_SHORT).show();
                    loadHistory();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void openItem(GenericItem item) {
        if (showingSolutions) {
            Intent intent = new Intent(this, GeometryInputActivity.class);
            intent.putExtra("SAVED_RAW", item.data);
            intent.putExtra("SAVED_PROBLEM", item.subtext);
            intent.putExtra("SAVED_NAME", item.title);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, DrawingActivity.class);
            intent.putExtra("LOAD_DRAWING_DATA", item.data);
            intent.putExtra("IS_VIEW_ONLY", true);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
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
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, DrawingActivity.class);
            intent.putExtra("LOAD_DRAWING_DATA", item.data);
            intent.putExtra("IS_VIEW_ONLY", false);
            intent.putExtra("EDIT_ID", item.id);
            intent.putExtra("SAVED_NAME", item.title);
            startActivity(intent);
        }
    }

    private void loadSolutions(String username, boolean isTestMode) {
        Cursor cursor = dbHelper.getHistory(username);
        addItemsFromCursor(cursor, true);
        if (isTestMode) {
            Cursor tempCursor = dbHelper.getHistory("TEMP_" + username);
            addItemsFromCursor(tempCursor, true);
        }
    }

    private void loadDrawings(String username, boolean isTestMode) {
        Cursor cursor = dbHelper.getDrawings(username);
        addItemsFromCursor(cursor, false);
        if (isTestMode) {
            Cursor tempCursor = dbHelper.getDrawings("TEMP_" + username);
            addItemsFromCursor(tempCursor, false);
        }
    }

    private void addItemsFromCursor(Cursor cursor, boolean isSolution) {
        if (cursor != null && cursor.moveToFirst()) {
            do {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow(isSolution ? "hist_id" : "drw_id"));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                String data = isSolution ? cursor.getString(cursor.getColumnIndexOrThrow("raw_response")) : cursor.getString(cursor.getColumnIndexOrThrow("data"));
                String sub = isSolution ? cursor.getString(cursor.getColumnIndexOrThrow("problem")) : getString(R.string.drawing_date, date);
                displayList.add(new GenericItem(id, name, sub, data, date));
            } while (cursor.moveToNext());
            cursor.close();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private static class GenericItem {
        int id;
        String title, subtext, data, date;
        GenericItem(int id, String t, String s, String d, String dt) {
            this.id = id; this.title = t; this.subtext = s; this.data = d; this.date = dt;
        }
    }

    private static class GenericAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> {
        private List<GenericItem> items;
        private OnItemActionListener listener;
        private boolean showEdit;

        interface OnItemActionListener { 
            void onItemClick(GenericItem item);
            void onEditClick(GenericItem item);
            void onRenameClick(GenericItem item);
            void onDeleteClick(GenericItem item);
            void onDownloadClick(GenericItem item);
        }

        GenericAdapter(List<GenericItem> items, OnItemActionListener listener, boolean showEdit) {
            this.items = items; this.listener = listener; this.showEdit = showEdit;
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GenericItem item = items.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvSub.setText(item.subtext);
            holder.tvDate.setText(item.date);
            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
            holder.btnDelete.setOnClickListener(v -> listener.onDeleteClick(item));
            holder.btnRename.setOnClickListener(v -> listener.onRenameClick(item));
            holder.btnDownload.setOnClickListener(v -> listener.onDownloadClick(item));
            
            if (showEdit) {
                holder.btnEdit.setVisibility(View.VISIBLE);
                holder.btnEdit.setOnClickListener(v -> listener.onEditClick(item));
            } else {
                holder.btnEdit.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvSub, tvDate;
            Button btnEdit;
            ImageButton btnDelete, btnRename, btnDownload;
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvHistoryName);
                tvSub = itemView.findViewById(R.id.tvHistoryProblem);
                tvDate = itemView.findViewById(R.id.tvHistoryDate);
                btnEdit = itemView.findViewById(R.id.btnEditHistory);
                btnDelete = itemView.findViewById(R.id.btnDeleteHistory);
                btnRename = itemView.findViewById(R.id.btnRenameHistory);
                btnDownload = itemView.findViewById(R.id.btnDownloadHistory);
            }
        }
    }
}
