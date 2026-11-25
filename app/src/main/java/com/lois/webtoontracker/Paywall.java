package com.lois.webtoontracker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Écran de paiement : le chapitre ouvert est-il verrouillé (pièces, accès anticipé, connexion…) ?
 *
 * Sur l'écran : un message de verrou ET presque pas de cases de webtoon affichées. Les deux sont
 * nécessaires — une page gratuite peut parler du chapitre suivant « à débloquer » sans être verrouillée.
 * En ligne (pour savoir quand il redevient gratuit) : la page annonce bien ce chapitre, ne contient
 * presque aucune image du chapitre, et affiche un message de verrou.
 *
 * L'appli ne contourne rien : elle constate qu'un chapitre est payant, ne le compte pas comme lu,
 * et te prévient quand le site le rend gratuit.
 */
final class Paywall {

    private Paywall() {
    }

    /** Messages d'un chapitre verrouillé (anglais, français, espagnol, portugais, indonésien, allemand). */
    static final Pattern LOCKED = Pattern.compile("(?iu)("
            + "this (?:chapter|episode) is (?:locked|premium|paid|not (?:yet )?(?:free|available))"
            + "|(?:chapter|episode) (?:is )?locked|locked (?:chapter|episode)"
            + "|unlock (?:this |the )?(?:chapter|episode)|unlock (?:it |now )?(?:for|with) \\d+"
            + "|unlock (?:with|using) (?:coins?|points?|ink|tickets?|a pass)|unlock now"
            + "|(?:buy|purchase) (?:this |the )?(?:chapter|episode)"
            + "|premium (?:chapter|episode)|early access (?:chapter|episode)|(?:available|read) (?:in|with) early access"
            + "|this is an early access|fast ?pass (?:episode|only)|read with (?:a )?(?:fast|daily|ad) ?pass"
            + "|use (?:a |your )?(?:daily|ad|fast) ?pass|wait (?:for|until) (?:it(?:'s| is) )?free|wait or pay"
            + "|(?:free|unlocks?) in \\d+\\s*(?:d|day|days|h|hr|hrs|hour|hours|min|mins|minutes)\\b"
            + "|(?:log ?in|sign ?in) to (?:read|unlock|continue reading)|(?:not enough|insufficient) (?:coins?|points?)"
            + "|coins? (?:to|needed to|required to) unlock"
            + "|ce chapitre est (?:verrouillé|payant|premium|bloqué)|chapitre (?:verrouillé|payant|premium|bloqué)"
            + "|épisode (?:verrouillé|payant)|(?:débloquer|déverrouiller|acheter) (?:ce |le |cet )?(?:chapitre|épisode)"
            + "|(?:débloque[rz]?|déverrouille[rz]?) (?:pour|avec) \\d+|accès anticipé"
            + "|gratuit dans \\d+|(?:attends?|attendez|attendre) (?:pour |et )?(?:le )?lire gratuitement"
            + "|connecte(?:z|-toi|-vous)? (?:pour|afin de) (?:lire|débloquer)|pièces? (?:pour|nécessaires? pour) débloquer"
            + "|pas assez de pièces"
            + "|cap[ií]tulo bloqueado|desbloquear (?:este |el )?(?:cap[ií]tulo|episodio)|episodio bloqueado"
            + "|gratis en \\d+|grátis em \\d+|bab terkunci|buka kunci (?:bab|episode)|kapitel (?:ist )?gesperrt"
            + "|kapitel freischalten"
            + ")");

    /** Classes HTML des extensions de chapitres payants (thèmes de sites de lecture). */
    private static final Pattern LOCK_MARKUP = Pattern.compile(
            "(?i)class=\"[^\"]*\\b(?:locked-chapter|chapter-locked|lock-chapter|paywall|premium-block|premium-chapter"
                    + "|chapter-premium|coin-required|unlock-chapter|purchase-chapter|protected-chapter|chapter-lock)\\b");

