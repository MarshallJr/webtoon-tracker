package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrouve une série sur WEBTOON dans une langue donnée, même quand son titre y est traduit.
 *
 * Requêtes essayées : les noms connus dans cette langue, la traduction automatique du titre
 * (« Reincarnated With Divine Titles » → « Réincarné avec des titres divins »), le titre tel quel
 * (beaucoup de séries gardent leur nom anglais), puis les auteurs. Chaque résultat est noté avec
 * {@link TitleMatch} contre tous les noms connus (+ bonus si un auteur correspond) ; le meilleur est
 * vérifié sur la page de la série (titre officiel, résumé, auteurs, image).
 */
final class WebtoonFinder {

    private WebtoonFinder() {
    }

    static final class Hit {
        String lang, base, titleNo, title, slugTitle, text, summary, image, authors, genre;
        double score;

        String listUrl() {
            return base + "/list?title_no=" + titleNo;
        }
    }

    private static final Pattern LINK = Pattern.compile(
            "(?:https?://(?:www\\.|m\\.)?webtoons\\.com)?/([a-z]{2}(?:-[a-z]+)?)/([^/\"'\\s<>?]+)/([^/\"'\\s<>?]+)/list\\?title_no=(\\d+)");
    private static final Pattern IMG = Pattern.compile("(https?://[a-z0-9.-]*pstatic\\.net/[^\"'\\s)<>]+)");
    private static final Set<String> LATIN = new HashSet<>(java.util.Arrays.asList("fr", "en", "es", "de", "id"));

    /** La série sur WEBTOON dans cette langue, ou null. Réseau : à appeler hors du fil principal. */
    static Hit find(Context ctx, String title, String lang) {
        List<String[]> named = SeriesNames.named(ctx, title);
        List<String> refs = SeriesNames.names(ctx, title);
        List<String> authors = SeriesNames.authors(ctx, title);
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        // 1. noms connus dans cette langue
        for (String[] n : named) {
            String l = n[1] != null ? n[1] : TitleMatch.guessLang(n[0]);
            if (lang.equals(l) && !n[0].equals(title)) queries.add(n[0]);
        }
        // 2. traduction automatique du titre (alphabets latins)
        String src = TitleMatch.guessLang(title);
        if (LATIN.contains(lang) && !src.equals(lang)) {
            String mt = Translate.title(ctx, title, src, lang);
            if (mt != null) {
                queries.add(mt);
                refs.add(mt);
            }
        }
        // 3. le titre tel quel, 4. les auteurs
        queries.add(title);
        for (String a : authors) if (queries.size() < 7) queries.add(a);

        Hit best = null;
        int tried = 0, max = "fr".equals(lang) || "en".equals(lang) ? 5 : 3;
        for (String q : queries) {
            if (tried++ >= max) break;
            for (Hit c : search(lang, q)) {
                c.score = Math.max(TitleMatch.best(c.title, refs), TitleMatch.best(c.slugTitle, refs));
                if (authorMatch(c.text, authors)) c.score += 0.15;
                if (best == null || c.score > best.score) best = c;
            }
            if (best != null && best.score >= 0.95) break;
            sleep(350);
        }
        // Rien de sûr par la recherche : catalogue complet des séries de cette langue (liste publique).
        if (best == null || best.score < TitleMatch.SAME) {
            for (Hit c : catalog(ctx, lang)) {
                c.score = Math.max(TitleMatch.best(c.title, refs), TitleMatch.best(c.slugTitle, refs));
                if (authorMatch(c.text, authors)) c.score += 0.15;
                if (best == null || c.score > best.score) best = c;
            }
        }
        if (best == null || best.score < TitleMatch.SAME) return null;
        enrich(best, refs, authors);
        return best.score >= TitleMatch.SAME ? best : null;
    }

    // ------------------------------------------------------------------ Catalogue (repli)

