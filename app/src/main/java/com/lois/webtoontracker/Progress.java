package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Où tu t'es arrêté dans le chapitre en cours.
 * De préférence la CASE (image) affichée — "case 34 / 52", une ancre précise —,
 * sinon un pourcentage de défilement (moins précis).
 */
public final class Progress {

    private static final String PREFS = "progress";

    public final double chapter;
    public final float pct;
    public final int index;   // case affichée (0-based), -1 si inconnue
    public final int count;   // nombre de cases du chapitre, 0 si inconnu
    public final long at;

    private Progress(double chapter, float pct, int index, int count, long at) {
        this.chapter = chapter;
        this.pct = pct;
        this.index = index;
        this.count = count;
        this.at = at;
    }

    private static String key(String title) {
        String k = Titles.key(title);
        return k.isEmpty() ? title.toLowerCase(Locale.ROOT) : k;
    }

    public static void save(Context ctx, String title, double chapter, float pct, int index, int count) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(title), chapter + ";" + pct + ";" + System.currentTimeMillis() + ";" + index + ";" + count)
                .apply();
    }

    public static void save(Context ctx, String title, double chapter, float pct) {
        save(ctx, title, chapter, pct, -1, 0);
    }

    public static Progress get(Context ctx, String title) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(key(title), null);
        if (v == null) return null;
        try {
            String[] parts = v.split(";");
            int idx = parts.length > 3 ? Integer.parseInt(parts[3]) : -1;
            int cnt = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
            return new Progress(Double.parseDouble(parts[0]), Float.parseFloat(parts[1]), idx, cnt, Long.parseLong(parts[2]));
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
        if (p.pct < 0.03f && p.index < 2) return null;      // tout début : rien d'utile à dire
        return p;
    }

    public boolean hasPanel() {
        return index >= 0 && count > 0;
    }

    public int percent() {
        return Math.max(1, Math.min(100, Math.round(pct * 100)));
    }

    public boolean finished() {
        return hasPanel() ? index >= count - 2 : pct >= 0.93f;
    }

    /** "case 34 / 52" ou "≈ 65 %" */
    public String where() {
        return hasPanel() ? "case " + (index + 1) + " / " + count : "≈ " + percent() + " %";
    }

    /** Texte court (page Détails, bouton). */
    public String hint() {
        String ch = Detector.formatChapter(chapter);
        if (finished()) return "Tu avais fini le ch. " + ch;
        return "Ch. " + ch + " · " + where();
    }

    public static void clear(Context ctx, String title) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(title)).apply();
    }
}
