package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tous les noms d'une série, dans toutes les langues, et ses auteurs.
 *
 * Une même série s'appelle « Reincarnated With Divine Titles » sur un site de scans et
 * « Réincarné avec des Titres Divins » sur WEBTOON France. Pour que les bases (AniList, MangaDex,
 * MangaUpdates) et WEBTOON la reconnaissent, on rassemble ses noms connus — titres officiels et
 * alternatifs, avec leur langue quand elle est connue — et on compare les titres avec
 * {@link TitleMatch} (mots apparentés, dans n'importe quel ordre), pas lettre à lettre.
 *
 * Chaque nom trouvé devient aussi un alias : lire la série sous ce nom (appli WEBTOON en français…)
 * l'ajoute à la même fiche.
 */
final class SeriesNames {

    private SeriesNames() {
    }

    private static final String PREFS = "series_names";
    private static final long GATHER_MS = 7L * 24 * 3600 * 1000;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject load(Context ctx, String title) {
        try {
            return new JSONObject(prefs(ctx).getString(Titles.key(title), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context ctx, String title, JSONObject o) {
        prefs(ctx).edit().putString(Titles.key(title), o.toString()).apply();
    }

    /** Noms connus (le titre de l'appli d'abord) : {nom, langue ou null}. */
    static List<String[]> named(Context ctx, String title) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{title, null});
        JSONArray a = load(ctx, title).optJSONArray("names");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject n = a.optJSONObject(i);
            if (n == null) continue;
            String t = n.optString("t", "").trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase(title)) out.add(new String[]{t, n.optString("l", "").isEmpty() ? null : n.optString("l")});
        }
        return out;
    }

    static List<String> names(Context ctx, String title) {
        List<String> out = new ArrayList<>();
        for (String[] n : named(ctx, title)) out.add(n[0]);
        return out;
    }

    static List<String> authors(Context ctx, String title) {
        List<String> out = new ArrayList<>();
        JSONArray a = load(ctx, title).optJSONArray("authors");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Ajoute un nom (et son alias en base). @return true s'il est nouveau */
    static boolean add(Context ctx, String title, String name, String lang, String source) {
        if (name == null) return false;
        name = name.replaceAll("\\s+", " ").trim();
        if (name.length() < 2 || name.length() > 160) return false;
        JSONObject o = load(ctx, title);
        JSONArray a = o.optJSONArray("names");
        if (a == null) a = new JSONArray();
        String k = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < a.length(); i++) {
            JSONObject n = a.optJSONObject(i);
            if (n != null && n.optString("t", "").toLowerCase(Locale.ROOT).equals(k)) {
                if ((n.optString("l", "").isEmpty()) && lang != null) {
                    try {
                        n.put("l", lang);
                        save(ctx, title, o);
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }
        }
        if (k.equals(title.toLowerCase(Locale.ROOT))) return false;
        try {
            a.put(new JSONObject().put("t", name).put("l", lang == null ? "" : SourceRegistry.normalize(lang)).put("s", source));
            o.put("names", a);
            save(ctx, title, o);
        } catch (Exception ignored) {
        }
        List<String> one = new ArrayList<>();
        one.add(name);
        DbHelper.get(ctx).addAliases(title, one);
        return true;
    }

    static void addAuthors(Context ctx, String title, Collection<String> names) {
        if (names == null || names.isEmpty()) return;
        JSONObject o = load(ctx, title);
        Set<String> all = new LinkedHashSet<>(authors(ctx, title));
        for (String n : names) if (n != null && !n.trim().isEmpty() && n.trim().length() <= 60) all.add(n.trim());
        try {
            o.put("authors", new JSONArray(new ArrayList<>(all)));
            save(ctx, title, o);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Bases de référence

    /**
     * Cherche la série dans MangaDex, AniList et MangaUpdates avec TOUS ses noms connus, et garde
     * les noms (avec leur langue) et auteurs du résultat qui correspond vraiment (une fois par semaine,
     * sauf si force).
     * @return true si de nouveaux noms ont été trouvés
     */
    static boolean gather(Context ctx, String title, boolean force) {
        JSONObject o = load(ctx, title);
        long now = System.currentTimeMillis();
        if (!force && now - o.optLong("at", 0) < GATHER_MS) return false;
        try {
            o.put("at", now);
            save(ctx, title, o);
        } catch (Exception ignored) {
        }
        boolean any = false;
        try {
            any |= fromMangaDex(ctx, title);
        } catch (Exception ignored) {
        }
        try {
            any |= fromAniList(ctx, title);
        } catch (Exception ignored) {
        }
        try {
            any |= fromMangaUpdates(ctx, title);
        } catch (Exception ignored) {
        }
        return any;
    }

    /** Requêtes : le titre puis les autres noms (3 au plus). */
    private static List<String> queries(Context ctx, String title) {
        List<String> q = new ArrayList<>();
        for (String n : names(ctx, title)) {
            if (q.size() >= 3) break;
            if (!q.contains(n)) q.add(n);
        }
        return q;
    }

    private static boolean fromMangaDex(Context ctx, String title) throws Exception {
        List<String> refs = names(ctx, title);
        for (String q : queries(ctx, title)) {
            JSONObject res = new JSONObject(ReleaseSources.get("https://api.mangadex.org/manga?limit=10&title="
                    + URLEncoder.encode(q, "UTF-8") + "&includes%5B%5D=author&includes%5B%5D=artist"
                    + "&order%5Brelevance%5D=desc"));
            JSONArray data = res.optJSONArray("data");
            if (data == null) continue;
            JSONObject best = null;
            double bestScore = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                JSONObject attrs = m == null ? null : m.optJSONObject("attributes");
                if (attrs == null) continue;
                double s = 0;
                for (String[] n : mdxNames(attrs)) s = Math.max(s, TitleMatch.best(n[0], refs));
                if (s > bestScore) {
                    bestScore = s;
                    best = m;
                }
            }
            if (best == null || bestScore < TitleMatch.SAME) continue;
            boolean any = false;
            JSONObject attrs = best.optJSONObject("attributes");
            for (String[] n : mdxNames(attrs)) any |= add(ctx, title, n[0], n[1], "MangaDex");
            List<String> people = new ArrayList<>();
            JSONArray rel = best.optJSONArray("relationships");
            if (rel != null) for (int i = 0; i < rel.length(); i++) {
                JSONObject r = rel.optJSONObject(i);
                if (r == null || !r.optString("type").matches("author|artist")) continue;
                JSONObject ra = r.optJSONObject("attributes");
                if (ra != null && !ra.optString("name", "").isEmpty()) people.add(ra.optString("name"));
            }
            addAuthors(ctx, title, people);
            DbHelper.Meta meta = DbHelper.get(ctx).getMeta(title);
            if (meta == null || meta.mdxId == null || ReleaseSources.NOT_FOUND.equals(meta.mdxId)) {
                DbHelper.get(ctx).setSourceIds(title, best.optString("id"), null);
            }
            return any;
        }
        return false;
    }

    /** Titres MangaDex avec leur langue : {"fr": "Réincarné…"}, {"ko": "…"}… */
    static List<String[]> mdxNames(JSONObject attrs) {
        List<String[]> out = new ArrayList<>();
        JSONObject t = attrs.optJSONObject("title");
        if (t != null) for (java.util.Iterator<String> it = t.keys(); it.hasNext(); ) {
            String l = it.next();
            out.add(new String[]{t.optString(l), langOf(l)});
        }
        JSONArray alts = attrs.optJSONArray("altTitles");
        if (alts != null) for (int i = 0; i < alts.length(); i++) {
            JSONObject a = alts.optJSONObject(i);
            if (a == null) continue;
            for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
                String l = it.next();
                out.add(new String[]{a.optString(l), langOf(l)});
            }
        }
        return out;
    }

    /** « ko-ro », « ja-ro » : romanisation, sans langue d'édition. */
    private static String langOf(String mdx) {
        if (mdx == null || mdx.endsWith("-ro")) return null;
        return SourceRegistry.normalize(mdx);
    }

    private static boolean fromAniList(Context ctx, String title) throws Exception {
        JSONObject media = SeriesInfo.searchMedia(title, names(ctx, title));
        if (media == null) return false;
        boolean any = false;
        String origin = SourceRegistry.originLang(media.optString("countryOfOrigin", null));
        JSONObject t = media.optJSONObject("title");
        if (t != null) {
            any |= add(ctx, title, SeriesInfo.nn(t.optString("english", "")), "en", "AniList");
            any |= add(ctx, title, SeriesInfo.nn(t.optString("romaji", "")), null, "AniList");
            any |= add(ctx, title, SeriesInfo.nn(t.optString("native", "")), origin, "AniList");
        }
        JSONArray syn = media.optJSONArray("synonyms");
        if (syn != null) for (int i = 0; i < syn.length(); i++) {
            String s = SeriesInfo.nn(syn.optString(i, ""));
            if (s != null) any |= add(ctx, title, s, TitleMatch.guessLang(s), "AniList");
        }
        List<String> people = new ArrayList<>();
        JSONObject staff = media.optJSONObject("staff");
        JSONArray edges = staff == null ? null : staff.optJSONArray("edges");
        if (edges != null) for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            JSONObject node = e == null ? null : e.optJSONObject("node");
            JSONObject name = node == null ? null : node.optJSONObject("name");
            if (name != null && !name.optString("full", "").isEmpty()) people.add(name.optString("full"));
        }
        addAuthors(ctx, title, people);
        return any;
    }

    private static boolean fromMangaUpdates(Context ctx, String title) throws Exception {
        List<String> refs = names(ctx, title);
        for (String q : queries(ctx, title)) {
            JSONObject body = new JSONObject().put("search", q).put("perpage", 8);
            JSONArray results = new JSONObject(ReleaseSources.post("https://api.mangaupdates.com/v1/series/search",
                    body.toString())).optJSONArray("results");
            if (results == null) continue;
            String id = null;
            double bestScore = 0;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                JSONObject rec = r == null ? null : r.optJSONObject("record");
                if (rec == null) continue;
                double s = Math.max(TitleMatch.best(rec.optString("title", ""), refs), TitleMatch.best(r.optString("hit_title", ""), refs));
                if (s > bestScore) {
                    bestScore = s;
                    id = String.valueOf(rec.optLong("series_id"));
                }
            }
            if (id == null || bestScore < TitleMatch.SAME) continue;
            JSONObject series = new JSONObject(ReleaseSources.get("https://api.mangaupdates.com/v1/series/" + id));
            boolean any = add(ctx, title, series.optString("title", ""), TitleMatch.guessLang(series.optString("title", "")), "MangaUpdates");
            JSONArray assoc = series.optJSONArray("associated");
            if (assoc != null) for (int i = 0; i < assoc.length(); i++) {
                JSONObject a = assoc.optJSONObject(i);
                String n = a == null ? null : SeriesInfo.nn(a.optString("title", ""));
                if (n != null) any |= add(ctx, title, n, TitleMatch.guessLang(n), "MangaUpdates");
            }
            List<String> people = new ArrayList<>();
            JSONArray au = series.optJSONArray("authors");
            if (au != null) for (int i = 0; i < au.length(); i++) {
                JSONObject a = au.optJSONObject(i);
                if (a != null && !a.optString("name", "").isEmpty()) people.add(a.optString("name"));
            }
            addAuthors(ctx, title, people);
            DbHelper.Meta meta = DbHelper.get(ctx).getMeta(title);
            if (meta == null || meta.muId == null || ReleaseSources.NOT_FOUND.equals(meta.muId)) {
                DbHelper.get(ctx).setSourceIds(title, null, id);
            }
            return any;
        }
        return false;
    }
}
