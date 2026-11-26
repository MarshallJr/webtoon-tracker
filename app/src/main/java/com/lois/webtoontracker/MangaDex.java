package com.lois.webtoontracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deuxième source de la recherche « Découvrir » : le catalogue public de MangaDex (titres dans toutes les
 * langues, résumé, couverture, auteurs, genres), converti dans le modèle commun {@link Discover.Media}.
 * Lecture seule, sans compte.
 *
 * <p>Seules les fiches servent : ni chapitres ni liens vers les pages de lecture de MangaDex. « Où lire »
 * ne garde que les plateformes officielles connues de {@link SourceRegistry}.
 *
 * <p>Règles de l'API (api.mangadex.org/docs/2-limitations) : vrai User-Agent, ~5 requêtes/s au plus
 * (ici 1 ou 2 par page de résultats, espacées d'au moins 250 ms), pause dès un 429 ou un 403 (insister
 * mène au blocage de l'adresse IP), offset + limit ≤ 10 000, MangaDex crédité dans la fiche.
 */
final class MangaDex {

    private MangaDex() {
    }

    static final String API = "https://api.mangadex.org";
    static final String COVERS = "https://uploads.mangadex.org/covers/";
    static final int PAGE = 20;                        // fiches par page (AniList : 30)
    private static final int MAX_WINDOW = 10_000;      // offset + limit au plus
    private static final int CONNECT_MS = 5000, READ_MS = 7000, STATS_MS = 2500;   // la note est facultative
    private static final long SPACING_MS = 250;        // ≤ 4 requêtes/s
    private static final int MAX_BYTES = 3_000_000;
    private static final String UA = "WebtoonTracker/5.3 (Android; usage personnel)";
    private static final String INCLUDES = "includes%5B%5D=cover_art&includes%5B%5D=author&includes%5B%5D=artist";
    private static final Pattern UUID_RE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Object GATE = new Object();
    private static long lastCall, pausedUntil;

    /** Échec MangaDex : code HTTP (0 si réseau), message. */
    static final class Failure extends Exception {
        final int http;

        Failure(String message, int http) {
            super(message);
            this.http = http;
        }
    }

    /** Une page de résultats (déjà filtrée) et la position de la suivante. */
    static final class Result {
        final List<Discover.Media> items = new ArrayList<>();
        int total, nextOffset;
        boolean hasNext;
    }

    // ------------------------------------------------------------------ Correspondances

    /** Genres MangaDex → genres AniList : mêmes libellés français et mêmes filtres partout. */
    private static final Map<String, String> GENRES = new HashMap<>();
    /** Thèmes MangaDex au nom différent de leur équivalent AniList (les autres gardent leur nom). */
    private static final Map<String, String> TAGS = new HashMap<>();
    /** Formats gardés comme thèmes (AniList a les mêmes) ; les autres (Adaptation, Anthology…) sont ignorés. */
    private static final Set<String> KEPT_FORMATS = new HashSet<>();

    static {
        String[][] g = {{"Action", "Action"}, {"Adventure", "Adventure"}, {"Comedy", "Comedy"}, {"Drama", "Drama"},
                {"Fantasy", "Fantasy"}, {"Horror", "Horror"}, {"Mystery", "Mystery"}, {"Psychological", "Psychological"},
                {"Romance", "Romance"}, {"Sci-Fi", "Sci-Fi"}, {"Slice of Life", "Slice of Life"}, {"Sports", "Sports"},
                {"Thriller", "Thriller"}, {"Mecha", "Mecha"}, {"Magical Girls", "Mahou Shoujo"},
                {"Supernatural", "Supernatural"}, {"Music", "Music"}};
        for (String[] p : g) GENRES.put(p[0], p[1]);
        String[][] t = {{"School Life", "School"}, {"Office Workers", "Office"}, {"Zombies", "Zombie"},
                {"Medical", "Medicine"}, {"Philosophical", "Philosophy"}};
        for (String[] p : t) TAGS.put(p[0], p[1]);
        KEPT_FORMATS.add("Long Strip");
        KEPT_FORMATS.add("Web Comic");
        KEPT_FORMATS.add("Full Color");
        KEPT_FORMATS.add("Award Winning");
        KEPT_FORMATS.add("4-Koma");
    }

    /** Langue d'origine MangaDex → pays AniList (KR, JP, CN) ; null pour les autres. */
    static String country(String lang) {
        if (lang == null) return null;
        switch (lang) {
            case "ko":
                return "KR";
            case "ja":
                return "JP";
            case "zh":
            case "zh-hk":
                return "CN";
            default:
                return null;
        }
    }

    /** Statut MangaDex → statut AniList. */
    static String status(String s) {
        if (s == null) return null;
        switch (s) {
            case "ongoing":
                return "RELEASING";
            case "completed":
                return "FINISHED";
            case "hiatus":
                return "HIATUS";
            case "cancelled":
                return "CANCELLED";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ Recherche

    /** Adresse de la recherche : texte, pertinence, contenu adulte exclu, filtres que MangaDex sait appliquer. */
    static String searchUrl(Discover.Query q, int offset) {
        StringBuilder u = new StringBuilder(API).append("/manga?title=").append(enc(q.search == null ? "" : q.search.trim()))
                .append("&limit=").append(PAGE).append("&offset=").append(Math.max(0, offset))
                .append('&').append(INCLUDES)
                .append("&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive")
                .append("&order%5Brelevance%5D=desc");
        if ("KR".equals(q.country)) u.append("&originalLanguage%5B%5D=ko");
        else if ("JP".equals(q.country)) u.append("&originalLanguage%5B%5D=ja");
        else if ("CN".equals(q.country)) u.append("&originalLanguage%5B%5D=zh&originalLanguage%5B%5D=zh-hk");
        if ("RELEASING".equals(q.status)) u.append("&status%5B%5D=ongoing");
        else if ("FINISHED".equals(q.status)) u.append("&status%5B%5D=completed");
        return u.toString();
    }

    /**
     * Une page de recherche (2 requêtes au plus : les fiches, puis leurs notes en un seul appel groupé).
     * Les séries que tu lis déjà et celles qui ne passent pas tes filtres sont écartées.
     */
    static Result search(Discover.Query q, int offset, Discover.Profile me) throws Exception {
        if (q == null || !q.searching()) return new Result();
        Result r = parsePage(new JSONObject(get(searchUrl(q, offset), CONNECT_MS, READ_MS)), offset, q, me);
        if (!r.items.isEmpty()) {
            try {
                applyStats(r.items, new JSONObject(get(statsUrl(r.items), STATS_MS, STATS_MS)));
            } catch (Exception e) {
                // Note indisponible : les fiches restent, sans note (sauf si tu filtres par note, ci-dessous).
            }
            if (q.minScore > 0) r.items.removeIf(m -> m.score < q.minScore);
        }
        return r;
    }

    static Result parsePage(JSONObject res, int offset, Discover.Query q, Discover.Profile me) {
        Result r = new Result();
        JSONArray data = res == null ? null : res.optJSONArray("data");
        int at = res == null ? offset : res.optInt("offset", offset);
        int limit = res == null ? PAGE : Math.max(1, res.optInt("limit", PAGE));
        r.total = res == null ? 0 : res.optInt("total", 0);
        r.nextOffset = at + limit;
        r.hasNext = data != null && data.length() > 0 && r.nextOffset < r.total && r.nextOffset + PAGE <= MAX_WINDOW;
        if (data != null) for (int i = 0; i < data.length(); i++) {
            Discover.Media m = parse(data.optJSONObject(i));
            if (m == null || m.adult) continue;
            if (me != null && me.has(m)) continue;     // déjà lue (même id AniList ou même titre)
            if (q != null && !matches(m, q)) continue;
            r.items.add(m);
        }
        return r;
    }

    /**
     * Les filtres de Découvrir appliqués à une fiche MangaDex. Ce qu'on ne peut pas vérifier (année
     * inconnue, genre ou thème sans équivalent) exclut la fiche : on préfère moins de résultats que des
     * résultats faux.
     */
    static boolean matches(Discover.Media m, Discover.Query q) {
        if (q.country != null && !q.country.equals(m.country)) return false;
        if (q.status != null && !q.status.equals(m.status)) return false;
        for (String g : q.genres) if (!m.genres.contains(g)) return false;
        for (String t : q.tags) if (!m.tags.contains(t)) return false;
        if (q.fromYear > 0 || q.toYear > 0) {
            if (m.year <= 0) return false;
            if (q.fromYear > 0 && m.year < q.fromYear) return false;
            if (q.toYear > 0 && m.year > q.toYear) return false;
        }
        return true;
    }

    /** La fiche d'une série MangaDex (rouverte depuis « Ma liste à lire »). */
    static Discover.Media details(String uuid) throws Exception {
        if (uuid == null || !UUID_RE.matcher(uuid).matches()) return null;
        JSONObject res = new JSONObject(get(API + "/manga/" + uuid + "?" + INCLUDES, CONNECT_MS, READ_MS));
        return parse(res.optJSONObject("data"));
    }

    // ------------------------------------------------------------------ Conversion vers le modèle commun

    /** Une fiche MangaDex → {@link Discover.Media} ; null si illisible ou hors catalogue (adulte, doujinshi). */
    static Discover.Media parse(JSONObject d) {
        if (d == null) return null;
        String uuid = str(d, "id");
        JSONObject a = d.optJSONObject("attributes");
        if (uuid == null || !UUID_RE.matcher(uuid).matches() || a == null) return null;
        Discover.Media x = new Discover.Media();
        x.source = Discover.Source.MANGADEX;
        x.extId = uuid.toLowerCase(Locale.ROOT);
        x.full = true;   // la recherche renvoie déjà résumé, genres et liens

        // Titres : le principal et les alternatifs, toutes langues.
        Map<String, String> main = localized(a.opt("title"));
        List<String[]> alts = new ArrayList<>();   // {langue, titre}
        JSONArray at = a.optJSONArray("altTitles");
        if (at != null) for (int i = 0; i < at.length(); i++) {
            for (Map.Entry<String, String> e : localized(at.opt(i)).entrySet()) alts.add(new String[]{e.getKey(), e.getValue()});
        }
        Set<String> names = new LinkedHashSet<>(main.values());
        for (String[] p : alts) names.add(p[1]);
        x.names.addAll(names);
        String lang = str(a, "originalLanguage");
        // Comme pour AniList : le titre anglais, sinon le titre principal.
        x.title = first(main.get("en"), altIn(alts, "en"), firstValue(main), alts.isEmpty() ? null : alts.get(0)[1]);
        if (x.title == null) return null;
        if (lang != null) {
            x.alt = first(differs(main.get(lang + "-ro"), x.title), differs(altIn(alts, lang + "-ro"), x.title),
                    differs(main.get(lang), x.title), differs(altIn(alts, lang), x.title));
        }

        Map<String, String> desc = localized(a.opt("description"));
        x.description = cleanDescription(first(desc.get("fr"), desc.get("en"), firstValue(desc)));
        x.country = country(lang);
        x.status = status(str(a, "status"));
        x.year = a.isNull("year") ? 0 : Math.max(0, a.optInt("year", 0));
        String last = str(a, "lastChapter");
        if ("FINISHED".equals(x.status) && last != null) {
            try {
                x.chapters = (int) Math.floor(Double.parseDouble(last));
            } catch (NumberFormatException ignored) {
                // « Prologue », « 12.5 (fin) »… : on ne compte pas
            }
        }
        String rating = str(a, "contentRating");
        x.adult = "erotica".equals(rating) || "pornographic".equals(rating);

        // Genres et thèmes, rangés comme ceux d'AniList.
        boolean longStrip = false, oneShot = false, doujin = false;
        JSONArray tags = a.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            JSONObject t = tags.optJSONObject(i);
            JSONObject ta = t == null ? null : t.optJSONObject("attributes");
            String name = ta == null ? null : localized(ta.opt("name")).get("en");
            if (name == null) continue;
            if ("Long Strip".equals(name) || "Web Comic".equals(name)) longStrip = true;
            if ("Oneshot".equals(name)) oneShot = true;
            if ("Doujinshi".equals(name)) doujin = true;
            String genre = GENRES.get(name);
            if (genre != null) {
                if (!x.genres.contains(genre)) x.genres.add(genre);
                continue;
            }
            if ("format".equals(str(ta, "group")) && !KEPT_FORMATS.contains(name)) continue;
            String tag = TAGS.containsKey(name) ? TAGS.get(name) : name;
            if (!x.tags.contains(tag)) x.tags.add(tag);
        }
        if (doujin) return null;   // œuvres de fans : hors catalogue, comme sur AniList
        x.format = oneShot ? "ONE_SHOT" : "MANGA";
        x.webtoon = longStrip && x.country == null;   // KR, JP, CN restent manhwa, manga, manhua

        // Identifiants croisés (doublons certains) et liens de lecture officiels.
        Object lo = a.opt("links");
        if (lo instanceof JSONObject) {
            JSONObject l = (JSONObject) lo;
            int al = number(str(l, "al"));
            if (al > 0) {
                x.id = al;
                x.siteUrl = "https://anilist.co/manga/" + al;
            }
            x.malId = number(str(l, "mal"));
            addOfficial(x, str(l, "engtl"), "EN");
            addOfficial(x, str(l, "raw"), null);
        }
        if (x.id <= 0) x.id = syntheticId(x.extId);

        // Couverture (vignette 256 px, comme la « large » d'AniList) et auteurs.
        JSONArray rel = d.optJSONArray("relationships");
        if (rel != null) for (int i = 0; i < rel.length(); i++) {
            JSONObject r = rel.optJSONObject(i);
            JSONObject ra = r == null ? null : r.optJSONObject("attributes");
            if (ra == null) continue;
            String type = str(r, "type");
            if ("cover_art".equals(type) && x.cover == null) {
                String file = str(ra, "fileName");
                if (file != null && file.matches("[\\w.-]+")) x.cover = COVERS + x.extId + "/" + file + ".256.jpg";
            } else if ("author".equals(type) || "artist".equals(type)) {
                String n = str(ra, "name");
                if (n != null && !x.authors.contains(n)) x.authors.add(n);
            }
        }
        return x;
    }

    /** Lien officiel : seulement une plateforme de lecture connue (pas de réseau social ni de site inconnu). */
    private static void addOfficial(Discover.Media x, String url, String lang) {
        if (url == null || !url.matches("(?i)^https?://\\S+$")) return;
        SourceRegistry.Platform p = SourceRegistry.platform(url);
        if (p == null || SourceRegistry.isSocial(SourceRegistry.host(url))) return;
        String code = lang != null ? lang : p.lang == null ? null : p.lang.toUpperCase(Locale.ROOT);
        for (String[] l : x.links) if (l[1].equals(url)) return;
        x.links.add(new String[]{p.name + (code == null ? "" : " · " + code), url});
    }

    /**
     * Id stable pour une fiche absente d'AniList : négatif (jamais un id AniList), tiré de l'UUID.
     * Sert de clé à « Ma liste à lire » ; l'UUID complet reste dans {@code extId}.
     */
    static int syntheticId(String uuid) {
        long h;
        try {
            UUID u = UUID.fromString(uuid);
            h = u.getMostSignificantBits() ^ u.getLeastSignificantBits();
        } catch (IllegalArgumentException e) {
            h = uuid.hashCode();
        }
        int v = (int) (h ^ (h >>> 32)) & 0x7fffffff;
        return -1 - v;
    }

    // ------------------------------------------------------------------ Notes

    static String statsUrl(List<Discover.Media> items) {
        StringBuilder u = new StringBuilder(API).append("/statistics/manga?");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) u.append('&');
            u.append("manga%5B%5D=").append(items.get(i).extId);
        }
        return u.toString();
    }

    /** Note bayésienne MangaDex (sur 10) → note sur 100 comme AniList ; abonnés → popularité. */
    static void applyStats(List<Discover.Media> items, JSONObject res) {
        JSONObject st = res == null ? null : res.optJSONObject("statistics");
        if (st == null) return;
        for (Discover.Media m : items) {
            JSONObject s = m.extId == null ? null : st.optJSONObject(m.extId);
            if (s == null) continue;
            JSONObject r = s.optJSONObject("rating");
            double b = r == null || r.isNull("bayesian") ? 0 : r.optDouble("bayesian", 0);
            if (b > 0 && !Double.isNaN(b)) m.score = (int) Math.round(Math.min(10, b) * 10);
            if (!s.isNull("follows")) m.popularity = Math.max(0, s.optInt("follows", 0));
        }
    }

    // ------------------------------------------------------------------ Réseau

    /** MangaDex en pause après un refus (429/403) ? */
    static boolean paused() {
        synchronized (GATE) {
            return System.currentTimeMillis() < pausedUntil;
        }
    }

    static String get(String url, int connectMs, int readMs) throws Exception {
        synchronized (GATE) {   // une requête à la fois, espacées : on reste loin des 5 requêtes/s
            long now = System.currentTimeMillis();
            if (now < pausedUntil) throw new Failure("MangaDex en pause (trop de demandes)", 429);
            long wait = lastCall + SPACING_MS - now;
            if (wait > 0) Thread.sleep(wait);
            lastCall = System.currentTimeMillis();
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 429 || code == 403) {
                pause(code, c.getHeaderField("X-RateLimit-Retry-After"));
                throw new Failure("MangaDex HTTP " + code, code);
            }
            if (code < 200 || code >= 300) throw new Failure("MangaDex HTTP " + code, code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (bo.size() + n > MAX_BYTES) throw new Failure("Réponse MangaDex trop grande", code);
                    bo.write(buf, 0, n);
                }
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 429 : silence jusqu'à la fin de la période (1 min au moins) ; 403 (blocage) : 15 min. */
    static void pause(int code, String retryAfter) {
        long now = System.currentTimeMillis();
        long until = now + (code == 403 ? 15 * 60_000L : 60_000L);
        if (retryAfter != null) {
            try {
                long t = Long.parseLong(retryAfter.trim()) * 1000L;   // horodatage UNIX en secondes
                if (t > until && t < now + 3_600_000L) until = t;
            } catch (NumberFormatException ignored) {
                // en-tête illisible : la durée par défaut suffit
            }
        }
        synchronized (GATE) {
            pausedUntil = Math.max(pausedUntil, until);
        }
    }

    /** Pour les tests : lève la pause. */
    static void resetPause() {
        synchronized (GATE) {
            pausedUntil = 0;
        }
    }

    // ------------------------------------------------------------------ Outils

    /** Résumé MangaDex (Markdown) → texte : sans liens, sans mise en forme, sans les notes après « --- ». */
    static String cleanDescription(String s) {
        if (s == null) return null;
        String t = s.replace("\r", "");
        Matcher hr = Pattern.compile("(?m)^[ \\t]*(?:[-_*][ \\t]*){3,}$").matcher(t);
        if (hr.find() && hr.start() >= 40) t = t.substring(0, hr.start());
        t = t.replaceAll("\\[([^\\]]*)]\\((?:https?://|/)[^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("\\*\\*|__|~~", "");
        t = t.replaceAll("(?<![\\w*])\\*(\\S[^*\\n]*?)\\*(?![\\w*])", "$1");
        t = t.replaceAll("(?m)^[ \\t]*(?:#+|>)[ \\t]*", "");
        return Discover.clean(t);
    }

    /** Texte localisé ({"en": "…", "ko-ro": "…"}) → langue → texte ; MangaDex renvoie [] quand c'est vide. */
    static Map<String, String> localized(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(o instanceof JSONObject)) return out;
        JSONObject j = (JSONObject) o;
        Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = str(j, k);
            if (v != null && !v.trim().isEmpty()) out.put(k, v.trim());
        }
        return out;
    }

    private static String altIn(List<String[]> alts, String lang) {
        for (String[] p : alts) if (p[0].equals(lang)) return p[1];
        return null;
    }

    private static String firstValue(Map<String, String> m) {
        return m.isEmpty() ? null : m.values().iterator().next();
    }

    private static String differs(String s, String than) {
        return s == null || s.equalsIgnoreCase(than) ? null : s;
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private static int number(String s) {
        if (s == null || !s.trim().matches("\\d{1,9}")) return 0;
        return Integer.parseInt(s.trim());
    }

    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        Object v = o.opt(k);
        if (!(v instanceof String)) return v instanceof Number ? String.valueOf(v) : null;
        String s = ((String) v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";   // UTF-8 existe toujours
        }
    }
}
