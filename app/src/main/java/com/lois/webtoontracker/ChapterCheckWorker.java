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

    private static final long SEARCH_RETRY_MS = 7L * 24 * 3600 * 1000;

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        DbHelper db = DbHelper.get(ctx);
        android.content.SharedPreferences tries = ctx.getSharedPreferences("covers_meta", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        boolean metered = Net.metered(ctx);   // données mobiles : listes complètes et éditions moins souvent
        int found = 0;

        // 0. Chapitres payants repérés sur tes sites : sont-ils devenus gratuits ?
        try {
            found += checkLocks(ctx, db);
        } catch (Exception ignored) {
        }

        for (DbHelper.Series s : db.getSeries()) {
            if (isStopped()) break;
            DbHelper.Meta m = db.getMeta(s.title);
            if (m == null || !m.follow) continue;   // 🔔 seulement les séries que tu as choisies
            String tryKey = "src_" + s.title.toLowerCase(java.util.Locale.ROOT);
            boolean canSearch = now - tries.getLong(tryKey, 0) > SEARCH_RETRY_MS;
            java.util.List<DbHelper.Release> rel = new java.util.ArrayList<>();

            // 1. MangaDex (EN + FR, scans et liens officiels)
            try {
                String id = m.mdxId;
                if ((id == null || (isNone(id) && canSearch))) {
                    id = ReleaseSources.mangadexFind(s.title);
                    db.setSourceIds(s.title, id, null);
                    Thread.sleep(250);
                }
                if (id != null && !isNone(id)) rel.addAll(ReleaseSources.mangadexLatest(s.title, id));
                Thread.sleep(250);
            } catch (Exception ignored) {
            }
            // 2. MangaUpdates (toutes les sorties, tous sites)
            try {
                String id = m.muId;
                if ((id == null || (isNone(id) && canSearch))) {
                    id = ReleaseSources.mangaupdatesFind(s.title);
                    db.setSourceIds(s.title, null, id);
                    Thread.sleep(400);
                }
                if (id != null && !isNone(id)) {
                    DbHelper.Release r = ReleaseSources.mangaupdatesLatest(s.title, id);
                    if (r != null) rel.add(r);
                }
                Thread.sleep(400);
            } catch (Exception ignored) {
            }
            // 3. Webtoon officiel (si la série est liée)
            try {
                if (m.titleNo != null && m.rssBase != null) {
                    DbHelper.Release r = ReleaseSources.webtoonLatest(s.title, m.rssBase, m.titleNo);
                    if (r != null) rel.add(r);
                }
            } catch (Exception ignored) {
            }
            // 4. Le site où tu lis (scans) : l'adresse du chapitre suivant existe-t-elle ?
            try {
                double last = db.lastRead(s.title);
                String known = null;
                double knownCh = 0;
                for (DbHelper.Entry e : db.getHistory(s.title)) {   // du plus récent au plus ancien
                    if (e.url != null && !e.url.contains("webtoons.com")) {
                        known = e.url;
                        knownCh = e.chapter;
                        break;
                    }
                }
                if (known == null && m.lastUrl != null && !m.lastUrl.contains("webtoons.com")) {
                    known = m.lastUrl;      // lu avant la v4.3 : l'adresse du dernier chapitre
                    knownCh = last;
                }
                // Liste complète des chapitres sur ce site (6 h au plus, en Wi-Fi), puis le chapitre suivant page par page.
                if (!metered) SiteChapters.refresh(ctx, s.title, known, knownCh, false);
                DbHelper.Release r = ReleaseSources.siteLatest(ctx, s.title, known, knownCh, last);
                if (r == null) r = fromSiteList(db, s.title, known, last);
                if (r != null) rel.add(r);
            } catch (Exception ignored) {
            }
            if (canSearch) tries.edit().putLong(tryKey, now).apply();

            for (DbHelper.Release r : rel) db.saveRelease(r);

            // 5. Éditions officielles dans toutes les langues (« Langues & sources » activé pour cette série)
            try {
                Editions.refreshIfStale(ctx, s.title, (metered ? 72L : 20L) * 3600 * 1000);   // données mobiles : tous les 3 jours
            } catch (Exception ignored) {
            }

            // Nouveau = plus loin que ton dernier chapitre lu ET pas déjà signalé.
            java.util.List<DbHelper.Release> fresh = db.newReleases(s.title);
            if (!fresh.isEmpty() && fresh.get(0).chapter > m.notifiedCh) {
                notifyNew(ctx, s.title, db.lastRead(s.title), fresh);
                db.setNotified(s.title, fresh.get(0).chapter);
                found++;
            }
        }
        ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_check", System.currentTimeMillis())
                .putInt("last_check_found", found).apply();
        ctx.sendBroadcast(new Intent(ReadingTrackerService.ACTION_UPDATED).setPackage(ctx.getPackageName()));
        return Result.success();
    }

    /**
     * Revérifie les chapitres payants encore verrouillés, du plus petit au plus grand (3 au plus par
     * série et par site ; on s'arrête au premier encore payant). Ceux devenus gratuits → notification.
     * @return nombre de séries pour lesquelles un chapitre est devenu gratuit
     */
    private int checkLocks(Context ctx, DbHelper db) throws InterruptedException {
        long now = System.currentTimeMillis();
        db.purgeLocks(now);
        java.util.LinkedHashMap<String, java.util.List<DbHelper.Lock>> groups = new java.util.LinkedHashMap<>();
        for (DbHelper.Lock l : db.activeLocks()) {
            groups.computeIfAbsent(l.title.toLowerCase(java.util.Locale.ROOT) + "|" + l.host, k -> new java.util.ArrayList<>()).add(l);
        }
        java.util.LinkedHashMap<String, java.util.List<DbHelper.Lock>> freed = new java.util.LinkedHashMap<>();
        for (java.util.List<DbHelper.Lock> g : groups.values()) {
            if (isStopped()) break;
            String title = g.get(0).title;
            // Contrôle : un chapitre lu sur ce même site (sa page doit montrer ses images).
            String ctl = null;
            double ctlCh = 0;
            for (DbHelper.Entry e : db.getHistory(title)) {
                if (e.url != null && g.get(0).host.equals(ChapterLinks.host(e.url))) {
                    ctl = e.url;
                    ctlCh = e.chapter;
                    break;
                }
            }
            int checked = 0;
            for (DbHelper.Lock l : g) {
                if (checked >= 3 || l.url == null || !l.url.startsWith("http")) break;   // appli de lecture : pas de page à vérifier
                Paywall.Probe p = Paywall.probe(l.url, l.chapter, ctl, ctlCh);
                checked++;
                if (p.state == Paywall.FREE) {
                    db.markFreed(title, l.host, l.chapter, now);
                    freed.computeIfAbsent(title, k -> new java.util.ArrayList<>()).add(l);
                    Thread.sleep(500);
                    continue;
                }
                if (p.state == Paywall.LOCKED_STATE) db.touchLock(title, l.host, l.chapter, now, p.freeAt);
                break;   // encore payant (ou impossible à vérifier) : les suivants aussi
            }
            Thread.sleep(400);
        }
        for (java.util.Map.Entry<String, java.util.List<DbHelper.Lock>> e : freed.entrySet()) {
            notifyFreed(ctx, e.getKey(), e.getValue());
            double top = 0;
            for (DbHelper.Lock l : e.getValue()) {
                db.markLockNotified(l.title, l.host, l.chapter);
                top = Math.max(top, l.chapter);
            }
            DbHelper.Meta m = db.getMeta(e.getKey());
            if (m != null && top > m.notifiedCh) db.setNotified(e.getKey(), top);   // pas de 2e alerte « dispo » pour lui
        }
        if (db.countActiveLocks() == 0) Follow.sync(ctx);
        return freed.size();
    }

    /** « 🔓 Chapitre 33 gratuit · sur en-thunderscans.com » avec un bouton Lire. */
    private static void notifyFreed(Context ctx, String series, java.util.List<DbHelper.Lock> locks) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        createChannel(ctx);
        locks.sort((a, b) -> Double.compare(a.chapter, b.chapter));
        DbHelper.Lock first = locks.get(0), last = locks.get(locks.size() - 1);
        String chs = locks.size() == 1 ? "Chapitre " + Detector.formatChapter(first.chapter) + " gratuit"
                : "Chapitres " + Detector.formatChapter(first.chapter) + " à " + Detector.formatChapter(last.chapter) + " gratuits";
        String text = chs + " sur " + first.host;
        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(EXTRA_SHOW_UPDATES, series)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, ("free" + series).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFFFA62B)
                .setContentTitle("🔓 " + series)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\nIl était payant quand tu es tombé dessus."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("new_chapters");
        if (first.url != null) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(first.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent vp = PendingIntent.getActivity(ctx, ("freeview" + series).hashCode(), view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, "Lire le ch. " + Detector.formatChapter(first.chapter), vp);
        }
        NotificationManagerCompat.from(ctx).notify(("free" + series.toLowerCase()).hashCode(), b.build());
    }

    /**
     * Adresses impossibles à deviner (identifiants) : le dernier chapitre de la liste du site,
     * s'il dépasse le dernier lu et n'est pas marqué payant.
     */
    private static DbHelper.Release fromSiteList(DbHelper db, String title, String knownUrl, double last) {
        String host = ChapterLinks.host(knownUrl);
        java.util.TreeMap<Double, String> list = db.siteChapters(title, host);
        if (list.isEmpty() || list.lastKey() <= last) return null;
        java.util.Set<Double> locked = new java.util.HashSet<>();
        for (DbHelper.Lock l : db.getLocks(title)) if (l.active() && host != null && host.equals(l.host)) locked.add(l.chapter);
        for (Double ch : list.descendingKeySet()) {
            if (ch <= last) break;
            if (locked.contains(ch)) continue;
            DbHelper.Release r = new DbHelper.Release();
            r.title = title;
            r.source = "Site";
            r.chapter = ch;
            r.url = list.get(ch);
            r.label = host;
            r.lang = "";
            r.foundAt = System.currentTimeMillis();
            return r;
        }
        return null;
    }

    private static boolean isNone(String id) {
        return ReleaseSources.NOT_FOUND.equals(id);
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

    public static final String EXTRA_SHOW_UPDATES = "show_updates";

    private static void notifyNew(Context ctx, String series, double lastRead, java.util.List<DbHelper.Release> fresh) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        createChannel(ctx);
        DbHelper.Release top = fresh.get(0);
        int extra = (int) Math.max(0, Math.floor(top.chapter - Math.max(lastRead, 0)));

        // Où le lire : chaque site une seule fois, le plus avancé d'abord.
        java.util.LinkedHashMap<String, DbHelper.Release> bySite = new java.util.LinkedHashMap<>();
        for (DbHelper.Release r : fresh) {
            String k = r.label + r.lang;
            if (!bySite.containsKey(k)) bySite.put(k, r);
        }
        StringBuilder where = new StringBuilder();
        for (DbHelper.Release r : bySite.values()) {
            if (where.length() > 0) where.append(" · ");
            where.append(r.label).append(" ").append(r.lang).append(" (ch. ").append(Detector.formatChapter(r.chapter)).append(")");
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(EXTRA_SHOW_UPDATES, series)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, series.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = "Ch. " + Detector.formatChapter(top.chapter) + " dispo"
                + (lastRead > 0 ? " · tu en es au " + Detector.formatChapter(lastRead) : "")
                + (extra > 1 ? " (" + extra + " de retard)" : "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF6D4AFF)
                .setContentTitle("⚡ " + series)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\nOù le lire : " + where))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("new_chapters");

        // Jusqu'à 2 boutons "Lire" qui ouvrent directement le chapitre dans le navigateur / l'appli.
        int i = 0;
        for (DbHelper.Release r : bySite.values()) {
            if (i >= 2 || r.url == null) break;
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent vp = PendingIntent.getActivity(ctx, (series + r.label + r.lang).hashCode(), view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, "Lire · " + r.label + " " + r.lang, vp);
            i++;
        }
        NotificationManagerCompat.from(ctx).notify(series.toLowerCase().hashCode(), b.build());
    }
}
