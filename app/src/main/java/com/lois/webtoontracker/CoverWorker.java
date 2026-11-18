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

    private static boolean hasWork(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (DbHelper.Series s : DbHelper.get(ctx).getSeries()) if (needsCover(ctx, s.title, p, now)) return true;
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
            if (!needsCover(ctx, s.title, p, now)) continue;
            p.edit().putLong(key(s.title), now).apply();
            try {
                String url = findCoverUrl(s.title);
                if (url == null) continue;
                Bitmap b = BitmapFactory.decodeStream(new java.io.ByteArrayInputStream(download(url)));
                if (b == null) continue;
                Covers.savePoster(ctx, s.title, b);
                Covers.setCustom(ctx, s.title, true); // la couverture officielle n'est plus remplacée par une capture
                found++;
                Thread.sleep(800); // reste poli avec l'API (limite ~90 requêtes/min)
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {
                // pas trouvé / pas de réseau : la capture d'écran prendra le relais, nouvel essai dans 7 jours
            }
        }
        if (found > 0) {
            WidgetProvider.updateAll(ctx);
            ctx.sendBroadcast(new Intent(ReadingTrackerService.ACTION_UPDATED).setPackage(ctx.getPackageName()));
        }
        return Result.success();
    }

    /** Recherche AniList (GraphQL public, sans clé) → URL de la couverture, ou null. */
    static String findCoverUrl(String title) throws Exception {
        JSONObject vars = new JSONObject().put("s", title);
        JSONObject body = new JSONObject()
                .put("query", "query($s:String){Media(search:$s,type:MANGA){coverImage{extraLarge large}}}")
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
            JSONObject img = media.optJSONObject("coverImage");
            if (img == null) return null;
            String u = img.optString("extraLarge", "");
            if (u.isEmpty() || "null".equals(u)) u = img.optString("large", "");
            return u.isEmpty() || "null".equals(u) ? null : u;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
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
