package com.lois.webtoontracker;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registre des sources : quelles plateformes publient officiellement des webtoons, en quelle langue,
 * avec quel modèle (gratuit, attente, payant), et comment classer une adresse.
 *
 * Catégories (voir {@link Editions}) :
 *  1. OFFICIELLE : lien donné comme officiel par une base de référence de la série (AniList, MangaDex)
 *                  ou trouvé directement sur une plateforme sous licence (WEBTOON).
 *  2. AUTORISÉE  : publiée par l'auteur lui-même (WEBTOON Canvas), ou ajoutée par toi avec une preuve
 *                  lue sur un canal officiel de la série.
 *  3. INCONNUE / NON AUTORISÉE : tout le reste. Jamais cherchée, jamais référencée.
 *
 * Classe Java pure (testable sans Android).
 */
final class SourceRegistry {

    private SourceRegistry() {
    }

    static final int OFFICIAL = 1, AUTHORIZED = 2, UNKNOWN = 3;

    /** Modèles de lecture d'une plateforme. */
    static final String FREE = "free", WAIT = "wait", PREVIEW = "preview", PAID = "paid", FASTPASS = "fastpass";

    static final class Platform {
        final String name;
        final String lang;    // langue par défaut (null : dépend de l'adresse)
        final String model;   // null : inconnu / variable selon la série

        Platform(String name, String lang, String model) {
            this.name = name;
            this.lang = lang;
            this.model = model;
        }
    }

    /** Domaine (ou fin de domaine) → plateforme. Le plus précis d'abord. */
    private static final Map<String, Platform> PLATFORMS = new LinkedHashMap<>();

    static {
        // Corée
        put("comic.naver.com", "Naver Webtoon", "ko", PREVIEW);
        put("series.naver.com", "Naver Series", "ko", null);
        put("page.kakao.com", "KakaoPage", "ko", WAIT);
        put("webtoon.kakao.com", "Kakao Webtoon", "ko", WAIT);
        put("ridibooks.com", "RIDI", "ko", null);
        put("mrblue.com", "Mr.Blue", "ko", null);
        put("bomtoon.com", "Bomtoon", "ko", null);
        put("toptoon.com", "Toptoon", "ko", null);
        put("lezhin.com", "Lezhin", null, PAID);
        // International
        put("webtoons.com", "WEBTOON", null, FASTPASS);
        put("tapas.io", "Tapas", "en", null);
        put("tappytoon.com", "Tappytoon", null, PAID);
        put("manta.net", "Manta", "en", PAID);
        put("pocketcomics.com", "Pocket Comics", null, null);
        put("toomics.com", "Toomics", null, null);
        put("inkr.com", "INKR", "en", null);
        put("comikey.com", "Comikey", "en", null);
        put("globalcomix.com", "GlobalComix", null, null);
        put("mangaplus.shueisha.co.jp", "MANGA Plus", null, null);
        put("viz.com", "VIZ", "en", null);
        put("kmanga.kodansha.com", "K MANGA", "en", null);
        put("azuki.co", "Azuki", "en", null);
        put("mangamo.com", "Mangamo", "en", null);
        put("global.bookwalker.jp", "BOOK☆WALKER", "en", PAID);
        // Japon
        put("fr.piccoma.com", "Piccoma", "fr", WAIT);
        put("piccoma.com", "Piccoma", "ja", WAIT);
        put("manga.line.me", "LINE Manga", "ja", WAIT);
        put("shonenjumpplus.com", "Shōnen Jump+", "ja", null);
        put("zebrack-comic.shueisha.co.jp", "Zebrack", "ja", null);
        put("comic-walker.com", "ComicWalker", "ja", null);
        put("comic-days.com", "Comic Days", "ja", null);
        put("bookwalker.jp", "BOOK☆WALKER", "ja", PAID);
        put("ebookjapan.yahoo.co.jp", "ebookjapan", "ja", PAID);
        // Chine
        put("manga.bilibili.com", "Bilibili Manga", "zh", null);
        put("bilibilicomics.com", "Bilibili Comics", "en", null);
        put("ac.qq.com", "Tencent Comics", "zh", null);
        put("kuaikanmanhua.com", "Kuaikan", "zh", null);
        put("dongmanmanhua.cn", "WEBTOON (Chine)", "zh", null);
        // France
        put("delitoon.com", "Delitoon", "fr", null);
        put("verytoon.com", "Verytoon", "fr", null);
        put("webtoonfactory.com", "Webtoon Factory", "fr", null);
        put("izneo.com", "izneo", "fr", PAID);
        put("mangas.io", "Mangas.io", "fr", PAID);
    }

    private static void put(String host, String name, String lang, String model) {
        PLATFORMS.put(host, new Platform(name, lang, model));
    }

