package com.example.lemm.io;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a Lemma 3D scene to neutral mesh formats (ASCII STL and Wavefront OBJ) so it can be
 * opened in SolidWorks (as a mesh/graphics body), Blender, Fusion, Unity, three.js, AR viewers, etc.
 *
 * <p>The model is handed in as a plain-data {@link ModelSnapshot} (built by
 * {@code GeometryCanvas3D.snapshot()}), so this class never touches the Android View layer or the
 * private geometry classes. The file is written to the app's external {@code Cad/} folder and either
 * shared via the system chooser (FileProvider) or copied into {@code Documents/Lemma} (Files app).
 *
 * <p>Honesty note surfaced in the UI: these are <b>meshes</b> — they carry final triangulated
 * geometry, not an editable parametric feature tree. Curved primitives are faceted. This is a
 * deliberate, achievable scope; native editable SolidWorks parts (.sldprt) are not producible by any
 * third party. STEP (a "dumb" but exact solid) is a later stage.
 */
public final class Cad3DExporter {

    /** Output mesh format. */
    public enum Format {
        STL("stl", "application/octet-stream"),
        OBJ("obj", "application/octet-stream");
        final String ext, mime;
        Format(String ext, String mime) { this.ext = ext; this.mime = mime; }
    }

    // ---------- public plain-data snapshot (populated inside GeometryCanvas3D.snapshot()) ----------

    /** A View-free copy of the 3D model so the exporter stays pure Java. */
    public static final class ModelSnapshot {
        /** point label (lower-cased) -> {x, y, z}. */
        public final Map<String, float[]> pointsByLabel = new HashMap<>();
        /** each entry = one face as an ordered list of point labels. */
        public final List<String[]> faces = new ArrayList<>();
        /** flattened prism: [baseY, height, n, x0,z0, x1,z1, ...]. */
        public final List<float[]> prisms = new ArrayList<>();
        /** {cx, cy, cz, r, h}. */
        public final List<float[]> cylinders = new ArrayList<>();
        /** {cx, cy, cz, r, h}. */
        public final List<float[]> cones = new ArrayList<>();
        /** {x, y, z, r}. */
        public final List<float[]> spheres = new ArrayList<>();
        /** {cx, cy, cz, r}. */
        public final List<float[]> circles = new ArrayList<>();

        /** True when there is at least one triangle-able element to export. */
        public boolean isEmpty() {
            return faces.isEmpty() && prisms.isEmpty() && cylinders.isEmpty()
                    && cones.isEmpty() && spheres.isEmpty() && circles.isEmpty();
        }
    }

    // ---------- welded triangle mesh ----------

    private static final class Mesh {
        final List<float[]> verts = new ArrayList<>(); // {x, y, z}
        final List<int[]> tris = new ArrayList<>();    // {i, j, k}, 0-based
        private final Map<String, Integer> weld = new HashMap<>();
        private static final float EPS = 1e-3f;

        int vertex(float x, float y, float z) {
            // Weld coincident vertices by their quantized coordinates. The key must encode the three
            // cell indices exactly (a lossy hash would merge distinct points that happen to collide).
            long kx = Math.round(x / EPS), ky = Math.round(y / EPS), kz = Math.round(z / EPS);
            String key = kx + ":" + ky + ":" + kz;
            Integer got = weld.get(key);
            if (got != null) return got;
            int id = verts.size();
            verts.add(new float[]{x, y, z});
            weld.put(key, id);
            return id;
        }

        void tri(int a, int b, int c) {
            if (a != b && b != c && a != c) tris.add(new int[]{a, b, c});
        }

        /** Fan-triangulate a convex face (the app's extruded faces are convex). */
        void convexFace(List<float[]> poly) {
            if (poly.size() < 3) return;
            int i0 = vertex(poly.get(0)[0], poly.get(0)[1], poly.get(0)[2]);
            for (int i = 1; i + 1 < poly.size(); i++) {
                int ia = vertex(poly.get(i)[0], poly.get(i)[1], poly.get(i)[2]);
                int ib = vertex(poly.get(i + 1)[0], poly.get(i + 1)[1], poly.get(i + 1)[2]);
                tri(i0, ia, ib);
            }
        }
    }

    // ---------- ModelSnapshot -> Mesh (Y-up preserved, matching the on-screen model) ----------

