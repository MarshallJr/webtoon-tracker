package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Vignette de chaque chapitre lu (comme la liste d'épisodes de WEBTOON) : un extrait de la page
 * que tu lisais, capturé automatiquement. ~20 Ko par chapitre, rangé dans files/chapters/&lt;série&gt;/.
 * Sans capture (appli qui bloque les captures, lu avant la v4.3…), on affiche la couverture recadrée.
 */
public final class ChapterThumbs {

    static final int W = 330, H = 236;   // ≈ 104 × 74 dp à l'écran

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private ChapterThumbs() {
    }

    private static String seriesKey(String title) {
        String k = Titles.key(title);
        return k.isEmpty() ? Integer.toHexString(title.toLowerCase(Locale.ROOT).hashCode()) : k;
    }

    private static File dir(Context ctx, String title) {
        return new File(new File(ctx.getFilesDir(), "chapters"), seriesKey(title));
    }

    private static File file(Context ctx, String title, double chapter) {
        return new File(dir(ctx, title), Detector.formatChapter(chapter) + ".jpg");
    }

    public static boolean has(Context ctx, String title, double chapter) {
        return file(ctx, title, chapter).exists();
    }

    /** Vignette (cache mémoire), ou null. À appeler hors du fil principal. */
    public static Bitmap get(Context ctx, String title, double chapter) {
        File f = file(ctx, title, chapter);
        String k = f.getAbsolutePath();
        Bitmap b = CACHE.get(k);
        if (b != null) return b;
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(k, o);
        if (b != null) CACHE.put(k, b);
        return b;
    }

    /**
     * Capture d'écran → bande centrale (la case lue, sans barres du haut et du bas), au format vignette.
     * @return false si l'image est inutilisable (écran noir : appli qui protège ses pages, etc.)
     */
    public static boolean saveFromScreenshot(Context ctx, String title, double chapter, Bitmap screen) {
        try {
            int w = screen.getWidth(), h = screen.getHeight();
            int bandH = Math.min(h, Math.round(w * H / (float) W));
            int top = Math.max(0, Math.min(h - bandH, Math.round(h * 0.46f - bandH / 2f)));
            Bitmap band = Bitmap.createBitmap(screen, 0, top, w, bandH);
            Bitmap out = Bitmap.createScaledBitmap(band, W, H, true);
            if (isBlank(out)) return false;
            File d = dir(ctx, title);
            if (!d.exists() && !d.mkdirs()) return false;
            File f = file(ctx, title, chapter);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 78, fos);
            }
            CACHE.remove(f.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Presque uniforme (noir d'une page protégée, blanc d'un chargement) → on ne garde pas. */
    private static boolean isBlank(Bitmap b) {
        long sum = 0, sum2 = 0;
        int n = 0;
        for (int y = 4; y < b.getHeight(); y += 12) {
            for (int x = 4; x < b.getWidth(); x += 12) {
                int c = b.getPixel(x, y);
                int l = ((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF);
                l /= 10;
                sum += l;
                sum2 += (long) l * l;
                n++;
            }
        }
        if (n == 0) return true;
        double mean = sum / (double) n, var = sum2 / (double) n - mean * mean;
        return var < 60;   // écart-type < ~8 niveaux de gris
    }

    /** Couverture recadrée au format vignette, assombrie en bas (pour les chapitres sans capture). */
    public static Bitmap coverStrip(Context ctx, String title) {
        Bitmap cover = Covers.get(ctx, title);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (cover != null) {
            int cw = cover.getWidth();
            int ch = Math.round(cw * H / (float) W);
            int top = Math.max(0, Math.min(cover.getHeight() - ch, Math.round(cover.getHeight() * 0.18f)));
            c.drawBitmap(cover, new Rect(0, top, cw, Math.min(cover.getHeight(), top + ch)), new Rect(0, 0, W, H),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            c.drawColor(SeriesAdapter.colorFor(title));
        }
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, H * 0.35f, 0, H, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, shade);
        return out;
    }

    /** Fusion / renommage de série : les vignettes suivent. */
    public static void move(Context ctx, String from, String to) {
        try {
            File a = dir(ctx, from), b = dir(ctx, to);
            if (!a.exists() || a.equals(b)) return;
            if (!b.exists()) b.mkdirs();
            File[] files = a.listFiles();
            if (files != null) {
                for (File f : files) {
                    File dest = new File(b, f.getName());
                    if (!dest.exists()) f.renameTo(dest);
                    else f.delete();
                }
            }
            a.delete();
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    public static void deleteAll(Context ctx, String title) {
        try {
            File d = dir(ctx, title);
            File[] files = d.listFiles();
            if (files != null) for (File f : files) f.delete();
            d.delete();
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    public static void delete(Context ctx, String title, double chapter) {
        File f = file(ctx, title, chapter);
        CACHE.remove(f.getAbsolutePath());
        f.delete();
    }
}
