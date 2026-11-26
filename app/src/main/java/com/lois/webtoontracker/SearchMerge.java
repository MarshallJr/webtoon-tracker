package com.lois.webtoontracker;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fusion, page après page, des résultats AniList et MangaDex d'une même recherche (Java pur, testable).
 *
 * <ul>
 * <li>Même œuvre, à coup sûr : même id AniList (MangaDex le connaît souvent), même id MyAnimeList ou même
 *     UUID MangaDex.</li>
 * <li>Sinon, il faut un titre identique une fois normalisé (principal ou alternatif, toutes langues) ET une
 *     confirmation (même année à un an près, ou un auteur commun) ET aucun indice contraire (pays, année,
 *     one-shot / série, auteurs différents). Dans le doute, on garde les deux fiches.</li>
 * <li>AniList prime : ses résultats gardent leur ordre ; une fiche MangaDex déjà affichée qu'une page
 *     AniList suivante reconnaît est remplacée sur place par la fiche AniList.</li>
 * <li>Les fiches MangaDex restantes s'intercalent selon la proximité de leur titre avec la recherche
 *     (titre exact, début, contenu), sans jamais passer devant un résultat AniList aussi proche.</li>
 * </ul>
 */
final class SearchMerge {

    /** Ce qu'une page change à la liste affichée. */
    static final class Page {
        /** Fiches à ajouter à la fin, dans l'ordre. */
        final List<Discover.Media> added = new ArrayList<>();
        /** Position dans la liste affichée → fiche AniList qui remplace la fiche MangaDex qui y était. */
        final Map<Integer, Discover.Media> replaced = new LinkedHashMap<>();
    }

    private final String query;
    private final List<Discover.Media> shown = new ArrayList<>();
    private final Map<Integer, Integer> byAnilist = new HashMap<>();
    private final Map<Integer, Integer> byMal = new HashMap<>();
    private final Map<String, Integer> byMangadex = new HashMap<>();
    private final Map<String, List<Integer>> byTitle = new HashMap<>();
    private final Map<Discover.Media, Set<String>> keys = new IdentityHashMap<>();   // titres normalisés, calculés une fois

    SearchMerge(String query) {
        this.query = norm(query);
    }

    /** Les fiches affichées jusqu'ici (même ordre que la grille). */
    List<Discover.Media> shown() {
        return Collections.unmodifiableList(shown);
    }

    /** Ajoute la page suivante de chaque source (listes vides si une source n'a rien donné). */
    Page add(List<Discover.Media> anilist, List<Discover.Media> mangadex) {
        Page page = new Page();

        // 1. AniList : gardée telle quelle, sauf ce qui est déjà affiché.
        List<Discover.Media> ani = new ArrayList<>();
        for (Discover.Media a : anilist) {
            Integer pos = shownMatch(a);
            if (pos == null) {
                if (!sameAsAny(a, ani)) ani.add(a);
                continue;
            }
            Discover.Media there = shown.get(pos);
            if (there.source == Discover.Source.MANGADEX && !page.replaced.containsKey(pos)) {
                shown.set(pos, a);   // la fiche AniList prend la place de la fiche MangaDex
                index(a, pos);
                page.replaced.put(pos, a);
            }
        }

        // 2. MangaDex : seulement ce qu'AniList n'a pas déjà (affiché ou dans cette page).
        List<Discover.Media> md = new ArrayList<>();
        for (Discover.Media m : mangadex) {
            if (shownMatch(m) != null || sameAsAny(m, ani) || sameAsAny(m, md)) continue;
            md.add(m);
        }

        // 3. Ordre : celui d'AniList, avec les fiches MangaDex placées selon la proximité de leur titre.
        for (Discover.Media m : interleave(ani, md)) {
            int pos = shown.size();
            shown.add(m);
            index(m, pos);
            page.added.add(m);
        }
        return page;
    }

    // ------------------------------------------------------------------ Identité

    /** Position d'une fiche déjà affichée qui désigne la même œuvre, ou null. */
    private Integer shownMatch(Discover.Media m) {
        if (m.id > 0 && byAnilist.containsKey(m.id)) return byAnilist.get(m.id);
        if (m.malId > 0 && byMal.containsKey(m.malId)) {
            Integer pos = byMal.get(m.malId);
            if (same(m, shown.get(pos))) return pos;
        }
        if (m.extId != null && byMangadex.containsKey(m.extId)) return byMangadex.get(m.extId);
        Set<Integer> seen = new LinkedHashSet<>();
        for (String k : keys(m)) {
            List<Integer> l = byTitle.get(k);
            if (l != null) seen.addAll(l);
        }
        for (Integer pos : seen) if (same(m, shown.get(pos))) return pos;
        return null;
    }

    private boolean sameAsAny(Discover.Media m, List<Discover.Media> list) {
        for (Discover.Media o : list) if (same(m, o)) return true;
        return false;
    }

    private boolean same(Discover.Media a, Discover.Media b) {
        return sameWork(a, keys(a), b, keys(b));
    }

    private Set<String> keys(Discover.Media m) {
        Set<String> k = keys.get(m);
        if (k == null) {
            k = titleKeys(m);
            keys.put(m, k);
        }
        return k;
    }

