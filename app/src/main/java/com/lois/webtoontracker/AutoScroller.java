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
 * Ramène à l'endroit où tu t'étais arrêté, à TA demande (bouton ↓ de la carte de reprise).
 *
 * Position mesurée en continu (0 → 1) :
 *   - par les cases : n° de l'image au centre + où on est DANS cette image (marche aussi avec
 *     des images très hautes, de plusieurs écrans) ;
 *   - et par le défilement annoncé par le site / l'appli, quand il est disponible.
 *
 * Déplacement :
 *   1. un saut direct à la case (si le lecteur ou le site l'accepte) ;
 *   2. loin du but : grands lancers rapides (plusieurs pages d'un coup) ;
 *   3. près du but : petits pas, et petit retour arrière si on a dépassé.
 * S'arrête : arrivé, page vraiment immobile (~4 s), changement d'appli, 45 s max — ou si tu touches ■.
 */
public final class AutoScroller {

    public interface Listener {
        /** @param current case actuelle (0-based) ou -1, total ou 0, pct 0…1 */
        void onProgress(int current, int total, float pct);

        void onDone(boolean reached, int current, int total, String how);
    }

    public interface PositionSource {
        float current();   // position annoncée par les événements de défilement, -1 si inconnue

        long ageMs();      // âge de cette mesure
    }

    private static final long MAX_MS = 45_000;
    private static final float TOLERANCE = 0.012f;   // ≈ 1 % du chapitre

    private final AccessibilityService svc;
    private final PositionSource src;
    private final Handler worker;
    private volatile boolean running;

    private Progress target;
    private Listener listener;
    private String pkg;
    private long startedAt;
    private int steps, stillSteps, backSteps;
    private boolean jumped, gestures;   // gestures : l'action "page suivante" ne fait rien ici, on passe aux gestes
    private int modeStill;
    private float lastPanelPos = -2, lastEventPos = -2, bestPos = -1;
    private int lastCur = -1, lastTotal = 0;
    private String how = "";

    public AutoScroller(AccessibilityService svc, PositionSource src) {
        this.svc = svc;
        this.src = src;
        HandlerThread t = new HandlerThread("webtoon-autoscroll");
        t.start();
        worker = new Handler(t.getLooper());
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Progress target, Listener l) {
        stop();
        this.target = target;
        this.listener = l;
        this.running = true;
        this.startedAt = SystemClock.uptimeMillis();
        this.steps = 0;
        this.stillSteps = 0;
        this.backSteps = 0;
        this.jumped = false;
        this.gestures = false;
        this.modeStill = 0;
        this.lastPanelPos = -2;
        this.lastEventPos = -2;
        this.bestPos = -1;
        this.how = "";
        this.pkg = null;
        worker.post(this::step);
    }

    public void stop() {
        running = false;
        worker.removeCallbacksAndMessages(null);
    }

    private void finish(boolean ok) {
        if (!running) return;
        running = false;
        worker.removeCallbacksAndMessages(null);
        listener.onDone(ok, lastCur, lastTotal, how);
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
                finish(false);
                return;
            }
            if (SystemClock.uptimeMillis() - startedAt > MAX_MS || steps > 400) {
                how += " · temps écoulé";
                finish(false);
                return;
            }

            // ---- Mesure
            Panels.Snapshot s = Panels.take(svc);
            float panelPos = s != null && s.current >= 0 ? s.pct() : -1;
            float eventPos = src.ageMs() < 2500 ? src.current() : -1;
            if (s != null) {
                lastCur = s.current;
                lastTotal = s.count;
            }
            // Même mesure que celle enregistrée pendant la lecture (cases si connues, sinon défilement).
            float pos = target.count > 0
                    ? (panelPos >= 0 ? panelPos : eventPos)
                    : (eventPos >= 0 ? eventPos : panelPos);
            boolean moved = Math.abs(panelPos - lastPanelPos) > 0.0015f || Math.abs(eventPos - lastEventPos) > 0.0015f;
            lastPanelPos = panelPos;
            lastEventPos = eventPos;
            if (steps > 0) {
                stillSteps = moved ? 0 : stillSteps + 1;
                modeStill = moved ? 0 : modeStill + 1;
                if (modeStill >= 2) {           // la méthode actuelle ne fait plus bouger : on change
                    gestures = !gestures;
                    modeStill = 0;
                    how += gestures ? " → gestes" : " → actions";
                }
            }

            if (pos < 0) {
                if (steps >= 5) {
                    how += (how.isEmpty() ? "" : " → ") + "position inconnue (ni cases ni défilement)";
                    finish(false);
                    return;
                }
                fling(root, 1, true); // on bouge un peu pour obtenir une première mesure
                steps++;
                again(350);
                return;
            }
            bestPos = Math.max(bestPos, pos);
            listener.onProgress(s != null ? s.current : -1, s != null ? s.count : 0, pos);

            float goal = target.pct;
            float remaining = goal - pos;
            if (Math.abs(remaining) <= TOLERANCE) {
                how += (how.isEmpty() ? "" : " → ") + (panelPos >= 0 ? (s.collection ? "liste" : "cases") : "défilement") + " ok";
                finish(true);
                return;
            }
            if (stillSteps >= 12) {  // ~4 s sans bouger du tout, avec les deux méthodes : fin de page ou site qui bloque
                how += " · page immobile vers " + Math.round(pos * 100) + " %";
                finish(remaining < 0.03f);
                return;
            }

            // ---- 1. Saut direct (une seule fois)
            if (!jumped && s != null && remaining > 0.05f) {
                jumped = true;
                int want = Math.max(0, Math.min(s.count - 1, (int) Math.floor(goal * s.count)));
                boolean ok;
                if (s.collection) {
                    Bundle args = new Bundle();
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, want);
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
                    ok = s.container.performAction(android.R.id.accessibilityActionScrollToPosition, args);
                    how = "saut liste " + (want + 1) + "/" + s.count + (ok ? "" : " refusé");
                } else {
                    ok = s.panels.get(want).performAction(android.R.id.accessibilityActionShowOnScreen);
                    how = "saut case " + (want + 1) + "/" + s.count + (ok ? "" : " refusé");
                }
                if (ok) {
                    steps++;
                    again(650);
                    return;
                }
            }

            // ---- 2/3. Lancers rapides puis petits pas
            steps++;
            if (remaining < 0) {
                // Dépassé : petit retour en arrière (3 fois max)
                if (backSteps++ >= 3) {
                    how += " · arrêté juste après";
                    finish(true);
                    return;
                }
                swipe(false, 0.18f, 260);
                again(380);
                return;
            }
            if (!how.contains("lancers")) how += (how.isEmpty() ? "" : " → ") + "lancers";
            if (remaining > 0.25f) {
                move(root, 3, 0.78f, 70);     // très loin : lancer fort
                again(260);
            } else if (remaining > 0.08f) {
                move(root, 2, 0.65f, 130);    // loin : lancer moyen
                again(300);
            } else if (remaining > 0.03f) {
                move(root, 1, 0.60f, 360);    // proche : glissé (sans élan)
                again(360);
            } else {
                swipe(true, 0.25f, 320);      // approche fine
                again(380);
            }
        } catch (Throwable t) {
            how += " · erreur " + t.getClass().getSimpleName();
            finish(false);
        }
    }

    /** Premier petit mouvement pour obtenir une mesure. */
    private void fling(AccessibilityNodeInfo root, int pages, boolean gentle) {
        move(root, pages, gentle ? 0.35f : 0.70f, gentle ? 220 : 90);
    }

    /**
     * Avance : "page suivante" de la zone qui défile (instantané) répété n fois,
     * ou un geste (court = lancer avec élan, long = glissé précis) si les actions ne marchent pas ici.
     */
    private void move(AccessibilityNodeInfo root, int pages, float distance, long gestureMs) {
        boolean ok = false;
        if (!gestures) {
            AccessibilityNodeInfo c = root == null ? null : Panels.biggestScrollable(root);
            if (c != null) {
                for (int i = 0; i < pages; i++) {
                    if (c.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) ok = true;
                    else break;
                }
            }
            if (!ok) gestures = true;
        }
        if (!ok) swipe(true, distance, gestureMs);
    }

    /** Geste vertical. distance = fraction de l'écran ; durée courte = lancer. */
    private boolean swipe(boolean forward, float distance, long durationMs) {
        try {
            int w = svc.getResources().getDisplayMetrics().widthPixels;
            int h = svc.getResources().getDisplayMetrics().heightPixels;
            float mid = h * 0.5f, half = h * distance / 2f;
            float from = forward ? mid + half : mid - half;
            float to = forward ? mid - half : mid + half;
            Path p = new Path();
            p.moveTo(w * 0.5f, from);
            p.lineTo(w * 0.5f, to);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs))
                    .build();
            return svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
