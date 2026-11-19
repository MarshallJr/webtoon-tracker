package com.lois.webtoontracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logique de détection "titre + chapitre" à partir du texte visible à l'écran.
 * Classe Java pure (aucune dépendance Android) pour pouvoir la tester facilement.
 */
public final class Detector {

    private Detector() {}

    /** Un morceau de texte visible à l'écran, avec sa position verticale. */
    public static final class TextNode {
        public final String text;
        public final int top;
        public final int left;

        public TextNode(String text, int top, int left) {
            this.text = text;
            this.top = top;
            this.left = left;
        }
    }

    /** Résultat d'une détection. */
    public static final class Result {
        public final String title;
        public final double chapter;
        public final String source;
        /** URL du chapitre (si lue dans un navigateur), sinon null. */
        public String url;
        /** webtoons.com : identifiant de série et base pour le flux RSS (alertes nouveaux chapitres). */
        public String titleNo;
        public String rssBase;
        /** Appli dans laquelle le chapitre a été lu. */
        public String pkg;

        public Result(String title, double chapter, String source) {
            this.title = title;
            this.chapter = chapter;
            this.source = source;
        }

        public boolean sameAs(Result o) {
            return o != null && chapter == o.chapter && title.equalsIgnoreCase(o.title);
        }

        @Override
        public String toString() {
            return title + " — ch. " + formatChapter(chapter) + " (" + source + ")";
        }
    }

    // ---------------------------------------------------------------- Apps surveillées

