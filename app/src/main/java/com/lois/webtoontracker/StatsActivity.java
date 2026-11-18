package com.lois.webtoontracker;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StatsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        View root = findViewById(R.id.statsRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        try {
            bind();
        } catch (Throwable t) {
            App.saveError(this, "Statistiques", t);
        }
    }

    private void bind() {
        DbHelper db = DbHelper.get(this);
        List<DbHelper.Series> series = db.getSeries();
        StatsCalc s = StatsCalc.compute(db.allReadTimes(), System.currentTimeMillis());

        text(R.id.sTime, "≈ " + StatsCalc.formatDuration(s.minutes));
        text(R.id.sTotal, s.total + " chapitres · " + series.size() + " séries");

        text(R.id.sStreak, s.currentStreak + (s.currentStreak > 1 ? " jours 🔥" : " jour 🔥"));
        text(R.id.sStreakSub, "Meilleure : " + s.bestStreak + " j");
        text(R.id.sRecord, s.recordCount + " chapitres");
        text(R.id.sRecordSub, s.recordCount > 0
                ? new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(s.recordDay)) : "—");
        text(R.id.sDay, s.favoriteWeekday >= 0 ? cap(StatsCalc.WEEKDAYS[s.favoriteWeekday]) : "—");
        text(R.id.sDaySub, s.favoriteWeekday >= 0 ? (int) s.weekdays[s.favoriteWeekday] + " chapitres lus" : "");
        text(R.id.sSlot, s.favoriteSlot >= 0 ? cap(StatsCalc.SLOTS[s.favoriteSlot]) : "—");
        text(R.id.sSlotSub, "");

        String[] l14 = new String[14];
        SimpleDateFormat dayFmt = new SimpleDateFormat("d", Locale.FRANCE);
        for (int i = 0; i < 14; i++) l14[i] = i % 2 == 1 ? dayFmt.format(new Date(s.last14Days[i])) : "";
        ((BarChartView) findViewById(R.id.chart14)).setData(s.last14, l14, 13);

        int todayWd = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
        ((BarChartView) findViewById(R.id.chartWeek)).setData(s.weekdays, StatsCalc.WEEKDAYS_SHORT, todayWd);

        buildTop(series);
    }

    private void buildTop(List<DbHelper.Series> series) {
        LinearLayout list = findViewById(R.id.topList);
        List<DbHelper.Series> sorted = new ArrayList<>(series);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.count, a.count));
        int max = sorted.isEmpty() ? 1 : Math.max(1, sorted.get(0).count);
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            DbHelper.Series s = sorted.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText((i + 1) + ".  " + s.title);
            name.setTextColor(ContextCompat.getColor(this, R.color.text_main));
            name.setTextSize(14);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = new TextView(this);
            count.setText(s.count + " ch.");
            count.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
            count.setTextSize(13);
            line.addView(count);
            row.addView(line);

            FrameLayout track = new FrameLayout(this);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(99 * dp);
            tb.setColor(ContextCompat.getColor(this, R.color.chart_grid));
            track.setBackground(tb);
            View fill = new View(this);
            GradientDrawable fb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ContextCompat.getColor(this, R.color.brand), SeriesAdapter.colorFor(s.title)});
            fb.setCornerRadius(99 * dp);
            fill.setBackground(fb);
            track.addView(fill, new FrameLayout.LayoutParams(0, (int) (8 * dp), Gravity.START));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, (int) (8 * dp));
            tlp.topMargin = (int) (6 * dp);
            row.addView(track, tlp);
            list.addView(row);

            final float ratio = (float) s.count / max;
            track.post(() -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                lp.width = (int) (track.getWidth() * ratio);
                fill.setLayoutParams(lp);
                fill.setScaleX(0f);
                fill.setPivotX(0f);
                fill.animate().scaleX(1f).setDuration(700).start();
            });
        }
        if (sorted.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Pas encore de lectures.");
            t.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
            list.addView(t);
        }
    }

    private void text(int id, String s) {
        ((TextView) findViewById(id)).setText(s);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
