package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « Langues & sources » : où une série est publiée officiellement, dans toutes les langues.
 *
 * Découverte, pas lecture : l'appli lit seulement des fiches de référence et des listes d'épisodes
 * publiques, jamais les pages ni les images des chapitres.
 *
 * Classification des sources (voir {@link SourceRegistry}) :
 *  1. OFFICIELLE : lien listé comme officiel par AniList ou MangaDex, ou série trouvée sur WEBTOON ;
 *  2. AUTORISÉE  : publiée par l'auteur (WEBTOON Canvas), ou ajoutée par toi avec une preuve lue sur
 *                  un canal officiel de la série (la page doit mentionner la source) ;
 *  3. INCONNUE ou NON AUTORISÉE : jamais cherchée, jamais enregistrée.
 *
 * Seulement pour les séries où tu actives « Langues & sources ». Aucune IA : les fiches de référence
 * donnent déjà les identifiants.
 */
final class Editions {

    private Editions() {
    }

    /** Préfixe des sorties tirées des éditions (supprimées quand tu coupes « Langues & sources »). */
    static final String RELEASE_SOURCE = "Édition";
    static final long STALE_MS = 12L * 3600 * 1000;
    private static final long WT_SEARCH_MS = 7L * 24 * 3600 * 1000;
    private static final String PREFS = "editions";
    private static final String[] WEBTOON_LANGS = {"en", "fr", "es", "de", "id", "th", "zh-hant"};
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final Pattern SERIES_LINK = Pattern.compile(
            "https?://(?:www\\.|m\\.)?webtoons\\.com/([a-z]{2}(?:-[a-z]+)?)/([^/\"'\\s]+)/([^/\"'\\s]+)/list\\?title_no=(\\d+)");

    private static final Set<String> running = Collections.synchronizedSet(new HashSet<>());

    static boolean isRunning(String title) {
        return running.contains(Titles.key(title));
    }

    /** Recherche si « Langues & sources » est activé et que la dernière recherche date de plus de maxAgeMs. */
    static boolean refreshIfStale(Context ctx, String title, long maxAgeMs) {
        DbHelper.Meta m = DbHelper.get(ctx).getMeta(title);
        if (m == null || !m.editionsOn) return false;
        if (System.currentTimeMillis() - m.editionsAt < maxAgeMs) return false;
        return refresh(ctx, title) >= 0;
    }

    /**
     * Cherche toutes les éditions officielles et autorisées de la série (fil d'arrière-plan).
     * @return nombre d'éditions trouvées automatiquement, -1 si une recherche est déjà en cours
     */
    static int refresh(Context ctx, String title) {
        String key = Titles.key(title);
        if (!running.add(key)) return -1;
        try {
            DbHelper db = DbHelper.get(ctx);
            DbHelper.Meta m = db.getMeta(title);
            Map<String, DbHelper.Edition> found = new LinkedHashMap<>();
            List<String> channels = new ArrayList<>();

            // 1. AniList : liens officiels avec leur langue, + canaux officiels (réseaux, site de la série)
            String origin = null;
            try {
                JSONObject media = anilistMedia(title, m);
                if (media != null) {
                    origin = SourceRegistry.originLang(str(media, "countryOfOrigin"));
                    fromAniList(media, origin, found, channels);
                }
            } catch (Exception ignored) {
            }
            // 2. MangaDex : original et traduction anglaise officiels, chapitres publiés sur des services officiels
            try {
                fromMangaDex(ctx, title, m, origin, found);
            } catch (Exception ignored) {
            }
            // 3. WEBTOON : la série déjà reliée, et la même série dans les autres langues du site
            try {
                fromWebtoonLink(m, found);
                fromWebtoonSearch(ctx, title, found);
            } catch (Exception ignored) {
            }
            // 4. Dernier chapitre et gratuit / payant, quand la plateforme publie sa liste d'épisodes
            long now = System.currentTimeMillis();
            for (DbHelper.Edition e : found.values()) {
                try {
                    if (SourceRegistry.isWebtoons(e.url)) webtoonEpisodes(e);
                } catch (Exception ignored) {
                }
                e.checkedAt = now;
            }
            List<DbHelper.Edition> list = new ArrayList<>(found.values());
            db.replaceEditions(title, list);
            for (DbHelper.Edition e : list) if (e.category == SourceRegistry.OFFICIAL) channels.add(e.url);
            saveChannels(ctx, title, channels);

            // 5. Les éditions dont on connaît les chapitres alimentent la liste d'épisodes et les alertes 🔔
            db.deleteReleases(title, RELEASE_SOURCE);
            for (DbHelper.Edition e : db.getEditions(title)) {
                double ch = e.freeUpto > 0 ? e.freeUpto : e.latest;
                String url = ch > 0 ? e.chapterUrl(ch) : null;
                if (url == null) continue;
                DbHelper.Release r = new DbHelper.Release();
                r.title = title;
                r.source = RELEASE_SOURCE + "·" + e.platform;
                r.lang = e.lang == null ? "" : e.lang.toUpperCase(Locale.ROOT);
                r.chapter = ch;
                r.url = url;
                r.label = e.platform;
                r.foundAt = now;
                db.saveRelease(r);
            }
            db.setEditionsAt(title, now);
            return list.size();
        } finally {
            running.remove(key);
        }
    }

