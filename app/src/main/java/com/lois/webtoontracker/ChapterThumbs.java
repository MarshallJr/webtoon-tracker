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
    /** En dessous, on préfère le fond flouté à une image moyenne. */
    static final float MIN_SCORE = 45f;

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
     * Capture d'écran → vignette du chapitre, cadrée par {@link ThumbPicker} : une seule scène,
     * sans texte ni bulle, sans grands vides, la plus colorée / détaillée (visages favorisés).
     * Pendant la lecture, plusieurs captures sont analysées ; seule la meilleure est gardée.
     *
     * @param panel colonne de la case au centre (bornes), ou null si inconnue
     * @param mid   capture prise pendant la lecture (sinon : au tout début, notée plus bas)
     * @return false si rien de présentable, ou moins bien que la vignette déjà gardée
     */
    public static boolean saveFromScreenshot(Context ctx, String title, double chapter, Bitmap screen, Rect panel, boolean mid) {
        try {
            int w = screen.getWidth(), h = screen.getHeight();
            int left = 0, right = w;
            if (panel != null && panel.right - panel.left >= w * 0.5f) {
                left = Math.max(0, panel.left);
                right = Math.min(w, panel.right);
            }
            int top = Math.round(h * 0.06f), bottom = Math.round(h * 0.94f);   // sans barres du haut / du bas
            int aw = right - left, ah = bottom - top;
            int sw = ThumbPicker.SW, sh = Math.round(ah * sw / (float) aw);
            Bitmap small = Bitmap.createScaledBitmap(Bitmap.createBitmap(screen, left, top, aw, ah), sw, sh, true);
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            ThumbPicker.Pick pick = ThumbPicker.pick(px, sw, sh, aw / (float) sw, left, top);
            if (pick.score < MIN_SCORE || pick.width <= 0) return false;   // rien d'assez beau sur cet écran
            int cl = Math.max(0, pick.left), ct = Math.max(0, pick.top);
            int cw = Math.min(pick.width, w - cl), ch = Math.min(pick.height, h - ct);
            Bitmap out = Bitmap.createScaledBitmap(Bitmap.createBitmap(screen, cl, ct, cw, ch), W, H, true);
            if (looksBad(out)) return false;

            File f = file(ctx, title, chapter);
            android.content.SharedPreferences scores = scores(ctx);
            String sk = f.getAbsolutePath();
            float score = mid ? pick.score : pick.score * 0.7f;   // le tout début (page de titre…) compte moins
            if (f.exists() && scores.getFloat(sk, 0f) >= score) return false;
            File d = dir(ctx, title);
            if (!d.exists() && !d.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            scores.edit().putFloat(sk, score).apply();
            CACHE.remove(sk);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Notes des vignettes (v3 : sélecteur « personnage d'abord »). Les vignettes officielles
     * notées avant sont reprises ; les anciennes captures repartent de zéro (donc remplacées).
     */
    static android.content.SharedPreferences scores(Context ctx) {
        android.content.SharedPreferences cur = ctx.getSharedPreferences("thumb_scores_v3", Context.MODE_PRIVATE);
        if (!cur.getBoolean("_migrated", false)) {
            android.content.SharedPreferences.Editor ed = cur.edit();
            for (String old : new String[]{"thumb_scores", "thumb_scores_v2"}) {
                for (java.util.Map.Entry<String, ?> e : ctx.getSharedPreferences(old, Context.MODE_PRIVATE).getAll().entrySet()) {
                    if (e.getValue() instanceof Float && (Float) e.getValue() >= WebtoonThumbs.OFFICIAL) ed.putFloat(e.getKey(), (Float) e.getValue());
                }
            }
            ed.putBoolean("_migrated", true).apply();
        }
        return cur;
    }

    /** Vignette officielle (WEBTOON) : recadrée au format, jamais remplacée par une capture. */
    public static boolean saveOfficial(Context ctx, String title, double chapter, Bitmap src) {
        try {
            float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            int cw = Math.min(src.getWidth(), Math.round(W / scale)), ch = Math.min(src.getHeight(), Math.round(H / scale));
            int cx = (src.getWidth() - cw) / 2, cy = Math.max(0, (src.getHeight() - ch) / 3);
            Bitmap out = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, cx, cy, cw, ch), W, H, true);
            File d = dir(ctx, title);
            if (!d.exists() && !d.mkdirs()) return false;
            File f = file(ctx, title, chapter);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            }
            scores(ctx).edit().putFloat(f.getAbsolutePath(), WebtoonThumbs.OFFICIAL).apply();
            CACHE.remove(f.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static float scoreOf(Context ctx, String title, double chapter) {
        File f = file(ctx, title, chapter);
        if (!f.exists()) return -1f;
        return scores(ctx).getFloat(f.getAbsolutePath(), 0f);
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
