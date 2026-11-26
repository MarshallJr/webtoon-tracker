package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « Découvrir » : chercher des séries sur AniList (tri par note, popularité, tendance, date ; filtres
 * genre, thème, type, statut, note, période) et des recommandations d'après ce que tu lis.
 * Les séries que tu lis déjà sont écartées. Source : l'API publique d'AniList, rien d'autre.
 */
final class Discover {

    private Discover() {
    }

    static final String ANILIST = "https://graphql.anilist.co";

    /** Champs d'une carte (grille, carrousels). */
    static final String CARD = "id siteUrl title{romaji english native} synonyms averageScore popularity genres"
            + " countryOfOrigin format status startDate{year} chapters coverImage{large color} isAdult";

    /** Champs de la fiche complète. */
    static final String FULL = CARD + " description(asHtml:false) tags{name rank isMediaSpoiler}"
            + " externalLinks{url site type language isDisabled}";

    /** Tris proposés (libellé, valeur AniList). */
    static final String[][] SORTS = {
            {"Mieux notés", "SCORE_DESC"},
            {"Populaires", "POPULARITY_DESC"},
            {"Tendances", "TRENDING_DESC"},
            {"Plus récents", "START_DATE_DESC"},
            {"Coups de cœur", "FAVOURITES_DESC"}};

    /** Les genres AniList (hors contenu adulte), avec leur nom en français. */
    static final String[][] GENRES = {
            {"Action", "Action"}, {"Adventure", "Aventure"}, {"Comedy", "Comédie"}, {"Drama", "Drame"},
            {"Fantasy", "Fantasy"}, {"Horror", "Horreur"}, {"Mystery", "Mystère"}, {"Psychological", "Psychologique"},
            {"Romance", "Romance"}, {"Sci-Fi", "Science-fiction"}, {"Slice of Life", "Tranche de vie"},
            {"Sports", "Sport"}, {"Supernatural", "Surnaturel"}, {"Thriller", "Thriller"}, {"Mecha", "Mecha"},
            {"Music", "Musique"}, {"Mahou Shoujo", "Magical girl"}, {"Ecchi", "Ecchi"}};

    /** Quelques thèmes AniList courants en français (les autres restent en anglais). */
    private static final Map<String, String> TAGS_FR = new HashMap<>();

    static {
        String[][] t = {{"Cultivation", "Cultivation"}, {"Reincarnation", "Réincarnation"}, {"Isekai", "Isekai"},
                {"Martial Arts", "Arts martiaux"}, {"Male Protagonist", "Héros masculin"},
                {"Female Protagonist", "Héroïne"}, {"Revenge", "Vengeance"}, {"Time Manipulation", "Voyage dans le temps"},
                {"Super Power", "Super-pouvoirs"}, {"Magic", "Magie"}, {"Swordplay", "Combat à l'épée"},
                {"Dungeon", "Donjons"}, {"Video Games", "Jeux vidéo"}, {"Full Color", "En couleurs"},
                {"Web Comic", "Webcomic"}, {"Long Strip", "Défilement vertical"}, {"Demons", "Démons"},
                {"Gods", "Dieux"}, {"Monsters", "Monstres"}, {"Survival", "Survie"}, {"School", "École"},
                {"Office", "Bureau"}, {"Historical", "Historique"}, {"Royal Affairs", "Royauté"},
                {"Tragedy", "Tragédie"}, {"Anti-Hero", "Anti-héros"}, {"Villainess", "Méchante"},
                {"Necromancy", "Nécromancie"}, {"Assassins", "Assassins"}, {"Politics", "Politique"},
                {"Wuxia", "Wuxia"}, {"Xianxia", "Xianxia"}, {"Urban Fantasy", "Fantasy urbaine"},
                {"Post-Apocalyptic", "Post-apocalyptique"}, {"Zombie", "Zombies"}, {"Medicine", "Médecine"},
                {"Cooking", "Cuisine"}, {"Family Life", "Vie de famille"}, {"Delinquents", "Voyous"},
                {"Skeleton", "Squelette"}, {"Rehabilitation", "Rédemption"}, {"Kingdom Management", "Gestion de royaume"},
                {"Body Swapping", "Échange de corps"}, {"Love Triangle", "Triangle amoureux"}, {"Military", "Militaire"},
                {"Mythology", "Mythologie"}, {"Philosophy", "Philosophie"}, {"Primarily Adult Cast", "Personnages adultes"}};
        for (String[] p : t) TAGS_FR.put(p[0], p[1]);
    }