    // ------------------------------------------------------------------ Éditions trouvées

    static DbHelper.Edition edition(String url, String platform, String lang, int category, String evidence, String model) {
        DbHelper.Edition e = new DbHelper.Edition();
        e.url = url;
        e.platform = platform;
        e.lang = SourceRegistry.normalize(lang);
        e.category = category;
        e.evidence = evidence;
        e.model = model;
        return e;
    }

    /** Une édition par plateforme et par langue : les doublons sont fusionnés (preuves cumulées). */
    private static void add(Map<String, DbHelper.Edition> found, DbHelper.Edition e) {
        if (e.category != SourceRegistry.OFFICIAL && e.category != SourceRegistry.AUTHORIZED) return;
        String k = (e.platform == null ? "" : e.platform.toLowerCase(Locale.ROOT)) + "|" + (e.lang == null ? "?" : e.lang);
        DbHelper.Edition cur = found.get(k);
        if (cur == null) {
            found.put(k, e);
            return;
        }
        if (cur.evidence != null && e.evidence != null && !cur.evidence.contains(e.evidence)) {
            cur.evidence = cur.evidence + " · " + e.evidence;
        }
        // l'adresse de la série (liste d'épisodes) plutôt que celle d'un chapitre
        if (cur.chapters != null && e.chapters == null) cur.url = e.url;
        if (e.chapters != null) cur.chapters = e.chapters;
        cur.latest = Math.max(cur.latest, e.latest);
        cur.category = Math.min(cur.category, e.category);
        if (cur.model == null) cur.model = e.model;
    }

    // ------------------------------------------------------------------ AniList

    static JSONObject anilistMedia(String title, DbHelper.Meta m) throws Exception {
        int id = 0;
        if (m != null && m.detailsJson != null) {
            JSONObject a = new JSONObject(m.detailsJson).optJSONObject("anilist");
            if (a != null) {
                id = a.optInt("id", 0);
                if (id == 0) {
                    Matcher d = Pattern.compile("anilist\\.co/manga/(\\d+)").matcher(a.optString("siteUrl", ""));
                    if (d.find()) id = Integer.parseInt(d.group(1));
                }
            }
        }
        if (id <= 0) return SeriesInfo.searchMedia(title);
        String q = "query($id:Int){Media(id:$id,type:MANGA){id siteUrl countryOfOrigin"
                + " externalLinks{url site type language isDisabled}}}";
        String res = ReleaseSources.post("https://graphql.anilist.co", new JSONObject().put("query", q)
                .put("variables", new JSONObject().put("id", id)).toString());
        JSONObject data = new JSONObject(res).optJSONObject("data");
        return data == null ? null : data.optJSONObject("Media");
    }

