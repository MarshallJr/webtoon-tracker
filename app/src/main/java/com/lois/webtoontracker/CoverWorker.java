package com.lois.webtoontracker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Récupère AUTOMATIQUEMENT la couverture officielle des séries qui n'ont pas de miniature,
 * via la base publique AniList (webtoons, manhwas, mangas). Aucune action de l'utilisateur.
 *
 * Économe : une seule recherche par série (re-tentée au plus 1 fois par semaine si rien trouvé),
 * uniquement avec du réseau, et seulement quand une série sans miniature existe.
 */
public class CoverWorker extends Worker {

    private static final String WORK = "covers";
    private static final String PREFS = "covers_meta";
    private static final long RETRY_MS = 7L * 24 * 3600 * 1000;

    public CoverWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    /** À appeler librement (ouverture de l'appli, nouvelle série) : ne lance rien s'il n'y a rien à faire. */
    /** Une fois : retire les couvertures et vignettes prises sur l'écran de veille, puis relance la recherche. */
    public static void cleanupBadImages(Context ctx) {
        SharedPreferences flags = ctx.getSharedPreferences("repairs", Context.MODE_PRIVATE);
        if (flags.getBoolean("dark_images_v1", false)) return;
        int covers = Covers.cleanupBad(ctx, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        ChapterThumbs.cleanupBad(ctx);
        flags.edit().putBoolean("dark_images_v1", true).apply();
        if (covers > 0) enqueueIfNeeded(ctx);
    }

    /** Une fois : retire les vignettes identiques sur plusieurs chapitres (images du site, pas du chapitre). */
    public static void cleanupDuplicateThumbs(Context ctx) {
        SharedPreferences flags = ctx.getSharedPreferences("repairs", Context.MODE_PRIVATE);
        if (flags.getBoolean("dup_thumbs_v1", false)) return;
        ChapterThumbs.cleanupDuplicates(ctx);
        flags.edit().putBoolean("dup_thumbs_v1", true).apply();
    }

    public static void enqueueIfNeeded(Context ctx) {
        try {
            if (!hasWork(ctx)) return;
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CoverWorker.class)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req);
        } catch (Throwable t) {
            App.saveError(ctx, "Couvertures", t);
        }
    }

    private static boolean needsCover(Context ctx, String title, SharedPreferences p, long now) {
        // Ancienne couverture carrée → on retélécharge l'officielle en portrait (sauf image choisie par toi).
        if (Covers.isCustom(ctx, title) && !Covers.isUserPick(ctx, title) && Covers.isSquare(ctx, title)) {
            return now - p.getLong("portrait_" + key(title), 0) > RETRY_MS;
        }
        if (Covers.isCustom(ctx, title)) return false;              // image perso ou officielle déjà là
        return now - p.getLong(key(title), 0) > RETRY_MS;
    }

    /** Tags automatiques : seulement si la série n'a encore aucun tag (on ne touche jamais aux tiens). */
    private static boolean needsTags(DbHelper.Series s, SharedPreferences p, long now) {
        if (!s.tags.isEmpty()) return false;
        return now - p.getLong("tags_" + key(s.title), 0) > RETRY_MS;
    }

    /**
     * Lien WEBTOON automatique (alertes, vignettes officielles des épisodes, résumé, image) : pour toutes
     * les séries pas encore reliées, une fois par semaine, même si WEBTOON la publie sous un titre traduit.
     */
    private static boolean needsLink(Context ctx, DbHelper.Series s, SharedPreferences p, long now) {
        DbHelper.Meta m = DbHelper.get(ctx).getMeta(s.title);
        if (m != null && m.titleNo != null) return false;
        return now - p.getLong("link_" + key(s.title), 0) > RETRY_MS;
    }

    /** Fiche officielle (note publique, alias, résumé) pas encore récupérée. */
    private static boolean needsInfo(Context ctx, DbHelper.Series s, SharedPreferences p, long now) {
        DbHelper.Meta m = DbHelper.get(ctx).getMeta(s.title);
        if (m != null && m.detailsJson != null && m.score > 0) return false;
        return now - p.getLong("info_" + key(s.title), 0) > RETRY_MS;
    }

    private static boolean hasWork(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (DbHelper.Series s : DbHelper.get(ctx).getSeries()) {
            if (needsCover(ctx, s.title, p, now) || needsTags(s, p, now) || needsLink(ctx, s, p, now)
                    || needsInfo(ctx, s, p, now)) return true;
        }
        return false;
    }

    private static String key(String title) {
        return "try_" + title.toLowerCase(Locale.ROOT);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int found = 0;
        DbHelper db = DbHelper.get(ctx);
        found += Repair.run(ctx); // titres incohérents (ex : "2879576") corrigés grâce à l'URL
        // Correspondances complètes (bases + WEBTOON) par passage, les autres au suivant ; moins en données mobiles.
        int resolveBudget = Net.metered(ctx) ? 3 : 8;
        for (DbHelper.Series s : db.getSeries()) {
            if (isStopped()) break;
            long now = System.currentTimeMillis();
            boolean wantCover = needsCover(ctx, s.title, p, now);
            boolean wantTags = needsTags(s, p, now);
            boolean canResolve = resolveBudget > 0;
            boolean wantLink = canResolve && needsLink(ctx, s, p, now);   // sinon : au prochain passage
            boolean wantInfo = needsInfo(ctx, s, p, now);
            if (!wantCover && !wantTags && !wantLink && !wantInfo) continue;
            if (wantCover) p.edit().putLong(key(s.title), now).putLong("portrait_" + key(s.title), now).apply();
            if (wantTags) p.edit().putLong("tags_" + key(s.title), now).apply();
            if (wantLink) p.edit().putLong("link_" + key(s.title), now).apply();
            p.edit().putLong("info_" + key(s.title), now).apply();
            String title = s.title;
            try {
                // Noms dans toutes les langues + la série sur WEBTOON (titre traduit compris).
                if (canResolve) {
                    resolveBudget--;
                    if (SeriesResolver.resolve(ctx, title, false)) found++;
                }
                AniInfo info = lookup(ctx, title);
                Thread.sleep(800); // reste poli avec les sites (AniList : ~90 requêtes/min)

                if (info != null) {
                    // 1. Titre officiel : si le nom vient d'une URL ("the-jobless-guy…"), on prend le vrai nom.
                    String official = SeriesInfo.preferredTitle(info.media);
                    if (official != null && !official.equals(title) && db.isUrlDerived(title)) {
                        db.renameSeries(title, official);
                        Covers.merge(ctx, title, official);
                        title = official;
                        found++;
                    }
                    // 2. Tous les autres noms → même série (lue ailleurs, sous un autre nom).
                    java.util.List<String> names = SeriesInfo.names(info.media);
                    names.add(s.title);
                    db.addAliases(title, names);
                    // 3. Note publique + fiche (résumé…) pour la page Détails.
                    if (info.score > 0) db.setScore(title, info.score);
                    DbHelper.Meta m = db.getMeta(title);
                    org.json.JSONObject store = m != null && m.detailsJson != null
                            ? new org.json.JSONObject(m.detailsJson) : new org.json.JSONObject();
                    store.put("anilist", info.media);
                    db.setDetails(title, store.toString());
                    found++;
                }

                if (wantLink && autoLink(ctx, title, info)) found++;
                if (info == null) continue;
                if (wantTags && !info.tags.isEmpty() && db.getMeta(title) != null
                        && (db.getMeta(title).tags == null || db.getMeta(title).tags.isEmpty())) {
                    db.setTags(title, info.tags);
                    found++;
                }
                if (!wantCover || info.coverUrl == null || Covers.isUserPick(ctx, title)) continue;
                Bitmap b = BitmapFactory.decodeStream(new java.io.ByteArrayInputStream(download(info.coverUrl)));
                if (b == null) continue;
                Covers.savePoster(ctx, title, b);
                Covers.setCustom(ctx, title, true); // la couverture officielle n'est plus remplacée par une capture
                found++;
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {
                // pas trouvé / pas de réseau : la capture d'écran prendra le relais, nouvel essai dans 7 jours
            }
        }
        if (found > 0) {
            if (DbHelper.get(ctx).countFollowed() > 0) {
                ChapterCheckWorker.checkNow(ctx); // mémorise le dernier épisode des séries tout juste liées
            }
            NoteWriter.write(ctx); // fichier note + sauvegarde auto avec les nouveaux tags
            WidgetProvider.updateAll(ctx);
            ctx.sendBroadcast(new Intent(ReadingTrackerService.ACTION_UPDATED).setPackage(ctx.getPackageName()));
        }
        return Result.success();
    }

    static final class AniInfo {
        String coverUrl;
        String tags = "";
        String webtoonsUrl; // lien officiel webtoons.com fourni par AniList (souvent en anglais)
        org.json.JSONObject media;
        int score;
    }

    // ------------------------------------------------------------------ Lien webtoons.com automatique

    private static final java.util.regex.Pattern SERIES_LINK = java.util.regex.Pattern.compile(
            "https?://(?:www\\.|m\\.)?webtoons\\.com/([a-z]{2}(?:-[a-z]+)?)/([^/\"'\\s]+)/([^/\"'\\s]+)/list\\?title_no=(\\d+)");

    /**
     * Relie la série à WEBTOON : d'abord le lien officiel donné par AniList, sinon la recherche
     * (français puis anglais) avec tous ses noms et la traduction de son titre — voir {@link SeriesResolver}.
     */
    static boolean autoLink(Context ctx, String title, AniInfo info) {
        DbHelper.Meta m0 = DbHelper.get(ctx).getMeta(title);
        if (m0 != null && m0.titleNo != null) return true;
        if (info != null && info.webtoonsUrl != null) {
            String[] parsed = Detector.parseSeriesLink(info.webtoonsUrl);
            if (parsed != null) {
                DbHelper.get(ctx).linkWebtoons(title, parsed[0], parsed[1]);
                return true;
            }
        }
        if (SeriesResolver.triedRecently(ctx, title)) return false;   // recherche complète faite il y a peu
        try {
            return SeriesResolver.linkWebtoon(ctx, title);
        } catch (Exception e) {
            return false;
        }
    }

    /** Recherche AniList avec tous les noms connus de la série (voir SeriesInfo.searchMedia). */
    static AniInfo lookup(Context ctx, String title) throws Exception {
        org.json.JSONObject media = SeriesInfo.searchMedia(title, SeriesNames.names(ctx, title));
        if (media == null) return null;
        AniInfo info = new AniInfo();
        info.media = media;
        info.score = media.optInt("averageScore", 0);
        JSONObject img = media.optJSONObject("coverImage");
        if (img != null) {
            String u = img.optString("extraLarge", "");
            if (u.isEmpty() || "null".equals(u)) u = img.optString("large", "");
            info.coverUrl = u.isEmpty() || "null".equals(u) ? null : u;
        }
        info.tags = AutoTags.build(media);
        org.json.JSONArray links = media.optJSONArray("externalLinks");
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                String u = links.optJSONObject(i) == null ? "" : links.optJSONObject(i).optString("url");
                if (u.contains("webtoons.com") && u.contains("title_no=")) {
                    info.webtoonsUrl = u;
                    break;
                }
            }
        }
        return info;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile WebtoonTracker");
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < 8_000_000) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
