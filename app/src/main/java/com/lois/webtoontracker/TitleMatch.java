package com.lois.webtoontracker;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deux titres désignent-ils la même série, même dans deux langues différentes ?
 *
 * « Reincarnated With Divine Titles » et « Réincarné avec des Titres Divins » : on retire les accents
 * et les petits mots (with, avec, des, the, le…), puis on apparie les mots restants, dans n'importe
 * quel ordre, en acceptant les mots apparentés (reincarn·ated / reincarn·é, divin·e / divin·s,
 * titl·es / titr·es). Le score va de 0 (rien à voir) à 1 (mêmes mots).
 *
 * Classe Java pure, testable sans Android.
 */
final class TitleMatch {

    private TitleMatch() {
    }

    /** Seuil au-delà duquel deux titres désignent la même série. */
    static final double SAME = 0.78;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            // anglais
            "the", "a", "an", "of", "with", "to", "in", "on", "and", "for", "my", "is", "as", "at", "by", "from",
            "his", "her", "its", "i", "me", "you", "your", "this", "that", "be", "was", "who", "into", "after", "s",
            // français
            "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "avec", "et", "en", "au", "aux", "mon", "ma",
            "mes", "ton", "ta", "tes", "son", "sa", "ses", "pour", "sur", "dans", "qui", "que", "ce", "cette", "je",
            "j", "il", "elle", "est", "par", "se", "ne", "pas",
            // espagnol, portugais, italien
            "el", "los", "las", "una", "del", "con", "y", "mi", "por", "para", "o", "os", "um", "uma", "do", "da",
            "dos", "das", "com", "em", "na", "lo", "gli", "uno", "di", "e", "nel", "della",
            // allemand
            "der", "die", "das", "ein", "eine", "einen", "mit", "und", "von", "im", "zu", "zum", "zur", "den", "dem",
            // indonésien
            "dengan", "yang", "di", "ke", "dan", "sang", "si"));

    /** Mots significatifs d'un titre, sans accents ni petits mots. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replace("&", " and ").replace("’", " ").replace("'", " ");
        for (String t : n.split("[^\\p{L}\\p{N}]+")) {
            if (t.isEmpty() || STOP.contains(t)) continue;
            if (t.length() == 1 && !Character.isDigit(t.charAt(0))) continue;
            out.add(t);
        }
        return out;
    }

    /** Proximité de deux mots : identiques, apparentés (même racine) ou presque identiques. */
    static double tokenSim(String a, String b) {
        if (a.equals(b)) return 1;
        if (isNumber(a) || isNumber(b)) return 0;          // un numéro de saison doit être identique
        int p = lcp(a, b), min = Math.min(a.length(), b.length());
        if (p >= 5 || (p >= 4 && min <= 6) || (p == min && min >= 4)) return 0.95;
        String sa = stem(a), sb = stem(b);
        if (sa.equals(sb) && sa.length() >= 3) return 0.9;
        int max = Math.max(sa.length(), sb.length());
        if (max < 4) return 0;
        double sim = 1 - lev(sa, sb) / (double) max;
        return sim >= 0.7 ? sim : 0;
    }

    /** Score de 0 à 1 : appariement des mots dans n'importe quel ordre (coefficient de Dice). */
    static double score(String a, String b) {
        List<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            String ka = Titles.key(a == null ? "" : a), kb = Titles.key(b == null ? "" : b);
            return !ka.isEmpty() && ka.equals(kb) ? 1 : 0;
        }
        if (ta.size() > tb.size()) {
            List<String> t = ta;
            ta = tb;
            tb = t;
        }
        boolean[] used = new boolean[tb.size()];
        double sum = 0;
        for (String x : ta) {
            int bi = -1;
            double bs = 0;
            for (int j = 0; j < tb.size(); j++) {
                if (used[j]) continue;
                double s = tokenSim(x, tb.get(j));
                if (s > bs) {
                    bs = s;
                    bi = j;
                }
            }
            if (bi >= 0) {
                used[bi] = true;
                sum += bs;
            }
        }
        return 2 * sum / (ta.size() + tb.size());
    }

    /** Meilleur score entre un titre et une liste de noms connus. */
    static double best(String candidate, Collection<String> names) {
        double s = 0;
        for (String n : names) if (n != null) s = Math.max(s, score(candidate, n));
        return s;
    }

    static boolean same(String a, String b) {
        return score(a, b) >= SAME;
    }

    /** Langue probable d'un titre (pour la traduction) : fr, es, de, id ou en par défaut. */
    static String guessLang(String title) {
        if (title == null) return "en";
        String t = " " + title.toLowerCase(Locale.ROOT) + " ";
        if (t.matches("(?s).*[\\uAC00-\\uD7AF].*")) return "ko";
        if (t.matches("(?s).*[\\u3040-\\u30FF].*")) return "ja";
        if (t.matches("(?s).*[\\u4E00-\\u9FFF].*")) return "zh";
        if (t.matches("(?s).*[ñ¿¡].*") || t.matches("(?s).* (el|los|las|del|con|y|una|mi) .*")) return "es";
        if (t.matches("(?s).*[àâçèéêëîïôûùüÿœ].*") || t.matches("(?s).* (le|la|les|des|du|avec|et|dans|une|un|mon|ma) .*")) return "fr";
        if (t.matches("(?s).*[äöüß].*") || t.matches("(?s).* (der|die|das|und|mit|ein|eine) .*")) return "de";
        if (t.matches("(?s).* (dengan|yang|dan|sang) .*")) return "id";
        return "en";
    }

    // ------------------------------------------------------------------ Outils

    private static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static int lcp(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static final String[] SUFFIXES = {"ations", "ation", "ements", "ement", "ments", "ment", "iques", "ique",
            "ness", "ings", "ing", "ies", "ied", "ees", "ee", "es", "ed", "er", "s", "e", "os", "as", "o", "a"};

    /** Racine grossière (anglais / français / espagnol) : titles → titl, titres → titr. */
    static String stem(String t) {
        for (String suf : SUFFIXES) {
            if (t.length() - suf.length() >= 3 && t.endsWith(suf)) return t.substring(0, t.length() - suf.length());
        }
        return t;
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
