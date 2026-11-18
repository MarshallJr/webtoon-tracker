package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Service d'accessibilité qui tourne en fond : quand une appli de lecture ou un navigateur
 * est au premier plan, il lit le texte affiché, détecte "titre + chapitre", et si le même
 * chapitre reste affiché quelques secondes (= tu le lis vraiment), il l'enregistre.
 */
public class ReadingTrackerService extends AccessibilityService {

    public static final String ACTION_UPDATED = "com.lois.webtoontracker.UPDATED";
    public static final String PREFS_DEBUG = "debug";

    /** Temps pendant lequel un chapitre doit rester affiché avant d'être compté comme lu. */
    private static final long DWELL_MS = 6000;
    private static final long MIN_SCAN_INTERVAL_MS = 700;
    private static final int MAX_NODES = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Detector.Result pending;
    private Runnable pendingCommit;
    private long lastScan;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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
        pending = r;
        final Detector.Result target = r;
        pendingCommit = () -> record(target);
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

    private void record(Detector.Result r) {
        new Thread(() -> {
            boolean isNew = DbHelper.get(this).insertRead(r.title, r.chapter, System.currentTimeMillis(), r.source);
            if (!isNew) return;
            NoteWriter.write(this);
            sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getPackageName()));
            handler.post(() -> Toast.makeText(this,
                    "📖 " + r.title + " — ch. " + Detector.formatChapter(r.chapter) + " noté",
                    Toast.LENGTH_SHORT).show());
        }).start();
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
