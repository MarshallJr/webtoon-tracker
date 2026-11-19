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
    public static boolean saveFromScreenshot(Context ctx, String title, double chapter, Bitmap screen, Rect panel) {
        try {
            Bitmap out = crop(screen, panel);
            if (out == null || looksBad(out)) return false;
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

    /**
     * Bande de la case affichée au centre (sans barres du site, sans marges), au format vignette.
     * @param panel bornes de la case au centre de l'écran, ou null si inconnues (bande centrale de l'écran)
     */
    static Bitmap crop(Bitmap screen, Rect panel) {
        int w = screen.getWidth(), h = screen.getHeight();
        Rect area = new Rect(0, 0, w, h);
        if (panel != null && !area.intersect(panel)) return null;
        if (panel == null) area.set(0, Math.round(h * 0.12f), w, Math.round(h * 0.88f));
        int aw = area.width();
        int bandH = Math.min(area.height(), Math.round(aw * H / (float) W));
        if (aw < w * 0.4f || bandH < h * 0.08f) return null;
        // bande centrée sur le centre de l'écran, gardée dans la case
        int top = Math.round(h * 0.5f - bandH / 2f);
        top = Math.max(area.top, Math.min(area.bottom - bandH, top));
        int bw = Math.min(aw, Math.round(bandH * W / (float) H));
        int left = area.left + (aw - bw) / 2;
        Bitmap band = Bitmap.createBitmap(screen, left, top, bw, bandH);
        return Bitmap.createScaledBitmap(band, W, H, true);
    }

    /**
     * Image inutilisable : presque uniforme (page protégée, chargement) ou presque toute noire
     * avec quelques chiffres (écran de veille « Always On », écran éteint).
     */
    public static boolean looksBad(Bitmap b) {
        long sum = 0, sum2 = 0;
        int n = 0, dark = 0;
        int step = Math.max(4, Math.min(b.getWidth(), b.getHeight()) / 40);
        for (int y = step / 2; y < b.getHeight(); y += step) {
            for (int x = step / 2; x < b.getWidth(); x += step) {
                int c = b.getPixel(x, y);
                int l = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                sum += l;
                sum2 += (long) l * l;
                if (l < 26) dark++;
                n++;
            }
        }
        if (n == 0) return true;
        double mean = sum / (double) n, var = sum2 / (double) n - mean * mean;
        return var < 60 || dark > n * 0.80;
    }

    /** Ancien fichier inutilisable ? (décodé en petit pour aller vite) */
    static boolean fileLooksBad(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return b != null && looksBad(b);
    }

    /** Ménage : supprime les vignettes prises sur l'écran de veille / écran noir (versions ≤ 4.4). */
    public static int cleanupBad(Context ctx) {
        int removed = 0;
        File root = new File(ctx.getFilesDir(), "chapters");
        File[] series = root.listFiles();
        if (series == null) return 0;
        for (File d : series) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                try {
                    if (f.getName().endsWith(".jpg") && fileLooksBad(f) && f.delete()) removed++;
                } catch (Throwable ignored) {
                }
            }
        }
        CACHE.evictAll();
        return removed;
    }

    /**
     * Fond des chapitres sans capture : la couverture très floutée et assombrie (une ambiance
     * aux couleurs de la série, pas la même image répétée), sur laquelle on écrit le numéro.
     */
    public static Bitmap placeholder(Context ctx, String title) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Bitmap cover = Covers.get(ctx, title);
        if (cover != null && !looksBad(cover)) {
            int cw = cover.getWidth();
            int ch = Math.round(cw * H / (float) W);
            int top = Math.max(0, Math.min(cover.getHeight() - ch, Math.round(cover.getHeight() * 0.25f)));
            Bitmap part = Bitmap.createBitmap(cover, 0, top, cw, Math.min(ch, cover.getHeight() - top));
            Bitmap tiny = Bitmap.createScaledBitmap(part, 10, 7, true);          // flou : on réduit beaucoup…
            Bitmap mid = Bitmap.createScaledBitmap(tiny, 40, 28, true);          // …puis on agrandit en deux temps
            c.drawBitmap(mid, null, new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            int base = SeriesAdapter.colorFor(title);
            Paint g = new Paint();
            g.setShader(new LinearGradient(0, 0, W, H, base, darker(base), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, g);
        }
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 0, 0, H, 0x59000000, 0xA6000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, shade);
        return out;
    }

    private static int darker(int c) {
        int r = ((c >> 16) & 0xFF) * 55 / 100, g = ((c >> 8) & 0xFF) * 55 / 100, b = (c & 0xFF) * 55 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