    static String host(String url) {
        try {
            String h = new URI(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^(www|m)\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    /** La plateforme connue derrière cette adresse, ou null. */
    static Platform platform(String url) {
        String h = host(url);
        if (h == null) return null;
        for (Map.Entry<String, Platform> e : PLATFORMS.entrySet()) {
            String k = e.getKey();
            if (h.equals(k) || h.endsWith("." + k)) return e.getValue();
        }
        return null;
    }

    /** Nom à afficher : la plateforme si elle est connue, sinon le domaine. */
    static String name(String url) {
        Platform p = platform(url);
        if (p != null) return p.name;
        String h = host(url);
        return h == null ? "site" : h;
    }

    /** « FR · WEBTOON », « comix.to » : langue et plateforme (ou site) d'une adresse de lecture. */
    static String badge(String url) {
        if (url == null || !url.matches("(?i)^https?://.+")) return null;
        String lang = langFromUrl(url);
        return (lang == null ? "" : lang.toUpperCase(java.util.Locale.ROOT) + " · ") + name(url);
    }

    /** « Épisode » sur WEBTOON, « Chapitre » ailleurs. */
    static String unit(String url) {
        return url != null && isWebtoons(url) ? "Épisode" : "Chapitre";
    }

    /** « ép. » sur WEBTOON, « ch. » ailleurs. */
    static String unitShort(String url) {
        return url != null && isWebtoons(url) ? "ép." : "ch.";
    }

    static boolean isWebtoons(String url) {
        String h = host(url);
        return h != null && (h.equals("webtoons.com") || h.endsWith(".webtoons.com"));
    }

    /** WEBTOON CANVAS : publié par l'auteur lui-même. */
    static boolean isCanvas(String url) {
        return isWebtoons(url) && url.matches("(?i).*webtoons\\.com/[a-z-]+/(canvas|challenge)/.*");
    }

    /**
     * Catégorie d'une adresse trouvée par l'appli.
     * @param fromReference vrai si le lien vient d'une base de référence de la série (AniList, MangaDex)
     *                      ou directement d'une plateforme sous licence
     */
    static int classify(String url, boolean fromReference) {
        if (url == null || !url.matches("(?i)^https?://.+")) return UNKNOWN;
        if (isCanvas(url)) return AUTHORIZED;
        if (fromReference) return OFFICIAL;
        return UNKNOWN;
    }

    // ------------------------------------------------------------------ Langues

    /** Langue lue dans l'adresse : /fr/, /es/, fr.site.com, ?lang=de… (null si rien de sûr). */
    static String langFromUrl(String url) {
        if (url == null) return null;
        if (isWebtoons(url)) {
            Matcher w = Pattern.compile("(?i)webtoons\\.com/([a-z]{2}(?:-[a-z]+)?)/").matcher(url);
            if (w.find()) return normalize(w.group(1));
        }
        Matcher m = Pattern.compile("(?i)^https?://[^/]+/(ko|ja|en|fr|es|de|it|pt|pt-br|id|th|vi|ru|tr|pl|zh|zh-hant|zh-tw|zh-hk|es-la|mx|br)(?:/|$|\\?)")
                .matcher(url);
        if (m.find()) return normalize(m.group(1));
        Matcher sub = Pattern.compile("(?i)^https?://(ko|ja|en|fr|es|de|it|pt|id|th|vi|ru|tr|pl)\\.").matcher(url);
        if (sub.find()) return normalize(sub.group(1));
        Matcher q = Pattern.compile("(?i)[?&](?:lang|language|locale)=([a-z]{2}(?:[-_][a-z]{2,4})?)").matcher(url);
        if (q.find()) return normalize(q.group(1));
        return null;
    }

    /** Codes homogènes : « zh-tw » → « zh-hant », « mx » → « es-la », « br » → « pt-br ». */
    static String normalize(String code) {
        if (code == null || code.isEmpty()) return null;
        String c = code.toLowerCase(Locale.ROOT).replace('_', '-');
        switch (c) {
            case "zh-tw":
            case "zh-hant":
            case "tw":
                return "zh-hant";
            case "mx":
            case "es-419":
            case "es-mx":
                return "es-la";
            case "br":
                return "pt-br";
            case "jp":
                return "ja";
            case "kr":
                return "ko";
            case "cn":
            case "zh-cn":
            case "zh-hans":
                return "zh";
            default:
                return c;
        }
    }

    /** Nom de langue AniList (« Korean ») → code. */
    static String codeFromName(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "korean": return "ko";
            case "japanese": return "ja";
            case "chinese": return "zh";
            case "english": return "en";
            case "french": return "fr";
            case "spanish": return "es";
            case "german": return "de";
            case "italian": return "it";
            case "portuguese": return "pt-br";
            case "indonesian": return "id";
            case "thai": return "th";
            case "vietnamese": return "vi";
            case "russian": return "ru";
            case "turkish": return "tr";
            case "polish": return "pl";
            case "arabic": return "ar";
            case "filipino":
            case "tagalog": return "tl";
            case "malaysian":
            case "malay": return "ms";
            case "dutch": return "nl";
            case "swedish": return "sv";
            case "hindi": return "hi";
            default: return null;
        }
    }

    /** Pays d'origine AniList (KR, JP, CN, TW) → langue originale. */
    static String originLang(String country) {
        if (country == null) return null;
        switch (country.toUpperCase(Locale.ROOT)) {
            case "KR": return "ko";
            case "JP": return "ja";
            case "CN": return "zh";
            case "TW": return "zh-hant";
            default: return null;
        }
    }

    static String flag(String code) {
        if (code == null) return "🌐";
        switch (normalize(code)) {
            case "ko": return "🇰🇷";
            case "ja": return "🇯🇵";
            case "zh": return "🇨🇳";
            case "zh-hk": return "🇭🇰";
            case "zh-hant": return "🇹🇼";
            case "en": return "🇬🇧";
            case "fr": return "🇫🇷";
            case "es": return "🇪🇸";
            case "es-la": return "🇲🇽";
            case "de": return "🇩🇪";
            case "it": return "🇮🇹";
            case "pt": return "🇵🇹";
            case "pt-br": return "🇧🇷";
            case "id": return "🇮🇩";
            case "th": return "🇹🇭";
            case "vi": return "🇻🇳";
            case "ru": return "🇷🇺";
            case "tr": return "🇹🇷";
            case "pl": return "🇵🇱";
            case "ar": return "🇸🇦";
            case "tl": return "🇵🇭";
            case "ms": return "🇲🇾";
            case "nl": return "🇳🇱";
            case "sv": return "🇸🇪";
            case "hi": return "🇮🇳";
            default: return "🌐";
        }
    }

    static String langName(String code) {
        if (code == null) return "Langue inconnue";
        switch (normalize(code)) {
            case "ko": return "Coréen";
            case "ja": return "Japonais";
            case "zh": return "Chinois";
            case "zh-hk": return "Chinois (Hong Kong)";
            case "zh-hant": return "Chinois traditionnel";
            case "en": return "Anglais";
            case "fr": return "Français";
            case "es": return "Espagnol";
            case "es-la": return "Espagnol (Amérique latine)";
            case "de": return "Allemand";
            case "it": return "Italien";
            case "pt": return "Portugais";
            case "pt-br": return "Portugais (Brésil)";
            case "id": return "Indonésien";
            case "th": return "Thaï";
            case "vi": return "Vietnamien";
            case "ru": return "Russe";
            case "tr": return "Turc";
            case "pl": return "Polonais";
            case "ar": return "Arabe";
            case "tl": return "Philippin";
            case "ms": return "Malais";
            case "nl": return "Néerlandais";
            case "sv": return "Suédois";
            case "hi": return "Hindi";
            default: return code.toUpperCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ Numéros d'épisodes

    /** « Episode 34 », « Épisode 34 », « Episodio 34 », « Kapitel 34 », « ตอนที่ 34 », « 第34話 », « Ep. 34 »… */
    private static final Pattern EP_TITLE = Pattern.compile(
            "(?iu)(?:(?<![\\p{L}\\p{N}])(?:épisode|episode|episodio|epizod|ep|ép|chapitre|chapter|chap|ch|cap[ií]tulo|cap|kapitel|bölüm|bab|odcinek|ตอนที่|ตอน)\\.?\\s*|第\\s*|#)"
                    + "(\\d{1,4}(?:[.,]\\d{1,2})?)(?![\\p{N}])");
    /** Saisons numérotées à part (« [Season 2] Ep. 1 ») : le numéro du titre repart à 1. */
    private static final Pattern SEASON = Pattern.compile(
            "(?iu)(season|saison|temporada|staffel|stagione|musim|sezon|ซีซั่น|第\\s*\\d+\\s*季|\\(s\\d+\\)|\\[s\\d+\\]|\\bs\\d+\\b)");

    /** Numéro de chapitre lu dans un titre d'épisode, ou NaN (saisons : NaN, on garde le n° d'épisode). */
    static double numberInTitle(String title) {
        if (title == null || SEASON.matcher(title).find()) return Double.NaN;
        Matcher m = EP_TITLE.matcher(title);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ Réseaux sociaux

    /** Domaines où une page est celle d'un compte : la preuve doit venir du compte officiel lui-même. */
    static boolean isSocial(String host) {
        if (host == null) return false;
        return host.matches("(?i)(.*\\.)?(x\\.com|twitter\\.com|instagram\\.com|facebook\\.com|tiktok\\.com|youtube\\.com|"
                + "threads\\.net|bsky\\.app|weibo\\.com|blog\\.naver\\.com|patreon\\.com|ko-fi\\.com|tumblr\\.com|pixiv\\.net)$");
    }
}
