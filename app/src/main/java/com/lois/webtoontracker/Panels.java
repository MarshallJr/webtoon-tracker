package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Repère les "cases" (images) du chapitre affiché : ce sont nos ancres.
 *
 *  - Lecteurs en liste (WEBTOON, Mihon, Tachiyomi…) : la liste annonce son nombre de lignes,
 *    et chaque ligne visible son numéro → case actuelle / total.
 *  - Pages web (Chrome, Firefox…) : on relève, dans l'ordre de la page, les images assez grandes
 *    pour être des cases de webtoon → case au centre de l'écran / total.
 */
public final class Panels {

    private Panels() {}

    public static final class Snapshot {
        public AccessibilityNodeInfo container;
        public final List<AccessibilityNodeInfo> panels = new ArrayList<>();
        public boolean collection;   // true : lecteur en liste (on saute par numéro de ligne)
        public int count;            // nombre de cases
        public int current = -1;     // case au centre de l'écran (0…count-1)
        public String pkg;

        public float pct() {
            return count <= 0 || current < 0 ? -1 : Math.min(1f, (current + 0.5f) / count);
        }

        @Override
        public String toString() {
            return (collection ? "liste" : "images") + " " + (current + 1) + "/" + count;
        }
    }

    private static final int MAX_NODES = 5000;

    public static Snapshot take(AccessibilityService svc) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        int sw = svc.getResources().getDisplayMetrics().widthPixels;
        int sh = svc.getResources().getDisplayMetrics().heightPixels;

        Snapshot s = new Snapshot();
        s.pkg = root.getPackageName() == null ? null : root.getPackageName().toString();
        s.container = biggestScrollable(root);

        // 1. Lecteur en liste : numéros de lignes.
        if (s.container != null) {
            AccessibilityNodeInfo.CollectionInfo ci = s.container.getCollectionInfo();
            if (ci != null && ci.getRowCount() >= 3) {
                s.collection = true;
                s.count = ci.getRowCount();
                int center = sh / 2, best = -1, firstVisible = -1;
                Rect r = new Rect();
                for (int i = 0; i < s.container.getChildCount(); i++) {
                    AccessibilityNodeInfo c = s.container.getChild(i);
                    if (c == null) continue;
                    AccessibilityNodeInfo.CollectionItemInfo it = c.getCollectionItemInfo();
                    if (it == null) continue;
                    c.getBoundsInScreen(r);
                    if (firstVisible < 0 || it.getRowIndex() < firstVisible) firstVisible = it.getRowIndex();
                    if (r.top <= center && r.bottom >= center) best = it.getRowIndex();
                }
                s.current = best >= 0 ? best : firstVisible;
                if (s.current >= 0) return s;
                s.collection = false; // pas exploitable, on tente la méthode images
            }
        }

        // 2. Page web : images de la taille d'une case, dans l'ordre de la page.
        int[] budget = {MAX_NODES};
        collectImages(s.container != null ? s.container : root, s.panels, sw, sh, budget, 0);
        s.count = s.panels.size();
        if (s.count < 3) return null;
        Rect r = new Rect();
        int center = sh / 2, firstVisible = -1, best = -1;
        for (int i = 0; i < s.count; i++) {
            AccessibilityNodeInfo n = s.panels.get(i);
            if (!n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (firstVisible < 0) firstVisible = i;
            if (r.top <= center && r.bottom >= center) best = i;
        }
        s.current = best >= 0 ? best : firstVisible;
        return s;
    }

    private static void collectImages(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out,
                                      int sw, int sh, int[] budget, int depth) {
        if (n == null || depth > 60 || budget[0]-- <= 0) return;
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().contains("Image")) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            // Une case de webtoon : large (≥ 55 % de l'écran) et pas minuscule en hauteur.
            // (les images hors écran ont parfois des bornes réduites : on accepte si invisible)
            boolean panelSized = r.width() >= sw * 0.55f && r.height() >= sh * 0.06f;
            if (panelSized || (!n.isVisibleToUser() && r.width() >= sw * 0.55f)) {
                out.add(n);
                return;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectImages(n.getChild(i), out, sw, sh, budget, depth + 1);
    }

    static AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo[] best = {null};
        long[] area = {0};
        int[] budget = {MAX_NODES};
        walk(root, best, area, budget, 0);
        return best[0];
    }

    private static void walk(AccessibilityNodeInfo n, AccessibilityNodeInfo[] best, long[] area, int[] budget, int depth) {
        if (n == null || depth > 40 || budget[0]-- <= 0) return;
        if (n.isScrollable()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long a = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            if (a > area[0]) {
                area[0] = a;
                best[0] = n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), best, area, budget, depth + 1);
    }
}
