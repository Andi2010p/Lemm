package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Exports a solved problem either as:
 *   • Image  — the figure (3D canvas) only, saved to the gallery.
 *   • PDF    — the figure, then the solution as real selectable text, saved to Documents.
 */
public final class SolutionExporter {

    private SolutionExporter() {}

    /** Shows the format chooser. {@code figure} may be null (image option then reports "no figure"). */
    public static void showExportDialog(Context ctx, Bitmap figure, String solutionText, String title) {
        final String safeTitle = (title == null || title.trim().isEmpty()) ? "Solution" : title.trim();
        CharSequence[] items = {
                ctx.getString(R.string.export_image_opt),
                ctx.getString(R.string.export_pdf_opt)
        };
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.export_choose)
                .setItems(items, (d, which) -> {
                    if (which == 0) saveImage(ctx, figure, safeTitle);
                    else savePdf(ctx, figure, solutionText, safeTitle);
                })
                .show();
    }

    /** Removes the 3D drawing commands from a raw solution, leaving the human-readable text. */
    public static String cleanSolutionText(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("DRAW3D:") || t.startsWith("LINE3D:") || t.startsWith("PLANE3D:")
                    || t.startsWith("CONE3D:") || t.startsWith("PYRAMID3D:") || t.startsWith("CYLINDER3D:")
                    || t.startsWith("SPHERE3D:") || t.startsWith("CIRCLE3D:")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ---- Image (figure only) → gallery ----
    private static void saveImage(Context ctx, Bitmap figure, String title) {
        if (figure == null) {
            Toast.makeText(ctx, R.string.no_figure, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String fileName = safeName(title) + "_" + System.currentTimeMillis() + ".png";
            OutputStream fos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lemma");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                fos = ctx.getContentResolver().openOutputStream(uri);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                fos = new FileOutputStream(new File(dir, fileName));
            }
            figure.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(ctx, R.string.solution_image_saved, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---- PDF (figure page + selectable solution text) → Documents ----
    private static void savePdf(Context ctx, Bitmap figure, String solutionText, String title) {
        final int pageW = 595, pageH = 842, margin = 40;       // A4 @ 72dpi
        final int contentW = pageW - 2 * margin;
        final int pageContentH = pageH - 2 * margin;

        try {
            PdfDocument doc = new PdfDocument();
            int pageIndex = 1;

            Paint titlePaint = new Paint();
            titlePaint.setColor(Color.BLACK);
            titlePaint.setFakeBoldText(true);
            titlePaint.setTextSize(18f);
            titlePaint.setAntiAlias(true);

            String body = solutionText == null ? "" : solutionText.trim();

            // Page 1: title + figure (if any). With no figure, the title rides on top of the text instead.
            if (figure != null) {
                PdfDocument.Page page = doc.startPage(
                        new PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex++).create());
                Canvas c = page.getCanvas();
                c.drawText(title, margin, margin + 18, titlePaint);
                float maxW = contentW, maxH = pageH - (margin + 50) - margin;
                float scale = Math.min(maxW / figure.getWidth(), maxH / figure.getHeight());
                float fw = figure.getWidth() * scale, fh = figure.getHeight() * scale;
                float fx = margin + (contentW - fw) / 2f, fy = margin + 50;
                c.drawBitmap(figure, null, new RectF(fx, fy, fx + fw, fy + fh), null);
                doc.finishPage(page);
            } else {
                body = title + "\n\n" + body;
            }

            if (!body.isEmpty()) {
                TextPaint tp = new TextPaint();
                tp.setColor(Color.BLACK);
                tp.setTextSize(12f);
                tp.setAntiAlias(true);

                StaticLayout layout = StaticLayout.Builder
                        .obtain(body, 0, body.length(), tp, contentW).build();
                int totalH = layout.getHeight();
                int pages = Math.max(1, (int) Math.ceil(totalH / (float) pageContentH));

                for (int i = 0; i < pages; i++) {
                    PdfDocument.Page page = doc.startPage(
                            new PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex++).create());
                    Canvas c = page.getCanvas();
                    c.save();
                    c.translate(margin, margin - i * pageContentH);
                    c.clipRect(0, i * pageContentH, contentW, (i + 1) * pageContentH);
                    layout.draw(c);
                    c.restore();
                    doc.finishPage(page);
                }
            }

            String fileName = safeName(title) + "_" + System.currentTimeMillis() + ".pdf";
            OutputStream fos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Lemma");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), cv);
                fos = ctx.getContentResolver().openOutputStream(uri);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                fos = new FileOutputStream(new File(dir, fileName));
            }
            doc.writeTo(fos);
            doc.close();
            fos.close();
            Toast.makeText(ctx, R.string.pdf_saved, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String safeName(String title) {
        String s = title.replaceAll("[^a-zA-Z0-9]", "_");
        return s.isEmpty() ? "Solution" : s;
    }
}