    private static final long CATALOG_MS = 3L * 24 * 3600 * 1000;
    private static final String[] DAYS = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};

    /**
     * Toutes les séries WEBTOON Originals d'une langue (en cours + terminées), depuis les listes
     * publiques du site. Gardé 3 jours (titre, adresse, n° de série seulement).
     */
    static List<Hit> catalog(Context ctx, String lang) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("webtoon_catalog", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        List<Hit> out = new ArrayList<>();
        if (now - p.getLong("at_" + lang, 0) < CATALOG_MS) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(p.getString("list_" + lang, "[]"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONArray e = a.optJSONArray(i);
                    if (e == null || e.length() < 3) continue;
                    Hit h = new Hit();
                    h.lang = lang;
                    h.titleNo = e.optString(0);
                    h.base = e.optString(1);
                    h.title = e.optString(2);
                    h.text = e.length() > 3 ? e.optString(3) : "";
                    String[] parsed = Detector.parseSeriesLink(h.base + "/list?title_no=" + h.titleNo);
                    h.slugTitle = parsed == null ? h.title : parsed[2];
                    out.add(h);
                }
                return out;
            } catch (Exception ignored) {
            }
        }
        java.util.LinkedHashMap<String, Hit> all = new java.util.LinkedHashMap<>();
        List<String> pages = new ArrayList<>();
        pages.add("https://www.webtoons.com/" + lang + "/originals");
        pages.add("https://www.webtoons.com/" + lang + "/originals/complete");
        for (int i = 0; i < pages.size(); i++) {
            try {
                for (Hit h : parseResults(Editions.http(pages.get(i), 4_000_000), lang)) all.putIfAbsent(h.titleNo, h);
            } catch (Exception ignored) {
            }
            // Page « en cours » limitée au jour même : on ajoute les 7 jours.
            if (i == 0 && all.size() < 80) for (String d : DAYS) pages.add("https://www.webtoons.com/" + lang + "/originals/" + d);
            sleep(300);
        }
        org.json.JSONArray a = new org.json.JSONArray();
        for (Hit h : all.values()) {
            org.json.JSONArray e = new org.json.JSONArray();
            e.put(h.titleNo).put(h.base).put(h.title == null ? "" : h.title)
                    .put(h.text == null ? "" : h.text.substring(0, Math.min(120, h.text.length())));
            a.put(e);
            out.add(h);
        }
        if (!all.isEmpty()) p.edit().putLong("at_" + lang, now).putString("list_" + lang, a.toString()).apply();
        return out;
    }

    private static boolean authorMatch(String text, List<String> authors) {
        if (text == null || authors.isEmpty()) return false;
        String t = Titles.key(text);
        for (String a : authors) {
            String k = Titles.key(a);
            if (k.length() >= 3 && t.contains(k)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Recherche

    static List<Hit> search(String lang, String q) {
        List<Hit> out = new ArrayList<>();
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return out;
        }
        try {
            out.addAll(parseResults(Editions.http("https://www.webtoons.com/" + lang + "/search?keyword=" + enc, 2_000_000), lang));
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) {
            try {   // suggestions de la barre de recherche (JSON)
                out.addAll(parseJson(Editions.http("https://www.webtoons.com/" + lang + "/search/immediate?keyword=" + enc, 500_000), lang));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Résultats d'une page de recherche : chaque lien « …/list?title_no=N » et le texte qui l'entoure. */
    static List<Hit> parseResults(String html, String lang) {
        List<Hit> out = new ArrayList<>();
        List<int[]> pos = new ArrayList<>();
        List<String[]> groups = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            pos.add(new int[]{m.start(), m.end()});
            groups.add(new String[]{m.group(1), m.group(2), m.group(3), m.group(4)});
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            String[] g = groups.get(i);
            if (!lang.equalsIgnoreCase(g[0]) || g[1].matches("(?i)canvas|challenge")) continue;
            if (!seen.add(g[3])) continue;
            int end = Math.min(html.length(), pos.get(i)[0] + 2500);
            for (int j = i + 1; j < groups.size(); j++) {
                if (!groups.get(j)[3].equals(g[3])) {
                    end = Math.min(end, pos.get(j)[0]);
                    break;
                }
            }
            // La carte du résultat : du lien jusqu'au lien suivant (jamais le texte de la carte précédente).
            String window = html.substring(pos.get(i)[0], Math.max(end, pos.get(i)[1]));
            Hit h = new Hit();
            h.lang = lang.toLowerCase(Locale.ROOT);
            h.base = "https://www.webtoons.com/" + g[0] + "/" + g[1] + "/" + g[2];
            h.titleNo = g[3];
            h.slugTitle = Detector.slugToTitle(g[2]);
            h.text = Paywall.visibleText(window).replaceAll("\\s+", " ").trim();
            Matcher t = Pattern.compile("(?is)class=\"[^\"]*(?:subj|title)[^\"]*\"[^>]*>(.*?)</").matcher(window);
            String tt = t.find() ? Paywall.visibleText(t.group(1)).replaceAll("\\s+", " ").trim() : "";
            h.title = tt.isEmpty() ? h.slugTitle : tt;
            Matcher img = IMG.matcher(window);
            if (img.find()) h.image = img.group(1).replace("&amp;", "&");
            out.add(h);
        }
        return out;
    }

    /** Résultats au format JSON : {"titleNo": 123, "title": "…", "writingAuthorName": "…"}. */
    static List<Hit> parseJson(String json, String lang) {
        List<Hit> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"titleNo\"\\s*:\\s*\"?(\\d+)").matcher(json);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            if (!seen.add(m.group(1))) continue;
            int a = json.lastIndexOf('{', m.start()), b = json.indexOf('}', m.end());
            if (a < 0 || b < 0) continue;
            String obj = json.substring(a, b + 1);
            Hit h = new Hit();
            h.lang = lang;
            h.titleNo = m.group(1);
            h.base = "https://www.webtoons.com/" + lang + "/-/-";
            h.title = unescape(field(obj, "title"));
            h.slugTitle = h.title;
            h.text = obj;
            String au = field(obj, "(?:writingAuthorName|pictureAuthorName|authorName|author)");
            h.authors = au == null ? null : unescape(au);
            String img = field(obj, "thumbnail[A-Za-z]*");
            if (img != null) h.image = img.startsWith("http") ? unescape(img) : "https://webtoon-phinf.pstatic.net" + unescape(img);
            if (h.title != null && !h.title.isEmpty()) out.add(h);
        }
        return out;
    }

    private static String field(String obj, String nameRegex) {
        Matcher f = Pattern.compile("\"" + nameRegex + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return f.find() ? f.group(1) : null;
    }

    static String unescape(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
                sb.append(n == 'n' ? ' ' : n == 't' ? ' ' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Page de la série

    /**
     * Page de la série (balises og: et en-tête) : titre officiel dans cette langue, résumé, image,
     * auteurs, genre, et adresse canonique. Si le titre officiel ne correspond pas → score 0.
     */
    static void enrich(Hit h, List<String> refs, List<String> authors) {
        String html;
        try {
            html = Editions.http(h.listUrl(), 2_500_000);
        } catch (Exception e) {
            return;   // on garde le résultat de la recherche
        }
        applyPage(h, html, refs, authors);
    }

    /** Partie testable de {@link #enrich}. */
    static void applyPage(Hit h, String html, List<String> refs, List<String> authors) {
        String ogTitle = meta(html, "og:title");
        if (ogTitle != null) {
            ogTitle = ogTitle.replaceAll("(?i)\\s*[|\\-–]\\s*WEBTOON.*$", "").trim();
            double s = TitleMatch.best(ogTitle, refs);
            if (s < 0.5 && !authorMatch(html, authors)) {
                h.score = 0;
                return;
            }
            h.title = ogTitle;
            h.score = Math.max(h.score, s);
        }
        String desc = meta(html, "og:description");
        if (desc != null && desc.length() > 20) h.summary = desc;
        String img = meta(html, "og:image");
        if (img != null) h.image = img;
        String url = meta(html, "og:url");
        if (url == null) {
            Matcher c = Pattern.compile("(?is)<link[^>]+rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)").matcher(html);
            if (c.find()) url = c.group(1);
        }
        String[] parsed = url == null ? null : Detector.parseSeriesLink(url.replace("&amp;", "&"));
        if (parsed != null && parsed[1].equals(h.titleNo)) h.base = parsed[0];
        Matcher a = Pattern.compile("(?is)<(?:div|p|span|a)[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</(?:div|p|span|a)>").matcher(html);
        if (a.find()) {
            String t = Paywall.visibleText(a.group(1)).replaceAll("(?i)(author info|info auteur|infos? de l'auteur)", "")
                    .replaceAll("\\s+", " ").trim();
            if (!t.isEmpty() && t.length() < 120) h.authors = t;
        }
        Matcher g = Pattern.compile("(?is)<h2[^>]*class=\"[^\"]*genre[^\"]*\"[^>]*>(.*?)</h2>").matcher(html);
        if (g.find()) {
            String t = Paywall.visibleText(g.group(1)).trim();
            if (!t.isEmpty() && t.length() < 40) h.genre = t;
        }
    }

    /** <meta property="og:title" content="…"> quel que soit l'ordre des attributs. */
    static String meta(String html, String prop) {
        Matcher tag = Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(prop) + "[\"'][^>]*>").matcher(html);
        if (!tag.find()) return null;
        Matcher c = Pattern.compile("(?is)content=\"([^\"]*)\"|content='([^']*)'").matcher(tag.group());
        if (!c.find()) return null;
        String v = c.group(1) != null ? c.group(1) : c.group(2);
        v = v.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------ Image

    /** Image officielle (WEBTOON exige l'en-tête Referer). */
    static Bitmap image(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            c.setRequestProperty("Referer", "https://www.webtoons.com/");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 6_000_000) bo.write(buf, 0, n);
                byte[] d = bo.toByteArray();
                return BitmapFactory.decodeByteArray(d, 0, d.length);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Ce que la page WEBTOON apporte à la fiche (résumé, auteurs, genre, titre local). */
    static JSONObject toJson(Hit h) {
        JSONObject o = new JSONObject();
        try {
            o.put("lang", h.lang).put("title", h.title).put("url", h.listUrl());
            if (h.summary != null) o.put("summary", h.summary);
            if (h.authors != null) o.put("authors", h.authors);
            if (h.genre != null) o.put("genre", h.genre);
            if (h.image != null) o.put("image", h.image);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
