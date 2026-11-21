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
    /** Sans personnage, il faut une image vraiment belle (paysage, scène d'action très colorée). */
    static final float MIN_SCORE_NO_CHARACTER = 75f;

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

    // Paliers de qualité : une vignette n'est remplacée que par une meilleure (palier, puis note).
    static final float TIER_CHARACTER = 3000f;   // personnage, règles strictes
    static final float TIER_SCENE = 2000f;       // belle scène sans personnage, règles strictes
    static final float TIER_RELAXED = 1000f;     // repli : un peu de texte ou de vide toléré
    static final float TIER_ANY = 0f;            // dernier recours : la bande du milieu

    /** Résultat d'analyse : la vignette prête et sa note composée (palier + note). */
    static final class Result {
        Bitmap thumb;
        float score;
        String why;
    }

    /**
     * Cherche la meilleure vignette dans une zone d'image (capture d'écran ou page du chapitre).
     * Strict d'abord ; sinon souple ; sinon la bande du milieu — toujours une image (sauf écran noir).
     */
    static Result analyse(Bitmap img, int left, int top, int right, int bottom) {
        int aw = right - left, ah = bottom - top;
        if (aw < 50 || ah < 50) return null;
        int sw = ThumbPicker.SW2, sh = Math.max(8, Math.round(ah * sw / (float) aw));
        Bitmap small = Bitmap.createScaledBitmap(Bitmap.createBitmap(img, left, top, aw, ah), sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        float scale = aw / (float) sw;
        float tier;
        ThumbPicker.Pick p = ThumbPicker.pick(px, sw, sh, scale, left, top, ThumbPicker.STRICT);
        if (p.width > 0 && p.hasChar && p.score >= MIN_SCORE) tier = TIER_CHARACTER;
        else if (p.width > 0 && p.score >= 30) tier = TIER_SCENE;
        else {
            p = ThumbPicker.pick(px, sw, sh, scale, left, top, ThumbPicker.RELAXED);
            tier = p.width > 0 && p.score > 0 ? TIER_RELAXED : -1;
        }
        Result r = new Result();
        if (tier < 0) {
            // Dernier recours : la bande la plus « remplie » au milieu de la zone.
            int bh = Math.min(ah, Math.round(aw / ThumbPicker.ASPECT));
            int bw = Math.round(bh * ThumbPicker.ASPECT);
            p = new ThumbPicker.Pick();
            p.left = left + (aw - bw) / 2;
            p.top = top + (ah - bh) / 2;
            p.width = bw;
            p.height = bh;
            p.score = 1;
            p.why = "bande du milieu";
            tier = TIER_ANY;
        }
        int cl = Math.max(0, p.left), ct = Math.max(0, p.top);
        int cw = Math.min(p.width, img.getWidth() - cl), ch = Math.min(p.height, img.getHeight() - ct);
        if (cw < 20 || ch < 20) return null;
        r.thumb = Bitmap.createScaledBitmap(Bitmap.createBitmap(img, cl, ct, cw, ch), W, H, true);
        if (looksBad(r.thumb)) return null;
        r.score = tier + Math.min(Math.max(p.score, 0), 999);
        r.why = p.why;
        return r;
    }

    /** Enregistre si c'est mieux que la vignette actuelle (ou s'il n'y en a pas). */
    static boolean saveIfBetter(Context ctx, String title, double chapter, Result r) {
        if (r == null || r.thumb == null) return false;
        try {
            File f = file(ctx, title, chapter);
            android.content.SharedPreferences scores = scores(ctx);
            String sk = f.getAbsolutePath();
            if (f.exists() && scores.getFloat(sk, 0f) >= r.score) return false;
            File d = dir(ctx, title);
            if (!d.exists() && !d.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(f)) {
                r.thumb.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            scores.edit().putFloat(sk, r.score).apply();
            CACHE.remove(sk);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Capture d'écran pendant la lecture → vignette (une seule scène, sans texte, personnage d'abord).
     * Plusieurs captures sont analysées par chapitre ; seule la meilleure reste. Il y a TOUJOURS
     * une image (repli souple), remplacée dès qu'une capture fait mieux.
     *
     * @param panel colonne de la case au centre (bornes), ou null si inconnue
     * @param mid   capture prise pendant la lecture (sinon : au tout début, notée un peu plus bas)
     */
    public static boolean saveFromScreenshot(Context ctx, String title, double chapter, Bitmap screen, Rect panel, boolean mid) {
        try {
            int w = screen.getWidth(), h = screen.getHeight();
            int left = 0, right = w;
            if (panel != null && panel.right - panel.left >= w * 0.5f) {
                left = Math.max(0, panel.left);
                right = Math.min(w, panel.right);
            }
            Result r = analyse(screen, left, Math.round(h * 0.06f), right, Math.round(h * 0.94f)); // sans barres
            if (r == null) return false;
            if (!mid) r.score -= 300;   // tout début du chapitre (page de titre, bandeau du site) : moins bien
            return saveIfBetter(ctx, title, chapter, r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Notes des vignettes (v5 : paliers + note). Les vignettes officielles
     * notées avant sont reprises ; les anciennes captures repartent de zéro (donc remplacées).
     */
    static android.content.SharedPreferences scores(Context ctx) {
        android.content.SharedPreferences cur = ctx.getSharedPreferences("thumb_scores_v5", Context.MODE_PRIVATE);
        if (!cur.getBoolean("_migrated", false)) {
            android.content.SharedPreferences.Editor ed = cur.edit();
            for (String old : new String[]{"thumb_scores", "thumb_scores_v2", "thumb_scores_v3", "thumb_scores_v4"}) {
                for (java.util.Map.Entry<String, ?> e : ctx.getSharedPreferences(old, Context.MODE_PRIVATE).getAll().entrySet()) {
                    if (!(e.getValue() instanceof Float)) continue;
                    float v = (Float) e.getValue();
                    if (v >= WebtoonThumbs.OFFICIAL) ed.putFloat(e.getKey(), v);
                    // v4 n'enregistrait que des vignettes passant les règles strictes : on les garde bien placées
                    else if (old.equals("thumb_scores_v4") && v > 0) ed.putFloat(e.getKey(), 2500f + Math.min(v, 499f));
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

    /** Empreinte d'image (8 × 8, plus clair / plus sombre que la moyenne) : repère les doublons. */
    static long aHash(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        int[] l = new int[64];
        int sum = 0;
        for (int i = 0; i < 64; i++) {
            int c = s.getPixel(i % 8, i / 8);
            l[i] = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
            sum += l[i];
        }
        int mean = sum / 64;
        long h = 0;
        for (int i = 0; i < 64; i++) if (l[i] > mean) h |= 1L << i;
        return h;
    }

    /**
     * Ménage : une même image sur 3 chapitres ou plus d'une série n'est pas une vraie vignette
     * (ex. la pièce d'un chapitre payant récupérée sur le site) → supprimée, l'épisode est réessayé.
     */
    public static int cleanupDuplicates(Context ctx) {
        int removed = 0;
        File root = new File(ctx.getFilesDir(), "chapters");
        File[] series = root.listFiles();
        if (series == null) return 0;
        android.content.SharedPreferences sc = scores(ctx);
        android.content.SharedPreferences tries = ctx.getSharedPreferences("site_thumbs", Context.MODE_PRIVATE);
        android.content.SharedPreferences hashes = ctx.getSharedPreferences("site_thumb_hashes", Context.MODE_PRIVATE);
        for (File d : series) {
            File[] files = d.listFiles();
            if (files == null) continue;
            java.util.List<File> fs = new java.util.ArrayList<>();
            java.util.List<Long> hs = new java.util.ArrayList<>();
            for (File f : files) {
                if (!f.getName().endsWith(".jpg") || sc.getFloat(f.getAbsolutePath(), 0f) >= WebtoonThumbs.OFFICIAL) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (b == null) continue;
                fs.add(f);
                hs.add(aHash(b));
            }
            boolean[] drop = new boolean[fs.size()];
            for (int i = 0; i < fs.size(); i++) {
                int same = 0;
                for (int j = 0; j < fs.size(); j++) if (Long.bitCount(hs.get(i) ^ hs.get(j)) <= 6) same++;
                if (same >= 3) drop[i] = true;
            }
            android.content.SharedPreferences.Editor se = sc.edit(), te = tries.edit(), he = hashes.edit();
            for (int i = 0; i < fs.size(); i++) {
                if (!drop[i]) continue;
                File f = fs.get(i);
                String ch = f.getName().substring(0, f.getName().length() - 4);
                if (f.delete()) removed++;
                se.remove(f.getAbsolutePath());
                te.remove(d.getName() + "#" + ch);
                he.remove(d.getName() + "#" + ch);
            }
            se.apply();
            te.apply();
            he.apply();
        }
        CACHE.evictAll();
        return removed;
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

    static void deleteIfNotOfficial(Context ctx, String title, double chapter) {
        File f = file(ctx, title, chapter);
        android.content.SharedPreferences sc = scores(ctx);
        if (sc.getFloat(f.getAbsolutePath(), 0f) >= WebtoonThumbs.OFFICIAL) return;
        sc.edit().remove(f.getAbsolutePath()).apply();
        CACHE.remove(f.getAbsolutePath());
        f.delete();
    }

    public static void delete(Context ctx, String title, double chapter) {
        File f = file(ctx, title, chapter);
        CACHE.remove(f.getAbsolutePath());
        f.delete();
    }
}
