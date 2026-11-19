package com.lois.webtoontracker;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lien pour rouvrir un chapitre précis depuis la liste d'épisodes.
 *  1. le lien exact enregistré quand tu l'as lu ;
 *  2. webtoons.com (série reliée) : lien du viewer avec episode_no — l'appli WEBTOON l'ouvre ;
 *  3. sinon on déduit le lien d'un chapitre voisin dont on connaît l'adresse, en changeant
 *     seulement le numéro — uniquement quand l'adresse ne contient pas d'identifiant propre
 *     au chapitre (ex. comix.to « 2879576-chapter-1 » : impossible à deviner, on n'essaie pas).
 */
public final class ChapterLinks {

    private ChapterLinks() {
    }

    private static final String KW = "(?:chapitre|chapter|chap|ch|episode|épisode|ep|c)";

    public static String urlFor(DbHelper.Meta meta, List<DbHelper.Entry> entries, double chapter) {
        DbHelper.Entry best = null;
        for (DbHelper.Entry e : entries) {
            if (e.chapter == chapter && e.url != null) return e.url;
            if (e.url != null && (best == null || Math.abs(e.chapter - chapter) < Math.abs(best.chapter - chapter))) best = e;
        }
        if (meta != null && meta.rssBase != null && meta.titleNo != null && chapter == Math.floor(chapter)) {
            long n = (long) chapter;
            return meta.rssBase + "/episode-" + n + "/viewer?title_no=" + meta.titleNo + "&episode_no=" + n;
        }
        if (best != null) {
            String g = guess(best.url, best.chapter, chapter);
            if (g != null) return g;
        }
        return null;
    }

    /** Remplace le numéro de chapitre dans une adresse connue, ou null si ce n'est pas sûr. */
    static String guess(String url, double known, double target) {
        if (url == null) return null;
        String k = Detector.formatChapter(known), t = Detector.formatChapter(target);
        Pattern p = Pattern.compile("(?i)(?<![a-z])(" + KW + "(?:[-_ .]|_no=|=|/)?)" + Pattern.quote(k) + "(?![0-9])");
        Matcher m = p.matcher(url);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            // le morceau d'adresse qui contient le numéro ne doit pas porter d'autre identifiant (≥ 4 chiffres)
            if (hasOtherId(url, m.start(), m.end())) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + t));
            changed = true;
        }
        m.appendTail(sb);
        if (!changed) {
            // adresse du type …/serie/12 ou …/serie/12/
            Matcher seg = Pattern.compile("/" + Pattern.quote(k) + "(/?)(?=$|[?#])").matcher(url);
            if (seg.find()) return url.substring(0, seg.start()) + "/" + t + seg.group(1) + url.substring(seg.end());
            return null;
        }
        String out = sb.toString();
        return out.equals(url) ? null : out;
    }

    private static boolean hasOtherId(String url, int start, int end) {
        int a = start - 1;
        while (a >= 0 && "/?&#".indexOf(url.charAt(a)) < 0) a--;
        int b = end;
        while (b < url.length() && "/?&#".indexOf(url.charAt(b)) < 0) b++;
        String segment = url.substring(a + 1, b);
        String rest = segment.replaceFirst("(?i)" + KW + "(?:[-_ .]|_no=|=)?[0-9.]+", "");
        return rest.matches(".*[0-9]{4,}.*");
    }
}
