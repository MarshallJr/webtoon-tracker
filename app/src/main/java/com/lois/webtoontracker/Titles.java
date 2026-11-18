package com.lois.webtoontracker;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Outils sur les titres : détection des doublons ("Tower of God" / "Tower Of God (S3)"). Java pur. */
public final class Titles {

    private Titles() {}

    /** Clé de comparaison : minuscules, sans accents, sans "saison 3", "(S2)", ponctuation… */
    public static String key(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\((?:s|saison|season)\\s*\\d+\\)", " ");
        s = s.replaceAll("\\b(?:saison|season|s)\\s*\\d+\\b", " ");
        s = s.replaceAll("\\b(?:the|le|la|les)\\b", " ");
        s = s.replaceAll("[^a-z0-9]+", "");
        return s;
    }

    /** Regroupe les titres qui désignent probablement la même série (groupes de 2+). */
    public static List<List<String>> duplicateGroups(List<String> titles) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String t : titles) {
            String k = key(t);
            if (k.isEmpty()) continue;
            List<String> g = map.get(k);
            if (g == null) {
                g = new ArrayList<>();
                map.put(k, g);
            }
            g.add(t);
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> g : map.values()) if (g.size() > 1) out.add(g);
        return out;
    }

    /** Liste de tags "Action, Romance" → texte propre et dédoublonné. */
    public static String normalizeTags(String raw) {
        if (raw == null) return "";
        Map<String, String> seen = new LinkedHashMap<>();
        for (String t : raw.split(",")) {
            String tag = t.trim().replaceAll("\\s+", " ");
            if (tag.isEmpty()) continue;
            tag = Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
            seen.put(tag.toLowerCase(Locale.ROOT), tag);
        }
        return String.join(", ", seen.values());
    }

    public static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        for (String t : tags.split(",")) {
            String s = t.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
