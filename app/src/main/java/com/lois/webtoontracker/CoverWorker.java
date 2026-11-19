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
        if (Covers.isCustom(ctx, title)) return false;              // image perso ou officielle déjà là
        return now - p.getLong(key(title), 0) > RETRY_MS;
    }

    /** Tags automatiques : seulement si la série n'a encore aucun tag (on ne touche jamais aux tiens). */
    private static boolean needsTags(DbHelper.Series s, SharedPreferences p, long now) {
        if (!s.tags.isEmpty()) return false;
        return now - p.getLong("tags_" + key(s.title), 0) > RETRY_MS;
    }

    /** Lien webtoons.com automatique (pour les alertes nouveaux chapitres). */
    private static boolean needsLink(Context ctx, DbHelper.Series s, SharedPreferences p, long now) {
        DbHelper.Meta m = DbHelper.get(ctx).getMeta(s.title);
        if (m != null && m.titleNo != null) return false;
        return now - p.getLong("link_" + key(s.title), 0) > RETRY_MS;
    }

    private static boolean hasWork(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (DbHelper.Series s : DbHelper.get(ctx).getSeries()) {
            if (needsCover(ctx, s.title, p, now) || needsTags(s, p, now) || needsLink(ctx, s, p, now)) return true;
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
        for (DbHelper.Series s : DbHelper.get(ctx).getSeries()) {
            if (isStopped()) break;
            long now = System.currentTimeMillis();
            boolean wantCover = needsCover(ctx, s.title, p, now);
            boolean wantTags = needsTags(s, p, now);
            boolean wantLink = needsLink(ctx, s, p, now);
            if (!wantCover && !wantTags && !wantLink) continue;
            if (wantCover) p.edit().putLong(key(s.title), now).apply();
            if (wantTags) p.edit().putLong("tags_" + key(s.title), now).apply();
            if (wantLink) p.edit().putLong("link_" + key(s.title), now).apply();
            try {
                AniInfo info = (wantCover || wantTags || wantLink) ? lookup(s.title) : null;
                Thread.sleep(800); // reste poli avec les sites (AniList : ~90 requêtes/min)
                if (wantLink && autoLink(ctx, s.title, info)) found++;
                if (info == null) continue;
                if (wantTags && !info.tags.isEmpty()) {
                    DbHelper.get(ctx).setTags(s.title, info.tags);
                    found++;
                }
                if (!wantCover || info.coverUrl == null) continue;
                Bitmap b = BitmapFactory.decodeStream(new java.io.ByteArrayInputStream(download(info.coverUrl)));
                if (b == null) continue;
                Covers.savePoster(ctx, s.title, b);
                Covers.setCustom(ctx, s.title, true); // la couverture officielle n'est plus remplacée par une capture
                found++;
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {
                // pas trouvé / pas de réseau : la capture d'écran prendra le relais, nouvel essai dans 7 jours
            }
        }
        if (found > 0) {
            if (ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(ChapterCheckWorker.KEY_ALERTS, false)) {
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
    }

    // ------------------------------------------------------------------ Lien webtoons.com automatique

    private static final java.util.regex.Pattern SERIES_LINK = java.util.regex.Pattern.compile(
            "https?://(?:www\\.|m\\.)?webtoons\\.com/([a-z]{2}(?:-[a-z]+)?)/([^/\"'\\s]+)/([^/\"'\\s]+)/list\\?title_no=(\\d+)");

    /** Cherche la série sur webtoons.com (FR puis EN), sinon prend le lien AniList. Vérifie le titre. */
    static boolean autoLink(Context ctx, String title, AniInfo info) {
        String want = Titles.key(title);
        for (String lang : new String[]{"fr", "en"}) {
            try {
                String html = new String(download("https://www.webtoons.com/" + lang + "/search?keyword="
                        + java.net.URLEncoder.encode(title, "UTF-8")), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = SERIES_LINK.matcher(html);
                while (m.find()) {
                    String slugKey = Titles.key(Detector.slugToTitle(m.group(3)));
                    boolean close = slugKey.equals(want) || (want.length() >= 6 && slugKey.length() >= 6
                            && (slugKey.contains(want) || want.contains(slugKey)));
                    if (!close) continue;
                    String base = "https://www.webtoons.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
                    DbHelper.get(ctx).linkWebtoons(title, base, m.group(4));
                    return true;
                }
                Thread.sleep(500);
            } catch (Exception ignored) {
            }
        }
        if (info != null && info.webtoonsUrl != null) {
            String[] parsed = Detector.parseSeriesLink(info.webtoonsUrl);
            if (parsed != null) {
                DbHelper.get(ctx).linkWebtoons(title, parsed[0], parsed[1]);
                return true;
            }
        }
        return false;
    }

    /** Recherche AniList (GraphQL public, sans clé) : couverture + genres + tags + statut. */
    static AniInfo lookup(String title) throws Exception {
        JSONObject vars = new JSONObject().put("s", title);
        JSONObject body = new JSONObject()
                .put("query", "query($s:String){Media(search:$s,type:MANGA){coverImage{extraLarge large}"
                        + " genres status tags{name rank isMediaSpoiler} externalLinks{url site}}}")
                .put("variables", vars);

        HttpURLConnection c = (HttpURLConnection) new URL("https://graphql.anilist.co").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            if (c.getResponseCode() != 200) return null;
            String json;
            try (InputStream in = c.getInputStream()) {
                json = new String(readAll(in), StandardCharsets.UTF_8);
            }
            JSONObject media = new JSONObject(json).optJSONObject("data");
            media = media == null ? null : media.optJSONObject("Media");
            if (media == null) return null;
            AniInfo info = new AniInfo();
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
        } finally {
            c.disconnect();
        }
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
