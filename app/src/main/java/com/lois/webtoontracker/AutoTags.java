package com.lois.webtoontracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Traduit les genres / tags AniList en tags français propres (3 genres + 2 thèmes + statut max). */
public final class AutoTags {

    private AutoTags() {}

    private static final Map<String, String> GENRES = new HashMap<>();
    private static final Map<String, String> THEMES = new HashMap<>();

    static {
        String[][] g = {{"Action", "Action"}, {"Adventure", "Aventure"}, {"Comedy", "Comédie"}, {"Drama", "Drame"},
                {"Fantasy", "Fantasy"}, {"Horror", "Horreur"}, {"Mystery", "Mystère"}, {"Psychological", "Psychologique"},
                {"Romance", "Romance"}, {"Sci-Fi", "Science-fiction"}, {"Slice of Life", "Slice of life"},
                {"Sports", "Sport"}, {"Supernatural", "Surnaturel"}, {"Thriller", "Thriller"}, {"Mecha", "Mecha"},
                {"Music", "Musique"}, {"Mahou Shoujo", "Magical girl"}};
        for (String[] e : g) GENRES.put(e[0], e[1]);
        String[][] t = {{"Martial Arts", "Arts martiaux"}, {"Wuxia", "Murim"}, {"Cultivation", "Murim"},
                {"Isekai", "Isekai"}, {"Reincarnation", "Réincarnation"}, {"Time Manipulation", "Régression"},
                {"Revenge", "Vengeance"}, {"School", "École"}, {"Video Games", "Jeux vidéo"}, {"Dungeon", "Donjons"},
                {"Super Power", "Super-pouvoirs"}, {"Magic", "Magie"}, {"Demons", "Démons"}, {"Gods", "Dieux"},
                {"Zombie", "Zombies"}, {"Post-Apocalyptic", "Post-apo"}, {"Boys' Love", "BL"}, {"Yuri", "GL"},
                {"Villainess", "Villainess"}, {"Office", "Bureau"}, {"Survival", "Survie"}, {"Monster Boy", "Monstres"},
                {"Overpowered Main Character", "Héros surpuissant"}, {"Tower", "Tour"}};
        for (String[] e : t) THEMES.put(e[0], e[1]);
    }

    public static String build(JSONObject media) {
        Set<String> out = new LinkedHashSet<>();
        JSONArray genres = media.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length() && out.size() < 3; i++) {
                String fr = GENRES.get(genres.optString(i));
                if (fr != null) out.add(fr);
            }
        }
        JSONArray tags = media.optJSONArray("tags");
        int themes = 0;
        if (tags != null) {
            for (int i = 0; i < tags.length() && themes < 2; i++) {
                JSONObject t = tags.optJSONObject(i);
                if (t == null || t.optBoolean("isMediaSpoiler") || t.optInt("rank") < 60) continue;
                String fr = THEMES.get(t.optString("name"));
                if (fr != null && out.add(fr)) themes++;
            }
        }
        String status = media.optString("status");
        if ("RELEASING".equals(status)) out.add("En cours");
        else if ("FINISHED".equals(status)) out.add("Terminé");
        return String.join(", ", out);
    }
}
