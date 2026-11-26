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
    public static final String ACTION_RESUME_HINT = "com.lois.webtoontracker.RESUME_HINT";
    public static final String EXTRA_HINT = "hint";
    public static final String EXTRA_HINT_TITLE = "hint_title";
    public static final String EXTRA_HINT_PCT = "hint_pct";
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
    /** Réseau (liste des chapitres du site…) : à part, pour ne jamais retarder la détection. */
    private final ExecutorService net = Executors.newSingleThreadExecutor();
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

    private final BroadcastReceiver resumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String title = intent.getStringExtra(EXTRA_HINT_TITLE);
            if (title == null || resumeCard == null) return;
            Progress p = Progress.forResume(ReadingTrackerService.this, title);
            if (p == null) return;
            // On laisse au chapitre le temps de s'ouvrir, puis on propose d'y aller.
            resumeCard.offer(title, p, 1800, new ResumeOverlay.Actions() {
                @Override
                public void onGo() {
                    startAutoScroll(title, p);
                }

                @Override
                public void onStop() {
                    if (scroller != null) scroller.stop();
                    resumeCard.done(false, "Arrêté · tu reprends la main");
                    saveScrollDebug(title, "arrêté par toi");
                }
            });
        }
    };

    private ResumeOverlay resumeCard;
    private AutoScroller scroller;

    /** Lancé par le bouton ↓ de la carte : ramène à la case retenue. */
    private void startAutoScroll(String title, Progress p) {
        if (scroller == null) scroller = new AutoScroller(this, new AutoScroller.PositionSource() {
            @Override
            public float current() {
                return livePct;
            }

            @Override
            public long ageMs() {
                return SystemClock.uptimeMillis() - livePctAt;
            }
        });
        livePct = -1; // la position d'une page précédente ne compte plus
        resumeCard.setRunning("Vers la " + p.where() + " · touche ■ pour arrêter");
        scroller.start(p, new AutoScroller.Listener() {
            @Override
            public void onProgress(int current, int total, float pct) {
                resumeCard.progress(pct, total > 0
                        ? "Case " + (current + 1) + " / " + total + " → " + p.where().replace("case ", "")
                        + "  ·  " + Math.round(pct * 100) + " %"
                        : Math.round(pct * 100) + " % → " + p.percent() + " %");
            }

            @Override
            public void onDone(boolean reached, int current, int total, String how) {
                saveScrollDebug(title, (reached ? "réussi" : "échec") + " · " + how);
                if (reached) {
                    resumeCard.done(true, total > 0 ? "Case " + (current + 1) + " / " + total + " · bonne lecture"
                            : "Te voilà vers " + p.percent() + " %");
                } else if (current >= 0 && total > 0) {
                    resumeCard.done(false, "Arrivé case " + (current + 1) + " / " + total + " · finis à la main");
                } else {
                    resumeCard.done(false, "Cette page ne se laisse pas guider · fais défiler à la main");
                }
            }
        });
    }

    private void saveScrollDebug(String title, String text) {
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE).edit()
                .putString("scroll", title + " : " + text)
                .putLong("scroll_at", System.currentTimeMillis())
                .apply();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        banner = new Banner(this);
        resumeCard = new ResumeOverlay(this);
        ContextCompat.registerReceiver(this, testReceiver,
                new IntentFilter(ACTION_TEST_BANNER), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, resumeReceiver,
                new IntentFilter(ACTION_RESUME_HINT), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(testReceiver);
            unregisterReceiver(resumeReceiver);
        } catch (Throwable ignored) {
        }
        if (banner != null) banner.release();
        if (resumeCard != null) resumeCard.release();
        if (scroller != null) scroller.stop();
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
        // Retour dans Webtoon Tracker : la carte « Reprendre » n'a plus rien à faire à l'écran.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.equals(getPackageName())
                && resumeCard != null && (scroller == null || !scroller.isRunning())) {
            resumeCard.hide();
        }
        if (!Detector.isWatched(pkg)) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) trackScroll(event);

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

        // Écran de paiement ? (message de verrou visible) → vérifié sur le fil IO (cases à l'écran ou non).
        String screenText = Paywall.join(nodes);
        if (Paywall.textSaysLocked(screenText) && now - lastLockCheck > 2000) {
            lastLockCheck = now;
            final Detector.Result lockTarget = r;
            final String lockPkg = pkg;
            io.execute(() -> safe("Paiement", () -> checkLock(lockTarget, screenText, lockPkg)));
        }

        if (pending != null && pending.sameAs(r)) return; // toujours le même chapitre → on attend

        if (pendingCommit != null) handler.removeCallbacks(pendingCommit);
        if (pendingAlready != null) handler.removeCallbacks(pendingAlready);
        pending = r;
        lastPct = -1;
        final Detector.Result target = r;

        pendingAlready = () -> io.execute(() -> safe("Déjà lu", () -> checkAlreadyRead(target)));
        handler.postDelayed(pendingAlready, ALREADY_DELAY_MS);

        pendingCommit = () -> io.execute(() -> safe("Enregistrement", () -> record(target)));
        handler.postDelayed(pendingCommit, DWELL_MS);
    }

    // ------------------------------------------------------------------ Position dans le chapitre

    private float lastPct = -1;
    private long lastPctSave, lastPanelScan;
    /** Dernière position mesurée dans la page affichée (au pourcentage), même hors chapitre détecté. */
    private volatile float livePct = -1;
    private volatile long livePctAt;

    /**
     * Relève où tu en es dans le chapitre en cours :
     *  1. de préférence la CASE affichée (image n° X sur Y) → ancre précise pour revenir ;
     *  2. sinon un pourcentage de défilement (scrollY / max, ou ligne visible / total).
     */
    private void trackScroll(AccessibilityEvent e) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float pct = -1;
        if (e.getMaxScrollY() > screenH * 2 && e.getScrollY() >= 0) {
            pct = Math.min(1f, e.getScrollY() / (float) e.getMaxScrollY());
        } else if (e.getItemCount() >= 5 && e.getToIndex() >= 0) {
            pct = Math.min(1f, (e.getToIndex() + 1) / (float) e.getItemCount());
        }
        if (pct >= 0) {
            livePct = pct;
            livePctAt = now0();
        }
        if (pending == null || (scroller != null && scroller.isRunning())) return; // pendant le guidage, on ne réécrit pas

        long now = SystemClock.uptimeMillis();
        if (now - lastPanelScan < 1800) return;      // au plus un relevé toutes les ~2 s pendant la lecture
        lastPanelScan = now;
        final Detector.Result r = pending;
        final float fallback = pct;
        io.execute(() -> safe("Position", () -> {
            String title = DbHelper.get(this).resolveTitle(r.title);
            Panels.Snapshot s = Panels.take(this);
            float at = -1;
            if (s != null && s.current >= 0) {
                at = s.pct();
                Progress.save(this, title, r.chapter, at, s.current, s.count);
            } else if (fallback >= 0 && Math.abs(fallback - lastPct) >= 0.02f) {
                lastPct = fallback;
                at = fallback;
                Progress.save(this, title, r.chapter, fallback);
            }
            // Vignette : on analyse une capture tous les ~7 % du chapitre (8 → 95 %, 12 au plus) ;
            // le sélecteur garde la meilleure (une seule scène, sans texte ni vide, visages favorisés).
            String key = title.toLowerCase(Locale.ROOT) + "#" + r.chapter;
            float[] st = midShots.get(key);
            if (st == null) st = new float[]{-1f, 0f};
            if (at >= 0.08f && at <= 0.95f && st[1] < 12 && Math.abs(at - st[0]) >= 0.07f
                    && DbHelper.get(this).getReadAt(title, r.chapter) >= 0) {
                st[0] = at;
                st[1]++;
                midShots.put(key, st);
                shoot(title, r.chapter, false, true, null);
            }
        }));
    }

    private static long now0() {
        return SystemClock.uptimeMillis();
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
        DbHelper db = DbHelper.get(this);
        r.title = db.resolveTitle(r.title); // même série vue sous un autre nom / sur un autre site
        long readAt = db.getReadAt(r.title, r.chapter);
        if (readAt < 0) return;
        double furthest = db.lastRead(r.title);
        SharedPreferences s = settings();
        NotifyPolicy.Decision d = policy.onAlreadyRead(r.title, r.chapter, furthest, System.currentTimeMillis(),
                s.getInt(KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART), s.getBoolean(KEY_NOTIFY_ALREADY, true));
        if (d == null || banner == null) return;
        String when = new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(readAt));
        String ch = Detector.formatChapter(r.chapter), last = Detector.formatChapter(furthest);
        final String title = r.title;
        // Plus ancien que ton dernier chapitre : on le dit, avec un raccourci pour revenir où tu en étais.
        String lu = DbHelper.SOURCE_BEFORE.equals(db.getSource(r.title, r.chapter)) ? "Lu ailleurs" : "Lu le " + when;
        banner.show(NotifyPolicy.Kind.ALREADY_READ, "Déjà lu · Chapitre " + ch,
                lu,
                7000, 0, "Aller au ch. " + last, () -> Resume.open(this, title));
    }

    private void record(Detector.Result r) {
        DbHelper db = DbHelper.get(this);
        r.title = db.resolveTitle(r.title);
        // Écran de paiement à la place du chapitre : ce n'est pas une lecture.
        if (!lockedRecently(r)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<Detector.TextNode> nodes = new ArrayList<>();
                collect(root, nodes, 0);
                String text = Paywall.join(nodes);
                if (Paywall.textSaysLocked(text)) {
                    checkLock(r, text, root.getPackageName() == null ? null : root.getPackageName().toString());
                }
            }
        }
        if (lockedRecently(r)) return;

        boolean newSeries = !db.seriesExists(r.title);
        long now = System.currentTimeMillis();
        boolean isNew = db.insertRead(r.title, r.chapter, now, r.source, r.url);
        if (isNew) {
            recordedKey = lockKeyOf(r.title, r.chapter);
            recordedAt = SystemClock.uptimeMillis();
        }
        ChapterLinks.rememberVisit(this, r.title, r.url, r.chapter); // le site à réutiliser pour les autres chapitres
        db.rememberSource(r); // pour "Reprendre" et les alertes, même si le chapitre était déjà lu
        updateSiteChapters(r, newSeries);
        if (!isNew) {
            db.markReopened(r.title, r.chapter, now, r.url, NotifyPolicy.SESSION_GAP_MS); // relecture
            if (!ChapterThumbs.has(this, r.title, r.chapter)) captureChapterThumb(r.title, r.chapter);
            return;
        }

        NoteWriter.write(this);

        NotifyPolicy.Decision d = policy.onNewChapter(r.title, newSeries, System.currentTimeMillis(),
                settings().getInt(KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART));

        // Miniature : capture de la case lue, AVANT d'afficher la bannière (pour ne pas la capturer).
        if (newSeries) CoverWorker.enqueueIfNeeded(this); // couverture officielle, en arrière-plan
        captureCoverIfNeeded(r.title, r.chapter, () -> {
            WidgetProvider.updateAll(this);
            sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
            showBannerFor(d, r);
        });
    }

    /**
     * Liste complète des chapitres (du 1er au dernier) sur le site où tu lis : tout de suite pour une
     * nouvelle série ou si ce chapitre dépasse la liste connue, sinon toutes les 6 h au plus.
     */
    private void updateSiteChapters(Detector.Result r, boolean newSeries) {
        if (r.url == null || !r.url.startsWith("http")) return;
        final String title = r.title, url = r.url;
        final double ch = r.chapter;
        java.util.TreeMap<Double, String> known = DbHelper.get(this).siteChapters(title, ChapterLinks.host(url));
        final boolean force = newSeries || known.isEmpty() || ch > known.lastKey();
        net.execute(() -> safe("Liste des chapitres", () -> {
            int n = SiteChapters.refresh(this, title, url, ch, force);
            if (n > 0) sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
        }));
    }

    // ------------------------------------------------------------------ Chapitres payants

    /** Dernier écran de paiement confirmé : « titre#chapitre » et moment (horloge système). */
    private volatile String lockKey;
    private volatile long lockSeenAt;
    private long lastLockCheck;
    /** Dernière lecture enregistrée comme NOUVELLE (annulée si un écran de paiement apparaît ensuite). */
    private volatile String recordedKey;
    private volatile long recordedAt;
    private final java.util.Map<String, Long> lockBanners = new java.util.HashMap<>();

    private static String lockKeyOf(String title, double chapter) {
        return Titles.key(title) + "#" + Detector.formatChapter(chapter);
    }

    private boolean lockedRecently(Detector.Result r) {
        return lockKey != null && lockKey.equals(lockKeyOf(r.title, r.chapter))
                && SystemClock.uptimeMillis() - lockSeenAt < 120_000;
    }

    /**
     * Un message de verrou est affiché (fil IO). C'est un écran de paiement seulement s'il n'y a presque
     * pas de cases à l'écran — sinon le message parle d'un autre chapitre (« suivant à débloquer »…).
     * Alors : le chapitre n'est pas compté comme lu (ou la lecture enregistrée il y a peu est annulée),
     * il est noté comme payant sur ce site, et une bannière le dit.
     */
    private void checkLock(Detector.Result r, String text, String pkg) {
        Panels.Snapshot s = Panels.take(this);
        if (s != null && s.count >= 5) return;
        DbHelper db = DbHelper.get(this);
        String title = db.resolveTitle(r.title);
        String key = lockKeyOf(title, r.chapter);
        lockKey = key;
        lockSeenAt = SystemClock.uptimeMillis();

        long now = System.currentTimeMillis();
        long in = Paywall.freeInMs(text);
        String host = r.url != null ? ChapterLinks.host(r.url) : pkg;
        if (host == null) host = "appli";
        db.saveLock(title, host, r.chapter, r.url, in > 0 ? now + in : 0, now);
        Follow.sync(this);   // vérification périodique : te prévenir quand il devient gratuit

        boolean undone = false;
        if (key.equals(recordedKey) && SystemClock.uptimeMillis() - recordedAt < 15 * 60_000) {
            db.undoRead(title, r.chapter);
            ChapterThumbs.deleteIfNotOfficial(this, title, r.chapter);
            recordedKey = null;
            undone = true;
            NoteWriter.write(this);
        }
        WidgetProvider.updateAll(this);
        sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));

        // Une bannière par chapitre (de nouveau après 30 min si tu y reviens).
        Long last = lockBanners.get(key);
        if (banner == null || (last != null && now - last < 30 * 60_000)) return;
        lockBanners.put(key, now);
        String when = in > 0 ? "gratuit " + inWords(in) : "je te préviens dès qu'il est gratuit";
        banner.show(NotifyPolicy.Kind.LOCKED, "Chapitre " + Detector.formatChapter(r.chapter) + " payant ici",
                (undone ? "Retiré de tes lectures · " : "Pas compté comme lu · ") + when, 5500, 0);
    }

    /** « dans ~2 j », « dans ~5 h », « dans ~20 min » */
    static String inWords(long ms) {
        long min = Math.max(1, ms / 60_000);
        if (min < 90) return "dans ~" + min + " min";
        long h = Math.round(min / 60.0);
        if (h < 36) return "dans ~" + h + " h";
        return "dans ~" + Math.round(h / 24.0) + " j";
    }

    // ------------------------------------------------------------------ Miniatures

    private final java.util.Map<String, Long> lastCapture = new java.util.HashMap<>();

    /**
     * Couverture capturée seulement si la série n'en a AUCUNE (la couverture officielle la remplace
     * dès qu'elle est trouvée). La vignette du chapitre est prise dans la même capture.
     */
    private void captureCoverIfNeeded(String title, double chapter, Runnable then) {
        boolean need = android.os.Build.VERSION.SDK_INT >= 30 && !Covers.isCustom(this, title) && !Covers.has(this, title);
        if (!need) {
            captureChapterThumb(title, chapter, then);   // la vignette du chapitre, elle, est toujours prise
            return;
        }
        shoot(title, chapter, true, false, then);
    }

    // ------------------------------------------------------------------ Vignettes de chapitres

    /** Essais de vignette au milieu de chaque chapitre : [dernière position, nombre d'essais]. */
    private final java.util.Map<String, float[]> midShots = new java.util.HashMap<>();
    private long lastThumbShot;

    private void captureChapterThumb(String title, double chapter) {
        captureChapterThumb(title, chapter, null);
    }

    /** @param after lancé (sur le fil IO) une fois la capture faite — ou tout de suite si on s'en passe. */
    private void captureChapterThumb(String title, double chapter, Runnable after) {
        shoot(title, chapter, false, false, after);
    }

    /**
     * Écran allumé, déverrouillé, et l'appli de lecture au premier plan ?
     * Sinon la capture montrerait l'écran de veille (« Always On »), l'écran de verrouillage ou une autre appli.
     */
    private boolean screenShowsReading() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isInteractive()) return false;
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String p = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
        return p != null && Detector.isWatched(p);
    }

    /**
     * Une capture d'écran (fil IO) → vignette du chapitre (+ couverture si withCover).
     * Seulement si une case de webtoon est bien au centre de l'écran (pas les boutons « Suivant »,
     * pas un écran de veille), et au plus une toutes les 1,5 s.
     */
    private void shoot(String title, double chapter, boolean withCover, boolean mid, Runnable after) {
        Runnable done = () -> {
            if (after != null) io.execute(after);
        };
        long now = SystemClock.uptimeMillis();
        boolean paywall = lockKey != null && now - lockSeenAt < 5000;   // jamais l'écran de paiement en vignette
        if (android.os.Build.VERSION.SDK_INT < 30 || now - lastThumbShot < 1500 || paywall || !screenShowsReading()) {
            done.run();
            return;
        }
        Panels.Snapshot snap = Panels.take(this);
        if (snap != null && snap.centerRect == null) {   // on voit des cases, mais pas au centre (bas de page…)
            done.run();
            return;
        }
        final android.graphics.Rect panel = snap == null ? null : snap.centerRect;
        lastThumbShot = now;
        handler.post(() -> {
            if ((banner != null && banner.isShowing()) || !screenShowsReading()) { // ni bannière, ni écran éteint
                done.run();
                return;
            }
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, io, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult res) {
                        try {
                            android.hardware.HardwareBuffer hb = res.getHardwareBuffer();
                            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                            if (hw != null) {
                                android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                if (withCover) Covers.saveFromScreenshot(ReadingTrackerService.this, title, sw, panel);
                                if (chapter >= 0) ChapterThumbs.saveFromScreenshot(ReadingTrackerService.this, title, chapter, sw, panel, mid);
                            }
                            hb.close();
                        } catch (Throwable t) {
                            App.saveError(ReadingTrackerService.this, "Vignette", t);
                        }
                        done.run();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        done.run();
                    }
                });
            } catch (Throwable t) {
                done.run();
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
