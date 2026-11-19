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

    /** Format portrait 2:3, comme une vraie couverture (lisible en grand dans la fiche et le widget). */
    private static final int W = 360, H = 540;
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

    /** Recadre en portrait 2:3, réduit à 360×540 et enregistre. @param bias 0 = haut, 0.5 = centre */
    public static void save(Context ctx, String title, Bitmap src, float bias) throws Exception {
        int w = src.getWidth(), h = src.getHeight();
        int cw, ch;
        if (w * 3 > h * 2) {          // plus large que 2:3 → on coupe les côtés
            ch = h;
            cw = Math.round(h * 2f / 3f);
        } else {                      // plus haut que 2:3 → on coupe en haut / en bas
            cw = w;
            ch = Math.round(w * 3f / 2f);
        }
        int x = (w - cw) / 2;
        int y = Math.round((h - ch) * bias);
        Bitmap crop = Bitmap.createBitmap(src, x, Math.max(0, y), cw, Math.min(ch, h));
        Bitmap small = Bitmap.createScaledBitmap(crop, W, H, true);
        try (FileOutputStream out = new FileOutputStream(file(ctx, title))) {
            small.compress(Bitmap.CompressFormat.JPEG, 88, out);
        }
        CACHE.put(key(title), small);
    }

    public static void save(Context ctx, String title, Bitmap src) throws Exception {
        save(ctx, title, src, 0.5f);
    }

    /** Affiche officielle (déjà en portrait) : on garde le haut si elle est plus haute que 2:3. */
    public static void savePoster(Context ctx, String title, Bitmap poster) throws Exception {
        save(ctx, title, poster, 0.15f);
    }

    /** Capture d'écran → la zone centrale (la case lue), sans barres du haut et du bas. */
    public static void saveFromScreenshot(Context ctx, String title, Bitmap screen) throws Exception {
        int w = screen.getWidth(), h = screen.getHeight();
        int top = (int) (h * 0.15f), height = (int) (h * 0.70f);
        save(ctx, title, Bitmap.createBitmap(screen, 0, top, w, height), 0.5f);
    }

    /** Ancienne miniature carrée (avant le format portrait) ? */
    public static boolean isSquare(Context ctx, String title) {
        File f = file(ctx, title);
        if (!f.exists()) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outWidth == o.outHeight;
    }

    /** Image choisie par toi dans la galerie : jamais remplacée automatiquement. */
    public static void markUserPick(Context ctx, String title) {
        try {
            new File(file(ctx, title).getAbsolutePath() + ".user").createNewFile();
        } catch (Exception ignored) {
        }
        setCustom(ctx, title, true);
    }

    public static boolean isUserPick(Context ctx, String title) {
        return new File(file(ctx, title).getAbsolutePath() + ".user").exists();
    }

    public static void saveFromUri(Context ctx, String title, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= W) sample *= 2;
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
        new File(file(ctx, title).getAbsolutePath() + ".user").delete();
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

    /** Couverture du widget : l'image arrondie, ou une vignette colorée avec l'initiale. */
    public static Bitmap widgetCover(Context ctx, String title, int w, int h, float radiusDp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        float r = radiusDp * density;
        Bitmap src = get(ctx, title);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF rect = new RectF(0, 0, w, h);
        if (src != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
            p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, r, r, p);
        } else {
            p.setColor(SeriesAdapter.colorFor(title));
            c.drawRoundRect(rect, r, r, p);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(h * 0.38f);
            t.setFakeBoldText(true);
            c.drawText(SeriesAdapter.initialFor(title), w / 2f, h / 2f - (t.descent() + t.ascent()) / 2f, t);
        }
        return out;
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
