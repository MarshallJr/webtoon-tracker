package com.lois.webtoontracker;

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
 *  - MangaDex      : chapitres EN / FR des équipes de scan (+ liens vers les sites officiels)
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
                if (sameTitle(want, titles.optString(it.next()))) return true;
            }
        }
        JSONArray alts = attributes.optJSONArray("altTitles");
        if (alts != null) {
            for (int i = 0; i < alts.length(); i++) {
                JSONObject alt = alts.optJSONObject(i);
                if (alt == null) continue;
                for (java.util.Iterator<String> it = alt.keys(); it.hasNext(); ) {
                    if (sameTitle(want, alt.optString(it.next()))) return true;
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

    /** Dernier chapitre EN et FR sur MangaDex (ou sur le site officiel vers lequel MangaDex renvoie). */
    public static List<DbHelper.Release> mangadexLatest(String title, String id) throws Exception {
        String url = "https://api.mangadex.org/manga/" + id + "/feed?limit=50"
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
            String ext = a.optString("externalUrl", "");
            if (!ext.isEmpty() && !"null".equals(ext)) {
                r.url = ext;
                r.source = "Officiel";
                r.label = "Officiel · " + host(ext);
            } else {
                r.url = "https://mangadex.org/chapter/" + ch.getString("id");
                r.source = "MangaDex";
                r.label = "MangaDex";
            }
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
            if (sameTitle(title, rec.optString("title")) || sameTitle(title, r.optString("hit_title"))) {
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
