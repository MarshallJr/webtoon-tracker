package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Fait défiler le chapitre jusqu'à un pourcentage donné, à TA demande (bouton "Y aller").
 *
 * 1. Lecteurs en liste d'images (WEBTOON, Mihon…) : saut direct à la bonne image si possible.
 * 2. Sinon : défilement page par page (action "défiler" de la page, ou geste de balayage),
 *    en mesurant la position après chaque pas grâce aux événements de défilement.
 * S'arrête tout seul : objectif atteint, page bloquée, changement d'appli, 40 s max — ou si tu touches la bannière.
 */
public final class AutoScroller {

    public interface Listener {
        void onProgress(float current, float target);

        void onDone(boolean reached, float current);
    }

    /** Fournit la dernière position mesurée (0 → 1, ou -1 si inconnue). */
    public interface PositionSource {
        float current();
    }

    private static final long STEP_MS = 420;
    private static final long MAX_MS = 40_000;

    private final AccessibilityService svc;
    private final PositionSource pos;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private float target;
    private String pkg;
    private long startedAt;
    private int steps, stuck;
    private float lastSeen;
    private boolean triedJump;
    private Listener listener;

    public AutoScroller(AccessibilityService svc, PositionSource pos) {
        this.svc = svc;
        this.pos = pos;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(float target, Listener l) {
        stop();
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            l.onDone(false, -1);
            return;
        }
        this.pkg = root.getPackageName().toString();
        this.target = Math.max(0.02f, Math.min(0.97f, target));
        this.listener = l;
        this.running = true;
        this.startedAt = SystemClock.uptimeMillis();
        this.steps = 0;
        this.stuck = 0;
        this.lastSeen = -1;
        this.triedJump = false;
        handler.post(this::step);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void finish(boolean ok) {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        listener.onDone(ok, pos.current());
    }

    private void step() {
        if (!running) return;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) {
            finish(false); // tu as changé d'appli : on arrête
            return;
        }
        float cur = pos.current();
        if (cur >= target - 0.01f) {
            finish(true);
            return;
        }
        if (SystemClock.uptimeMillis() - startedAt > MAX_MS || steps > 300) {
            finish(false);
            return;
        }
        if (cur < 0 && steps >= 4) {
            finish(false); // ce site ne dit pas où on en est : on ne défile pas à l'aveugle
            return;
        }
        if (cur >= 0 && cur <= lastSeen + 0.0005f) stuck++;
        else stuck = 0;
        if (stuck >= 7) {
            finish(false); // la page ne bouge plus (fin, ou le site bloque le défilement)
            return;
        }
        lastSeen = Math.max(lastSeen, cur);
        if (cur >= 0) listener.onProgress(cur, target);

        AccessibilityNodeInfo scroller = biggestScrollable(root);
        boolean acted = false;
        if (!triedJump && scroller != null) {
            triedJump = true;
            acted = jumpToPosition(scroller);
        }
        // Proche du but : petits pas pour ne pas dépasser.
        boolean near = cur >= 0 && target - cur < 0.04f;
        if (!acted && scroller != null && !near) {
            acted = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        if (!acted) acted = swipe(near);
        steps++;
        handler.postDelayed(this::step, acted ? STEP_MS : STEP_MS * 2);
    }

    /** Saut direct (listes d'images) : ligne ≈ pourcentage × nombre de lignes. */
    private boolean jumpToPosition(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo.CollectionInfo ci = node.getCollectionInfo();
        if (ci == null || ci.getRowCount() < 5) return false;
        boolean supported = false;
        for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
            if (a.getId() == android.R.id.accessibilityActionScrollToPosition) supported = true;
        }
        if (!supported) return false;
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, (int) Math.max(0, target * ci.getRowCount() - 1));
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
        return node.performAction(android.R.id.accessibilityActionScrollToPosition, args);
    }

    /** Geste de balayage vers le haut (nécessite que le service ait le droit d'effectuer des gestes). */
    private boolean swipe(boolean small) {
        try {
            int w = svc.getResources().getDisplayMetrics().widthPixels;
            int h = svc.getResources().getDisplayMetrics().heightPixels;
            Path p = new Path();
            p.moveTo(w / 2f, h * (small ? 0.60f : 0.80f));
            p.lineTo(w / 2f, h * (small ? 0.45f : 0.22f));
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, small ? 300 : 220))
                    .build();
            return svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** La zone qui défile la plus grande de l'écran = la page du chapitre. */
    private static AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo[] best = {null};
        long[] bestArea = {0};
        walk(root, best, bestArea, 0);
        return best[0];
    }

    private static void walk(AccessibilityNodeInfo n, AccessibilityNodeInfo[] best, long[] bestArea, int depth) {
        if (n == null || depth > 30) return;
        if (n.isScrollable()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea[0]) {
                bestArea[0] = area;
                best[0] = n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), best, bestArea, depth + 1);
    }
}