    static void fromAniList(JSONObject media, String origin, Map<String, DbHelper.Edition> found, List<String> channels) {
        JSONArray links = media.optJSONArray("externalLinks");
        if (links == null) return;
        for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i);
            if (l == null || l.optBoolean("isDisabled", false)) continue;
            String url = str(l, "url");
            if (url == null || !url.matches("(?i)^https?://.+")) continue;
            String type = str(l, "type");
            SourceRegistry.Platform p = SourceRegistry.platform(url);
            // Réseaux, site officiel, fiche… : ce ne sont pas des éditions, mais des canaux officiels (preuves)
            if ("SOCIAL".equals(type) || (p == null && !"STREAMING".equals(type))) {
                channels.add(url);
                continue;
            }
            String lang = SourceRegistry.codeFromName(str(l, "language"));
            if (lang == null) lang = SourceRegistry.langFromUrl(url);
            if (lang == null && p != null) lang = p.lang;
            if (lang == null && p != null && origin != null && p.name.matches("(?i).*(naver|kakao|ridi|bomtoon|piccoma|line manga|bilibili manga|tencent|kuaikan).*")) {
                lang = origin;
            }
            String name = p != null ? p.name : (str(l, "site") != null ? str(l, "site") : SourceRegistry.name(url));
            add(found, edition(url, name, lang, SourceRegistry.classify(url, true), "Listée par AniList",
                    p == null ? null : p.model));
        }
    }

    // ------------------------------------------------------------------ MangaDex

    static void fromMangaDex(Context ctx, String title, DbHelper.Meta m, String origin,
                             Map<String, DbHelper.Edition> found) throws Exception {
        DbHelper db = DbHelper.get(ctx);
        String id = m == null ? null : m.mdxId;
        if (id == null) {
            id = ReleaseSources.mangadexFind(title);
            db.setSourceIds(title, id, null);
        }
        if (id == null || ReleaseSources.NOT_FOUND.equals(id)) return;
        JSONObject data = new JSONObject(ReleaseSources.get("https://api.mangadex.org/manga/" + id)).optJSONObject("data");
        JSONObject attrs = data == null ? null : data.optJSONObject("attributes");
        if (attrs == null) return;
        String orig = SourceRegistry.normalize(str(attrs, "originalLanguage"));
        if (orig == null) orig = origin;
        JSONObject links = attrs.optJSONObject("links");
        if (links != null) {
            String raw = str(links, "raw"), engtl = str(links, "engtl");
            if (raw != null && raw.matches("(?i)^https?://.+")) {
                SourceRegistry.Platform p = SourceRegistry.platform(raw);
                String lang = SourceRegistry.langFromUrl(raw);
                add(found, edition(raw, SourceRegistry.name(raw), lang != null ? lang : orig, SourceRegistry.classify(raw, true),
                        "Original officiel indiqué par MangaDex", p == null ? null : p.model));
            }
            if (engtl != null && engtl.matches("(?i)^https?://.+")) {
                SourceRegistry.Platform p = SourceRegistry.platform(engtl);
                add(found, edition(engtl, SourceRegistry.name(engtl), "en", SourceRegistry.classify(engtl, true),
                        "Traduction anglaise officielle indiquée par MangaDex", p == null ? null : p.model));
            }
        }
        // Chapitres publiés sur des services officiels (toutes langues) : seulement ceux qui ont une
        // adresse externe. Les chapitres hébergés par MangaDex lui-même ne sont jamais retenus.
        Map<String, DbHelper.Edition> byKey = new LinkedHashMap<>();
        Map<String, TreeMap<Double, String>> chapters = new LinkedHashMap<>();
        for (int offset = 0; offset <= 100; offset += 100) {
            String url = "https://api.mangadex.org/manga/" + id + "/feed?limit=100&offset=" + offset
                    + "&order%5Bchapter%5D=desc&includeExternalUrl=1"
                    + "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica";
            JSONArray feed = new JSONObject(ReleaseSources.get(url)).optJSONArray("data");
            if (feed == null) break;
            for (int i = 0; i < feed.length(); i++) {
                JSONObject a = feed.optJSONObject(i) == null ? null : feed.optJSONObject(i).optJSONObject("attributes");
                if (a == null) continue;
                String ext = str(a, "externalUrl");
                if (ext == null || !ext.matches("(?i)^https?://.+")) continue;
                double num;
                try {
                    num = Double.parseDouble(a.optString("chapter", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                String lang = SourceRegistry.normalize(str(a, "translatedLanguage"));
                String name = SourceRegistry.name(ext);
                String k = name + "|" + lang;
                DbHelper.Edition e = byKey.get(k);
                if (e == null) {
                    SourceRegistry.Platform p = SourceRegistry.platform(ext);
                    e = edition(ext, name, lang, SourceRegistry.classify(ext, true),
                            "Chapitres officiels listés par MangaDex", p == null ? null : p.model);
                    byKey.put(k, e);
                    chapters.put(k, new TreeMap<>());
                }
                if (num > e.latest) {
                    e.latest = num;
                    e.url = ext;                       // le chapitre le plus récent
                }
                TreeMap<Double, String> map = chapters.get(k);
                if (map.size() < 80) map.put(num, ext);
            }
            if (feed.length() < 100) break;
            Thread.sleep(250);
        }
        for (Map.Entry<String, DbHelper.Edition> en : byKey.entrySet()) {
            JSONObject o = new JSONObject();
            for (Map.Entry<Double, String> c : chapters.get(en.getKey()).entrySet()) {
                o.put(Detector.formatChapter(c.getKey()), c.getValue());
            }
            en.getValue().chapters = o.toString();
            add(found, en.getValue());
        }
    }

    // ------------------------------------------------------------------ WEBTOON

    /** La série déjà reliée à WEBTOON (lue sur webtoons.com, ou trouvée par la recherche de titre). */
    static void fromWebtoonLink(DbHelper.Meta m, Map<String, DbHelper.Edition> found) {
        if (m == null || m.rssBase == null || m.titleNo == null) return;
        String url = m.rssBase + "/list?title_no=" + m.titleNo;
        add(found, edition(url, "WEBTOON", SourceRegistry.langFromUrl(url), SourceRegistry.classify(url, true),
                "Série trouvée sur WEBTOON", SourceRegistry.FASTPASS));
    }

    /**
     * La même série dans les autres langues de WEBTOON : recherche du titre exact sur chaque version
     * du site (une fois par semaine au plus). Les séries CANVAS homonymes sont ignorées.
     */
    static void fromWebtoonSearch(Context ctx, String title, Map<String, DbHelper.Edition> found) throws Exception {
        SharedPreferences p = prefs(ctx);
        String key = Titles.key(title);
        if (key.length() < 4) return;
        long now = System.currentTimeMillis();
        JSONArray cached = new JSONArray(p.getString("wt_found_" + key, "[]"));
        if (now - p.getLong("wt_search_" + key, 0) > WT_SEARCH_MS) {
            p.edit().putLong("wt_search_" + key, now).apply();
            Set<String> have = new HashSet<>();
            for (DbHelper.Edition e : found.values()) {
                if (SourceRegistry.isWebtoons(e.url) && e.lang != null) have.add(e.lang);
            }
            cached = new JSONArray();
            for (String lang : WEBTOON_LANGS) {
                if (have.contains(lang)) continue;
                try {
                    String html = http("https://www.webtoons.com/" + lang + "/search?keyword="
                            + URLEncoder.encode(title, "UTF-8"), 1_500_000);
                    Matcher mm = SERIES_LINK.matcher(html);
                    while (mm.find()) {
                        if (mm.group(2).matches("(?i)canvas|challenge")) continue;
                        if (!Titles.key(Detector.slugToTitle(mm.group(3))).equals(key)) continue;
                        cached.put("https://www.webtoons.com/" + mm.group(1) + "/" + mm.group(2) + "/" + mm.group(3)
                                + "/list?title_no=" + mm.group(4));
                        break;
                    }
                    Thread.sleep(400);
                } catch (Exception ignored) {
                }
            }
            p.edit().putString("wt_found_" + key, cached.toString()).apply();
        }
        for (int i = 0; i < cached.length(); i++) {
            String url = cached.optString(i, "");
            if (url.isEmpty()) continue;
            add(found, edition(url, "WEBTOON", SourceRegistry.langFromUrl(url), SourceRegistry.classify(url, true),
                    "Même titre trouvé sur WEBTOON", SourceRegistry.FASTPASS));
        }
    }

    /**
     * Dernier épisode public d'une édition WEBTOON (flux RSS : les épisodes gratuits du site).
     * Les épisodes plus récents, en Fast Pass, se paient sur l'appli puis deviennent gratuits.
     */
    static void webtoonEpisodes(DbHelper.Edition e) throws Exception {
        String[] parsed = Detector.parseSeriesLink(e.url);
        if (parsed == null) return;
        String xml = http(parsed[0] + "/rss?title_no=" + parsed[1], 1_000_000);
        applyRss(e, parsed[0], parsed[1], RssParser.items(xml));
    }

    /** Partie testable : choisit le dernier épisode et déduit le numéro de chapitre. */
    static void applyRss(DbHelper.Edition e, String base, String titleNo, List<RssParser.Latest> items) {
        RssParser.Latest best = null;
        for (RssParser.Latest it : items) if (best == null || it.episodeNo > best.episodeNo) best = it;
        if (best == null) return;
        double n = SourceRegistry.numberInTitle(best.title);
        // Titre sans numéro, saisons numérotées à part, numéro très éloigné : on garde le n° d'épisode.
        if (Double.isNaN(n) || n < 1 || n != Math.floor(n) || Math.abs(n - best.episodeNo) > 20) n = best.episodeNo;
        e.latest = n;
        e.freeUpto = n;
        e.epOffset = (int) (best.episodeNo - (long) n);
        e.chapterTpl = base + "/episode-{e}/viewer?title_no=" + titleNo + "&episode_no={e}";
        e.model = SourceRegistry.FASTPASS;
        if (e.lang == null) e.lang = SourceRegistry.langFromUrl(base + "/");
    }

    // ------------------------------------------------------------------ Source ajoutée avec une preuve

    /**
     * Ajoute une source AUTORISÉE, seulement si la preuve est vérifiable :
     *  - elle est publiée sur un canal officiel de la série (site ou compte listé par AniList,
     *    plateforme officielle) — pour un réseau social, sur le compte officiel lui-même ;
     *  - la page se lit publiquement et mentionne bien la source.
     * @return null si la source est acceptée et enregistrée, sinon la raison du refus.
     */
    static String addAuthorized(Context ctx, String title, String sourceUrl, String proofUrl) {
        sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        proofUrl = proofUrl == null ? "" : proofUrl.trim();
        if (!sourceUrl.matches("(?i)^https?://[^/\\s]+\\S*$")) return "L'adresse de la source doit commencer par https://";
        if (!proofUrl.matches("(?i)^https?://[^/\\s]+\\S*$")) return "L'adresse de la preuve doit commencer par https://";
        String sh = SourceRegistry.host(sourceUrl), ph = SourceRegistry.host(proofUrl);
        if (sh == null || ph == null) return "Adresse invalide";
        if (sh.equals(ph)) return "La preuve doit venir d'un canal officiel de l'auteur ou de l'éditeur, pas de la source elle-même.";
        List<String> channels = channels(ctx, title);
        if (channels.isEmpty()) {
            return "Aucun canal officiel connu pour cette série (ni AniList ni MangaDex n'en listent) : la preuve ne peut pas être vérifiée.";
        }
        if (officialChannelFor(proofUrl, channels) == null) {
            return "La preuve doit être publiée sur un canal officiel de la série : " + hostsOf(channels) + ".";
        }
        String html;
        try {
            html = http(proofUrl, 2_000_000);
        } catch (Exception e) {
            return "Impossible de lire la page de preuve (" + e.getMessage() + "). Les pages qui demandent une connexion"
                    + " ou s'affichent par script (X, Instagram…) ne peuvent pas être vérifiées.";
        }
        if (!mentions(html, sourceUrl, sh)) return "La page de preuve ne mentionne pas " + sh + ".";
        DbHelper.Edition e = edition(sourceUrl, SourceRegistry.name(sourceUrl), SourceRegistry.langFromUrl(sourceUrl),
                SourceRegistry.AUTHORIZED, "Mentionnée par " + ph, null);
        e.title = title;
        e.proof = proofUrl;
        e.checkedAt = System.currentTimeMillis();
        DbHelper.get(ctx).addUserEdition(e);
        return null;
    }

    /** La page cite-t-elle la source (son domaine ou son adresse) ? */
    static boolean mentions(String html, String sourceUrl, String sourceHost) {
        String t = html.toLowerCase(Locale.ROOT).replace("\\/", "/");
        return t.contains(sourceHost.toLowerCase(Locale.ROOT)) || t.contains(sourceUrl.toLowerCase(Locale.ROOT));
    }

    /**
     * Canal officiel qui couvre cette adresse de preuve, ou null. Pour un réseau social, la preuve
     * doit être une page du compte officiel lui-même (même premier segment d'adresse).
     */
    static String officialChannelFor(String proofUrl, List<String> channels) {
        String ph = SourceRegistry.host(proofUrl);
        if (ph == null) return null;
        for (String c : channels) {
            String ch = SourceRegistry.host(c);
            if (ch == null || !(ph.equals(ch) || ph.endsWith("." + ch))) continue;
            if (SourceRegistry.isSocial(ch) || ch.equals("webtoons.com")) {
                String acc = firstSegment(c), pp = firstSegment(proofUrl);
                if (ch.equals("webtoons.com")) {   // même série WEBTOON : même title_no
                    Matcher a = Pattern.compile("title_no=(\\d+)").matcher(c), b = Pattern.compile("title_no=(\\d+)").matcher(proofUrl);
                    if (!a.find() || !b.find() || !a.group(1).equals(b.group(1))) continue;
                } else if (acc.isEmpty() || !acc.equalsIgnoreCase(pp)) {
                    continue;
                }
            }
            return c;
        }
        return null;
    }

    private static String firstSegment(String url) {
        try {
            String path = new URI(url.trim()).getPath();
            if (path == null) return "";
            for (String s : path.split("/")) if (!s.isEmpty()) return s.replaceFirst("^@", "");
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String hostsOf(List<String> urls) {
        Set<String> hs = new java.util.LinkedHashSet<>();
        for (String u : urls) {
            String h = SourceRegistry.host(u);
            if (h != null) hs.add(h);
            if (hs.size() >= 5) break;
        }
        return android.text.TextUtils.join(", ", hs);
    }

    // ------------------------------------------------------------------ Canaux officiels (mémorisés)

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveChannels(Context ctx, String title, List<String> channels) {
        JSONArray a = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (String c : channels) if (c != null && seen.add(c)) a.put(c);
        prefs(ctx).edit().putString("ch_" + Titles.key(title), a.toString()).apply();
    }

    static List<String> channels(Context ctx, String title) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("ch_" + Titles.key(title), "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ Outils

    static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "").trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    static String http(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        boolean wt = SourceRegistry.isWebtoons(url);
        c.setRequestProperty("User-Agent", wt ? DESKTOP_UA : MOBILE_UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/rss+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "fr,en;q=0.8");
        if (wt) {
            c.setRequestProperty("Referer", "https://www.webtoons.com/");
            c.setRequestProperty("Cookie", "needGDPR=false; needCCPA=false; needCOPPA=false");
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
