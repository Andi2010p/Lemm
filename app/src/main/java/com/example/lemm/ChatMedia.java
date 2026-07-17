package com.example.lemm;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The Storage half of chat media. Bytes (photos, voice notes, files) live in Firebase Cloud Storage;
 * only the resulting download URL and a little metadata go into the database via
 * {@link Social#sendMedia}. See {@code docs/storage.rules} for who may read/write.
 *
 * <p>Images are downscaled and JPEG-recompressed before upload so a chat photo costs kilobytes, not
 * megabytes, and are cached in memory on the way back down.
 */
public final class ChatMedia {

    private ChatMedia() {}

    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024; // mirrors storage.rules
    private static final int MAX_IMAGE_DIM = 1600;
    private static final int IMAGE_QUALITY = 80;
    private static final long MAX_IMAGE_FETCH = 10L * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    public interface UploadCallback {
        void onProgress(int percent);
        void onComplete(String url, String name, String mime, long size);
        void onError(String message);
    }

    public interface ImageCallback { void onImage(Bitmap bitmap); }

    /** Uploads a picked/captured {@code uri} and hands back its download URL + metadata on the main thread. */
    public static void upload(Context c, Uri uri, String type, UploadCallback cb) {
        final String me = Social.uid();
        if (me == null) { cb.onError("Not signed in"); return; }
        final Context app = c.getApplicationContext();

        new Thread(() -> {
            try {
                byte[] bytes;
                String mime;
                String name = displayName(app, uri);

                if (Social.TYPE_IMAGE.equals(type)) {
                    bytes = compressImage(app, uri);
                    mime = "image/jpeg";
                    if (name == null || !name.toLowerCase().endsWith(".jpg")) name = "photo.jpg";
                } else {
                    bytes = readAll(app, uri);
                    mime = app.getContentResolver().getType(uri);
                    if (mime == null) mime = Social.TYPE_VOICE.equals(type) ? "audio/m4a" : "application/octet-stream";
                    if (name == null) name = Social.TYPE_VOICE.equals(type) ? "voice.m4a" : "file";
                }

                if (bytes == null) { main(() -> cb.onError("Could not read the selection")); return; }
                if (bytes.length > MAX_UPLOAD_BYTES) { main(() -> cb.onError("Too large — 25 MB max")); return; }

                final String fName = name, fMime = mime;
                final long size = bytes.length;
                String ext = extFor(mime, name);
                StorageReference ref = FirebaseStorage.getInstance().getReference()
                        .child("chat_media").child(me)
                        .child(System.nanoTime() + (ext == null ? "" : "." + ext));
                StorageMetadata meta = new StorageMetadata.Builder().setContentType(mime).build();

                UploadTask task = ref.putBytes(bytes, meta);
                task.addOnProgressListener(s -> {
                    long total = s.getTotalByteCount();
                    if (total > 0) cb.onProgress((int) (100 * s.getBytesTransferred() / total));
                });
                task.continueWithTask(t -> {
                    if (!t.isSuccessful() && t.getException() != null) throw t.getException();
                    return ref.getDownloadUrl();
                }).addOnSuccessListener(url -> cb.onComplete(url.toString(), fName, fMime, size))
                  .addOnFailureListener(e -> cb.onError(friendly(e)));
            } catch (Throwable e) {
                main(() -> cb.onError(friendly(e)));
            }
        }).start();
    }

    /** Loads an image message's bitmap (cached). Silently no-ops on failure, leaving any placeholder. */
    public static void loadImage(String url, ImageCallback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) { cb.onImage(cached); return; }
        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(url);
            ref.getBytes(MAX_IMAGE_FETCH).addOnSuccessListener(bytes -> {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) { CACHE.put(url, bmp); cb.onImage(bmp); }
            });
        } catch (Exception ignored) { /* bad URL / storage unavailable — leave the placeholder */ }
    }

    /** Human-readable size, e.g. "2.4 MB". */
    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return (i == 0 ? (long) v + " " + u[i] : String.format(java.util.Locale.US, "%.1f %s", v, u[i]));
    }

    // ---------- internals ----------

    private static void main(Runnable r) { MAIN.post(r); }

    private static byte[] compressImage(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int sample = 1, longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_IMAGE_DIM) sample *= 2;

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(is, null, opt);
        }
        if (bmp == null) return null;
        bmp = applyExifRotation(c, uri, bmp);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, bos);
        return bos.toByteArray();
    }

    /** Camera photos are often stored sideways with an EXIF orientation flag — honour it. */
    private static Bitmap applyExifRotation(Context c, Uri uri, Bitmap bmp) {
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            if (is == null) return bmp;
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
        } catch (Exception ignored) {}
        return bmp;
    }

    private static byte[] readAll(Context c, Uri uri) throws Exception {
        try (InputStream is = c.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cur.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extFor(String mime, String name) {
        if (name != null) {
            int d = name.lastIndexOf('.');
            if (d >= 0 && d < name.length() - 1) return name.substring(d + 1);
        }
        if (mime != null) {
            String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (e != null) return e;
        }
        return null;
    }

    private static String friendly(Throwable e) {
        String m = (e == null) ? null : e.getMessage();
        if (m == null) return "Upload failed. Check your connection and that Storage is set up.";
        return m;
    }
}
