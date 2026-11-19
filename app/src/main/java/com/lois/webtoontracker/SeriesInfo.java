package com.lois.webtoontracker;

import android.content.Context;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fiche détaillée d'une série : résumé (FR via MangaDex si dispo, sinon EN via AniList),
 * auteurs, année, statut, note, nombre de chapitres, recommandations.
 * Téléchargée une fois puis gardée en base (rafraîchie au plus 1 fois par semaine).
 */
public final class SeriesInfo {

    public static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    public String altTitle, summary, summarySource, status, country, authors, siteUrl;
    public int year, chapters, score;
    public final List<String[]> recos = new ArrayList<>(); // {titre, url de couverture}
    public final List<String> genres = new ArrayList<>();

    // ------------------------------------------------------------------ Téléchargement

    /** Télécharge et met en cache. Appelé hors du fil principal. */
    public static SeriesInfo fetch(Context ctx, String title) throws Exception {
        JSONObject store = new JSONObject();

        JSONObject vars = new JSONObject().put("s", title);
        String q = "query($s:String){Media(search:$s,type:MANGA){siteUrl title{romaji english native}"
                + " description(asHtml:false) status startDate{year} chapters averageScore genres countryOfOrigin"
                + " staff(perPage:4){edges{role node{name{full}}}}"
                + " recommendations(perPage:8,sort:RATING_DESC){nodes{mediaRecommendation{title{romaji english} coverImage{large}}}}}}";
        try {
            String res = ReleaseSources.post("https://graphql.anilist.co",
                    new JSONObject().put("query", q).put("variables", vars).toString());
            JSONObject data = new JSONObject(res).optJSONObject("data");
            JSONObject media = data == null ? null : data.optJSONObject("Media");
            if (media != null) store.put("anilist", media);
        } catch (Exception ignored) {
        }

        // Résumé en français : MangaDex en a souvent un.
        try {
            DbHelper db = DbHelper.get(ctx);
            DbHelper.Meta m = db.getMeta(title);
            String id = m == null ? null : m.mdxId;
            if (id == null) {
                id = ReleaseSources.mangadexFind(title);
                db.setSourceIds(title, id, null);
            }
            if (id != null && !ReleaseSources.NOT_FOUND.equals(id)) {
                JSONObject attrs = new JSONObject(ReleaseSources.get("https://api.mangadex.org/manga/" + id))
                        .optJSONObject("data").optJSONObject("attributes");
                JSONObject desc = attrs == null ? null : attrs.optJSONObject("description");
                if (desc != null) {
                    store.put("descFr", desc.optString("fr", ""));
                    store.put("descEn", desc.optString("en", ""));
                }
            }
        } catch (Exception ignored) {
        }

        if (store.length() == 0) return null;
        DbHelper.get(ctx).setDetails(title, store.toString());
        return parse(store.toString());
    }

    // ------------------------------------------------------------------ Lecture du cache

    public static SeriesInfo parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject store = new JSONObject(json);
            SeriesInfo i = new SeriesInfo();
            JSONObject a = store.optJSONObject("anilist");

            String fr = clean(store.optString("descFr", ""));
            String mdxEn = clean(store.optString("descEn", ""));
            String aniEn = a == null ? "" : clean(a.optString("description", ""));
            if (!fr.isEmpty()) {
                i.summary = fr;
                i.summarySource = "Résumé · MangaDex (FR)";
            } else if (!aniEn.isEmpty()) {
                i.summary = aniEn;
                i.summarySource = "Résumé · AniList (en anglais)";
            } else if (!mdxEn.isEmpty()) {
                i.summary = mdxEn;
                i.summarySource = "Résumé · MangaDex (en anglais)";
            }

            if (a != null) {
                JSONObject t = a.optJSONObject("title");
                if (t != null) {
                    String en = t.optString("english", ""), ro = t.optString("romaji", ""), na = t.optString("native", "");
                    i.altTitle = join(" · ", nn(en), nn(ro), nn(na));
                }
                i.status = status(a.optString("status"));
                JSONObject sd = a.optJSONObject("startDate");
                i.year = sd == null ? 0 : sd.optInt("year", 0);
                i.chapters = a.optInt("chapters", 0);
                i.score = a.optInt("averageScore", 0);
                i.country = country(a.optString("countryOfOrigin"));
                i.siteUrl = nn(a.optString("siteUrl", ""));
                JSONArray g = a.optJSONArray("genres");
                if (g != null) for (int k = 0; k < g.length(); k++) i.genres.add(g.optString(k));

                JSONObject staff = a.optJSONObject("staff");
                JSONArray edges = staff == null ? null : staff.optJSONArray("edges");
                if (edges != null) {
                    List<String> names = new ArrayList<>();
                    for (int k = 0; k < edges.length(); k++) {
                        JSONObject e = edges.optJSONObject(k);
                        if (e == null) continue;
                        String name = e.optJSONObject("node") == null ? "" :
                                e.optJSONObject("node").optJSONObject("name").optString("full", "");
                        String role = roleFr(e.optString("role", ""));
                        if (!name.isEmpty() && !names.contains(name + role)) names.add(name + role);
                    }
                    i.authors = String.join(", ", names);
                }

                JSONObject rec = a.optJSONObject("recommendations");
                JSONArray nodes = rec == null ? null : rec.optJSONArray("nodes");
                if (nodes != null) {
                    for (int k = 0; k < nodes.length(); k++) {
                        JSONObject mr = nodes.optJSONObject(k) == null ? null
                                : nodes.optJSONObject(k).optJSONObject("mediaRecommendation");
                        if (mr == null) continue;
                        JSONObject rt = mr.optJSONObject("title");
                        String name = rt == null ? "" : nn(rt.optString("english", ""));
                        if (name == null || name.isEmpty()) name = rt == null ? "" : rt.optString("romaji", "");
                        String cover = mr.optJSONObject("coverImage") == null ? null
                                : mr.optJSONObject("coverImage").optString("large", null);
                        if (!name.isEmpty()) i.recos.add(new String[]{name, cover});
                    }
                }
            }
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Helpers

    private static String clean(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return "";
        String s = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString();
        s = s.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");          // liens markdown → texte
        s = s.replaceAll("(?s)\\n-{3,}.*$", "");                       // pied de page MangaDex
        s = s.replaceAll("(?i)\\(source:[^)]*\\)", "");
        s = s.replaceAll("[*_]{1,2}", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String nn(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !out.contains(p)) out.add(p);
        return String.join(sep, out);
    }

    private static String status(String s) {
        switch (s == null ? "" : s) {
            case "RELEASING": return "En cours";
            case "FINISHED": return "Terminé";
            case "HIATUS": return "En pause";
            case "CANCELLED": return "Annulé";
            case "NOT_YET_RELEASED": return "À venir";
            default: return null;
        }
    }

    private static String country(String c) {
        switch (c == null ? "" : c) {
            case "KR": return "🇰🇷 Manhwa";
            case "CN": return "🇨🇳 Manhua";
            case "JP": return "🇯🇵 Manga";
            case "TW": return "🇹🇼 Manhua";
            default: return null;
        }
    }

    private static String roleFr(String role) {
        String r = role.toLowerCase();
        if (r.contains("story") && r.contains("art")) return " (scénario & dessin)";
        if (r.contains("story") || r.contains("original")) return " (scénario)";
        if (r.contains("art")) return " (dessin)";
        return "";
    }
}
