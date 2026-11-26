package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fait correspondre une série de l'appli avec les bases et WEBTOON, quelle que soit la langue du titre.
 *
 *  1. ses noms dans toutes les langues (MangaDex, AniList, MangaUpdates) — {@link SeriesNames} ;
 *  2. la série sur WEBTOON, en français puis en anglais, même sous un titre traduit — {@link WebtoonFinder} ;
 *  3. ce que la page WEBTOON apporte : lien (alertes, vignettes officielles des épisodes), titre local,
 *     résumé, auteurs, genre, et l'image officielle à la place d'une capture d'écran.
 *
 * Une fois par semaine et par série au plus (sauf si on le demande), hors du fil principal.
 */
final class SeriesResolver {

    private SeriesResolver() {
    }

    private static final String PREFS = "resolver";
    private static final long RETRY_MS = 7L * 24 * 3600 * 1000;
    private static final Set<String> running = Collections.synchronizedSet(new HashSet<>());

    /** @return true si la fiche a changé (lien WEBTOON, image, résumé…) */
    static boolean resolve(Context ctx, String title, boolean force) {
        String key = Titles.key(title);
        if (key.isEmpty() || !running.add(key)) return false;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (!force && now - p.getLong("at_" + key, 0) < RETRY_MS) return false;
            p.edit().putLong("at_" + key, now).apply();

            DbHelper db = DbHelper.get(ctx);
            boolean changed = SeriesNames.gather(ctx, title, force);
            DbHelper.Meta m = db.getMeta(title);
            if (m == null || m.titleNo == null || m.rssBase == null) {
                if (linkWebtoon(ctx, title)) {
                    changed = true;
                } else if (Net.metered(ctx)) {
                    // En données mobiles, le catalogue WEBTOON n'est pas téléchargé : nouvel essai dans 6 h.
                    p.edit().putLong("at_" + key, now - RETRY_MS + 6L * 3600 * 1000).apply();
                }
            } else if (!hasWebtoonDetails(m)) {
                // Déjà reliée (lue sur webtoons.com) : on récupère quand même résumé, auteurs, image.
                WebtoonFinder.Hit h = new WebtoonFinder.Hit();
                h.lang = SourceRegistry.langFromUrl(m.rssBase + "/");
                h.base = m.rssBase;
                h.titleNo = m.titleNo;
                h.score = 1;
                WebtoonFinder.enrich(h, SeriesNames.names(ctx, title), SeriesNames.authors(ctx, title));
                if (h.score > 0) {
                    apply(ctx, title, h, false);
                    changed = true;
                }
            }
            return changed;
        } catch (Exception e) {
            App.saveError(ctx, "Correspondance", e);
            return false;
        } finally {
            running.remove(key);
        }
    }

    /** La correspondance complète vient-elle d'être tentée (moins d'une heure) ? */
    static boolean triedRecently(Context ctx, String title) {
        long at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("at_" + Titles.key(title), 0);
        return System.currentTimeMillis() - at < 3600_000L;
    }

    /** Cherche la série sur WEBTOON (français, puis anglais) et la relie. */
    static boolean linkWebtoon(Context ctx, String title) {
        for (String lang : new String[]{"fr", "en"}) {
            WebtoonFinder.Hit h = WebtoonFinder.find(ctx, title, lang);
            if (h == null) continue;
            apply(ctx, title, h, true);
            // Son nom local (ex. français) peut faire connaître la série aux bases de référence.
            SeriesNames.gather(ctx, title, true);
            return true;
        }
        return false;
    }

    private static boolean hasWebtoonDetails(DbHelper.Meta m) {
        return m.detailsJson != null && m.detailsJson.contains("\"webtoon\"");
    }

    /** Relie la série, garde son nom local, ses auteurs, son résumé, son genre et l'image officielle. */
    static void apply(Context ctx, String title, WebtoonFinder.Hit h, boolean link) {
        DbHelper db = DbHelper.get(ctx);
        if (link) db.linkWebtoons(title, h.base, h.titleNo);
        SeriesNames.add(ctx, title, h.title, h.lang, "WEBTOON");
        if (h.authors != null) {
            List<String> people = new ArrayList<>();
            for (String a : h.authors.split("\\s*[/,&]\\s*")) if (!a.trim().isEmpty()) people.add(a.trim());
            SeriesNames.addAuthors(ctx, title, people);
        }
        db.setDetailsPart(title, "webtoon", WebtoonFinder.toJson(h));
        DbHelper.Meta m = db.getMeta(title);
        if (h.genre != null && (m == null || m.tags == null || m.tags.isEmpty())) db.setTags(title, h.genre);
        // Image officielle à la place d'une capture d'écran (jamais à la place d'une image choisie par toi).
        if (h.image != null && !Covers.isCustom(ctx, title)) {
            Bitmap b = WebtoonFinder.image(h.image);
            if (b != null) {
                try {
                    Covers.savePoster(ctx, title, b);
                    Covers.setCustom(ctx, title, true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Une fois : relance la recherche de correspondance pour toutes les séries (titres traduits). */
    static void rematchAllOnce(Context ctx) {
        SharedPreferences flags = ctx.getSharedPreferences("repairs", Context.MODE_PRIVATE);
        if (flags.getBoolean("rematch_v1", false)) return;
        SharedPreferences covers = ctx.getSharedPreferences("covers_meta", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = covers.edit();
        for (String k : covers.getAll().keySet()) {
            if (k.startsWith("link_") || k.startsWith("info_")) ed.remove(k);
        }
        ed.apply();
        ctx.getSharedPreferences("official_thumbs", Context.MODE_PRIVATE).edit().clear().apply();
        DbHelper db = DbHelper.get(ctx);
        for (DbHelper.Meta m : db.getAllMeta()) {
            if (m.editionsOn) db.setEditionsAt(m.title, 0);
        }
        ctx.getSharedPreferences("editions", Context.MODE_PRIVATE).edit().clear().apply();
        flags.edit().putBoolean("rematch_v1", true).apply();
        CoverWorker.enqueueIfNeeded(ctx);
    }
}
