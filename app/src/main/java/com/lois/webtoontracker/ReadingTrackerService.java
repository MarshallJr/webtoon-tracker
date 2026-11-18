package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service d'accessibilité qui tourne en fond : quand une appli de lecture ou un navigateur
 * est au premier plan, il lit le texte affiché, détecte "titre + chapitre", et si le même
 * chapitre reste affiché quelques secondes (= tu le lis vraiment), il l'enregistre.
 */
public class ReadingTrackerService extends AccessibilityService {

    public static final String ACTION_UPDATED = "com.lois.webtoontracker.UPDATED";
    public static final String ACTION_TEST_BANNER = "com.lois.webtoontracker.TEST_BANNER";
    public static final String PREFS_DEBUG = "debug";

    public static final String KEY_NOTIFY_MODE = "notify_mode";
    public static final String KEY_NOTIFY_ALREADY = "notify_already";

    /** Temps pendant lequel un chapitre doit rester affiché avant d'être compté comme lu. */
    private static final long DWELL_MS = 6000;
    /** Délai avant d'afficher "Déjà lu" (évite de clignoter quand on fait défiler une liste). */
    private static final long ALREADY_DELAY_MS = 1800;
    private static final long MIN_SCAN_INTERVAL_MS = 700;
    private static final int MAX_NODES = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final NotifyPolicy policy = new NotifyPolicy();
    private Banner banner;
    private Detector.Result pending;
    private Runnable pendingCommit, pendingAlready;
    private long lastScan;

    private final BroadcastReceiver testReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (banner != null) {
                banner.show(NotifyPolicy.Kind.ADDED, "Ajouté à ta collection", "Exemple · Ch. 42");
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        banner = new Banner(this);
        ContextCompat.registerReceiver(this, testReceiver,
                new IntentFilter(ACTION_TEST_BANNER), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(testReceiver);
        } catch (Throwable ignored) {
        }
        if (banner != null) banner.release();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handle(event);
        } catch (Throwable t) {
            // Jamais de crash depuis le service : on note l'erreur pour l'onglet Diagnostic.
            App.saveError(this, "Service", t);
        }
    }

    private void handle(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!Detector.isWatched(pkg)) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastScan < MIN_SCAN_INTERVAL_MS) return;
        lastScan = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<Detector.TextNode> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        Detector.Result r = Detector.detect(pkg, nodes, screenH);
        saveDebug(pkg, nodes, r);

        if (r == null) return;
        if (pending != null && pending.sameAs(r)) return; // toujours le même chapitre → on attend

        if (pendingCommit != null) handler.removeCallbacks(pendingCommit);
        if (pendingAlready != null) handler.removeCallbacks(pendingAlready);
        pending = r;
        final Detector.Result target = r;

        pendingAlready = () -> io.execute(() -> safe("Déjà lu", () -> checkAlreadyRead(target)));
        handler.postDelayed(pendingAlready, ALREADY_DELAY_MS);

        pendingCommit = () -> io.execute(() -> safe("Enregistrement", () -> record(target)));
        handler.postDelayed(pendingCommit, DWELL_MS);
    }

    private void collect(AccessibilityNodeInfo node, List<Detector.TextNode> out, int depth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES) return;
        CharSequence t = node.getText();
        if (t == null || t.length() == 0) t = node.getContentDescription();
        if (t != null && t.length() > 0 && t.length() < 300 && node.isVisibleToUser()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            out.add(new Detector.TextNode(t.toString(), b.top, b.left));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }

    // ------------------------------------------------------------------ Logique (thread IO)

    private SharedPreferences settings() {
        return getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
    }

    private void checkAlreadyRead(Detector.Result r) {
        long readAt = DbHelper.get(this).getReadAt(r.title, r.chapter);
        if (readAt < 0) return;
        SharedPreferences s = settings();
        NotifyPolicy.Decision d = policy.onAlreadyRead(r.title, readAt, System.currentTimeMillis(),
                s.getInt(KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART), s.getBoolean(KEY_NOTIFY_ALREADY, true));
        if (d == null || banner == null) return;
        String when = new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(readAt));
        banner.show(NotifyPolicy.Kind.ALREADY_READ, "Déjà lu",
                r.title + " · Ch. " + Detector.formatChapter(r.chapter) + " · le " + when);
    }

    private void record(Detector.Result r) {
        DbHelper db = DbHelper.get(this);
        boolean newSeries = !db.seriesExists(r.title);
        boolean isNew = db.insertRead(r.title, r.chapter, System.currentTimeMillis(), r.source);
        db.rememberSource(r); // pour "Reprendre" et les alertes, même si le chapitre était déjà lu
        if (!isNew) return;

        NoteWriter.write(this);

        NotifyPolicy.Decision d = policy.onNewChapter(r.title, newSeries, System.currentTimeMillis(),
                settings().getInt(KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART));

        // Miniature : capture de la case lue, AVANT d'afficher la bannière (pour ne pas la capturer).
        captureCoverIfNeeded(r.title, () -> {
            WidgetProvider.updateAll(this);
            sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
            showBannerFor(d, r);
        });
    }

    // ------------------------------------------------------------------ Miniatures

    private final java.util.Map<String, Long> lastCapture = new java.util.HashMap<>();

    /** Une capture si la série n'a pas encore de miniature, sinon une par session de lecture (30 min). */
    private void captureCoverIfNeeded(String title, Runnable then) {
        long now = System.currentTimeMillis();
        String k = title.toLowerCase(Locale.ROOT);
        Long last = lastCapture.get(k);
        boolean need = android.os.Build.VERSION.SDK_INT >= 30
                && !Covers.isCustom(this, title)
                && (!Covers.has(this, title) || last == null || now - last > NotifyPolicy.SESSION_GAP_MS);
        if (!need) {
            then.run();
            return;
        }
        lastCapture.put(k, now);
        handler.post(() -> {
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, io, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult res) {
                        try {
                            android.hardware.HardwareBuffer hb = res.getHardwareBuffer();
                            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                            if (hw != null) {
                                android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                Covers.saveFromScreenshot(ReadingTrackerService.this, title, sw);
                            }
                            hb.close();
                        } catch (Throwable t) {
                            App.saveError(ReadingTrackerService.this, "Miniature", t);
                        }
                        then.run();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        then.run();
                    }
                });
            } catch (Throwable t) {
                io.execute(then);
            }
        });
    }

    private void showBannerFor(NotifyPolicy.Decision d, Detector.Result r) {
        if (d == null || banner == null) return;

        String sub = r.title + " · Ch. " + Detector.formatChapter(r.chapter);
        switch (d.kind) {
            case NEW_SERIES:
                banner.show(d.kind, "Nouvelle série ajoutée ✨", sub);
                break;
            case STREAK:
                banner.show(d.kind, d.streak + " chapitres d'affilée !", sub);
                break;
            default:
                banner.show(d.kind, "Ajouté à ta collection", sub);
        }
    }

    private void safe(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            App.saveError(this, where, t);
        }
    }

    /** Garde une trace du dernier écran analysé, visible dans l'appli (utile pour régler la détection). */
    private void saveDebug(String pkg, List<Detector.TextNode> nodes, Detector.Result r) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Detector.TextNode t : nodes) {
            if (n++ >= 12) break;
            sb.append("• ").append(t.text.replace('\n', ' ')).append('\n');
        }
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE).edit()
                .putString("pkg", pkg)
                .putString("texts", sb.toString())
                .putString("result", r == null ? "rien détecté" : r.toString())
                .putLong("at", System.currentTimeMillis())
                .apply();
    }

    @Override
    public void onInterrupt() {
        // rien
    }
}