    private static Mesh build(ModelSnapshot m) {
        Mesh mesh = new Mesh();

        // Faces (this covers every extruded solid: extrude stores its faces as Plane3D).
        for (String[] labels : m.faces) {
            List<float[]> poly = new ArrayList<>();
            for (String l : labels) {
                float[] p = (l == null) ? null : m.pointsByLabel.get(l.toLowerCase(Locale.US));
                if (p != null) poly.add(p);
            }
            mesh.convexFace(poly);
        }

        // Prisms: an XZ profile at baseY extruded +height along +Y (mirrors extrudeProfileToSolid).
        for (float[] pr : m.prisms) {
            if (pr.length < 3) continue;
            float baseY = pr[0], h = pr[1];
            int n = (int) pr[2];
            if (n < 3 || pr.length < 3 + 2 * n) continue;
            List<float[]> bot = new ArrayList<>(), top = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                float x = pr[3 + 2 * i], z = pr[4 + 2 * i];
                bot.add(new float[]{x, baseY, z});
                top.add(new float[]{x, baseY + h, z});
            }
            mesh.convexFace(bot);
            List<float[]> topR = new ArrayList<>(top);
            Collections.reverse(topR);
            mesh.convexFace(topR);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                mesh.convexFace(Arrays.asList(bot.get(i), bot.get(j), top.get(j), top.get(i)));
            }
        }

        final int SEG = 24;
        for (float[] c : m.cylinders) if (c.length >= 5) cylinder(mesh, c[0], c[1], c[2], c[3], c[4], SEG);
        for (float[] c : m.cones)     if (c.length >= 5) cone(mesh, c[0], c[1], c[2], c[3], c[4], SEG);
        for (float[] s : m.spheres)   if (s.length >= 4) sphere(mesh, s[0], s[1], s[2], s[3], 16, 24);
        for (float[] d : m.circles)   if (d.length >= 4) disc(mesh, d[0], d[1], d[2], d[3], SEG);

        return mesh;
    }

    private static float[] ring(float cx, float cz, float r, float y, int seg, int i) {
        double a = 2 * Math.PI * i / seg;
        return new float[]{cx + r * (float) Math.cos(a), y, cz + r * (float) Math.sin(a)};
    }

    private static void cylinder(Mesh mesh, float cx, float cy, float cz, float r, float h, int seg) {
        List<float[]> bot = new ArrayList<>(), top = new ArrayList<>();
        for (int i = 0; i < seg; i++) { bot.add(ring(cx, cz, r, cy, seg, i)); top.add(ring(cx, cz, r, cy + h, seg, i)); }
        mesh.convexFace(bot);
        List<float[]> topR = new ArrayList<>(top);
        Collections.reverse(topR);
        mesh.convexFace(topR);
        for (int i = 0; i < seg; i++) {
            int j = (i + 1) % seg;
            mesh.convexFace(Arrays.asList(bot.get(i), bot.get(j), top.get(j), top.get(i)));
        }
    }

    private static void cone(Mesh mesh, float cx, float cy, float cz, float r, float h, int seg) {
        List<float[]> bot = new ArrayList<>();
        for (int i = 0; i < seg; i++) bot.add(ring(cx, cz, r, cy, seg, i));
        mesh.convexFace(bot);
        int apex = mesh.vertex(cx, cy + h, cz);
        for (int i = 0; i < seg; i++) {
            int j = (i + 1) % seg;
            int a = mesh.vertex(bot.get(i)[0], bot.get(i)[1], bot.get(i)[2]);
            int b = mesh.vertex(bot.get(j)[0], bot.get(j)[1], bot.get(j)[2]);
            mesh.tri(a, b, apex);
        }
    }

    private static void disc(Mesh mesh, float cx, float cy, float cz, float r, int seg) {
        List<float[]> ring = new ArrayList<>();
        for (int i = 0; i < seg; i++) ring.add(ring(cx, cz, r, cy, seg, i));
        mesh.convexFace(ring);
    }

    private static void sphere(Mesh mesh, float cx, float cy, float cz, float r, int stacks, int slices) {
        float[][] grid = new float[stacks + 1][(slices + 1) * 3];
        for (int s = 0; s <= stacks; s++) {
            double phi = Math.PI * s / stacks;
            for (int t = 0; t <= slices; t++) {
                double th = 2 * Math.PI * t / slices;
                grid[s][t * 3]     = cx + r * (float) (Math.sin(phi) * Math.cos(th));
                grid[s][t * 3 + 1] = cy + r * (float) Math.cos(phi);
                grid[s][t * 3 + 2] = cz + r * (float) (Math.sin(phi) * Math.sin(th));
            }
        }
        for (int s = 0; s < stacks; s++) {
            for (int t = 0; t < slices; t++) {
                float[] a = {grid[s][t * 3], grid[s][t * 3 + 1], grid[s][t * 3 + 2]};
                float[] b = {grid[s][(t + 1) * 3], grid[s][(t + 1) * 3 + 1], grid[s][(t + 1) * 3 + 2]};
                float[] c = {grid[s + 1][(t + 1) * 3], grid[s + 1][(t + 1) * 3 + 1], grid[s + 1][(t + 1) * 3 + 2]};
                float[] d = {grid[s + 1][t * 3], grid[s + 1][t * 3 + 1], grid[s + 1][t * 3 + 2]};
                mesh.convexFace(Arrays.asList(a, b, c, d));
            }
        }
    }

    // ---------- format writers ----------

    private static void writeStl(Mesh m, Writer w) throws Exception {
        w.write("solid Lemma\n");
        for (int[] t : m.tris) {
            float[] p0 = m.verts.get(t[0]), p1 = m.verts.get(t[1]), p2 = m.verts.get(t[2]);
            float[] n = normal(p0, p1, p2);
            w.write(String.format(Locale.US, "  facet normal %e %e %e%n", n[0], n[1], n[2]));
            w.write("    outer loop\n");
            w.write(String.format(Locale.US, "      vertex %e %e %e%n", p0[0], p0[1], p0[2]));
            w.write(String.format(Locale.US, "      vertex %e %e %e%n", p1[0], p1[1], p1[2]));
            w.write(String.format(Locale.US, "      vertex %e %e %e%n", p2[0], p2[1], p2[2]));
            w.write("    endloop\n  endfacet\n");
        }
        w.write("endsolid Lemma\n");
        w.flush();
    }

    private static void writeObj(Mesh m, Writer w) throws Exception {
        w.write("# Lemma OBJ export\n");
        for (float[] v : m.verts) w.write(String.format(Locale.US, "v %f %f %f%n", v[0], v[1], v[2]));
        for (int[] t : m.tris) w.write(String.format(Locale.US, "f %d %d %d%n", t[0] + 1, t[1] + 1, t[2] + 1)); // OBJ is 1-based
        w.flush();
    }

    private static float[] normal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9f) len = 1f;
        return new float[]{nx / len, ny / len, nz / len};
    }

    private static void emit(Mesh mesh, Format fmt, Writer w) throws Exception {
        if (fmt == Format.OBJ) writeObj(mesh, w); else writeStl(mesh, w);
    }

    // ---------- public entry points ----------

    /**
     * Writes the model to the app's {@code Cad/} folder and opens the system share chooser.
     * Returns true if the file was written and the chooser launched.
     */
    public static boolean share(Context ctx, ModelSnapshot snap, Format fmt, String baseName) {
        if (snap == null || snap.isEmpty()) return false;
        try {
            Mesh mesh = build(snap);
            if (mesh.tris.isEmpty()) return false;
            File dir = new File(ctx.getExternalFilesDir(null), "Cad");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdirs failed");
            File out = new File(dir, safe(baseName) + "." + fmt.ext);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out))) { emit(mesh, fmt, w); }

            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(fmt.mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(send, out.getName()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Writes the model into {@code Documents/Lemma} (visible in the Files app), mirroring the
     * MediaStore pattern in SolutionExporter. Returns the display name on success, else null.
     */
    public static String saveToDocuments(Context ctx, ModelSnapshot snap, Format fmt, String baseName) {
        if (snap == null || snap.isEmpty()) return null;
        Mesh mesh = build(snap);
        if (mesh.tris.isEmpty()) return null;
        String fileName = safe(baseName) + "_" + System.currentTimeMillis() + "." + fmt.ext;
        try {
            OutputStream os;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, fmt.mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Lemma");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), cv);
                if (uri == null) return null;
                os = ctx.getContentResolver().openOutputStream(uri);
            } else {
                // Pre-Q: no WRITE_EXTERNAL_STORAGE in the manifest — fall back to app-specific storage.
                File dir = new File(ctx.getExternalFilesDir(null), "Cad");
                if (!dir.exists()) dir.mkdirs();
                os = new FileOutputStream(new File(dir, fileName));
            }
            if (os == null) return null;
            try (Writer w = new OutputStreamWriter(os)) { emit(mesh, fmt, w); }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String title) {
        if (title == null) return "Lemma";
        String s = title.replaceAll("[^a-zA-Z0-9]", "_");
        return s.isEmpty() ? "Lemma" : s;
    }

    private Cad3DExporter() {}
}
