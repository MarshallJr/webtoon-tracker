package com.lois.webtoontracker;

import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Où trouver le dernier chapitre d'une série, en anglais ou en français, quel que soit le site :
 *  - MangaDex      : liens vers les chapitres publiés sur des services officiels (EN / FR)
 *  - MangaUpdates  : base qui suit TOUTES les sorties, tous groupes et tous sites confondus
 *  - Webtoon       : flux RSS officiel webtoons.com (si la série y est liée)
 * Toutes publiques et gratuites, sans compte ni clé.
 */
public final class ReleaseSources {

    private ReleaseSources() {}

    public static final String NOT_FOUND = "-";

    // ------------------------------------------------------------------ Comparaison de titres

    static boolean sameTitle(String a, String b) {
        String x = Titles.key(a), y = Titles.key(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        return x.length() >= 6 && y.length() >= 6 && (x.contains(y) || y.contains(x));
    }

    private static boolean anyTitleMatches(String want, JSONObject attributes) {
        JSONObject titles = attributes.optJSONObject("title");
        if (titles != null) {
            for (java.util.Iterator<String> it = titles.keys(); it.hasNext(); ) {
                String t = titles.optString(it.next());
                if (sameTitle(want, t) || TitleMatch.same(want, t)) return true;
            }
        }
        JSONArray alts = attributes.optJSONArray("altTitles");
        if (alts != null) {
            for (int i = 0; i < alts.length(); i++) {
                JSONObject alt = alts.optJSONObject(i);
                if (alt == null) continue;
                for (java.util.Iterator<String> it = alt.keys(); it.hasNext(); ) {
                    String t = alt.optString(it.next());
                    if (sameTitle(want, t) || TitleMatch.same(want, t)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ MangaDex

    public static String mangadexFind(String title) throws Exception {
        JSONObject res = new JSONObject(get("https://api.mangadex.org/manga?limit=10&title="
                + enc(title) + "&order%5Brelevance%5D=desc"));
        JSONArray data = res.optJSONArray("data");
        if (data == null) return NOT_FOUND;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            if (anyTitleMatches(title, m.optJSONObject("attributes"))) return m.getString("id");
        }
        return NOT_FOUND;
    }

    /**
     * Dernier chapitre EN et FR publié sur un service OFFICIEL vers lequel MangaDex renvoie
     * (MANGA Plus, Comikey…). Les chapitres hébergés par MangaDex lui-même sont surtout des
     * traductions de fans non officielles : ils ne sont plus proposés.
     */
    public static List<DbHelper.Release> mangadexLatest(String title, String id) throws Exception {
        String url = "https://api.mangadex.org/manga/" + id + "/feed?limit=100"
                + "&translatedLanguage%5B%5D=en&translatedLanguage%5B%5D=fr"
                + "&order%5Bchapter%5D=desc&includeExternalUrl=1"
                + "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica";
        JSONArray data = new JSONObject(get(url)).optJSONArray("data");
        Map<String, DbHelper.Release> best = new HashMap<>();
        if (data == null) return new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject ch = data.getJSONObject(i);
            JSONObject a = ch.optJSONObject("attributes");
            if (a == null) continue;
            String ext = a.optString("externalUrl", "");
            if (ext.isEmpty() || "null".equals(ext)) continue;   // hébergé par MangaDex : pas officiel
            double num;
            try {
                num = Double.parseDouble(a.optString("chapter", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String lang = a.optString("translatedLanguage", "").toUpperCase(Locale.ROOT);
            if (!lang.equals("EN") && !lang.equals("FR")) continue;
            DbHelper.Release cur = best.get(lang);
            if (cur != null && cur.chapter >= num) continue;
            DbHelper.Release r = new DbHelper.Release();
            r.title = title;
            r.lang = lang;
            r.chapter = num;
            r.url = ext;
            r.source = "Officiel";
            r.label = SourceRegistry.name(ext);
            r.foundAt = System.currentTimeMillis();
            best.put(lang, r);
        }
        return new ArrayList<>(best.values());
    }

    // ------------------------------------------------------------------ MangaUpdates

    public static String mangaupdatesFind(String title) throws Exception {
        JSONObject body = new JSONObject().put("search", title).put("perpage", 8);
        JSONObject res = new JSONObject(post("https://api.mangaupdates.com/v1/series/search", body.toString()));
        JSONArray results = res.optJSONArray("results");
        if (results == null) return NOT_FOUND;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            JSONObject rec = r.optJSONObject("record");
            if (rec == null) continue;
            if (sameTitle(title, rec.optString("title")) || sameTitle(title, r.optString("hit_title"))
                    || TitleMatch.same(title, rec.optString("title")) || TitleMatch.same(title, r.optString("hit_title"))) {
                return String.valueOf(rec.optLong("series_id"));
            }
        }
        return NOT_FOUND;
    }

    /** Dernier chapitre connu de MangaUpdates (toutes sources) + le groupe qui l'a sorti. */
    public static DbHelper.Release mangaupdatesLatest(String title, String id) throws Exception {
        JSONObject series = new JSONObject(get("https://api.mangaupdates.com/v1/series/" + id));
        double latest = series.optDouble("latest_chapter", 0);
        if (latest <= 0) return null;
        String page = series.optString("url", "https://www.mangaupdates.com/series.html?id=" + id);

        String group = null;
        try {
            JSONObject body = new JSONObject().put("search", id).put("search_type", "series").put("perpage", 1);
            JSONArray rel = new JSONObject(post("https://api.mangaupdates.com/v1/releases/search", body.toString()))
                    .optJSONArray("results");
            if (rel != null && rel.length() > 0) {
                JSONArray groups = rel.getJSONObject(0).optJSONObject("record").optJSONArray("groups");
                if (groups != null && groups.length() > 0) group = groups.getJSONObject(0).optString("name");
            }
        } catch (Exception ignored) {
        }

        DbHelper.Release r = new DbHelper.Release();
        r.title = title;
        r.source = "MangaUpdates";
        r.lang = "EN";
        r.chapter = latest;
        r.url = page; // la fiche liste les groupes et les liens vers leurs sites
        r.label = group == null || group.isEmpty() ? "Scan (MangaUpdates)" : "Scan · " + group;
        r.foundAt = System.currentTimeMillis();
        return r;
    }

    // ------------------------------------------------------------------ Webtoon (RSS officiel)

    public static DbHelper.Release webtoonLatest(String title, String rssBase, String titleNo) throws Exception {
        RssParser.Latest l = RssParser.latest(get(rssBase + "/rss?title_no=" + titleNo));
        if (l == null) return null;
        double num = l.episodeNo;
        Matcher m = Detector.EPISODE.matcher(l.title);
        if (m.find()) {
            try {
                num = Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException ignored) {
            }
        }
        DbHelper.Release r = new DbHelper.Release();
        r.title = title;
        r.source = "Webtoon";
        r.lang = rssBase.contains("/fr/") ? "FR" : "EN";
        r.chapter = num;
        r.url = l.link;
        r.label = "WEBTOON";
        r.foundAt = System.currentTimeMillis();
        return r;
    }

    // ------------------------------------------------------------------ Le site où tu lis

    /**
     * Le site où tu lis cette série : on essaie directement l'adresse du chapitre suivant
     * (ex. …/demonic-emperor-chapter-936/ → …-937/). Beaucoup de sites de scans publient avant
     * que MangaUpdates ne soit à jour. On avance tant que les chapitres existent (5 au plus).
     * Un chapitre PAYANT sur ce site n'est pas annoncé comme disponible : il est noté comme payant,
     * et tu seras prévenu quand il deviendra gratuit.
     *
     * @param knownUrl adresse d'un chapitre lu, knownCh son numéro, from le dernier chapitre lu
     */
    public static DbHelper.Release siteLatest(android.content.Context ctx, String title, String knownUrl, double knownCh,
                                              double from) throws Exception {
        if (knownUrl == null || from != Math.floor(from)) return null;
        DbHelper db = DbHelper.get(ctx);
        DbHelper.Release best = null;
        for (int i = 1; i <= 5; i++) {
            double ch = from + i;
            String url = ChapterLinks.guess(knownUrl, knownCh, ch);
            if (url == null) break;
            Paywall.Probe p = Paywall.probe(url, ch, knownUrl, knownCh);
            String host = host(url);
            long now = System.currentTimeMillis();
            if (p.state == Paywall.MISSING || (p.state == Paywall.UNKNOWN && !p.announced)) break;
            if (p.state == Paywall.LOCKED_STATE) {
                db.saveLock(title, ChapterLinks.host(url), ch, url, p.freeAt, now);
                Thread.sleep(600);
                continue;   // payant ici : le suivant peut exister (payant lui aussi)
            }
            db.markFreed(title, ChapterLinks.host(url), ch, now);   // était payant ? il ne l'est plus
            best = new DbHelper.Release();
            best.title = title;
            best.source = "Site";
            best.chapter = ch;
            best.url = url;
            best.label = host;
            best.lang = host.matches("(?i)^(fr|french)[-.].*|.*[-.]fr$|.*scan-?vf.*|.*(fr)scans?\\..*") ? "FR"
                    : host.matches("(?i)^(en|english)[-.].*") ? "EN" : "";
            best.foundAt = now;
            Thread.sleep(600);
        }
        if (best != null || db.countActiveLocks() > 0) Follow.sync(ctx);
        return best;
    }

    /**
     * La page existe vraiment : réponse 200, l'adresse finale contient toujours le numéro (pas de
     * renvoi vers l'accueil) et le titre de la page annonce bien ce chapitre (pas une page « introuvable »).
     */
    static boolean chapterPageExists(String url, double ch) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            c.setRequestProperty("Accept-Language", "fr,en;q=0.8");
            if (c.getResponseCode() != 200) return false;
            String n = Detector.formatChapter(ch);
            if (!c.getURL().toString().contains(n)) return false;
            String head;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[96 * 1024];
                int len = 0, r;
                while (len < buf.length && (r = in.read(buf, len, buf.length - len)) > 0) len += r;
                head = new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(head);
            String pageTitle = t.find() ? t.group(1) : "";
            if (pageTitle.matches("(?is).*(not found|introuvable|404|error|erreur).*")) return false;
            Pattern num = Pattern.compile("(?i)(chap(?:ter|itre)?|ch\\.?|[ée]pisode|ep\\.?)\\s*[-#:]?\\s*0*"
                    + Pattern.quote(n) + "(?![0-9]|\\.[0-9])");
            if (num.matcher(pageTitle).find()) return true;
            Matcher h1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(head);
            return h1.find() && num.matcher(h1.group(1).replaceAll("<[^>]+>", " ")).find();
        } catch (Exception e) {
            return false;   // site injoignable, protégé, etc. : on ne prévient pas
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ HTTP

    static String host(String url) {
        try {
            return new URL(url).getHost().replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "site";
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "WebtoonTracker/2.5 (Android; usage personnel)");
        c.setRequestProperty("Accept", "application/json, application/rss+xml, */*");
        return read(c);
    }

    static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "WebtoonTracker/2.5 (Android; usage personnel)");
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private static String read(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 3_000_000) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
