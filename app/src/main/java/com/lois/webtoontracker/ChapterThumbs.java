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
     * Capture d'écran → vignette du chapitre.
     *
     * On cadre le DÉBUT d'une scène : juste sous une marge unie (l'espace blanc ou noir entre deux
     * cases), plutôt qu'une tranche prise au hasard au milieu d'une bulle. Pendant la lecture, on
     * essaie plusieurs fois au milieu du chapitre et on garde la plus belle image (couleurs, contraste).
     *
     * @param panel colonne de la case au centre (bornes), ou null si inconnue
     * @param mid   capture prise au milieu du chapitre (sinon : au tout début, provisoire)
     * @return false si l'image est inutilisable ou moins bonne que celle déjà gardée
     */
    public static boolean saveFromScreenshot(Context ctx, String title, double chapter, Bitmap screen, Rect panel, boolean mid) {
        try {
            boolean[] scene = new boolean[1];
            Bitmap out = sectionCrop(screen, panel, scene);
            if (out == null) out = crop(screen, panel);
            if (out == null || looksBad(out)) return false;
            File f = file(ctx, title, chapter);
            android.content.SharedPreferences scores = ctx.getSharedPreferences("thumb_scores", Context.MODE_PRIVATE);
            String sk = f.getAbsolutePath();
            float score = mid ? score(out) * (scene[0] ? 1.2f : 1f) : 0f;   // une image du début est provisoire
            if (f.exists() && scores.getFloat(sk, 0f) >= score && (mid || scores.getFloat(sk, 0f) > 0f)) return false;
            File d = dir(ctx, title);
            if (!d.exists() && !d.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            }
            scores.edit().putFloat(sk, score).apply();
            CACHE.remove(sk);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Début d'une scène : on cherche, dans la moitié haute de l'écran, une marge unie (≥ ~1 % de
     * l'écran) suivie d'image ; la vignette commence juste en dessous. Null si rien de net.
     */
    static Bitmap sectionCrop(Bitmap screen, Rect panel, boolean[] found) {
        int w = screen.getWidth(), h = screen.getHeight();
        int left = 0, right = w;
        if (panel != null) {
            left = Math.max(0, panel.left);
            right = Math.min(w, panel.right);
        }
        int aw = right - left;
        if (aw < w * 0.4f) return null;
        int bandH = Math.round(aw * H / (float) W);
        int top = Math.round(h * 0.07f), bottom = Math.round(h * 0.93f);   // sans barres du haut / du bas
        if (bandH > (bottom - top) * 0.8f) return null;
        int minGutter = Math.max(8, Math.round(h * 0.01f));
        int run = 0;
        for (int y = top; y < top + (bottom - top) * 0.6f; y += 2) {
            if (uniformRow(screen, y, left, right)) {
                run += 2;
                continue;
            }
            if (run >= minGutter && y + bandH <= bottom && contentBelow(screen, y, bandH, left, right) >= 0.75f) {
                found[0] = true;
                Bitmap band = Bitmap.createBitmap(screen, left, y, aw, bandH);
                return Bitmap.createScaledBitmap(band, W, H, true);
            }
            run = 0;
        }
        return null;
    }

    /** Ligne presque d'une seule couleur (marge entre deux cases). */
    private static boolean uniformRow(Bitmap b, int y, int left, int right) {
        int min = 255, max = 0, step = Math.max(1, (right - left) / 48);
        for (int x = left + step / 2; x < right; x += step) {
            int c = b.getPixel(x, y);
            int l = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
            if (l < min) min = l;
            if (l > max) max = l;
            if (max - min > 12) return false;
        }
        return true;
    }

    /** Part des lignes « avec de l'image » dans la bande qui suit. */
    private static float contentBelow(Bitmap b, int y0, int bandH, int left, int right) {
        int rows = 0, content = 0;
        for (int y = y0; y < y0 + bandH; y += 6) {
            rows++;
            if (!uniformRow(b, y, left, right)) content++;
        }
        return rows == 0 ? 0 : content / (float) rows;
    }

    /** Beauté d'une vignette : couleurs (colorfulness de Hasler & Süsstrunk) + contraste. */
    static float score(Bitmap b) {
        double sRg = 0, sYb = 0, sRg2 = 0, sYb2 = 0, sL = 0, sL2 = 0;
        int n = 0;
        for (int y = 3; y < b.getHeight(); y += 7) {
            for (int x = 3; x < b.getWidth(); x += 7) {
                int c = b.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                double rg = r - g, yb = 0.5 * (r + g) - bl, l = 0.3 * r + 0.59 * g + 0.11 * bl;
                sRg += rg;
                sYb += yb;
                sRg2 += rg * rg;
                sYb2 += yb * yb;
                sL += l;
                sL2 += l * l;
                n++;
            }
        }
        if (n == 0) return 0;
        double mRg = sRg / n, mYb = sYb / n, mL = sL / n;
        double vRg = sRg2 / n - mRg * mRg, vYb = sYb2 / n - mYb * mYb, vL = sL2 / n - mL * mL;
        double colorful = Math.sqrt(Math.max(0, vRg) + Math.max(0, vYb)) + 0.3 * Math.sqrt(mRg * mRg + mYb * mYb);
        return (float) (colorful + 0.6 * Math.sqrt(Math.max(0, vL)) + 1);
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
