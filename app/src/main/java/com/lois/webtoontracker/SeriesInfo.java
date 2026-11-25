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

    /** Tous les champs AniList utiles (couverture, tags, fiche, recommandations…), en une requête. */
    static final String MEDIA_FIELDS = "id siteUrl title{romaji english native} synonyms description(asHtml:false)"
            + " status startDate{year} chapters averageScore genres countryOfOrigin coverImage{extraLarge large}"
            + " tags{name rank isMediaSpoiler} externalLinks{url site type language isDisabled}"
            + " staff(perPage:4){edges{role node{name{full}}}}"
            + " recommendations(perPage:10,sort:RATING_DESC){nodes{mediaRecommendation{siteUrl averageScore"
            + " countryOfOrigin title{romaji english} coverImage{large} externalLinks{url site}}}}";

    /** Une recommandation : titre, couverture, note publique, liens où la lire. */
    public static final class Reco {
        public String title, cover, siteUrl, country;
        public int score;
        public final List<String[]> links = new ArrayList<>(); // {nom du site, url}
    }

    /**
     * Cherche la série sur AniList et ne garde QUE un résultat dont un des noms
     * (anglais, romanisé, original, synonymes) correspond vraiment au titre. Sinon null.
     */
    public static JSONObject searchMedia(String title) throws Exception {
        String q = "query($s:String){Page(perPage:5){media(search:$s,type:MANGA,sort:SEARCH_MATCH){" + MEDIA_FIELDS + "}}}";
        String res = ReleaseSources.post("https://graphql.anilist.co", new JSONObject().put("query", q)
                .put("variables", new JSONObject().put("s", title)).toString());
        JSONObject data = new JSONObject(res).optJSONObject("data");
        JSONObject page = data == null ? null : data.optJSONObject("Page");
        JSONArray list = page == null ? null : page.optJSONArray("media");
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m != null && matches(title, m)) return m;
        }
        return null;
    }

    public static boolean matches(String title, JSONObject media) {
        for (String n : names(media)) if (ReleaseSources.sameTitle(title, n)) return true;
        return false;
    }

    /** Tous les noms officiels d'une série AniList. */
    public static List<String> names(JSONObject media) {
        List<String> out = new ArrayList<>();
        JSONObject t = media.optJSONObject("title");
        if (t != null) for (String k : new String[]{"english", "romaji", "native"}) {
            String v = nn(t.optString(k, ""));
            if (v != null) out.add(v);
        }
        JSONArray syn = media.optJSONArray("synonyms");
        if (syn != null) for (int i = 0; i < syn.length(); i++) {
            String v = nn(syn.optString(i, ""));
            if (v != null) out.add(v);
        }
        return out;
    }

    /** Titre "propre" à afficher : l'anglais officiel, sinon le romanisé. */
    public static String preferredTitle(JSONObject media) {
        JSONObject t = media.optJSONObject("title");
        if (t == null) return null;
        String en = nn(t.optString("english", ""));
        return en != null ? en : nn(t.optString("romaji", ""));
    }

    public String altTitle, summary, summarySource, status, country, authors, siteUrl;
    public int year, chapters, score;
    public final List<Reco> recos = new ArrayList<>();
    public final List<String[]> credits = new ArrayList<>();   // {nom, rôle}
    public final List<String> altTitles = new ArrayList<>();
    public String format, origin;                               // "Manhua", "Chine"
    public final List<String> genres = new ArrayList<>();

    // ------------------------------------------------------------------ Téléchargement

    /** Télécharge et met en cache. Appelé hors du fil principal. */
    public static SeriesInfo fetch(Context ctx, String title) throws Exception {
        JSONObject store = new JSONObject();

        try {
            JSONObject media = searchMedia(title);
            if (media != null) {
                store.put("anilist", media);
                int sc = media.optInt("averageScore", 0);
                if (sc > 0) DbHelper.get(ctx).setScore(title, sc);
                List<String> names = names(media);
                DbHelper.get(ctx).addAliases(title, names);
            }
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
                String cc = a.optString("countryOfOrigin");
                i.format = "KR".equals(cc) ? "Manhwa" : "CN".equals(cc) || "TW".equals(cc) ? "Manhua" : "JP".equals(cc) ? "Manga" : null;
                i.origin = "KR".equals(cc) ? "Corée du Sud" : "CN".equals(cc) ? "Chine" : "TW".equals(cc) ? "Taïwan"
                        : "JP".equals(cc) ? "Japon" : null;
                for (String n : names(a)) if (!n.equalsIgnoreCase(preferredTitle(a) == null ? "" : preferredTitle(a))) i.altTitles.add(n);
                i.siteUrl = nn(a.optString("siteUrl", ""));
                JSONArray g = a.optJSONArray("genres");
                if (g != null) for (int k = 0; k < g.length(); k++) i.genres.add(AutoTags.genreFr(g.optString(k)));

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
                        if (!name.isEmpty() && !names.contains(name + role)) {
                            names.add(name + role);
                            String r = role.replace(" (", "").replace(")", "").trim();
                            i.credits.add(new String[]{name, r.isEmpty() ? "Auteur" : cap(r)});
                        }
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
                        Reco r = new Reco();
                        r.title = preferredTitle(mr);
                        if (r.title == null) continue;
                        r.cover = mr.optJSONObject("coverImage") == null ? null
                                : nn(mr.optJSONObject("coverImage").optString("large", ""));
                        r.score = mr.optInt("averageScore", 0);
                        r.siteUrl = nn(mr.optString("siteUrl", ""));
                        r.country = country(mr.optString("countryOfOrigin"));
                        JSONArray links = mr.optJSONArray("externalLinks");
                        if (links != null) for (int x = 0; x < links.length(); x++) {
                            JSONObject l = links.optJSONObject(x);
                            if (l == null) continue;
                            String site = l.optString("site", ""), url = l.optString("url", "");
                            if (!url.isEmpty() && isReadingSite(site)) r.links.add(new String[]{site, url});
                        }
                        i.recos.add(r);
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

    /** On garde les plateformes de lecture, pas les réseaux sociaux. */
    static boolean isReadingSite(String site) {
        String s = site.toLowerCase();
        for (String bad : new String[]{"twitter", "instagram", "youtube", "tiktok", "facebook", "x.com", "weibo", "discord", "reddit"}) {
            if (s.contains(bad)) return false;
        }
        return true;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String nn(String s) {
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

    static String country(String c) {
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
