package com.lois.webtoontracker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Décide QUAND afficher une bannière, pour ne pas polluer le téléphone.
 * Java pur (testable sans Android).
 *
 * Mode intelligent :
 *  - Nouvelle série            → toujours une bannière ("Nouvelle série ajoutée").
 *  - 1er chapitre d'une session → bannière "Ajouté à ta collection".
 *    (une session = lecture d'une même série sans pause de plus de 30 min)
 *  - Chapitres suivants         → silencieux… sauf un palier tous les 10 chapitres ("🔥 10 d'affilée").
 *  - Déjà lu                    → une seule fois par série et par session, et jamais pour un
 *                                 chapitre lu pendant la session en cours (tu le sais déjà).
 */
public final class NotifyPolicy {

    public static final int MODE_SMART = 0;
    public static final int MODE_ALL = 1;
    public static final int MODE_OFF = 2;

    public static final long SESSION_GAP_MS = 30L * 60 * 1000;
    public static final int STREAK_STEP = 10;

    public enum Kind { NEW_SERIES, ADDED, STREAK, ALREADY_READ, RESUME }

    public static final class Decision {
        public final Kind kind;
        public final int streak;

        Decision(Kind kind, int streak) {
            this.kind = kind;
            this.streak = streak;
        }
    }

    private static final class State {
        long lastNewAt = Long.MIN_VALUE / 2;
        int sessionCount = 0;
        long lastAlreadyShownAt = Long.MIN_VALUE / 2;
    }

    private final Map<String, State> states = new HashMap<>();

    private State state(String title) {
        String key = title.toLowerCase(Locale.ROOT).trim();
        State s = states.get(key);
        if (s == null) {
            s = new State();
            states.put(key, s);
        }
        return s;
    }

    /** Un nouveau chapitre vient d'être enregistré. @return la bannière à afficher, ou null. */
    public Decision onNewChapter(String title, boolean isNewSeries, long now, int mode) {
        State s = state(title);
        boolean sessionStart = now - s.lastNewAt > SESSION_GAP_MS;
        s.sessionCount = sessionStart ? 1 : s.sessionCount + 1;
        s.lastNewAt = now;

        if (mode == MODE_OFF) return null;
        if (isNewSeries) return new Decision(Kind.NEW_SERIES, 1);
        if (mode == MODE_ALL) return new Decision(Kind.ADDED, s.sessionCount);
        if (sessionStart) return new Decision(Kind.ADDED, 1);
        if (s.sessionCount % STREAK_STEP == 0) return new Decision(Kind.STREAK, s.sessionCount);
        return null;
    }

    /** Un chapitre déjà présent dans la collection vient d'être ouvert. */
    public Decision onAlreadyRead(String title, long readAt, long now, int mode, boolean enabled) {
        if (!enabled || mode == MODE_OFF) return null;
        if (now - readAt < SESSION_GAP_MS) return null;          // lu il y a peu : inutile de le rappeler
        State s = state(title);
        if (mode == MODE_SMART && now - s.lastAlreadyShownAt < SESSION_GAP_MS) return null;
        s.lastAlreadyShownAt = now;
        return new Decision(Kind.ALREADY_READ, 0);
    }
}