    static String genreFr(String g) {
        for (String[] p : GENRES) if (p[0].equals(g)) return p[1];
        return g;
    }

    static String tagFr(String t) {
        String fr = TAGS_FR.get(t);
        return fr == null ? t : fr;
    }

    // ------------------------------------------------------------------ Série

    /** Une série AniList : carte de la grille, puis fiche complète. */
    static final class Media {
        int id, score, popularity, year, chapters;
        String title, alt, cover, color, siteUrl, status, country, format, description, because;
        boolean adult, full;
        final List<String> genres = new ArrayList<>();
        final List<String> tags = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String[]> links = new ArrayList<>();   // {site, adresse} : liens officiels

        static Media from(JSONObject m) {
            Media x = new Media();
            x.id = m.optInt("id", 0);
            x.names.addAll(SeriesInfo.names(m));
            String pref = SeriesInfo.preferredTitle(m);
            x.title = pref == null ? (x.names.isEmpty() ? "?" : x.names.get(0)) : pref;
            JSONObject t = m.optJSONObject("title");
            if (t != null) {
                String romaji = t.optString("romaji", ""), nat = t.optString("native", "");
                String alt = !romaji.isEmpty() && !"null".equals(romaji) && !romaji.equalsIgnoreCase(x.title) ? romaji
                        : (!nat.isEmpty() && !"null".equals(nat) ? nat : null);
                x.alt = alt;
            }
            x.score = m.optInt("averageScore", 0);
            x.popularity = m.optInt("popularity", 0);
            x.chapters = m.optInt("chapters", 0);
            x.siteUrl = str(m, "siteUrl");
            x.status = str(m, "status");
            x.country = str(m, "countryOfOrigin");
            x.format = str(m, "format");
            x.adult = m.optBoolean("isAdult", false);
            JSONObject sd = m.optJSONObject("startDate");
            x.year = sd == null ? 0 : sd.optInt("year", 0);
            JSONObject img = m.optJSONObject("coverImage");
            if (img != null) {
                x.cover = str(img, "large");
                x.color = str(img, "color");
            }
            JSONArray g = m.optJSONArray("genres");
            if (g != null) for (int i = 0; i < g.length(); i++) x.genres.add(g.optString(i));
            JSONArray tags = m.optJSONArray("tags");
            if (tags != null) for (int i = 0; i < tags.length(); i++) {
                JSONObject o = tags.optJSONObject(i);
                if (o != null && !o.optBoolean("isMediaSpoiler", false) && o.optInt("rank", 0) >= 60) {
                    x.tags.add(o.optString("name"));
                }
            }
            if (m.has("description")) {
                x.full = true;
                x.description = clean(str(m, "description"));
            }
            JSONArray links = m.optJSONArray("externalLinks");
            if (links != null) for (int i = 0; i < links.length(); i++) {
                JSONObject o = links.optJSONObject(i);
                if (o == null || o.optBoolean("isDisabled", false)) continue;
                String url = str(o, "url"), site = str(o, "site"), type = str(o, "type");
                if (url == null || site == null) continue;
                // Où lire officiellement : les plateformes de lecture (pas les réseaux sociaux ni les fiches info)
                boolean reading = "STREAMING".equals(type) || SourceRegistry.platform(url) != null;
                if (reading && !SourceRegistry.isSocial(SourceRegistry.host(url))) {
                    String lang = str(o, "language");
                    x.links.add(new String[]{site + (lang == null ? "" : " · " + langCode(lang)), url});
                }
            }
            return x;
        }

        /** « Manhwa », « Manga », « Manhua »… d'après le pays d'origine. */
        String type() {
            if ("KR".equals(country)) return "Manhwa";
            if ("CN".equals(country)) return "Manhua";
            if ("TW".equals(country)) return "Manhua (TW)";
            if ("ONE_SHOT".equals(format)) return "One-shot";
            return "Manga";
        }

        /** « 8,4 » (AniList note sur 100). */
        String scoreText() {
            return score <= 0 ? "" : String.format(Locale.FRANCE, "%.1f", score / 10f);
        }

