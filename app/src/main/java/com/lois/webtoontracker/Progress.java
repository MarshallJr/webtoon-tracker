package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Position approximative dans le chapitre en cours (0 → 1), relevée pendant la lecture
 * grâce aux événements de défilement. Sert d'indication quand tu reprends ("vers 65 %").
 */
public final class Progress {

    private static final String PREFS = "progress";

    public final double chapter;
    public final float pct;
    public final long at;

    private Progress(double chapter, float pct, long at) {
        this.chapter = chapter;
        this.pct = pct;
        this.at = at;
    }

    private static String key(String title) {
        String k = Titles.key(title);
        return k.isEmpty() ? title.toLowerCase(Locale.ROOT) : k;
    }

    public static void save(Context ctx, String title, double chapter, float pct) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(title), chapter + ";" + pct + ";" + System.currentTimeMillis())
                .apply();
    }

    public static Progress get(Context ctx, String title) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(key(title), null);
        if (v == null) return null;
        try {
            String[] parts = v.split(";");
            return new Progress(Double.parseDouble(parts[0]), Float.parseFloat(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception e) {
            return null;
        }
    }

    /** Position utile pour reprendre ? (seulement si elle concerne ton dernier chapitre lu) */
    public static Progress forResume(Context ctx, String title) {
        Progress p = get(ctx, title);
        if (p == null) return null;
        double last = DbHelper.get(ctx).lastRead(title);
        if (p.chapter + 0.001 < last) return null;          // tu as lu plus loin depuis
        if (p.pct < 0.03f) return null;                       // tout début : rien d'utile à dire
        return p;
    }

    public int percent() {
        return Math.max(1, Math.min(100, Math.round(pct * 100)));
    }

    public boolean finished() {
        return pct >= 0.93f;
    }

    /** Texte de la bannière affichée à la reprise. */
    public String hint() {
        String ch = Detector.formatChapter(chapter);
        if (finished()) return "Tu avais fini le ch. " + ch + " · passe au suivant 👉";
        return "Tu t'étais arrêté vers " + percent() + " % du ch. " + ch;
    }

    public static void clear(Context ctx, String title) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(title)).apply();
    }
}
