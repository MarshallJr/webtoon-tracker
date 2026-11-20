package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lien pour ouvrir un chapitre précis depuis la liste d'épisodes — sur le MÊME site que le
 * dernier chapitre que tu as consulté dans cette série.
 *
 * Ordre :
 *  1. le lien exact du chapitre, s'il est sur ce site ;
 *  2. l'adresse d'un chapitre connu de ce site, dont on change seulement le numéro
 *     (…-chapter-24/ → …-chapter-22/, …/chapter/24 → …/chapter/22, ?ch=24, /24, slug-24…) ;
 *  3. si le site met un identifiant propre à chaque chapitre (ex. comix.to « 2879576-chapter-1 »),
 *     impossible à deviner : la page de la série sur ce même site ;
 *  4. sinon un autre site où tu l'as lu, puis webtoons.com (série reliée).
 */
public final class ChapterLinks {

    private ChapterLinks() {
    }

    /** Résultat : l'adresse, et si c'est seulement la page de la série (numéro impossible à deviner). */
    public static final class Link {
        public final String url;
        public final boolean seriesPage;

        Link(String url, boolean seriesPage) {
            this.url = url;
            this.seriesPage = seriesPage;
        }

        public String host() {
            return ChapterLinks.host(url);
        }
    }

    private static final String KW = "(?:chapitres?|chapters?|chap|ch|episodes?|épisodes?|ep|c)";
    private static final String PREFS = "recent_sites";

    // ------------------------------------------------------------------ Dernier site consulté

    /** Le service note chaque chapitre ouvert (nouveau ou relu) : c'est ce site qui sera réutilisé. */
    public static void rememberVisit(Context ctx, String title, String url, double chapter) {
        if (url == null || title == null) return;
        String k = Titles.key(title);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(k + "|u", url)
                .putString(k + "|c", String.valueOf(chapter))
                .apply();
    }

    private static final class Known {
        final String url;
        final double chapter;   // NaN : numéro inconnu (on le cherche dans l'adresse)
        final long at;

        Known(String url, double chapter, long at) {
            this.url = url;
            this.chapter = chapter;
            this.at = at;
        }
    }

    public static Link linkFor(Context ctx, String title, DbHelper.Meta meta, List<DbHelper.Entry> entries, double chapter) {
        // Adresses connues, de la plus récemment consultée à la plus ancienne.
        List<Known> known = new ArrayList<>();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String k = Titles.key(title);
        String recent = p.getString(k + "|u", null);
        if (recent != null) {
            double c = Double.NaN;
            try {
                c = Double.parseDouble(p.getString(k + "|c", "NaN"));
            } catch (NumberFormatException ignored) {
            }
            known.add(new Known(recent, c, Long.MAX_VALUE));
        }
        DbHelper.Entry own = null;
        for (DbHelper.Entry e : entries) {
            if (e.chapter == chapter) own = e;
            if (e.url != null) known.add(new Known(e.url, e.chapter, Math.max(e.readAt, e.lastAt)));
        }
        // adresse du chapitre le plus avancé (enregistrée avant la v4.3 : numéro supposé = dernier lu)
        double furthest = entries.isEmpty() ? Double.NaN : entries.get(0).chapter;
        if (meta != null && meta.lastUrl != null) known.add(new Known(meta.lastUrl, furthest, 0));
        known.sort((a, b) -> Long.compare(b.at, a.at));

        String site = known.isEmpty() ? null : host(known.get(0).url);
        if (site != null) {
            // 1. lien exact sur ce site
            if (own != null && own.url != null && site.equals(host(own.url))) return new Link(own.url, false);
            // 2. numéro remplacé dans une adresse de ce site
            for (Known kn : known) {
                if (!site.equals(host(kn.url))) continue;
                String g = guessAny(kn, chapter);
                if (g != null) return new Link(g, false);
            }
            // 3. page de la série sur ce site
            String series = seriesPage(known.get(0).url);
            if (series != null) return new Link(series, true);
        }
        // 4. ailleurs : lien exact, autre site, puis webtoons.com
        if (own != null && own.url != null) return new Link(own.url, false);
        for (Known kn : known) {
            String g = guessAny(kn, chapter);
            if (g != null) return new Link(g, false);
        }
        if (meta != null && meta.rssBase != null && meta.titleNo != null && chapter == Math.floor(chapter)) {
            long n = (long) chapter;
            return new Link(meta.rssBase + "/episode-" + n + "/viewer?title_no=" + meta.titleNo + "&episode_no=" + n, false);
        }
        return null;
    }

    private static String guessAny(Known kn, double target) {
        if (!Double.isNaN(kn.chapter)) {
            String g = guess(kn.url, kn.chapter, target);
            if (g != null) return g;
        }
        double inUrl = chapterInUrl(kn.url);
        return Double.isNaN(inUrl) ? null : guess(kn.url, inUrl, target);
    }

