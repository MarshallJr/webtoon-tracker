package com.lois.webtoontracker;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Calculs des statistiques à partir des dates de lecture. Java pur (testable). */
public final class StatsCalc {

    /** Estimation : un chapitre de webtoon se lit en ~5 minutes. */
    public static final int MINUTES_PER_CHAPTER = 5;
    private static final long DAY = 24L * 3600 * 1000;

    public int total;
    public long minutes;
    public int recordCount;
    public long recordDay;
    public int favoriteWeekday = -1;          // 0 = lundi … 6 = dimanche
    public int favoriteSlot = -1;             // 0 nuit, 1 matin, 2 après-midi, 3 soirée
    public int currentStreak, bestStreak;     // jours consécutifs avec au moins 1 chapitre
    public final float[] last14 = new float[14];
    public final long[] last14Days = new long[14];
    public final float[] weekdays = new float[7];

    public static StatsCalc compute(List<Long> times, long now) {
        StatsCalc s = new StatsCalc();
        s.total = times.size();
        s.minutes = (long) s.total * MINUTES_PER_CHAPTER;

        Map<Long, Integer> perDay = new HashMap<>();
        int[] slots = new int[4];
        Calendar c = Calendar.getInstance();
        for (long t : times) {
            long d = dayStart(t);
            perDay.put(d, perDay.getOrDefault(d, 0) + 1);
            c.setTimeInMillis(t);
            int wd = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lundi = 0
            s.weekdays[wd]++;
            int h = c.get(Calendar.HOUR_OF_DAY);
            slots[h < 6 ? 0 : h < 12 ? 1 : h < 18 ? 2 : 3]++;
        }
        for (Map.Entry<Long, Integer> e : perDay.entrySet()) {
            if (e.getValue() > s.recordCount) {
                s.recordCount = e.getValue();
                s.recordDay = e.getKey();
            }
        }
        if (s.total > 0) {
            s.favoriteWeekday = argMax(s.weekdays);
            float[] f = new float[4];
            for (int i = 0; i < 4; i++) f[i] = slots[i];
            s.favoriteSlot = argMax(f);
        }

        long today = dayStart(now);
        for (int i = 0; i < 14; i++) {
            long d = dayStart(today - (13 - i) * DAY + DAY / 2);
            s.last14Days[i] = d;
            s.last14[i] = perDay.getOrDefault(d, 0);
        }

        // Séries de jours consécutifs (on tolère qu'aujourd'hui ne soit pas encore lu).
        long d = perDay.containsKey(today) ? today : dayStart(today - DAY / 2);
        while (perDay.containsKey(d)) {
            s.currentStreak++;
            d = dayStart(d - DAY / 2);
        }
        Long[] days = perDay.keySet().toArray(new Long[0]);
        java.util.Arrays.sort(days);
        int run = 0;
        long prev = Long.MIN_VALUE;
        for (long day : days) {
            run = (prev != Long.MIN_VALUE && dayStart(prev + DAY + DAY / 2) == day) ? run + 1 : 1;
            s.bestStreak = Math.max(s.bestStreak, run);
            prev = day;
        }
        return s;
    }

    public static String formatDuration(long minutes) {
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60, m = minutes % 60;
        if (h >= 48) return (h / 24) + " j " + (h % 24) + " h";
        return h + " h" + (m > 0 ? String.format(java.util.Locale.ROOT, " %02d", m) : "");
    }

    public static final String[] WEEKDAYS = {"lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"};
    public static final String[] WEEKDAYS_SHORT = {"L", "M", "M", "J", "V", "S", "D"};
    public static final String[] SLOTS = {"la nuit 🌙", "le matin ☀️", "l'après-midi 🌤️", "le soir 🌆"};

    private static int argMax(float[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
