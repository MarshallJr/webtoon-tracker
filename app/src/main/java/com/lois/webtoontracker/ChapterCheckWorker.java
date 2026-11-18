package com.lois.webtoontracker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Vérifie s'il y a de nouveaux épisodes pour les séries liées à webtoons.com.
 * Économe : 2 fois par jour maximum, seulement avec du réseau et une batterie non faible,
 * planifié par Android (WorkManager) qui regroupe les réveils avec ceux des autres applis.
 * Un petit fichier RSS par série, rien d'autre.
 */
public class ChapterCheckWorker extends Worker {

    public static final String KEY_ALERTS = "chapter_alerts";
    private static final String WORK_NAME = "chapter-check";
    private static final String CHANNEL = "new_chapters";

    public ChapterCheckWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    // ------------------------------------------------------------------ Planification

    private static Constraints constraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
    }

    public static void enable(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(ChapterCheckWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void disable(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME);
    }

    public static void checkNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueue(new OneTimeWorkRequest.Builder(ChapterCheckWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build());
    }

    // ------------------------------------------------------------------ Travail

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        DbHelper db = DbHelper.get(ctx);
        int found = 0;
        for (DbHelper.Meta m : db.getAllMeta()) {
            if (isStopped()) break;
            if (m.titleNo == null || m.rssBase == null) continue;
            try {
                String xml = fetch(m.rssBase + "/rss?title_no=" + m.titleNo);
                RssParser.Latest latest = RssParser.latest(xml);
                if (latest == null) continue;
                if (m.rssLastEp == 0) {
                    db.setRssLastEp(m.title, latest.episodeNo);   // 1re fois : on mémorise sans alerter
                } else if (latest.episodeNo > m.rssLastEp) {
                    db.setRssLastEp(m.title, latest.episodeNo);
                    notifyNew(ctx, m.title, latest);
                    found++;
                }
            } catch (Exception e) {
                // pas de réseau, site indisponible… on réessaiera au prochain passage
            }
        }
        ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_check", System.currentTimeMillis())
                .putInt("last_check_found", found).apply();
        return Result.success();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WebtoonTracker");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 400_000) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ Notification

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Te prévient quand une série suivie sort un nouvel épisode");
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private static void notifyNew(Context ctx, String series, RssParser.Latest latest) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        createChannel(ctx);
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(latest.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, series.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF6D4AFF)
                .setContentTitle("⚡ Nouveau chapitre : " + series)
                .setContentText(latest.title + " est disponible")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("new_chapters");
        NotificationManagerCompat.from(ctx).notify(series.toLowerCase().hashCode(), b.build());
    }
}
