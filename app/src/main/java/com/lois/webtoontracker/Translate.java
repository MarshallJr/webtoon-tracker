package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Traduction automatique d'un TITRE de série (quelques mots), pour le chercher là où il est publié
 * sous un nom traduit : « Reincarnated With Divine Titles » → « Réincarné avec des titres divins ».
 *
 * Service public MyMemory (sans compte, quelques milliers de caractères par jour : largement assez
 * pour des titres). Chaque traduction est gardée : un titre n'est traduit qu'une fois par langue.
 */
final class Translate {

    private Translate() {
    }

    private static final String PREFS = "title_translations";

    /** @return le titre traduit, ou null (même langue, service indisponible, quota atteint). */
    static String title(Context ctx, String text, String from, String to) {
        if (text == null || text.trim().isEmpty() || from == null || to == null) return null;
        String f = base(from), t = base(to);
        if (f.equals(t)) return null;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = f + "|" + t + "|" + text.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.contains(key)) {
            String v = p.getString(key, "");
            return v.isEmpty() ? null : v;
        }
        try {
            String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text.trim(), "UTF-8")
                    + "&langpair=" + f + "%7C" + t;
            JSONObject o = new JSONObject(ReleaseSources.get(url));
            if (o.optInt("responseStatus", 0) != 200) return null;   // quota, erreur : on réessaiera plus tard
            JSONObject d = o.optJSONObject("responseData");
            String out = d == null ? "" : d.optString("translatedText", "").trim();
            if (out.toUpperCase(java.util.Locale.ROOT).contains("MYMEMORY WARNING")) return null;   // quota du jour : plus tard
            if (out.equalsIgnoreCase(text.trim())) out = "";                                         // rien à traduire
            out = android.text.Html.fromHtml(out, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
            p.edit().putString(key, out).apply();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** « zh-hant » → « zh-TW », « es-la » → « es » : codes compris par le service. */
    private static String base(String code) {
        String c = SourceRegistry.normalize(code);
        if ("zh-hant".equals(c)) return "zh-TW";
        if ("es-la".equals(c)) return "es";
        if ("pt-br".equals(c)) return "pt-BR";
        return c;
    }
}
