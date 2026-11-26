package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La liste complète des chapitres d'une série — du 1er au dernier publié — sur le site où tu la lis.
 *
 * Lue sur les pages publiques de CE site uniquement (aucun autre site n'est cherché) :
 *  1. le menu des chapitres de la page que tu lis (la plupart des lecteurs en ont un) ;
 *  2. sinon la page de la série (liste des chapitres), y compris la liste chargée à part des thèmes
 *     WordPress de lecture ;
 *  3. sur WEBTOON : le dernier épisode public (flux RSS), les adresses se déduisent.
 *
 * Les liens retenus ont la même « forme » d'adresse que le chapitre lu (seul le numéro change) :
 * les autres séries de la page (populaires, suggestions) sont ignorées. Quand l'adresse ne contient
 * qu'un identifiant (…/chapter/5263888), le numéro est lu dans le texte du lien (« Chapter 34 »).
 */
final class SiteChapters {

    private SiteChapters() {
    }

    private static final String PREFS = "site_chapters";
    private static final long FRESH_MS = 6L * 3600 * 1000;
    private static final Set<String> running = Collections.synchronizedSet(new HashSet<>());

    static final class Result {
        String host;
        final TreeMap<Double, String> chapters = new TreeMap<>();
        final Set<Double> locked = new HashSet<>();
    }

    /**
     * Met à jour la liste de la série sur ce site (fil d'arrière-plan). Pas plus d'une fois toutes les
     * 6 h, sauf si force (nouvelle série, chapitre plus récent que la liste…).
     * @return nombre de chapitres connus sur ce site, -1 si la page ne donne pas de liste lisible
     */
    static int refresh(Context ctx, String title, String chapterUrl, double chapter, boolean force) {
        if (chapterUrl == null || !chapterUrl.matches("(?i)^https?://.+")) return -1;
        String host = ChapterLinks.host(chapterUrl);
        if (host == null) return -1;
        String key = Titles.key(title) + "|" + host;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong(key, 0) < FRESH_MS) return -1;
        if (!running.add(key)) return -1;
        try {
            p.edit().putLong(key, now).apply();
            Result r = fetch(chapterUrl, chapter, SeriesNames.names(ctx, title));
            if (r == null || r.chapters.size() < 2) return -1;
            DbHelper db = DbHelper.get(ctx);
            List<Double> appeared = db.saveSiteChapters(title, host, r.chapters, now);
            // Chapitres marqués payants dans la liste du site (cadenas, pièces…) : repérés d'avance.
            for (Double c : r.locked) {
                if (db.getReadAt(title, c) < 0) db.saveLock(title, host, c, r.chapters.get(c), 0, now);
            }
            announce(db, title, host, r, appeared, now);
            return r.chapters.size();
        } catch (Exception e) {
            return -1;
        } finally {
            running.remove(key);
        }
    }

    /**
     * Un chapitre apparu sur ton site depuis la lecture précédente de sa liste, plus loin que ton
     * dernier lu et gratuit ici, devient une sortie (carte « Nouveaux chapitres » de l'accueil).
     */
    static DbHelper.Release announce(DbHelper db, String title, String host, Result r, List<Double> appeared, long now) {
        if (appeared == null || appeared.isEmpty()) return null;
        double last = db.lastRead(title);
        Set<Double> locked = new HashSet<>(r.locked);
        for (DbHelper.Lock l : db.getLocks(title)) if (l.active() && host.equals(l.host)) locked.add(l.chapter);
        double best = -1;
        for (Double c : appeared) if (c > last && c > best && !locked.contains(c)) best = c;
        if (best < 0) return null;
        for (DbHelper.Release x : db.newReleases(title)) {
            // Déjà signalé (même site), ou une sortie « Site » plus avancée qu'on écraserait.
            if (x.chapter >= best && (host.equals(x.label) || ("Site".equals(x.source) && "".equals(x.lang)))) return null;
        }
        DbHelper.Release rel = new DbHelper.Release();
        rel.title = title;
        rel.source = "Site";
        rel.lang = "";
        rel.chapter = best;
        rel.url = r.chapters.get(best);
        rel.label = host;
        rel.foundAt = now;
        db.saveRelease(rel);
        return rel;
    }

    /** Âge de la liste pour ce site (ms), ou -1 si jamais lue. */
    static long age(Context ctx, String title, String host) {
        long at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(Titles.key(title) + "|" + host, 0);
        return at == 0 ? -1 : System.currentTimeMillis() - at;
    }

    // ------------------------------------------------------------------ Lecture des pages

    static Result fetch(String chapterUrl, double chapter, List<String> names) throws Exception {
        Result r = new Result();
        r.host = ChapterLinks.host(chapterUrl);
        if (SourceRegistry.isWebtoons(chapterUrl)) return webtoon(chapterUrl, r);
        Shape shape = Shape.of(chapterUrl, chapter);
        if (shape == null) return r;

        String html = http(chapterUrl, null);
        extract(html, chapterUrl, shape, r);
        if (complete(r, chapter)) return r;

        // Page de la série : liste complète des chapitres.
        for (String series : seriesCandidates(html, chapterUrl, shape, names)) {
            try {
                String sHtml = http(series, null);
                int before = r.chapters.size();
                extract(sHtml, series, shape, r);
                if (r.chapters.size() <= before + 1 && sHtml.matches("(?s).*(wp-manga|manga-chapters-holder|manga_get_chapters).*")) {
                    madaraAjax(series, sHtml, shape, r);
                }
                if (complete(r, chapter)) break;
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    /** Assez complet : la liste part du début et couvre le chapitre lu. */
    private static boolean complete(Result r, double chapter) {
        if (r.chapters.size() < 2) return false;
        return r.chapters.firstKey() <= 1.5 && r.chapters.lastKey() >= chapter
                && r.chapters.size() >= 0.6 * Math.floor(r.chapters.lastKey());
    }

    /** WEBTOON : dernier épisode public (RSS), adresses déduites du n° d'épisode. */
    private static Result webtoon(String url, Result r) throws Exception {
        String[] parsed = Detector.parseSeriesLink(url);
        if (parsed == null) return r;
        List<RssParser.Latest> items = RssParser.items(http(parsed[0] + "/rss?title_no=" + parsed[1], null));
        long last = 0;
        for (RssParser.Latest it : items) last = Math.max(last, it.episodeNo);
        for (long n = 1; n <= last && n <= 3000; n++) {
            r.chapters.put((double) n, parsed[0] + "/episode-" + n + "/viewer?title_no=" + parsed[1] + "&episode_no=" + n);
        }
        return r;
    }

    // ------------------------------------------------------------------ Forme des adresses

    /**
     * Forme d'une adresse de chapitre : le segment qui porte le numéro devient un motif (chiffres → #),
     * le reste est gardé tel quel. « /demonic-emperor-chapter-937/ » → « demonic-emperor-chapter-# ».
     */
    static final class Shape {
        String host;
        String[] segs;
        int var;
        String varShape;
        /** Les numéros de l'adresse sont des numéros de chapitre (sinon : des identifiants). */
        boolean urlNumbers;

        static Shape of(String url, double chapter) {
            try {
                URI u = new URI(url.trim());
                Shape s = new Shape();
                s.host = ChapterLinks.host(url);
                s.segs = segments(u.getRawPath());
                s.var = -1;
                for (int i = s.segs.length - 1; i >= 0 && s.var < 0; i--) {
                    if (s.segs[i].matches("(?i).*(?<![a-z0-9])(?:chapitres?|chapters?|chap|ch|episodes?|épisodes?|ep|c)[-_ .]?\\d+.*")) s.var = i;
                }
                for (int i = s.segs.length - 1; i >= 0 && s.var < 0; i--) if (s.segs[i].matches(".*\\d.*")) s.var = i;
                if (s.var < 0) return null;
                s.varShape = digits(s.segs[s.var]);
                double inUrl = ChapterLinks.chapterInUrl(url);
                s.urlNumbers = !Double.isNaN(inUrl) && Math.abs(inUrl - chapter) < 0.001;
                return s;
            } catch (Exception e) {
                return null;
            }
        }

        /** Même série sur le même site (seul le numéro diffère) ? */
        boolean matches(String url) {
            try {
                if (!host.equals(ChapterLinks.host(url))) return false;
                String[] o = segments(new URI(url.trim()).getRawPath());
                if (o.length != segs.length) return false;
                for (int i = 0; i < o.length; i++) {
                    if (i == var) {
                        if (!digits(o[i]).equalsIgnoreCase(varShape)) return false;
                    } else if (!o[i].equalsIgnoreCase(segs[i])) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Numéro lu dans le segment variable (dernier nombre). */
        double numberIn(String url) {
            try {
                String[] o = segments(new URI(url.trim()).getRawPath());
                Matcher m = Pattern.compile("(\\d+(?:[.\\-_]\\d{1,2})?)(?!.*\\d)").matcher(o[var]);
                if (m.find()) return Double.parseDouble(m.group(1).replace('-', '.').replace('_', '.'));
            } catch (Exception ignored) {
            }
            return Double.NaN;
        }

        private static String[] segments(String path) {
            if (path == null) return new String[0];
            String p = path.replaceAll("/+$", "").replaceAll("^/+", "");
            return p.isEmpty() ? new String[0] : p.split("/");
        }

        private static String digits(String seg) {
            return seg.replaceAll("\\d+(?:[.\\-_]\\d{1,2})?", "#");
        }
    }

    // ------------------------------------------------------------------ Extraction des liens

    private static final Pattern A = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern OPTION = Pattern.compile("(?is)<option\\b([^>]*)>(.*?)</option>");
    private static final Pattern HREF = Pattern.compile("(?is)(?:href|value|data-redirect|data-href|data-url)\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern LOCK_MARK = Pattern.compile(
            "(?iu)(premium|locked|\\block\\b|\\bvip\\b|coins?\\b|early[-_ ]?access|paywall|\\bpaid\\b|🔒|ticket|purchase|unlock)");

    /** Tous les liens de chapitres de la série dans cette page (numéro → adresse), + ceux marqués payants. */
    static void extract(String html, String pageUrl, Shape shape, Result r) {
        List<String[]> links = new ArrayList<>();   // {adresse absolue, texte, html du bloc}
        for (Pattern p : new Pattern[]{A, OPTION}) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                Matcher h = HREF.matcher(m.group(1));
                if (!h.find()) continue;
                String abs = absolute(pageUrl, h.group(1).replace("&amp;", "&").trim());
                if (abs == null || !shape.matches(abs)) continue;
                int end = Math.min(html.length(), m.end() + 300);
                int li = html.toLowerCase(Locale.ROOT).indexOf("</li>", m.end());
                if (li > 0 && li < end) end = li;
                links.add(new String[]{abs, Paywall.visibleText(m.group(2)).replaceAll("\\s+", " ").trim(),
                        m.group() + html.substring(m.end(), end)});
            }
        }
        TreeMap<Double, String> found = new TreeMap<>();
        TreeMap<Double, Boolean> marks = new TreeMap<>();
        for (String[] l : links) {
            double n = shape.urlNumbers ? ChapterLinks.chapterInUrl(l[0]) : Double.NaN;
            if (shape.urlNumbers && Double.isNaN(n)) n = shape.numberIn(l[0]);
            if (Double.isNaN(n)) n = numberIn(l[1]);
            if (Double.isNaN(n) || n < 0 || n > 5000) continue;
            if (!found.containsKey(n)) found.put(n, l[0]);
            if (LOCK_MARK.matcher(l[2]).find()) marks.put(n, true);
        }
        r.chapters.putAll(found);
        // Marques de chapitre payant : seulement si elles distinguent une minorité de chapitres
        // (sinon c'est un élément commun à toutes les lignes, pas un cadenas).
        if (!marks.isEmpty() && marks.size() <= found.size() * 0.5) r.locked.addAll(marks.keySet());
    }

    /** « Chapter 34 », « Chapitre 34 », « Ep. 34 », « Глава 34 », « 34화 », « 第34话 », « 34 »… */
    static double numberIn(String text) {
        if (text == null) return Double.NaN;
        Matcher m = Pattern.compile("(?iu)(?:chapitre|chapter|chap|ch|épisode|episode|episodio|ep|ép|cap[ií]tulo|capitulo|cap"
                + "|capitolo|kapitel|глава|розділ|chương|bab|bölüm|rozdział|ตอนที่|ตอน|第)\\.?\\s*#?\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)")
                .matcher(text);
        if (m.find()) return parse(m.group(1));
        Matcher k = Pattern.compile("(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(?:화|話|话|章|회)").matcher(text);
        if (k.find()) return parse(k.group(1));
        Matcher bare = Pattern.compile("^\\s*#?\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*$").matcher(text);
        if (bare.find()) return parse(bare.group(1));
        return Double.NaN;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String absolute(String base, String href) {
        try {
            if (href.startsWith("javascript:") || href.startsWith("#") || href.startsWith("mailto:")) return null;
            return new URI(base).resolve(href.replace(" ", "%20")).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Page de la série

    /** Pages de la série à essayer : chemin parent, lien au titre de la série, lien contenant son nom. */
    static List<String> seriesCandidates(String html, String chapterUrl, Shape shape, List<String> names) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String parent = ChapterLinks.seriesPage(chapterUrl);
        if (parent != null) out.add(parent);
        // Nom de la série tiré de l'adresse du chapitre : « demonic-emperor-chapter-937 » → « demonic-emperor ».
        String slug = shape.segs[shape.var].replaceAll("(?i)[-_]?(?:chapitres?|chapters?|chap|ch|episodes?|ep)[-_ .]?\\d.*$", "")
                .replaceAll("[-_]?\\d+$", "");
        Matcher m = A.matcher(html);
        while (m.find() && out.size() < 4) {
            Matcher h = HREF.matcher(m.group(1));
            if (!h.find()) continue;
            String abs = absolute(chapterUrl, h.group(1).replace("&amp;", "&").trim());
            if (abs == null || shape.matches(abs) || !shape.host.equals(ChapterLinks.host(abs))) continue;
            String text = Paywall.visibleText(m.group(2)).replaceAll("\\s+", " ").trim();
            boolean byName = !text.isEmpty() && text.length() < 150 && TitleMatch.best(text, names) >= TitleMatch.SAME;
            boolean bySlug = slug.length() >= 4 && abs.toLowerCase(Locale.ROOT).matches(".*/" + Pattern.quote(slug.toLowerCase(Locale.ROOT)) + "/?$");
            if (byName || bySlug) out.add(abs);
        }
        return new ArrayList<>(out);
    }

    /** Thèmes WordPress de lecture : la liste des chapitres se charge à part. */
    private static void madaraAjax(String seriesUrl, String html, Shape shape, Result r) {
        String base = seriesUrl.endsWith("/") ? seriesUrl : seriesUrl + "/";
        try {
            extract(post(base + "ajax/chapters/", "", seriesUrl), seriesUrl, shape, r);
            if (r.chapters.size() >= 2) return;
        } catch (Exception ignored) {
        }
        Matcher id = Pattern.compile("(?is)(?:data-id|data-post|manga_id|rating-post-id)[\"'=:\\s]+(\\d{2,9})").matcher(html);
        if (!id.find()) return;
        try {
            URI u = new URI(seriesUrl);
            String ajax = u.getScheme() + "://" + u.getHost() + "/wp-admin/admin-ajax.php";
            extract(post(ajax, "action=manga_get_chapters&manga=" + id.group(1), seriesUrl), seriesUrl, shape, r);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Réseau

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

    static String http(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", SourceRegistry.isWebtoons(url)
                ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36" : UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "fr,en;q=0.8");
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (SourceRegistry.isWebtoons(url)) {
            c.setRequestProperty("Referer", "https://www.webtoons.com/");
            c.setRequestProperty("Cookie", "needGDPR=false; needCCPA=false; needCOPPA=false");
        }
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static String post(String url, String body, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("Referer", referer);
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static String read(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
