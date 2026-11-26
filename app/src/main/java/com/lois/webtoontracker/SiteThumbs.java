package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vignettes tirées directement des pages du chapitre, sur le site où tu lis.
 *
 * Pour un chapitre sans belle vignette (lu avant, lu trop vite, ou pas encore lu), on ouvre sa page
 * (même adresse que le bouton « Lire »), on repère la suite d'images du chapitre — le plus grand
 * groupe d'images rangées au même endroit sur le site, qu'elles soient dans la page ou dans le
 * script du lecteur — puis on analyse 3 images prises au milieu avec {@link ThumbPicker}.
 *
 * Économe : seulement les épisodes affichés, une tentative par chapitre et par semaine, quelques
 * chapitres par ouverture de l'onglet (moins sur les données mobiles), images décodées par morceaux.
 */
final class SiteThumbs {

    private SiteThumbs() {
    }

    private static final String PREFS = "site_thumbs";
    private static final long RETRY_MS = 7L * 24 * 3600 * 1000;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final Pattern IMG_URL = Pattern.compile(
            "https?:(?:\\\\?/){2}[^\"'\\s<>()]+?\\.(?:jpe?g|png|webp)(?:\\?[^\"'\\s<>()]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_PANEL = Pattern.compile(
            "(?i)(logo|avatar|icon|banner|(?<![a-z])ads?[/_-]|gravatar|emoji|favicon|button|discord|patreon|cover|thumb|sprite|loading|placeholder"
                    + "|coin|lock|premium|vip|unlock|price|reward|gift|badge|medal|token|wallet|bonus"   // chapitres payants
                    + "|/themes?/|/plugins?/|/assets?/|/static/|/icons?/"                                 // décor du site
                    + "|-\\d{2,4}x\\d{2,4}\\.)");   // -200x300.jpg : miniatures WordPress (autres séries)