    // ------------------------------------------------------------------ Numéro dans l'adresse

    private static final Pattern KW_NUM = Pattern.compile(
            "(?i)(?<![a-z0-9])" + KW + "(?:[-_ .]|_no=|=|/)?([0-9]+(?:[.\\-_][0-9]{1,2})?)(?![0-9a-z])");

    /** Numéro de chapitre lu dans une adresse (le dernier « chapter-N », « ch=N », « /N »…), ou NaN. */
    static double chapterInUrl(String url) {
        if (url == null) return Double.NaN;
        Matcher m = KW_NUM.matcher(url);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) {
            Matcher seg = Pattern.compile("/([0-9]{1,4})/?(?=$|[?#])").matcher(url);
            if (seg.find()) last = seg.group(1);
        }
        if (last == null) return Double.NaN;
        try {
            return Double.parseDouble(last.replace('-', '.').replace('_', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Les façons d'écrire un numéro dans une adresse : 12.5 → « 12.5 », « 12-5 », « 12_5 ». */
    private static String[] spellings(double c) {
        String s = Detector.formatChapter(c);
        if (!s.contains(".")) return new String[]{s};
        return new String[]{s, s.replace('.', '-'), s.replace('.', '_')};
    }

    private static String spell(double c, String like) {
        String s = Detector.formatChapter(c);
        if (like.contains("-")) return s.replace('.', '-');
        if (like.contains("_")) return s.replace('.', '_');
        return s;
    }

    /** Remplace le numéro de chapitre dans une adresse connue, ou null si ce n'est pas sûr. */
    static String guess(String url, double known, double target) {
        if (url == null || known == target) return url;
        for (String k : spellings(known)) {
            String t = spell(target, k);
            // a. « chapter-24 », « ch.24 », « episode_no=24 », « chapter/24 », « c24 »…
            Pattern p = Pattern.compile("(?i)(?<![a-z0-9])(" + KW + "(?:[-_ .]|_no=|=|/)?)" + Pattern.quote(k) + "(?![0-9a-z])");
            Matcher m = p.matcher(url);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                // le morceau d'adresse qui contient le numéro ne doit pas porter d'autre identifiant (≥ 4 chiffres)
                if (hasOtherId(url, m.start(), m.end(), k)) return null;
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + t));
                changed = true;
            }
            m.appendTail(sb);
            if (changed) {
                String out = sb.toString();
                return out.equals(url) ? null : out;
            }
            // b. …/serie/24 ou …/serie/24/
            Matcher seg = Pattern.compile("/" + Pattern.quote(k) + "(/?)(?=$|[?#])").matcher(url);
            if (seg.find()) return url.substring(0, seg.start()) + "/" + t + seg.group(1) + url.substring(seg.end());
            // c. …/nom-de-la-serie-24/ (numéro au bout du dernier morceau de l'adresse)
            Matcher tail = Pattern.compile("(/[^/?#]*[a-zA-Z][^/?#]*[-_])" + Pattern.quote(k) + "(/?)(?=$|[?#])").matcher(url);
            if (tail.find() && !hasOtherId(url, tail.start() + 1, tail.end(), k)) {
                return url.substring(0, tail.start()) + tail.group(1) + t + tail.group(2) + url.substring(tail.end());
            }
        }
        return null;
    }

    private static boolean hasOtherId(String url, int start, int end, String k) {
        int a = start - 1;
        while (a >= 0 && "/?&#".indexOf(url.charAt(a)) < 0) a--;
        int b = end;
        while (b < url.length() && "/?&#".indexOf(url.charAt(b)) < 0) b++;
        String segment = url.substring(a + 1, b);
        String rest = segment.replaceFirst(Pattern.quote(k) + "(?![0-9])", "");
        return rest.matches(".*[0-9]{4,}.*");
    }

    // ------------------------------------------------------------------ Page de la série

    /**
     * Page de la série sur le même site : on enlève le morceau d'adresse du chapitre
     * (…/title/q8qxd-nom/2879576-chapter-1 → …/title/q8qxd-nom). Null si rien d'utilisable ne reste.
     */
    static String seriesPage(String url) {
        try {
            URI u = new URI(url);
            String path = u.getPath() == null ? "" : u.getPath();
            String[] parts = path.split("/");
            int cut = -1;
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].matches("(?i).*(?<![a-z])" + KW + "[-_ .]?[0-9]+.*") || parts[i].matches("[0-9]+")) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 1) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < cut; i++) sb.append('/').append(parts[i]);
            String last = parts[cut - 1].toLowerCase(Locale.ROOT);
            if (last.matches(KW.replace("(?:", "(?:read|reader|viewer|") ) || last.isEmpty()) return null;
            return u.getScheme() + "://" + u.getHost() + sb;
        } catch (Exception e) {
            return null;
        }
    }

    static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? null : h.replaceFirst("^(www|m)\\.", "").toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