    /** Navigateurs : on ne se fie qu'à l'URL (évite les faux positifs). */
    public static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android"
    ));

    /** Applis de lecture : on lit le titre / chapitre affichés en haut de l'écran. */
    public static final Set<String> READER_APPS = new HashSet<>(Arrays.asList(
            "com.naver.linewebtoon",      // WEBTOON
            "com.webtoonscorp.android.jp",
            "com.lezhin.comics",          // Lezhin
            "com.tapas.android",          // Tapas
            "com.piccoma.fr",             // Piccoma
            "app.mihon",                  // Mihon
            "eu.kanade.tachiyomi",        // Tachiyomi
            "eu.kanade.tachiyomi.sy",
            "app.komikku"
    ));

    public static boolean isWatched(String pkg) {
        return BROWSERS.contains(pkg) || READER_APPS.contains(pkg);
    }

    // ---------------------------------------------------------------- Regex

    // ⚠️ Pas de UNICODE_CHARACTER_CLASS : ce flag n'existe pas sur Android (crash au démarrage).
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** "Ep. 12", "Épisode 12", "Chapitre 12.5", "Chapter 120", "Ch.3", "#45" */
    static final Pattern EPISODE = Pattern.compile(
            "(?:(?<![\\p{L}\\p{N}])(?:épisode|episode|ép|ep|chapitre|chapter|chap|ch)\\.?\\s*|#)(\\d{1,4}(?:[.,]\\d{1,2})?)(?![\\p{L}\\p{N}])",
            FLAGS);

    /** URL webtoons.com : /fr/fantasy/tower-of-god/saison-1-ep-12/viewer?title_no=..&episode_no=12 */
    static final Pattern WEBTOONS_URL = Pattern.compile(
            "webtoons\\.com/([a-z-]+)/([^/\\s]+)/([^/\\s?#]+)/[^/\\s?#]+/viewer\\S*?[?&]episode_no=(\\d+)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern TITLE_NO = Pattern.compile("[?&]title_no=(\\d+)");

    /** Segment d'URL de chapitre générique : "chapter-12", "solo-leveling-chapitre-120", "ep_3" */
    static final Pattern URL_CHAPTER_SEGMENT = Pattern.compile(
            "^(?:(.+?)[-_])?(?:chapitre|chapter|chap|episode|ep|ch)[-_]?(\\d{1,4}(?:[.-]\\d{1,2})?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> GENERIC_SEGMENTS = new HashSet<>(Arrays.asList(
            "manga", "mangas", "manhwa", "manhua", "webtoon", "webtoons", "read", "reader",
            "lecture", "lire", "series", "serie", "comic", "comics", "title", "titles", "fr", "en", "www"
    ));

    private static final Set<String> UI_WORDS = new HashSet<>(Arrays.asList(
            "retour", "back", "partager", "share", "menu", "commentaires", "commentaire", "comments",
            "like", "j'aime", "suivant", "précédent", "next", "previous", "prev", "s'abonner",
            "abonné", "subscribe", "subscribed", "liste", "list", "paramètres", "settings",
            "télécharger", "download", "plus", "more", "info", "infos", "options"
    ));

    // ---------------------------------------------------------------- API

    public static Result detect(String pkg, List<TextNode> nodes, int screenHeight) {
        Result r = null;
        for (TextNode n : nodes) {
            r = fromUrl(n.text);
            if (r != null) break;
        }
        if (r == null && !BROWSERS.contains(pkg)) r = fromScreen(nodes, screenHeight);
        if (r != null) r.pkg = pkg;
        return r;
    }

    /** Lien webtoons.com collé par l'utilisateur → base RSS + title_no (ou null). */
    public static String[] parseSeriesLink(String link) {
        if (link == null) return null;
        Matcher t = TITLE_NO.matcher(link);
        Matcher p = Pattern.compile("webtoons\\.com/([a-z-]+)/([^/\\s?#]+)/([^/\\s?#]+)", Pattern.CASE_INSENSITIVE)
                .matcher(link);
        if (!t.find() || !p.find()) return null;
        return new String[]{"https://www.webtoons.com/" + p.group(1) + "/" + p.group(2) + "/" + p.group(3),
                t.group(1), slugToTitle(p.group(3))};
    }

    /** Détection depuis une URL (barre d'adresse du navigateur). */
    public static Result fromUrl(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.contains(" ") || !text.contains("/")) return null;

        Matcher w = WEBTOONS_URL.matcher(text);
        if (w.find()) {
            Result r = new Result(slugToTitle(w.group(3)), parseChapter(w.group(4)), "webtoons.com");
            r.url = text.startsWith("http") ? text : "https://" + text.replaceFirst("^/+", "");
            Matcher t = TITLE_NO.matcher(text);
            if (t.find()) {
                r.titleNo = t.group(1);
                r.rssBase = "https://www.webtoons.com/" + w.group(1) + "/" + w.group(2) + "/" + w.group(3);
            }
            return r;
        }

        String noScheme = text.replaceFirst("^[a-zA-Z]+://", "");
        int cut = indexOfAny(noScheme, '?', '#');
        if (cut >= 0) noScheme = noScheme.substring(0, cut);
        String[] segs = noScheme.split("/");
        if (segs.length < 2 || !segs[0].contains(".")) return null;

        for (int i = segs.length - 1; i >= 1; i--) {
            Matcher m = URL_CHAPTER_SEGMENT.matcher(segs[i]);
            if (!m.matches()) continue;
            String slug = m.group(1);
            if (slug == null || slug.isEmpty()) {
                for (int j = i - 1; j >= 1; j--) {
                    String s = segs[j].toLowerCase(Locale.ROOT);
                    if (!GENERIC_SEGMENTS.contains(s) && !s.matches("\\d+")) {
                        slug = segs[j];
                        break;
                    }
                }
            }
            if (slug == null || slug.isEmpty()) return null;
            Result r = new Result(slugToTitle(slug), parseChapter(m.group(2)), host(segs[0]));
            r.url = text.startsWith("http") ? text : "https://" + text;
            return r;
        }
        return null;
    }

    /** Détection depuis le texte affiché en haut de l'écran (barre de titre du lecteur). */
    public static Result fromScreen(List<TextNode> nodes, int screenHeight) {
        int limit = (int) (screenHeight * 0.25);
        List<TextNode> top = new ArrayList<>();
        for (TextNode n : nodes) {
            if (n.top >= 0 && n.top < limit && n.text != null && !n.text.trim().isEmpty()) top.add(n);
        }
        top.sort((a, b) -> a.top != b.top ? Integer.compare(a.top, b.top) : Integer.compare(a.left, b.left));

        Double chapter = null;
        String titleFromSameNode = null;
        TextNode chapterNode = null;
        for (TextNode n : top) {
            Matcher m = EPISODE.matcher(n.text);
            if (m.find()) {
                chapter = parseChapter(m.group(1));
                chapterNode = n;
                String prefix = n.text.substring(0, m.start())
                        .replaceAll("(?i)[\\s\\-–—:|·•]*(?:saison|season|s)\\s*\\d+[\\s\\-–—:|·•]*$", "")
                        .replaceAll("[\\s\\-–—:|·•]+$", "").trim();
                if (prefix.length() >= 2 && !isUiWord(prefix) && !EPISODE.matcher(prefix).find()) {
                    titleFromSameNode = prefix;
                }
                break;
            }
        }
        if (chapter == null) return null;

        String title = titleFromSameNode;
        if (title == null) {
            for (TextNode n : top) {
                if (n == chapterNode) continue;
                String t = n.text.trim();
                if (t.length() < 2 || t.length() > 80) continue;
                if (EPISODE.matcher(t).find()) continue;
                if (t.matches("(?i)(?:saison|season)\\s*\\d+")) continue;
                if (isUiWord(t) || t.matches("[\\d\\s.,:/%]+")) continue;
                title = t;
                break;
            }
        }
        if (title == null) return null;
        return new Result(cleanTitle(title), chapter, "app");
    }

    // ---------------------------------------------------------------- Helpers

    public static String formatChapter(double c) {
        if (c == Math.floor(c)) return String.valueOf((long) c);
        return String.format(Locale.ROOT, "%s", c);
    }

    static double parseChapter(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.').replace('-', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String slugToTitle(String slug) {
        String s = slug.replaceAll("[-_+]+", " ").replaceAll("%20", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    static String cleanTitle(String t) {
        return t.replaceAll("\\s+", " ").trim();
    }

    private static boolean isUiWord(String t) {
        return UI_WORDS.contains(t.trim().toLowerCase(Locale.ROOT));
    }

    private static String host(String h) {
        return h.replaceFirst("^(www\\.|m\\.)", "");
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
