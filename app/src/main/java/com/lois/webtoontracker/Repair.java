package com.lois.webtoontracker;

import android.content.Context;

/**
 * Répare les séries enregistrées sous un titre incohérent (ex : "2879576", un identifiant de site)
 * en relisant leur dernière URL avec la nouvelle détection. Rapide et hors-ligne.
 */
public final class Repair {

    private Repair() {}

    static boolean looksBroken(String title) {
        String letters = title.replaceAll("[^\\p{L}]", "");
        return letters.length() < 3 || title.matches(".*\\d{5,}.*") && letters.length() < 6;
    }

    /** @return nombre de séries corrigées */
    public static synchronized int run(Context ctx) {
        DbHelper db = DbHelper.get(ctx);
        int fixed = 0;
        for (DbHelper.Series s : db.getSeries()) {
            if (!looksBroken(s.title)) continue;
            DbHelper.Meta m = db.getMeta(s.title);
            if (m == null || m.lastUrl == null) continue;
            UrlTitle.Hit h = UrlTitle.extract(m.lastUrl);
            if (h == null || h.title == null || h.title.equalsIgnoreCase(s.title)) continue;
            String target = db.resolveTitle(h.title);
            db.renameSeries(s.title, target);
            Covers.merge(ctx, s.title, target);
            fixed++;
        }
        if (fixed > 0) {
            NoteWriter.write(ctx);
            WidgetProvider.updateAll(ctx);
        }
        return fixed;
    }
}