    /** « Gratuit dans 2 j », « Free in 1d 5h », « Unlocks in 3 days »… */
    private static final Pattern FREE_IN = Pattern.compile(
            "(?iu)(?:free|gratuit|gratis|grátis|unlocks?|déblocage|débloqué|disponible|available|dispo)\\D{0,30}?"
                    + "(?:in|dans|en|em|after|après|within)\\s*(\\d{1,3})\\s*"
                    + "(d|j|days?|jours?|días?|dias?|h|hrs?|hours?|heures?|horas?|m|mins?|minutes?|minutos?)\\b"
                    + "(?:\\s*(\\d{1,2})\\s*(h|hrs?|hours?|heures?|horas?|m|mins?|minutes?|minutos?)\\b)?");
    /** Compte à rebours « 1d 23:59:10 » ou « 23:59 » près d'un mot « gratuit / unlock ». */
    private static final Pattern COUNTDOWN = Pattern.compile(
            "(?iu)(?:free|gratuit|gratis|unlock|débloqu|disponible|available)\\D{0,40}?"
                    + "(?:(\\d{1,2})\\s*(?:d|j|days?|jours?)\\s*)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    static boolean textSaysLocked(String text) {
        return text != null && LOCKED.matcher(text).find();
    }

    /** Texte visible à l'écran, une ligne par élément. */
    static String join(List<Detector.TextNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Detector.TextNode n : nodes) sb.append(n.text).append('\n');
        return sb.toString();
    }

    /** Délai avant que le chapitre devienne gratuit, s'il est annoncé (ms), sinon -1. */
    static long freeInMs(String text) {
        if (text == null) return -1;
        // D'abord un compte à rebours (« 1d 23:59:10 »), plus précis ; sinon « dans 2 j 5 h ».
        Matcher c = COUNTDOWN.matcher(text);
        if (c.find()) {
            long days = c.group(1) == null ? 0 : Long.parseLong(c.group(1));
            long ms = days * 86_400_000L + Long.parseLong(c.group(2)) * 3_600_000L + Long.parseLong(c.group(3)) * 60_000L
                    + (c.group(4) == null ? 0 : Long.parseLong(c.group(4)) * 1000L);
            if (ms > 0) return ms;
        }
        Matcher m = FREE_IN.matcher(text);
        if (m.find()) {
            long ms = unit(Long.parseLong(m.group(1)), m.group(2));
            if (m.group(3) != null) ms += unit(Long.parseLong(m.group(3)), m.group(4));
            return ms > 0 && ms < 120L * 24 * 3600 * 1000 ? ms : -1;
        }
        return -1;
    }

    private static long unit(long n, String u) {
        String s = u.toLowerCase();
        if (s.startsWith("d") || s.startsWith("j")) return n * 86_400_000L;
        if (s.startsWith("h")) return n * 3_600_000L;
        return n * 60_000L;
    }

    // ------------------------------------------------------------------ Vérification en ligne

    static final int MISSING = 0, FREE = 1, LOCKED_STATE = 2, UNKNOWN = 3;

    static final class Probe {
        int state = UNKNOWN;
        /** La page annonce bien ce chapitre (titre ou en-tête) : elle existe. */
        boolean announced;
        int images;
        boolean lockText;
        long freeAt;   // date annoncée (0 = inconnue)

        @Override
        public String toString() {
            return new String[]{"absent", "gratuit", "payant", "inconnu"}[state] + " (" + images + " images)";
        }
    }

    /** Classement d'une page de chapitre déjà téléchargée (testable). */
    static Probe classify(String html, String finalUrl, double ch, List<String> chapterImages, long now) {
        Probe p = new Probe();
        String n = Detector.formatChapter(ch);
        if (finalUrl != null && !finalUrl.contains(n)) {
            // Renvoyé ailleurs : vers une page de connexion / d'achat = verrouillé, sinon le chapitre n'existe pas.
            p.state = finalUrl.matches("(?i).*(login|log-in|signin|sign-in|connexion|account|register|premium|coins?|purchase|unlock|subscribe).*")
                    ? LOCKED_STATE : MISSING;
            return p;
        }
        String text = visibleText(html);
        Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        String pageTitle = t.find() ? t.group(1) : "";
        if (pageTitle.matches("(?is).*(not found|introuvable|404|page non trouvée).*")) {
            p.state = MISSING;
            return p;
        }
        Pattern num = Pattern.compile("(?i)(chap(?:ter|itre)?|ch\\.?|[ée]pisode|ep\\.?)\\s*[-#:]?\\s*0*"
                + Pattern.quote(n) + "(?![0-9]|\\.[0-9])");
        Matcher h1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html);
        boolean announces = num.matcher(pageTitle).find() || (h1.find() && num.matcher(h1.group(1).replaceAll("<[^>]+>", " ")).find());
        p.announced = announces;
        p.images = chapterImages == null ? 0 : chapterImages.size();
        p.lockText = textSaysLocked(text) || LOCK_MARKUP.matcher(html).find();
        if (!announces && !p.lockText) {
            p.state = MISSING;
            return p;
        }
        if (p.images >= 5) {
            p.state = FREE;
        } else if (p.lockText) {
            p.state = LOCKED_STATE;
            long in = freeInMs(text);
            if (in > 0) p.freeAt = now + in;
            Matcher epoch = Pattern.compile("(?i)data-(?:countdown|time|release|unlock|free)[a-z_-]*=\"(\\d{10,13})\"").matcher(html);
            if (p.freeAt == 0 && epoch.find()) {
                long v = Long.parseLong(epoch.group(1));
                if (v < 100_000_000_000L) v *= 1000;
                if (v > now && v < now + 120L * 24 * 3600 * 1000) p.freeAt = v;
            }
        } else {
            p.state = UNKNOWN;   // page construite par script : impossible de trancher
        }
        return p;
    }

    /** Texte visible d'une page (sans scripts ni styles). */
    static String visibleText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&rsquo;", "'").replace("&quot;", "\"");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
    }

    /**
     * Télécharge la page du chapitre et la classe : absent, gratuit, payant ou inconnu.
     * @param controlUrl un chapitre que tu as lu sur ce même site : si lui aussi « paraît » payant,
     *                   c'est que le site construit ses pages par script — on ne tranche pas.
     */
    static Probe probe(String url, double ch, String controlUrl, double controlCh) {
        Probe p;
        try {
            p = fetch(url, ch);
        } catch (Exception e) {
            return new Probe();
        }
        if (p.state == LOCKED_STATE && controlUrl != null && !controlUrl.equals(url)) {
            String host = ChapterLinks.host(controlUrl);
            long[] cached = host == null ? null : CONTROL.get(host);
            boolean reliable;
            if (cached != null && System.currentTimeMillis() - cached[0] < 6L * 3600 * 1000) {
                reliable = cached[1] == 1;
            } else {
                try {
                    reliable = fetch(controlUrl, controlCh).state == FREE;
                } catch (Exception e) {
                    reliable = false;
                }
                if (host != null) CONTROL.put(host, new long[]{System.currentTimeMillis(), reliable ? 1 : 0});
            }
            if (!reliable) p.state = UNKNOWN;
        }
        return p;
    }

    /** Par site : la page d'un chapitre lu montre-t-elle ses images (alors un verdict « payant » est fiable) ? */
    private static final java.util.Map<String, long[]> CONTROL = new java.util.concurrent.ConcurrentHashMap<>();

    private static Probe fetch(String url, double ch) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "fr,en;q=0.8");
        try {
            int code = c.getResponseCode();
            Probe p = new Probe();
            if (code == 404 || code == 410) {
                p.state = MISSING;
                return p;
            }
            if (code == 401 || code == 402) {
                p.state = LOCKED_STATE;
                return p;
            }
            if (code != 200) return p;   // protection anti-robot, erreur : inconnu
            String html;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) > 0 && bo.size() < 3_000_000) bo.write(buf, 0, r);
                html = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            String finalUrl = c.getURL().toString();
            return classify(html, finalUrl, ch, SiteThumbs.chapterImages(html, finalUrl), System.currentTimeMillis());
        } finally {
            c.disconnect();
        }
    }
}
