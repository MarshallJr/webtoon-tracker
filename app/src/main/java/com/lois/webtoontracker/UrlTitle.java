package com.lois.webtoontracker;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrouve "titre + chapitre" dans l'URL de N'IMPORTE QUEL site de lecture.
 *
 * Exemples gérés :
 *   comix.to/title/q8qxd-the-jobless-guy-who-has-come-to-slay/2879576-chapter-1
 *   site.net/series/solo-leveling-abc123/chapter/120
 *   sushiscan.fr/the-greatest-estate-developer-chapitre-45/
 *   reader.com/manga/omniscient-reader/ep_12.5
 *
 * Principe : on repère le segment "chapitre", puis on cherche AVANT lui le segment qui ressemble
 * le plus à un titre, en jetant les identifiants techniques (q8qxd, 2879576, aa951409…)
 * et les mots génériques (title, series, manga, read…). Java pur (testable).
 */
public final class UrlTitle {

    private UrlTitle() {}

    public static final class Hit {
        public final String title;
        public final double chapter;
        public final String host;

        public Hit(String title, double chapter, String host) {
            this.title = title;
            this.chapter = chapter;
            this.host = host;
        }

        @Override
        public String toString() {
            return title + " | ch." + chapter + " | " + host;
        }
    }

    private static final String KW = "(?:chapitre|chapter|chap|episode|ep|ch|c)";
    /** Mot-clé + numéro DANS un segment : "2879576-chapter-1", "chapitre-45", "ep_12.5", "c120" */
    private static final Pattern IN_SEGMENT = Pattern.compile(
            "(?:^|[-_.])" + KW + "[-_.]?(\\d{1,4}(?:[.-]\\d{1,2})?)(?=$|[-_.])", Pattern.CASE_INSENSITIVE);
    /** Segment mot-clé seul suivi d'un segment numéro : ".../chapter/120" */
    private static final Pattern KW_ONLY = Pattern.compile("^" + KW + "s?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,4}(?:[.-]\\d{1,2})?)$");

    /** Mots de site retirés seulement au DÉBUT ou à la FIN d'un nom ("nano-machine-raw", "manga-solo-leveling"). */
    private static final Set<String> EDGE_NOISE = new HashSet<>(Arrays.asList(
            "manga", "mangas", "manhwa", "manhua", "webtoon", "webtoons", "comic", "comics", "read", "online",
            "free", "scan", "scans", "raw", "raws", "vf", "vostfr", "fr", "en", "eng", "full", "official",
            "lecture", "html", "php"));

    /** Segments qui ne sont jamais un titre quand ils sont seuls ("title", "series", "manga"…). */
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "manga", "mangas", "manhwa", "manhua", "webtoon", "webtoons", "comic", "comics", "read", "reader",
            "reading", "online", "title", "titles", "series", "serie", "chapter", "chapters", "chapitre",
            "chapitres", "episode", "episodes", "www", "index", "view", "viewer", "list", "book", "books",
            "novel", "detail", "details", "info", "lecture", "en", "ligne", "fr", "us", "library", "home"));

    public static Hit extract(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.contains(" ") || !text.contains("/")) return null;
        String noScheme = text.replaceFirst("^[a-zA-Z]+://", "");
        int cut = firstIndex(noScheme, '?', '#');
        if (cut >= 0) noScheme = noScheme.substring(0, cut);
        String[] parts = noScheme.split("/");
        if (parts.length < 2 || !parts[0].contains(".")) return null;
        String host = parts[0].toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.)", "");

        List<String> segs = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String s = decode(parts[i]).replaceAll("(?i)\\.(html?|php|aspx?)$", "");
            if (!s.isEmpty()) segs.add(s);
        }

        // 1. Où est le chapitre ? (on part de la fin de l'URL)
        int chIdx = -1;
        String prefix = null;
        double chapter = -1;
        for (int i = segs.size() - 1; i >= 0 && chIdx < 0; i--) {
            String seg = segs.get(i);
            if (KW_ONLY.matcher(seg).matches() && i + 1 < segs.size()) {
                Matcher n = NUMBER.matcher(segs.get(i + 1));
                if (n.matches()) {
                    chIdx = i;
                    prefix = "";
                    chapter = num(n.group(1));
                    break;
                }
            }
            Matcher m = IN_SEGMENT.matcher(seg);
            int lastStart = -1;
            String lastNum = null;
            while (m.find()) {
                lastStart = m.start();
                lastNum = m.group(1);
            }
            if (lastStart >= 0) {
                chIdx = i;
                prefix = seg.substring(0, lastStart);
                chapter = num(lastNum);
            }
        }
        if (chIdx < 0 || chapter < 0) return null;

        // 2. Quel segment est le titre ? D'abord le début du segment chapitre, puis les segments précédents.
        String title = clean(prefix);
        for (int j = chIdx - 1; title == null && j >= 0; j--) title = clean(segs.get(j));
        return new Hit(title, chapter, host); // title == null : chapitre trouvé mais pas de titre dans l'URL

    }

    /** URL d'une page de lecture sans numéro lisible (ex : mangadex.org/chapter/uuid/1) → hôte, sinon null. */
    public static String readerPageHost(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceFirst("^[a-zA-Z]+://", "");
        int cut = firstIndex(t, '?', '#');
        if (cut >= 0) t = t.substring(0, cut);
        String[] parts = t.split("/");
        if (parts.length < 2 || !parts[0].contains(".")) return null;
        for (int i = 1; i < parts.length; i++) {
            if (KW_ONLY.matcher(parts[i]).matches() || parts[i].matches("(?i)(read|reader|viewer|lecture)")) {
                return parts[0].toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.)", "");
            }
        }
        return null;
    }

    /** "q8qxd-the-jobless-guy-who-has-come-to-slay" → "The Jobless Guy Who Has Come To Slay" (ou null). */
    static String clean(String slug) {
        if (slug == null || slug.isEmpty()) return null;
        List<String> keep = new ArrayList<>();
        for (String t : slug.split("[-_+.\\s]+")) {
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.matches("\\d+")) continue;                                    // identifiant numérique
            if (low.matches(".*\\d.*") && low.matches(".*[a-z].*") && low.length() <= 8) continue; // q8qxd, aa951409
            if (low.matches("[0-9a-f]{8,}")) continue;                             // hash
            keep.add(t);
        }
        while (!keep.isEmpty() && EDGE_NOISE.contains(keep.get(0).toLowerCase(Locale.ROOT))) keep.remove(0);
        while (!keep.isEmpty() && EDGE_NOISE.contains(keep.get(keep.size() - 1).toLowerCase(Locale.ROOT))) keep.remove(keep.size() - 1);
        boolean allGeneric = true;
        int letters = 0;
        boolean realWord = false;
        for (String t : keep) {
            if (!GENERIC.contains(t.toLowerCase(Locale.ROOT))) allGeneric = false;
            int l = t.replaceAll("[^\\p{L}]", "").length();
            letters += l;
            if (l >= 3) realWord = true;
        }
        if (keep.isEmpty() || allGeneric || !realWord || letters < 4) return null;
        StringBuilder sb = new StringBuilder();
        for (String t : keep) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        return sb.toString();
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace('-', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int firstIndex(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