        String statusFr() {
            if (status == null) return null;
            switch (status) {
                case "RELEASING":
                    return "En cours";
                case "FINISHED":
                    return "Terminé";
                case "NOT_YET_RELEASED":
                    return "À venir";
                case "CANCELLED":
                    return "Annulé";
                case "HIATUS":
                    return "En pause";
                default:
                    return null;
            }
        }

        /** « Manhwa · 2021 » */
        String meta() {
            return type() + (year > 0 ? " · " + year : "");
        }

        /** L'essentiel, pour « Ma liste à lire » et le cache. */
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                if (alt != null) o.put("alt", alt);
                if (cover != null) o.put("cover", cover);
                if (color != null) o.put("color", color);
                if (siteUrl != null) o.put("siteUrl", siteUrl);
                if (status != null) o.put("status", status);
                if (country != null) o.put("country", country);
                if (format != null) o.put("format", format);
                if (because != null) o.put("because", because);
                o.put("score", score);
                o.put("popularity", popularity);
                o.put("year", year);
                o.put("chapters", chapters);
                o.put("genres", new JSONArray(genres));
                o.put("names", new JSONArray(names));
            } catch (Exception ignored) {
            }
            return o;
        }

        static Media fromJson(JSONObject o) {
            Media x = new Media();
            x.id = o.optInt("id", 0);
            x.title = o.optString("title", "?");
            x.alt = str(o, "alt");
            x.cover = str(o, "cover");
            x.color = str(o, "color");
            x.siteUrl = str(o, "siteUrl");
            x.status = str(o, "status");
            x.country = str(o, "country");
            x.format = str(o, "format");
            x.because = str(o, "because");
            x.score = o.optInt("score", 0);
            x.popularity = o.optInt("popularity", 0);
            x.year = o.optInt("year", 0);
            x.chapters = o.optInt("chapters", 0);
            JSONArray g = o.optJSONArray("genres");
            if (g != null) for (int i = 0; i < g.length(); i++) x.genres.add(g.optString(i));
            JSONArray n = o.optJSONArray("names");
            if (n != null) for (int i = 0; i < n.length(); i++) x.names.add(n.optString(i));
            if (x.names.isEmpty()) x.names.add(x.title);
            return x;
        }
    }

    // ------------------------------------------------------------------ Recherche

    /** Critères : texte, tri, filtres. */
    static final class Query {
        String search;
        String sort = "SCORE_DESC";
        final LinkedHashSet<String> genres = new LinkedHashSet<>();
        final LinkedHashSet<String> tags = new LinkedHashSet<>();
        String country;     // KR, JP, CN ou null
        String status;      // RELEASING, FINISHED ou null
        int minScore;       // 0, 70, 80, 85
        int fromYear, toYear;

        Query copy() {
            Query q = new Query();
            q.search = search;
            q.sort = sort;
            q.genres.addAll(genres);
            q.tags.addAll(tags);
            q.country = country;
            q.status = status;
            q.minScore = minScore;
            q.fromYear = fromYear;
            q.toYear = toYear;
            return q;
        }

        boolean searching() {
            return search != null && !search.trim().isEmpty();
        }

        /** Nombre de filtres actifs (hors tri et texte). */
        int filters() {
            return genres.size() + tags.size() + (country != null ? 1 : 0) + (status != null ? 1 : 0)
                    + (minScore > 0 ? 1 : 0) + (fromYear > 0 || toYear > 0 ? 1 : 0);
        }

        /** Variables GraphQL (les absentes sont ignorées par AniList). */
        JSONObject variables(int page, Set<Integer> exclude) throws Exception {
            JSONObject v = new JSONObject();
            v.put("page", page);
            if (searching()) v.put("search", search.trim());
            v.put("sort", new JSONArray().put(searching() ? "SEARCH_MATCH" : sort));
            if (!genres.isEmpty()) v.put("genres", new JSONArray(genres));
            if (!tags.isEmpty()) v.put("tags", new JSONArray(tags));
            if (country != null) v.put("country", country);
            if (status != null) v.put("status", status);
            if (minScore > 0) v.put("minScore", minScore - 1);           // « 8+ » : plus de 79
            if (fromYear > 0) v.put("from", fromYear * 10000 - 1);        // inclut les dates « année seule »
            if (toYear > 0) v.put("to", (toYear + 1) * 10000);            // avant l'année suivante
            // Classement par note : seulement des séries assez lues pour que la note veuille dire quelque chose.
            if (!searching()) v.put("minPop", "SCORE_DESC".equals(sort) ? 1500 : 200);
            if (exclude != null && !exclude.isEmpty()) v.put("not", new JSONArray(exclude));
            return v;
        }
    }

    static final String BROWSE = "query($page:Int,$search:String,$sort:[MediaSort],$genres:[String],$tags:[String],"
            + "$country:CountryCode,$status:MediaStatus,$minScore:Int,$from:FuzzyDateInt,$to:FuzzyDateInt,$minPop:Int,$not:[Int]){"
            + "Page(page:$page,perPage:30){pageInfo{hasNextPage}media(type:MANGA,format_in:[MANGA,ONE_SHOT],isAdult:false,"
            + "search:$search,sort:$sort,genre_in:$genres,tag_in:$tags,countryOfOrigin:$country,status:$status,"
            + "averageScore_greater:$minScore,startDate_greater:$from,startDate_lesser:$to,popularity_greater:$minPop,"
            + "id_not_in:$not){" + CARD + "}}}";

    static final class Result {
        final List<Media> items = new ArrayList<>();
        boolean hasNext;
    }

    /** Une page de résultats (30 au plus), sans les séries que tu lis déjà. */
    static Result browse(Query q, int page, Profile me) throws Exception {
        JSONObject data = graphql(BROWSE, q.variables(page, me == null ? null : me.ids));
        return parsePage(data, me);
    }

    static Result parsePage(JSONObject data, Profile me) {
        Result r = new Result();
        JSONObject p = data == null ? null : data.optJSONObject("Page");
        if (p == null) return r;
        JSONObject info = p.optJSONObject("pageInfo");
        r.hasNext = info != null && info.optBoolean("hasNextPage", false);
        JSONArray list = p.optJSONArray("media");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Media m = Media.from(o);
            if (m.id == 0 || m.adult || (me != null && me.has(m))) continue;
            r.items.add(m);
        }
        return r;
    }

    /** La fiche complète d'une série (résumé, thèmes, liens officiels). */
    static Media details(int id) throws Exception {
        JSONObject data = graphql("query($id:Int){Media(id:$id){" + FULL + "}}", new JSONObject().put("id", id));
        JSONObject m = data == null ? null : data.optJSONObject("Media");
        return m == null ? null : Media.from(m);
    }

    // ------------------------------------------------------------------ Ce que tu lis

    /** Une série de ta bibliothèque, pour le profil : titre, chapitres lus, fiche AniList gardée en base. */
    static final class Read {
        final String title;
        final int count;
        final String details;

        Read(String title, int count, String details) {
            this.title = title;
            this.count = count;
            this.details = details;
        }
    }

    /**
     * Ton profil de lecture : séries AniList déjà lues (écartées partout), genres et thèmes préférés
     * (pondérés par le nombre de chapitres lus) et les séries recommandées par la communauté AniList
     * pour chacune de tes séries.
     */
    static final class Profile {
        final Set<Integer> ids = new HashSet<>();
        final Set<String> keys = new HashSet<>();                  // noms normalisés des séries lues
        final LinkedHashMap<String, Double> genres = new LinkedHashMap<>();
        final LinkedHashMap<String, Double> tags = new LinkedHashMap<>();
        final LinkedHashMap<Integer, Double> picks = new LinkedHashMap<>();
        final Map<Integer, String> because = new HashMap<>();
        int series;

        /** Déjà lue (ou en cours) ? */
        boolean has(Media m) {
            if (ids.contains(m.id)) return true;
            for (String n : m.names) {
                String k = Titles.key(n);
                if (k.length() >= 3 && keys.contains(k)) return true;
            }
            return false;
        }

        List<String> topGenres(int n) {
            return new ArrayList<>(genres.keySet()).subList(0, Math.min(n, genres.size()));
        }

        List<String> topTags(int n) {
            return new ArrayList<>(tags.keySet()).subList(0, Math.min(n, tags.size()));
        }

        /** « Action · Fantasy · Cultivation » */
        String summary() {
            List<String> parts = new ArrayList<>();
            for (String g : topGenres(2)) parts.add(genreFr(g));
            for (String t : topTags(3)) {
                if (parts.size() >= 4) break;
                String fr = tagFr(t);
                if (!parts.contains(fr) && !"Héros masculin".equals(fr) && !"Défilement vertical".equals(fr)
                        && !"En couleurs".equals(fr) && !"Webcomic".equals(fr)) parts.add(fr);
            }
            return String.join(" · ", parts);
        }
    }

    private static final Pattern ANILIST_ID = Pattern.compile("anilist\\.co/manga/(\\d+)");

    static Profile profile(Context ctx) {
        DbHelper db = DbHelper.get(ctx);
        List<Read> reads = new ArrayList<>();
        for (DbHelper.Series s : db.getSeries()) {
            DbHelper.Meta m = db.getMeta(s.title);
            reads.add(new Read(s.title, s.count, m == null ? null : m.detailsJson));
        }
        return buildProfile(reads, db.aliasKeys());
    }

    /** Calcul du profil (sans base de données : testable). */
    static Profile buildProfile(List<Read> reads, Set<String> aliasKeys) {
        Profile p = new Profile();
        if (aliasKeys != null) p.keys.addAll(aliasKeys);
        Map<String, Double> g = new HashMap<>(), t = new HashMap<>();
        Map<Integer, Double> picks = new HashMap<>(), best = new HashMap<>();
        for (Read r : reads) {
            p.series++;
            String k = Titles.key(r.title);
            if (k.length() >= 3) p.keys.add(k);
            JSONObject a = null;
            try {
                a = r.details == null ? null : new JSONObject(r.details).optJSONObject("anilist");
            } catch (Exception ignored) {
            }
            if (a == null) continue;
            int id = a.optInt("id", 0);
            if (id > 0) p.ids.add(id);
            for (String n : SeriesInfo.names(a)) {
                String nk = Titles.key(n);
                if (nk.length() >= 3) p.keys.add(nk);
            }
            double w = 1 + Math.log1p(Math.max(0, r.count));   // plus tu en as lu, plus elle compte
            JSONArray genres = a.optJSONArray("genres");
            if (genres != null) for (int i = 0; i < genres.length(); i++) g.merge(genres.optString(i), w, Double::sum);
            JSONArray tags = a.optJSONArray("tags");
            if (tags != null) for (int i = 0; i < tags.length(); i++) {
                JSONObject o = tags.optJSONObject(i);
                if (o == null || o.optBoolean("isMediaSpoiler", false)) continue;
                int rank = o.optInt("rank", 0);
                if (rank >= 60) t.merge(o.optString("name"), w * rank / 100.0, Double::sum);
            }
            JSONObject recos = a.optJSONObject("recommendations");
            JSONArray nodes = recos == null ? null : recos.optJSONArray("nodes");
            if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                JSONObject mr = node == null ? null : node.optJSONObject("mediaRecommendation");
                if (mr == null) continue;
                int rid = mr.optInt("id", 0);
                if (rid == 0) {
                    Matcher mm = ANILIST_ID.matcher(mr.optString("siteUrl", ""));
                    if (mm.find()) rid = Integer.parseInt(mm.group(1));
                }
                if (rid == 0) continue;
                double c = w * Math.max(0.2, 1.0 - i * 0.08);     // les premières recommandations pèsent plus
                picks.merge(rid, c, Double::sum);
                if (c > best.getOrDefault(rid, 0.0)) {
                    best.put(rid, c);
                    p.because.put(rid, r.title);
                }
            }
        }
        for (Integer id : p.ids) picks.remove(id);
        sortInto(g, p.genres);
        sortInto(t, p.tags);
        sortInto(picks, p.picks);
        return p;
    }

    private static <K> void sortInto(Map<K, Double> from, LinkedHashMap<K, Double> to) {
        List<Map.Entry<K, Double>> l = new ArrayList<>(from.entrySet());
        Collections.sort(l, (x, y) -> Double.compare(y.getValue(), x.getValue()));
        for (Map.Entry<K, Double> e : l) to.put(e.getKey(), e.getValue());
    }

    // ------------------------------------------------------------------ Pour toi

    private static final long FOR_YOU_MS = 12L * 3600 * 1000;

    /**
     * « Pour toi » : les séries que la communauté AniList recommande pour celles que tu lis, les plus
     * citées et les mieux notées d'abord (note publique ≥ 7), sans celles que tu lis déjà. Complété au
     * besoin par le meilleur de ton genre préféré. Gardé 12 h.
     */
    static List<Media> forYou(Context ctx, Profile me, boolean force) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("discover", Context.MODE_PRIVATE);
        String sig = me.series + ":" + me.ids.size();
        if (!force && sig.equals(p.getString("for_you_sig", "")) && System.currentTimeMillis() - p.getLong("for_you_at", 0) < FOR_YOU_MS) {
            List<Media> cached = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(p.getString("for_you", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    Media m = Media.fromJson(a.getJSONObject(i));
                    if (!me.has(m)) cached.add(m);
                }
            } catch (Exception ignored) {
            }
            if (!cached.isEmpty()) return cached;
        }

        List<Media> out = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (Integer id : me.picks.keySet()) {
            if (ids.size() >= 45) break;
            ids.add(id);
        }
        if (!ids.isEmpty()) {
            JSONObject data = graphql("query($ids:[Int]){Page(perPage:50){media(id_in:$ids,type:MANGA,isAdult:false){" + CARD + "}}}",
                    new JSONObject().put("ids", new JSONArray(ids)));
            Map<Integer, Media> byId = new HashMap<>();
            for (Media m : parsePage(data, me).items) byId.put(m.id, m);
            List<Media> ranked = new ArrayList<>();
            Map<Integer, Double> weight = new HashMap<>();
            for (Integer id : ids) {
                Media m = byId.get(id);
                if (m == null || m.score < 70) continue;   // « très bien notées »
                String src = me.because.get(id);
                m.because = src == null ? null : "Comme " + src;
                weight.put(id, me.picks.get(id) * (0.5 + m.score / 100.0));
                ranked.add(m);
            }
            Collections.sort(ranked, (x, y) -> Double.compare(weight.get(y.id), weight.get(x.id)));
            out.addAll(ranked);
        }
        // Peu de recommandations (fiches pas encore chargées) : le meilleur de ton genre préféré.
        if (out.size() < 8) {
            Query q = new Query();
            List<String> top = me.topGenres(1);
            if (!top.isEmpty()) q.genres.add(top.get(0));
            q.minScore = 75;
            Set<Integer> seen = new HashSet<>();
            for (Media m : out) seen.add(m.id);
            for (Media m : browse(q, 1, me).items) {
                if (out.size() >= 20) break;
                if (!seen.add(m.id)) continue;
                m.because = top.isEmpty() ? "Incontournable" : "Top " + genreFr(top.get(0));
                out.add(m);
            }
        }
        if (out.size() > 24) out = new ArrayList<>(out.subList(0, 24));
        JSONArray a = new JSONArray();
        for (Media m : out) a.put(m.toJson());
        p.edit().putString("for_you", a.toString()).putLong("for_you_at", System.currentTimeMillis())
                .putString("for_you_sig", sig).apply();
        return out;
    }

    // ------------------------------------------------------------------ Outils

    /** Requête GraphQL AniList : renvoie « data », ou une exception avec le message d'AniList. */
    static JSONObject graphql(String query, JSONObject variables) throws Exception {
        String body = new JSONObject().put("query", query).put("variables", variables).toString();
        String res = ReleaseSources.post(ANILIST, body);
        JSONObject o = new JSONObject(res);
        JSONObject data = o.optJSONObject("data");
        JSONArray errors = o.optJSONArray("errors");
        if (data == null && errors != null && errors.length() > 0) {
            throw new Exception(errors.optJSONObject(0) == null ? "AniList" : errors.optJSONObject(0).optString("message", "AniList"));
        }
        return data;
    }

    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String s = o.optString(k, "");
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String langCode(String lang) {
        String c = SourceRegistry.codeFromName(lang);
        return c == null ? lang : c.toUpperCase(Locale.ROOT);
    }

    /** Résumé AniList sans balises ni mentions de source. */
    static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
        s = s.replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">");
        s = s.replaceAll("(?is)\\(source:.*?\\)", "").replaceAll("\\n{3,}", "\n\n").trim();
        return s.isEmpty() ? null : s;
    }
}