    /** @return true si au moins une vignette a été ajoutée ou améliorée */
    static boolean fetch(Context ctx, String title, List<Double> chapters) {
        DbHelper db = DbHelper.get(ctx);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean metered = Net.metered(ctx);
        int budget = metered ? 3 : 10;
        List<DbHelper.Entry> entries = db.getHistory(title);
        DbHelper.Meta meta = db.getMeta(title);
        boolean any = false;
        for (Double c : chapters) {
            if (budget <= 0) break;
            if (c == null || ChapterThumbs.scoreOf(ctx, title, c) >= ChapterThumbs.TIER_CHARACTER) continue;
            String key = Titles.key(title) + "#" + Detector.formatChapter(c);
            long now = System.currentTimeMillis();
            if (now - p.getLong(key, 0) < RETRY_MS) continue;
            ChapterLinks.Link link = ChapterLinks.linkFor(ctx, title, meta, entries, c);
            if (link == null || link.seriesPage || link.url.contains("webtoons.com")) continue;
            p.edit().putLong(key, now).apply();
            budget--;
            try {
                if (fromChapterPage(ctx, title, c, link.url)) any = true;
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    private static boolean fromChapterPage(Context ctx, String title, double chapter, String pageUrl) throws Exception {
        String html = new String(bytes(pageUrl, pageUrl, 3_000_000), StandardCharsets.UTF_8);
        List<String> images = chapterImages(html, pageUrl);
        if (images.size() < 5) return false;
        ChapterThumbs.Result best = null;
        // 3 images au milieu du chapitre (le début : page de titre ; la fin : crédits, pub du site)
        for (float at : new float[]{0.35f, 0.55f, 0.75f}) {
            String u = images.get(Math.min(images.size() - 1, Math.round(at * (images.size() - 1))));
            ChapterThumbs.Result r = analyseImage(u, pageUrl);
            if (r != null && (best == null || r.score > best.score)) best = r;
            if (best != null && best.score >= ChapterThumbs.TIER_CHARACTER + 120) break;   // déjà très bien
            Thread.sleep(300);
        }
        if (best == null) return false;
        // Même image que pour un autre chapitre : c'est une image du site (pièce « à débloquer »,
        // bandeau…), pas le contenu du chapitre.
        long hash = ChapterThumbs.aHash(best.thumb);
        SharedPreferences hp = ctx.getSharedPreferences("site_thumb_hashes", Context.MODE_PRIVATE);
        String sk = Titles.key(title);
        for (Map.Entry<String, ?> e : hp.getAll().entrySet()) {
            if (!e.getKey().startsWith(sk + "#") || e.getKey().equals(sk + "#" + Detector.formatChapter(chapter))) continue;
            if (e.getValue() instanceof Long && Long.bitCount((Long) e.getValue() ^ hash) <= 6) {
                // l'autre chapitre avait reçu la même image du site : on la lui retire aussi
                String other = e.getKey().substring(sk.length() + 1);
                try {
                    ChapterThumbs.deleteIfNotOfficial(ctx, title, Double.parseDouble(other));
                } catch (NumberFormatException ignored) {
                }
                hp.edit().remove(e.getKey()).apply();
                return false;
            }
        }
        boolean saved = ChapterThumbs.saveIfBetter(ctx, title, chapter, best);
        if (saved) hp.edit().putLong(sk + "#" + Detector.formatChapter(chapter), hash).apply();
        return saved;
    }

    /**
     * Les images du chapitre : toutes les adresses d'images de la page (balises et scripts),
     * regroupées par dossier ; le plus grand groupe (au moins 3) est la suite de cases.
     */
    static List<String> chapterImages(String html, String pageUrl) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = IMG_URL.matcher(html);
        while (m.find()) {
            String u = m.group().replace("\\/", "/").replace("&amp;", "&");
            if (!seen.add(u) || NOT_PANEL.matcher(u).find()) continue;
            String path = u.contains("?") ? u.substring(0, u.indexOf('?')) : u;
            String dir = path.substring(0, path.lastIndexOf('/') + 1);
            groups.computeIfAbsent(dir, k -> new ArrayList<>()).add(u);
        }
        // relatives dans <img src="/…"> : rares sur ces sites, ignorées
        List<String> best = new ArrayList<>();
        for (List<String> g : groups.values()) if (g.size() > best.size()) best = g;
        // Un chapitre, c'est une vraie suite d'images (au moins 5) ; en dessous, c'est le décor du site
        // ou une page « chapitre à débloquer ».
        return best.size() >= 5 ? best : new ArrayList<>();
    }

    /** Télécharge une image du chapitre et l'analyse par morceaux d'environ deux écrans de haut. */
    private static ChapterThumbs.Result analyseImage(String url, String referer) throws Exception {
        byte[] data = bytes(url, referer, 12_000_000);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int w = o.outWidth, h = o.outHeight;
        if (w < 200 || h < 150) return null;
        int sample = 1;
        while (w / (sample * 2) >= 540) sample *= 2;                 // ~540-1080 px de large suffisent
        BitmapRegionDecoder dec = Build31.newDecoder(data);
        if (dec == null) return null;
        ChapterThumbs.Result best = null;
        try {
            int chunk = Math.min(h, w * 2);                            // ~2 écrans de haut
            int parts = Math.min(6, (h + chunk - 1) / chunk);          // 6 morceaux au plus, répartis
            for (int k = 0; k < parts; k++) {
                int y = parts == 1 ? 0 : Math.round(k * (h - chunk) / (float) (parts - 1));
                BitmapFactory.Options ro = new BitmapFactory.Options();
                ro.inSampleSize = sample;
                Bitmap part = dec.decodeRegion(new Rect(0, y, w, Math.min(h, y + chunk)), ro);
                if (part == null) continue;
                Bitmap soft = part.getConfig() == Bitmap.Config.ARGB_8888 ? part : part.copy(Bitmap.Config.ARGB_8888, false);
                ChapterThumbs.Result r = ChapterThumbs.analyse(soft, 0, 0, soft.getWidth(), soft.getHeight());
                if (r != null && (best == null || r.score > best.score)) best = r;
            }
        } finally {
            dec.recycle();
        }
        return best;
    }

    private static byte[] bytes(String url, String referer, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer);      // beaucoup de sites refusent leurs images sans ça
        c.setRequestProperty("Accept", "text/html,image/avif,image/webp,image/*,*/*");
        try {
            String host = new URI(referer).getHost();
            if (host != null) c.setRequestProperty("Origin", "https://" + host);
        } catch (Exception ignored) {
        }
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** BitmapRegionDecoder.newInstance : signature différente selon la version d'Android. */
    private static final class Build31 {
        @SuppressWarnings("deprecation")
        static BitmapRegionDecoder newDecoder(byte[] data) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31) return BitmapRegionDecoder.newInstance(data, 0, data.length);
                return BitmapRegionDecoder.newInstance(data, 0, data.length, false);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
