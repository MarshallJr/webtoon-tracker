package com.lois.webtoontracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Widget CONFORT (5×2) : dernière lecture en grand + Reprendre + carrousel ‹ › des autres séries.
 * Économie de batterie : aucune mise à jour périodique ; redessiné seulement quand une lecture
 * change, quand l'appli s'ouvre, ou quand tu touches ‹ ›.
 */
public class WidgetProvider extends AppWidgetProvider {

    static final String ACTION_PAGE = "com.lois.webtoontracker.WIDGET_PAGE";
    static final String EXTRA_DELTA = "delta";
    private static final String PREFS = "widget";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        renderConfort(ctx, mgr, ids);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_PAGE.equals(intent.getAction())) {
            int delta = intent.getIntExtra(EXTRA_DELTA, 1);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("page", Math.max(0, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("page", 0) + delta))
                    .apply();
            updateAll(ctx);
        }
    }

    /** Redessine les deux widgets (Confort et Compact). */
    public static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] a = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (a != null && a.length > 0) renderConfort(ctx, mgr, a);
            int[] b = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetCompactProvider.class));
            if (b != null && b.length > 0) renderCompact(ctx, mgr, b);
        } catch (Throwable t) {
            App.saveError(ctx, "Widget", t);
        }
    }

    // ------------------------------------------------------------------ Confort

    static void renderConfort(Context ctx, AppWidgetManager mgr, int[] ids) {
        DbHelper db = DbHelper.get(ctx);
        List<DbHelper.Series> series = db.getSeries();
        int week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        float d = ctx.getResources().getDisplayMetrics().density;

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_glass);
        v.setTextViewText(R.id.wWeek, week > 0 ? "∿ " + week + (week > 1 ? " chapitres" : " chapitre") + " cette semaine" : "");
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.wHeader, activity(ctx, 0, open));

        if (series.isEmpty()) {
            v.setTextViewText(R.id.wTitle, "Aucune lecture");
            v.setTextViewText(R.id.wSub, "Lis un chapitre pour le voir ici");
            v.setImageViewBitmap(R.id.wCover, Covers.widgetCover(ctx, "?", (int) (68 * d), (int) (100 * d), 14));
            v.setViewVisibility(R.id.wResume, View.GONE);
            v.setViewVisibility(R.id.wRows, View.GONE);
            v.setOnClickPendingIntent(R.id.wMain, activity(ctx, 1, open));
        } else {
            DbHelper.Series s = series.get(0);
            v.setTextViewText(R.id.wTitle, s.title);
            v.setTextViewText(R.id.wSub, "Chapitre " + Detector.formatChapter(s.lastChapter) + " · " + when(s.lastReadAt, true));
            v.setImageViewBitmap(R.id.wCover, Covers.widgetCover(ctx, s.title, (int) (68 * d), (int) (100 * d), 14));
            v.setViewVisibility(R.id.wResume, View.VISIBLE);
            PendingIntent resume = activity(ctx, 10, resumeIntent(ctx, s.title));
            v.setOnClickPendingIntent(R.id.wResume, resume);
            v.setOnClickPendingIntent(R.id.wMain, resume);

            // Carrousel : les autres séries, 2 par 2.
            int others = series.size() - 1;
            v.setViewVisibility(R.id.wRows, others > 0 ? View.VISIBLE : View.GONE);
            if (others > 0) {
                int pages = (others + 1) / 2;
                int page = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("page", 0) % pages;
                int first = 1 + page * 2;
                row(ctx, v, series, first, R.id.wRow1, R.id.wRow1Cover, R.id.wRow1Title, R.id.wRow1Ch, d);
                row(ctx, v, series, first + 1, R.id.wRow2, R.id.wRow2Cover, R.id.wRow2Title, R.id.wRow2Ch, d);
                boolean paging = pages > 1;
                v.setViewVisibility(R.id.wPrev, paging ? View.VISIBLE : View.INVISIBLE);
                v.setViewVisibility(R.id.wNext, paging ? View.VISIBLE : View.INVISIBLE);
                v.setOnClickPendingIntent(R.id.wPrev, pageIntent(ctx, pages - 1)); // -1 modulo pages
                v.setOnClickPendingIntent(R.id.wNext, pageIntent(ctx, 1));
            }
        }
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    private static void row(Context ctx, RemoteViews v, List<DbHelper.Series> series, int i,
                            int rowId, int coverId, int titleId, int chId, float d) {
        if (i >= series.size()) {
            v.setViewVisibility(rowId, View.INVISIBLE);
            return;
        }
        DbHelper.Series s = series.get(i);
        v.setViewVisibility(rowId, View.VISIBLE);
        v.setImageViewBitmap(coverId, Covers.widgetCover(ctx, s.title, (int) (30 * d), (int) (44 * d), 7));
        v.setTextViewText(titleId, s.title);
        v.setTextViewText(chId, "Ch. " + Detector.formatChapter(s.lastChapter) + " · " + when(s.lastReadAt, false));
        v.setOnClickPendingIntent(rowId, activity(ctx, 20 + i, resumeIntent(ctx, s.title)));
    }

    // ------------------------------------------------------------------ Compact

    static void renderCompact(Context ctx, AppWidgetManager mgr, int[] ids) {
        DbHelper db = DbHelper.get(ctx);
        List<DbHelper.Series> series = db.getSeries();
        int week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        float d = ctx.getResources().getDisplayMetrics().density;

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_compact);
        v.setTextViewText(R.id.cWeek, "∿ " + week + " cette semaine");
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.cHeader, activity(ctx, 100, open));
        if (series.isEmpty()) {
            v.setTextViewText(R.id.cTitle, "Aucune lecture");
            v.setTextViewText(R.id.cSub, "Lis un chapitre pour le voir ici");
            v.setImageViewBitmap(R.id.cCover, Covers.widgetCover(ctx, "?", (int) (66 * d), (int) (96 * d), 14));
            v.setViewVisibility(R.id.cResume, View.GONE);
            v.setOnClickPendingIntent(R.id.cMain, activity(ctx, 101, open));
        } else {
            DbHelper.Series s = series.get(0);
            v.setTextViewText(R.id.cTitle, s.title);
            v.setTextViewText(R.id.cSub, "Dernier chapitre lu : " + Detector.formatChapter(s.lastChapter));
            v.setImageViewBitmap(R.id.cCover, Covers.widgetCover(ctx, s.title, (int) (66 * d), (int) (96 * d), 14));
            v.setViewVisibility(R.id.cResume, View.VISIBLE);
            PendingIntent resume = activity(ctx, 110, resumeIntent(ctx, s.title));
            v.setOnClickPendingIntent(R.id.cResume, resume);
            v.setOnClickPendingIntent(R.id.cMain, resume);
        }
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    // ------------------------------------------------------------------ Utilitaires

    private static Intent resumeIntent(Context ctx, String title) {
        return new Intent(ctx, ResumeActivity.class)
                .putExtra(ResumeActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static PendingIntent activity(Context ctx, int code, Intent i) {
        return PendingIntent.getActivity(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent pageIntent(Context ctx, int delta) {
        Intent i = new Intent(ctx, WidgetProvider.class).setAction(ACTION_PAGE).putExtra(EXTRA_DELTA, delta);
        return PendingIntent.getBroadcast(ctx, delta == 1 ? 501 : 502, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** "Aujourd'hui, 02:07" / "Hier" / "12 nov." (court : "02:07", "hier", "12 nov.") */
    static String when(long t, boolean full) {
        long today = DbHelper.dayStart(System.currentTimeMillis());
        String hm = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(t));
        if (t >= today) return full ? "Aujourd'hui, " + hm : hm;
        if (t >= today - 24L * 3600 * 1000) return full ? "Hier, " + hm : "hier";
        return new SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(t));
    }
}