    private void index(Discover.Media m, int pos) {
        if (m.id > 0) byAnilist.put(m.id, pos);
        if (m.malId > 0) byMal.put(m.malId, pos);
        if (m.extId != null) byMangadex.put(m.extId, pos);
        for (String k : keys(m)) {
            List<Integer> l = byTitle.get(k);
            if (l == null) {
                l = new ArrayList<>();
                byTitle.put(k, l);
            }
            if (!l.contains(pos)) l.add(pos);
        }
    }

    /**
     * Même œuvre ? Les identifiants tranchent quand les deux fiches en ont un du même type ; sinon il faut
     * un titre commun, une confirmation et aucun indice contraire.
     */
    static boolean sameWork(Discover.Media a, Discover.Media b) {
        return sameWork(a, titleKeys(a), b, titleKeys(b));
    }

    private static boolean sameWork(Discover.Media a, Set<String> ka, Discover.Media b, Set<String> kb) {
        if (a == b) return true;
        if (a.id > 0 && b.id > 0) return a.id == b.id;
        if (a.malId > 0 && b.malId > 0) return a.malId == b.malId;
        if (a.extId != null && a.extId.equals(b.extId)) return true;
        if (Collections.disjoint(ka, kb)) return false;   // aucun titre commun
        if (a.country != null && b.country != null && !a.country.equals(b.country)) return false;
        if (a.year > 0 && b.year > 0 && Math.abs(a.year - b.year) > 1) return false;
        if (a.format != null && b.format != null && "ONE_SHOT".equals(a.format) != "ONE_SHOT".equals(b.format)) return false;
        boolean authorsKnown = !a.authors.isEmpty() && !b.authors.isEmpty();
        boolean sameAuthor = authorsKnown && sharesAuthor(a, b);
        if (authorsKnown && !sameAuthor) return false;
        boolean sameYear = a.year > 0 && b.year > 0;   // (écart ≤ 1 vérifié plus haut)
        return sameYear || sameAuthor;
    }

    private static boolean sharesAuthor(Discover.Media a, Discover.Media b) {
        Set<String> ka = new LinkedHashSet<>();
        for (String n : a.authors) ka.addAll(nameParts(n));
        for (String n : b.authors) for (String p : nameParts(n)) if (ka.contains(p)) return true;
        return false;
    }

    /** « Jang Sung-Lak (장성락) » → {jangsunglak, 장성락} : le nom et sa graphie d'origine. */
    private static Set<String> nameParts(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : name.split("[()（）/,、]")) {
            String k = norm(part);
            if (k.codePointCount(0, k.length()) >= 2) out.add(k);
        }
        return out;
    }

    /** Titres normalisés assez longs pour servir de preuve (3 caractères au moins). */
    static Set<String> titleKeys(Discover.Media m) {
        Set<String> out = new LinkedHashSet<>();
        List<String> all = new ArrayList<>(m.names);
        if (m.title != null) all.add(0, m.title);
        for (String n : all) {
            String k = norm(n);
            if (k.codePointCount(0, k.length()) >= 3) out.add(k);
        }
        return out;
    }

    /**
     * Normalisation : pleine chasse → normale, minuscules, accents latins retirés, seules les lettres et les
     * chiffres restent (toutes écritures : 나 혼자만 레벨업, 全职高手…). « Solo Leveling! » = « solo-leveling ».
     */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = LATIN_MARKS.matcher(Normalizer.normalize(t, Normalizer.Form.NFD)).replaceAll("");
        t = Normalizer.normalize(t, Normalizer.Form.NFC);
        return NOT_ALNUM.matcher(t).replaceAll("");
    }

    private static final Pattern LATIN_MARKS = Pattern.compile("[\\u0300-\\u036f]+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    // ------------------------------------------------------------------ Ordre

    /** 0 : titre exact, 1 : commence par la recherche, 2 : la contient, 3 : autre. */
    int tier(Discover.Media m) {
        if (query.isEmpty()) return 3;
        int best = 3;
        for (String k : keys(m)) {
            if (k.equals(query)) return 0;
            if (k.startsWith(query)) best = Math.min(best, 1);
            else if (k.contains(query)) best = Math.min(best, 2);
        }
        return best;
    }

    /** AniList dans son ordre ; une fiche MangaDex passe devant les résultats AniList moins proches qu'elle. */
    private List<Discover.Media> interleave(List<Discover.Media> ani, List<Discover.Media> md) {
        List<Discover.Media> mds = new ArrayList<>(md);
        final Map<Discover.Media, Integer> tiers = new HashMap<>();
        for (Discover.Media m : mds) tiers.put(m, tier(m));
        Collections.sort(mds, (x, y) -> Integer.compare(tiers.get(x), tiers.get(y)));   // tri stable
        List<Discover.Media> out = new ArrayList<>(ani.size() + mds.size());
        int i = 0, j = 0;
        while (i < ani.size() || j < mds.size()) {
            if (j >= mds.size()) out.add(ani.get(i++));
            else if (i >= ani.size()) out.add(mds.get(j++));
            else if (tiers.get(mds.get(j)) < tier(ani.get(i))) out.add(mds.get(j++));
            else out.add(ani.get(i++));
        }
        return out;
    }
}
