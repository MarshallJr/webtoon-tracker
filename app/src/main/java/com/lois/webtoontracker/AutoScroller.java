package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Ramène à l'endroit où tu t'étais arrêté, à TA demande (bouton ↓ de la carte de reprise).
 *
 * Sans aucun geste simulé : l'appli ne déclare pas la capacité « gestes », car Chrome/Brave
 * considèrent alors qu'un outil d'accessibilité pilote l'écran et bloquent le plein écran
 * (barres d'adresse et du bas toujours visibles).
 *
 * Position mesurée en continu (0 → 1) : n° de la case au centre + où on est DANS la case,
 * ou le défilement annoncé par le site / l'appli.
 *
 * Déplacement :
 *   1. saut direct à la bonne case (jusqu'à 3 fois si la page se recale en chargeant) ;
 *   2. puis « page suivante / précédente » pour se placer dans la case, en mesurant
 *      ce que vaut une page pour s'arrêter au plus près sans dépasser.
 * S'arrête : arrivé, page vraiment immobile (~3 s), changement d'appli, 45 s max — ou si tu touches ■.
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
    private static final int MAX_JUMPS = 3;

    private static final int[] FORWARD = {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, android.R.id.accessibilityActionScrollDown};
    private static final int[] BACKWARD = {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, android.R.id.accessibilityActionScrollUp};

    private final AccessibilityService svc;
    private final PositionSource src;
    private final Handler worker;
    private volatile boolean running;

    private Progress target;
    private Listener listener;
    private String pkg;
    private long startedAt;
    private int steps, stillSteps, methodStill, backSteps, jumps, method;
    private boolean jumpRefused;
    private float lastPanelPos, lastEventPos;
    private float perPage;          // part du chapitre parcourue par une « page » (mesurée), -1 inconnue
    private float posBeforePage;    // position avant la dernière page, -1 si le dernier pas n'était pas une page
    private int lastDir;
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
        steps = stillSteps = methodStill = backSteps = jumps = method = 0;
        jumpRefused = false;
        lastPanelPos = lastEventPos = -2;
        perPage = -1;
        posBeforePage = -1;
        lastDir = 0;
        how = "";
        pkg = null;
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

    private void note(String s) {
        how += (how.isEmpty() ? "" : " → ") + s;
    }

    private void step() {
        if (!running) return;
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            String p = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
            if (pkg == null) pkg = p;
            if (p == null || !p.equals(pkg)) {
                note("appli changée");
                finish(false);
                return;
            }
            if (SystemClock.uptimeMillis() - startedAt > MAX_MS || steps > 300) {
                note("temps écoulé");
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
                methodStill = moved ? 0 : methodStill + 1;
                if (methodStill >= 2) {   // cette zone / cette action ne fait rien ici : on essaie la suivante
                    method++;
                    methodStill = 0;
                }
            }
            // Ce que vaut une page, mesuré sur le pas précédent.
            if (posBeforePage >= 0 && pos >= 0 && moved) {
                float d = Math.abs(pos - posBeforePage);
                if (d > 0.001f && d < 0.5f) perPage = perPage < 0 ? d : (perPage + d) / 2f;
            }
            posBeforePage = -1;

            if (pos < 0) {
                if (steps >= 5) {
                    note("position inconnue (ni cases ni défilement)");
                    finish(false);
                    return;
                }
                page(root, true);   // on bouge un peu pour obtenir une première mesure
                steps++;
                again(300);
                return;
            }
            listener.onProgress(s != null ? s.current : -1, s != null ? s.count : 0, pos);

            float goal = target.pct;
            float remaining = goal - pos;
            float slack = perPage > 0 ? Math.max(TOLERANCE, perPage * 0.5f) : TOLERANCE;
            if (Math.abs(remaining) <= slack) {
                note((panelPos >= 0 ? (s.collection ? "liste" : "cases") : "défilement") + " ok");
                finish(true);
                return;
            }
            if (stillSteps >= 10) {   // ~3 s sans bouger du tout, avec toutes les méthodes : fin de page ou site bloquant
                note("page immobile vers " + Math.round(pos * 100) + " %");
                finish(Math.abs(remaining) < 0.03f);
                return;
            }

            // ---- 1. Saut direct à la bonne case
            if (s != null && s.count > 0 && !jumpRefused && jumps < MAX_JUMPS) {
                int want = target.count == s.count && target.index >= 0
                        ? target.index
                        : (int) Math.floor(goal * s.count);
                want = Math.max(0, Math.min(s.count - 1, want));
                if (want != s.current) {
                    jumps++;
                    boolean ok;
                    if (s.collection) {
                        Bundle args = new Bundle();
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, want);
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
                        ok = s.container.performAction(android.R.id.accessibilityActionScrollToPosition, args);
                    } else {
                        ok = s.panels.get(want).performAction(android.R.id.accessibilityActionShowOnScreen);
                    }
                    note((s.collection ? "saut liste " : "saut case ") + (want + 1) + "/" + s.count + (ok ? "" : " refusé"));
                    if (ok) {
                        steps++;
                        again(450);
                        return;
                    }
                    jumpRefused = true;
                }
            }

            // ---- 2. Page par page pour se placer dans la case
            steps++;
            boolean forward = remaining > 0;
            if (!forward && backSteps++ >= 4) {
                note("arrêté juste après");
                finish(true);
                return;
            }
            if (lastDir != 0 && lastDir != (forward ? 1 : -1)) {
                // Demi-tour : si une page est trop grande pour l'écart, on reste là (au plus près).
                if (perPage > 0 && Math.abs(remaining) < perPage * 0.75f) {
                    note("au plus près");
                    finish(true);
                    return;
                }
            }
            if (!how.endsWith("pages")) note("pages");
            posBeforePage = pos;
            lastDir = forward ? 1 : -1;
            page(root, forward);
            again(240);
        } catch (Throwable t) {
            note("erreur " + t.getClass().getSimpleName());
            finish(false);
        }
    }

    /**
     * Une page vers l'avant / l'arrière sur la zone qui défile. Si une méthode ne fait rien,
     * {@link #method} passe à la suivante : (zone 1, « suivante ») → (zone 1, « bas ») → (zone 2, …).
     */
    private void page(AccessibilityNodeInfo root, boolean forward) {
        if (root == null) return;
        List<AccessibilityNodeInfo> zones = Panels.scrollables(root);
        if (zones.isEmpty()) return;
        int[] actions = forward ? FORWARD : BACKWARD;
        int total = zones.size() * actions.length;
        for (int k = 0; k < total; k++) {
            int m = (method + k) % total;
            AccessibilityNodeInfo z = zones.get(m / actions.length);
            if (z.performAction(actions[m % actions.length])) {
                method = m;
                return;
            }
        }
    }
}
