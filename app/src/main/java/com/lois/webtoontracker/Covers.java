package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Miniatures des séries : une petite image JPEG (320 px) par série dans le stockage de l'appli.
 * Vient d'une capture de la case lue (automatique) ou d'une image choisie par l'utilisateur.
 */
public final class Covers {

    private static final int SIZE = 320;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Covers() {}

    private static String key(String title) {
        String k = Titles.key(title);
        return k.isEmpty() ? Integer.toHexString(title.toLowerCase(Locale.ROOT).hashCode()) : k;
    }

    private static File file(Context ctx, String title) {
        File dir = new File(ctx.getFilesDir(), "covers");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key(title) + ".jpg");
    }

    public static boolean has(Context ctx, String title) {
        return file(ctx, title).exists();
    }

    public static Bitmap get(Context ctx, String title) {
        String k = key(title);
        Bitmap b = CACHE.get(k);
        if (b != null) return b;
        File f = file(ctx, title);
        if (!f.exists()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) CACHE.put(k, b);
        return b;
    }

    /** Recadre en carré, réduit à 320 px et enregistre. */
    public static void save(Context ctx, String title, Bitmap src) throws Exception {
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap small = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true);
        try (FileOutputStream out = new FileOutputStream(file(ctx, title))) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        }
        CACHE.put(key(title), small);
    }

    /**
     * Capture d'écran → on garde la zone centrale (là où est la case du webtoon),
     * sans la barre d'état, la barre d'adresse ni les boutons du lecteur.
     */
    public static void saveFromScreenshot(Context ctx, String title, Bitmap screen) throws Exception {
        int w = screen.getWidth(), h = screen.getHeight();
        int side = Math.min(w, (int) (h * 0.5));
        int top = (h - side) / 2;
        Bitmap center = Bitmap.createBitmap(screen, (w - side) / 2, top, side, side);
        save(ctx, title, center);
    }

    public static void saveFromUri(Context ctx, String title, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= SIZE) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o2);
        }
        if (b == null) throw new IllegalStateException("Image illisible");
        save(ctx, title, b);
    }

    public static void delete(Context ctx, String title) {
        file(ctx, title).delete();
        lockFile(ctx, title).delete();
        CACHE.remove(key(title));
    }

    /** Une image choisie à la main n'est jamais remplacée par une capture automatique. */
    private static File lockFile(Context ctx, String title) {
        return new File(file(ctx, title).getAbsolutePath() + ".custom");
    }

    public static boolean isCustom(Context ctx, String title) {
        return lockFile(ctx, title).exists();
    }

    public static void setCustom(Context ctx, String title, boolean custom) {
        File f = lockFile(ctx, title);
        try {
            if (custom) f.createNewFile();
            else f.delete();
        } catch (Exception ignored) {
        }
    }

    /** Fusion de séries : garde la miniature de la cible, sinon reprend celle de la source. */
    public static void merge(Context ctx, String from, String into) {
        File a = file(ctx, from), b = file(ctx, into);
        if (a.equals(b)) return;
        if (!b.exists() && a.exists()) a.renameTo(b);
        else a.delete();
        CACHE.remove(key(from));
        CACHE.remove(key(into));
    }

    /** Version aux coins arrondis (pour le widget, qui ne sait pas arrondir une image). */
    public static Bitmap rounded(Bitmap src, float radiusRatio) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = src.getWidth() * radiusRatio;
        c.drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), r, r, p);
        return out;
    }
}
