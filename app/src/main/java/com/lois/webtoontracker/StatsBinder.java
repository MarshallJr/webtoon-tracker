package com.lois.webtoontracker;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Remplit la page "Activité" (statistiques). */
public final class StatsBinder {

    private StatsBinder() {}

    private static final long DAY = 24L * 3600 * 1000;
    /** Tags qui ne sont pas des genres. */
    private static final Set<String> NOT_GENRES = new HashSet<>(java.util.Arrays.asList(
            "en cours", "terminé", "favori ⭐", "favori"));

    public static void bind(Activity a, View root) {
        DbHelper db = DbHelper.get(a);
        List<DbHelper.Series> series = db.getSeries();
        long now = System.currentTimeMillis();
        StatsCalc s = StatsCalc.compute(db.allReadTimes(), now);

        text(root, R.id.sTime, "≈ " + StatsCalc.formatDuration(s.minutes));
        text(root, R.id.sTotal, s.total + " chapitres · " + series.size() + " séries");

        int week = db.countSince(now - 7 * DAY);
        int prev = db.countSince(now - 14 * DAY) - week;
        text(root, R.id.aWeek, String.valueOf(week));
        String trend;
        if (prev <= 0) trend = week > 0 ? "✨ Bonne semaine" : "Prêt pour une nouvelle semaine";
        else {
            int diff = Math.round(100f * (week - prev) / prev);
            trend = diff > 0 ? "↑ +" + diff + " % vs sem. dernière"
                    : diff < 0 ? "↓ " + diff + " % vs sem. dernière" : "= comme la semaine dernière";
        }
        text(root, R.id.aTrend, trend);

        text(root, R.id.sStreak, s.currentStreak + (s.currentStreak > 1 ? " jours" : " jour"));
        text(root, R.id.sStreakSub, "Meilleure : " + s.bestStreak + " j");
        text(root, R.id.sRecord, s.recordCount + " ch.");
        text(root, R.id.sRecordSub, s.recordCount > 0
                ? new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(s.recordDay)) : "");
        text(root, R.id.sDay, s.favoriteWeekday >= 0 ? cap(StatsCalc.WEEKDAYS[s.favoriteWeekday]) : "—");
        text(root, R.id.sDaySub, s.favoriteWeekday >= 0 ? (int) s.weekdays[s.favoriteWeekday] + " chapitres lus" : "");
        text(root, R.id.sSlot, s.favoriteSlot >= 0 ? cap(StatsCalc.SLOTS[s.favoriteSlot]) : "—");
        text(root, R.id.sSlotSub, "");

        String[] l14 = new String[14];
        SimpleDateFormat dayFmt = new SimpleDateFormat("d", Locale.FRANCE);
        for (int i = 0; i < 14; i++) l14[i] = i % 2 == 1 ? dayFmt.format(new Date(s.last14Days[i])) : "";
        ((BarChartView) root.findViewById(R.id.chart14)).setData(s.last14, l14, 13);
        int todayWd = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
        ((BarChartView) root.findViewById(R.id.chartWeek)).setData(s.weekdays, StatsCalc.WEEKDAYS_SHORT, todayWd);

