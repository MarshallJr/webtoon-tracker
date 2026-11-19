package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Ramène à la case où tu t'étais arrêté, à TA demande (bouton de la carte de reprise).
 *
 *  1. Saut direct à l'ancre :
 *     - lecteur en liste → "défiler jusqu'à la ligne N"
 *     - page web          → "afficher à l'écran" sur l'image N
 *  2. Si le saut ne suffit pas : pas à pas vers l'avant / l'arrière en recomptant les cases.
 *  3. Si la page n'a pas de cases repérables : pas à pas au pourcentage (moins précis).
 * S'arrête tout seul (arrivé, bloqué, changement d'appli, 40 s) ou dès que tu touches ■.
 */
public final class AutoScroller {

    public interface Listener {
        /** @param current case actuelle (0-based) ou -1, total ou 0, pct 0…1 */
        void onProgress(int current, int total, float pct);

        void onDone(boolean reached, int current, int total, String how);
    }

    public interface PositionSource {
        float current();   // position au pourcentage (événements de défilement), -1 si inconnue
    }

    private static final long STEP_MS = 450;
    private static final long JUMP_WAIT_MS = 800;
    private static final long MAX_MS = 40_000;

    private final AccessibilityService svc;
    private final PositionSource pos;
    private final Handler worker;
    private volatile boolean running;

    private Progress target;
    private Listener listener;
    private String pkg;
    private long startedAt;
    private int jumps, steps, stuck, lastIdx;
    private float lastPct;
    private String how = "";

    public AutoScroller(AccessibilityService svc, PositionSource pos) {
        this.svc = svc;
        this.pos = pos;
        HandlerThread t = new HandlerThread("webtoon-autoscroll");
        t.start();
        worker = new Handler(t.getLooper());
    }

    public void start(Progress target, Listener l) {
        stop();
        this.target = target;
        this.listener = l;
        this.running = true;
        this.startedAt = SystemClock.uptimeMillis();
        this.jumps = 0;
        this.steps = 0;
        this.stuck = 0;
        this.lastIdx = -2;
        this.lastPct = -1;
        this.how = "";
        this.pkg = null;
        worker.post(this::step);
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        worker.removeCallbacksAndMessages(null);
    }

    private void finish(boolean ok, int cur, int total) {
        if (!running) return;
        running = false;
        worker.removeCallbacksAndMessages(null);
        listener.onDone(ok, cur, total, how);
    }

    private void again(long ms) {
        if (running) worker.postDelayed(this::step, ms);
    }

    private void step() {
        if (!running) return;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            String p = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
            if (pkg == null) pkg = p;
            if (p == null || !p.equals(pkg)) {
                how += " · appli changée";
                finish(false, -1, 0);
                return;
            }
            if (SystemClock.uptimeMillis() - startedAt > MAX_MS || steps > 250) {
                how += " · temps écoulé";
                finish(false, lastIdx, 0);
                return;
            }

            Panels.Snapshot s = Panels.take(svc);
            if (s != null && s.current >= 0) {
                stepByPanels(s);
            } else {
                stepByPercent();
            }
        } catch (Throwable t) {
            how += " · erreur " + t.getClass().getSimpleName();
            finish(false, -1, 0);
        }
    }

    // ------------------------------------------------------------------ Par cases (précis)

    private void stepByPanels(Panels.Snapshot s) {
        int want = wantedIndex(s);
        listener.onProgress(s.current, s.count, s.pct());
        if (Math.abs(s.current - want) <= 1) {
            how = (how.isEmpty() ? "" : how + " → ") + (s.collection ? "liste" : "images") + " ok";
            finish(true, s.current, s.count);
            return;
        }
        if (s.current == lastIdx) stuck++;
        else stuck = 0;
        lastIdx = s.current;
        if (stuck >= 6) {
            how += " · bloqué case " + (s.current + 1) + "/" + s.count;
            finish(false, s.current, s.count);
            return;
        }

        // 1. Saut direct à l'ancre (3 essais : les images qui se chargent peuvent décaler la page).
        if (jumps < 3) {
            jumps++;
            boolean ok;
            if (s.collection) {
                Bundle args = new Bundle();
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, want);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
                ok = s.container.performAction(android.R.id.accessibilityActionScrollToPosition, args);
                how = "saut liste ligne " + (want + 1) + (ok ? "" : " refusé");
            } else {
                ok = s.panels.get(want).performAction(android.R.id.accessibilityActionShowOnScreen);
                how = "saut image " + (want + 1) + "/" + s.count + (ok ? "" : " refusé");
            }
            if (ok) {
                again(JUMP_WAIT_MS);
                return;
            }
            jumps = 3; // le site refuse le saut : on passe au pas à pas
        }

        // 2. Pas à pas en recomptant les cases.
        boolean forward = s.current < want;
        boolean near = Math.abs(s.current - want) <= 3;
        boolean acted = false;
        if (s.container != null && !near) {
            acted = s.container.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        if (!acted) acted = swipe(forward, near);
        if (!how.contains("pas à pas")) how += " → pas à pas";
        steps++;
        again(acted ? STEP_MS : STEP_MS * 2);
    }

    /** Case visée : même case qu'à l'enregistrement si le chapitre a le même nombre de cases, sinon au prorata. */
    private int wantedIndex(Panels.Snapshot s) {
        int want;
        if (target.index >= 0 && target.count > 0
                && Math.abs(s.count - target.count) <= Math.max(3, target.count / 4)) {
            want = target.index;
        } else {
            want = Math.round(target.pct * s.count) - 1;
        }
        return Math.max(0, Math.min(s.count - 1, want));
    }

    // ------------------------------------------------------------------ Au pourcentage (secours)

    private void stepByPercent() {
        float cur = pos.current();
        if (!how.contains("pourcentage")) how += (how.isEmpty() ? "" : " → ") + "pourcentage (pas de cases repérées)";
        if (cur >= 0) listener.onProgress(-1, 0, cur);
        if (cur >= target.pct - 0.015f) {
            finish(true, -1, 0);
            return;
        }
        if (cur < 0 && steps >= 4) {
            how += " · position inconnue";
            finish(false, -1, 0);
            return;
        }
        if (cur >= 0 && cur <= lastPct + 0.0005f) stuck++;
        else stuck = 0;
        if (stuck >= 6) {
            how += " · bloqué";
            finish(false, -1, 0);
            return;
        }
        lastPct = Math.max(lastPct, cur);
        boolean near = cur >= 0 && target.pct - cur < 0.04f;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        AccessibilityNodeInfo c = root == null ? null : Panels.biggestScrollable(root);
        boolean acted = !near && c != null && c.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!acted) acted = swipe(true, near);
        steps++;
        again(acted ? STEP_MS : STEP_MS * 2);
    }

    /** Balayage (demande le droit "effectuer des gestes" du service d'accessibilité). */
    private boolean swipe(boolean forward, boolean small) {
        try {
            int w = svc.getResources().getDisplayMetrics().widthPixels;
            int h = svc.getResources().getDisplayMetrics().heightPixels;
            float from = h * (small ? 0.58f : 0.78f), to = h * (small ? 0.42f : 0.24f);
            Path p = new Path();
            p.moveTo(w / 2f, forward ? from : to);
            p.lineTo(w / 2f, forward ? to : from);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, small ? 320 : 240))
                    .build();
            return svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
