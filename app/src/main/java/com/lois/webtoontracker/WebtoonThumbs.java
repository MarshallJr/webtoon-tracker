package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vignettes OFFICIELLES des épisodes, quand la série existe sur webtoons.com.
 *
 * Sur WEBTOON, chaque épisode a une vignette choisie et recadrée par l'auteur ou le studio
 * (souvent un gros plan de personnage), hébergée par WEBTOON : l'appli WEBTOON ne fait que
 * l'afficher. La page « liste des épisodes » du site donne l'adresse de chacune (10 par page) ;
 * les images exigent l'en-tête Referer de webtoons.com.
 *
 * Les sites de scans n'ont pas d'équivalent : pour eux, on garde nos captures.
 * Numérotation : on associe le chapitre N à l'épisode n° N de WEBTOON (episode_no).
 */
final class WebtoonThumbs {

    private WebtoonThumbs() {
    }

    private static final String PREFS = "official_thumbs";
    private static final long LINK_RETRY_MS = 7L * 24 * 3600 * 1000;
    private static final long PAGE_TTL_MS = 24L * 3600 * 1000;
    private static final int PER_PAGE = 10;
    private static final int MAX_PAGES_PER_RUN = 6;
    /** Score donné aux vignettes officielles : aucune capture ne les remplace. */
    static final float OFFICIAL = 1_000_000f;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String COOKIES = "needGDPR=false; needCCPA=false; needCOPPA=false";
    private static final Pattern EP_NO = Pattern.compile("episode_no=(\\d+)");
    private static final Pattern IMG = Pattern.compile("(?:data-src|src)=\"(https://webtoon-phinf\\.pstatic\\.net/[^\"]+)\"");

    /**
     * Récupère (en arrière-plan) les vignettes officielles manquantes pour ces chapitres.
     * @return true si au moins une nouvelle vignette a été enregistrée
     */
    static boolean fetch(Context ctx, String title, Collection<Double> chapters) {
        try {
            DbHelper db = DbHelper.get(ctx);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String k = Titles.key(title);
            DbHelper.Meta m = db.getMeta(title);
            if (m == null || m.rssBase == null || m.titleNo == null) {
                // Pas encore reliée à webtoons.com : on cherche la série (une fois par semaine au plus).
                long now = System.currentTimeMillis();
                if (now - p.getLong("link_" + k, 0) < LINK_RETRY_MS) return false;
                p.edit().putLong("link_" + k, now).apply();
                if (!CoverWorker.autoLink(ctx, title, null)) return false;
                m = db.getMeta(title);
                if (m == null || m.rssBase == null || m.titleNo == null) return false;
            }
            TreeSet<Integer> need = new TreeSet<>();
            for (Double c : chapters) {
                if (c == null || c != Math.floor(c) || c < 1) continue;
                if (official(ctx, title, c)) continue;
                need.add(c.intValue());
            }
            if (need.isEmpty()) return false;
            // Pas plus d'une recherche toutes les 6 h par série (les épisodes absents de WEBTOON
            // resteraient sinon « à chercher » à chaque ouverture de l'onglet).
            long now = System.currentTimeMillis();
            if (now - p.getLong("run_" + k, 0) < 6L * 3600 * 1000) return false;
            p.edit().putLong("run_" + k, now).apply();

            String list = m.rssBase + "/list?title_no=" + m.titleNo + "&page=";
            Map<Integer, String> urls = new HashMap<>();
            Map<Integer, String> first = parse(get(list + 1));
            urls.putAll(first);
            int latest = 0;
            for (int n : first.keySet()) latest = Math.max(latest, n);
            if (latest == 0) return false;

            // Pages à charger : la liste va du plus récent au plus ancien, 10 par page.
            TreeSet<Integer> pages = new TreeSet<>();
            for (int c : need) {
                if (c > latest || urls.containsKey(c)) continue;
                pages.add((latest - c) / PER_PAGE + 1);
            }
            int loaded = 0;
            for (int page : pages) {
                if (loaded >= MAX_PAGES_PER_RUN) break;
                String key = "page_" + m.titleNo + "_" + page;
                if (System.currentTimeMillis() - p.getLong(key, 0) < PAGE_TTL_MS && page != 1) continue;
                urls.putAll(parse(get(list + page)));
                p.edit().putLong(key, System.currentTimeMillis()).apply();
                loaded++;
                Thread.sleep(350);
            }

            boolean any = false;
            for (int c : need) {
                String u = urls.get(c);
                if (u == null) continue;
                Bitmap b = image(u);
                if (b != null && ChapterThumbs.saveOfficial(ctx, title, c, b)) any = true;
            }
            return any;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean official(Context ctx, String title, double chapter) {
        return ChapterThumbs.scoreOf(ctx, title, chapter) >= OFFICIAL;
    }

    /** Épisodes de la page : n° d'épisode → adresse de la vignette. */
    static Map<Integer, String> parse(String html) {
        Map<Integer, String> out = new HashMap<>();
        for (String li : html.split("<li")) {
            Matcher e = EP_NO.matcher(li), i = IMG.matcher(li);
            if (e.find() && i.find()) out.put(Integer.parseInt(e.group(1)), i.group(1).replace("&amp;", "&"));
        }
        return out;
    }

    /** L'image en meilleure qualité : d'abord sans paramètre de taille, sinon telle quelle. */
    private static Bitmap image(String url) {
        String[] tries = url.contains("?") ? new String[]{url.substring(0, url.indexOf('?')), url} : new String[]{url};
        for (String u : tries) {
            try {
                byte[] data = bytes(u);
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b != null && b.getWidth() >= 60) return b;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        return new String(bytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.webtoons.com/");   // exigé par les images de WEBTOON
        c.setRequestProperty("Cookie", COOKIES);                        // évite la page de consentement
        c.setRequestProperty("Accept-Language", "fr,en;q=0.8");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