        bindGenres(a, root, series);
        bindTop(a, root, series);
        bindRecent(a, root);
    }

    /** Genres préférés : tags des séries, pondérés par le nombre de chapitres lus. */
    private static void bindGenres(Activity a, View root, List<DbHelper.Series> series) {
        Map<String, Integer> weight = new HashMap<>();
        for (DbHelper.Series s : series) {
            for (String t : Titles.splitTags(s.tags)) {
                if (NOT_GENRES.contains(t.toLowerCase(Locale.ROOT))) continue;
                weight.put(t, weight.getOrDefault(t, 0) + s.count);
            }
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(weight.entrySet());
        Collections.sort(list, (x, y) -> Integer.compare(y.getValue(), x.getValue()));
        LinearLayout box = root.findViewById(R.id.aGenres);
        box.removeAllViews();
        root.findViewById(R.id.aGenresCard).setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        int max = list.isEmpty() ? 1 : Math.max(1, list.get(0).getValue());
        int total = 0;
        for (Map.Entry<String, Integer> e : list) total += e.getValue();
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            View row = LayoutInflater.from(a).inflate(R.layout.item_stat_row, box, false);
            row.findViewById(R.id.srAvatar).setVisibility(View.GONE);
            ((View) row.findViewById(R.id.srAvatar).getParent()).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.srTitle)).setText(e.getKey());
            ((TextView) row.findViewById(R.id.srRight)).setText(Math.round(100f * e.getValue() / total) + " %");
            row.findViewById(R.id.srSub).setVisibility(View.GONE);
            bar(a, row, (float) e.getValue() / max, SeriesAdapter.colorFor(e.getKey()));
            box.addView(row);
        }
    }

    private static void bindTop(Activity a, View root, List<DbHelper.Series> series) {
        LinearLayout box = root.findViewById(R.id.topList);
        box.removeAllViews();
        List<DbHelper.Series> sorted = new ArrayList<>(series);
        Collections.sort(sorted, (x, y) -> Integer.compare(y.count, x.count));
        int max = sorted.isEmpty() ? 1 : Math.max(1, sorted.get(0).count);
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            DbHelper.Series s = sorted.get(i);
            View row = LayoutInflater.from(a).inflate(R.layout.item_stat_row, box, false);
            SeriesAdapter.bindCover(row.findViewById(R.id.srAvatar), row.findViewById(R.id.srCover), s.title);
            ((TextView) row.findViewById(R.id.srTitle)).setText((i + 1) + ". " + s.title);
            ((TextView) row.findViewById(R.id.srRight)).setText(s.count + " ch.");
            ((TextView) row.findViewById(R.id.srSub)).setText(s.score > 0 ? "★ " + SeriesAdapter.formatScore(s.score)
                    + " · ch. " + Detector.formatChapter(s.lastChapter) : "Ch. " + Detector.formatChapter(s.lastChapter));
            bar(a, row, (float) s.count / max, SeriesAdapter.colorFor(s.title));
            row.setOnClickListener(v -> DetailActivity.open(a, s.title));
            box.addView(row);
        }
        if (sorted.isEmpty()) empty(a, box, "Pas encore de lectures.");
    }

    private static void bindRecent(Activity a, View root) {
        LinearLayout box = root.findViewById(R.id.aRecent);
        box.removeAllViews();
        List<Object[]> recent = DbHelper.get(a).recentReads(8);
        for (Object[] r : recent) {
            String title = (String) r[0];
            double ch = (Double) r[1];
            long at = (Long) r[2];
            View row = LayoutInflater.from(a).inflate(R.layout.item_stat_row, box, false);
            SeriesAdapter.bindCover(row.findViewById(R.id.srAvatar), row.findViewById(R.id.srCover), title);
            ((TextView) row.findViewById(R.id.srTitle)).setText(title);
            ((TextView) row.findViewById(R.id.srSub)).setText("Chapitre " + Detector.formatChapter(ch));
            ((TextView) row.findViewById(R.id.srRight)).setText(
                    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            row.setOnClickListener(v -> DetailActivity.open(a, title));
            box.addView(row);
        }
        if (recent.isEmpty()) empty(a, box, "Rien pour l'instant.");
    }

    private static void bar(Activity a, View row, float ratio, int color) {
        FrameLayout track = row.findViewById(R.id.srTrack);
        View fill = row.findViewById(R.id.srFill);
        track.setVisibility(View.VISIBLE);
        float dp = a.getResources().getDisplayMetrics().density;
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(99 * dp);
        tb.setColor(ContextCompat.getColor(a, R.color.chart_grid));
        track.setBackground(tb);
        GradientDrawable fb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ContextCompat.getColor(a, R.color.brand), color});
        fb.setCornerRadius(99 * dp);
        fill.setBackground(fb);
        track.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            lp.width = (int) (track.getWidth() * Math.max(0.04f, ratio));
            fill.setLayoutParams(lp);
            fill.setPivotX(0f);
            fill.setScaleX(0f);
            fill.animate().scaleX(1f).setDuration(650).start();
        });
    }

    private static void empty(Activity a, LinearLayout box, String msg) {
        TextView t = new TextView(a);
        t.setText(msg);
        t.setTextColor(ContextCompat.getColor(a, R.color.text_soft));
        t.setPadding(0, 8, 0, 8);
        box.addView(t);
    }

    private static void text(View root, int id, String s) {
        ((TextView) root.findViewById(id)).setText(s);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
