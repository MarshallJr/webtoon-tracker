package com.lois.webtoontracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Widget "verre" de l'écran d'accueil.
 * Économie de batterie : aucune mise à jour périodique (updatePeriodMillis = 0) ;
 * il n'est redessiné que lorsqu'un chapitre est enregistré ou que l'appli est ouverte.
 */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx, mgr, ids);
    }

    public static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (ids != null && ids.length > 0) render(ctx, mgr, ids);
        } catch (Throwable t) {
            App.saveError(ctx, "Widget", t);
        }
    }

    private static void render(Context ctx, AppWidgetManager mgr, int[] ids) {
        DbHelper db = DbHelper.get(ctx);
        List<DbHelper.Series> series = db.getSeries();
        int week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_glass);
        v.setTextViewText(R.id.wWeek, week > 0 ? "🔥 " + week + " cette semaine" : "");

        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.wHeader, pending(ctx, 0, open));

        if (series.isEmpty()) {
            v.setTextViewText(R.id.wTitle, "Aucune lecture");
            v.setTextViewText(R.id.wSub, "Lis un chapitre pour le voir ici");
            v.setTextViewText(R.id.wAvatar, "?");
            v.setInt(R.id.wAvatarBg, "setColorFilter", 0xFF6D4AFF);
            v.setViewVisibility(R.id.wResume, View.GONE);
            v.setViewVisibility(R.id.wRows, View.GONE);
            v.setOnClickPendingIntent(R.id.wMain, pending(ctx, 1, open));
        } else {
            DbHelper.Series s = series.get(0);
            v.setTextViewText(R.id.wTitle, s.title);
            v.setTextViewText(R.id.wSub, "Chapitre " + Detector.formatChapter(s.lastChapter) + " · " + when(s.lastReadAt));
            v.setTextViewText(R.id.wAvatar, SeriesAdapter.initialFor(s.title));
            v.setInt(R.id.wAvatarBg, "setColorFilter", SeriesAdapter.colorFor(s.title));
            android.graphics.Bitmap cover = Covers.get(ctx, s.title);
            if (cover != null) {
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(cover, 168, 168, true);
                v.setImageViewBitmap(R.id.wCover, Covers.rounded(small, 0.28f));
                v.setViewVisibility(R.id.wCover, View.VISIBLE);
                v.setViewVisibility(R.id.wAvatar, View.GONE);
                v.setViewVisibility(R.id.wAvatarBg, View.GONE);
            } else {
                v.setViewVisibility(R.id.wCover, View.GONE);
                v.setViewVisibility(R.id.wAvatar, View.VISIBLE);
                v.setViewVisibility(R.id.wAvatarBg, View.VISIBLE);
            }
            v.setViewVisibility(R.id.wResume, View.VISIBLE);
            PendingIntent resume = pending(ctx, 10, resumeIntent(ctx, s.title));
            v.setOnClickPendingIntent(R.id.wResume, resume);
            v.setOnClickPendingIntent(R.id.wMain, resume);

            v.setViewVisibility(R.id.wRows, series.size() > 1 ? View.VISIBLE : View.GONE);
            row(ctx, v, series, 1, R.id.wRow1, R.id.wRow1Title, R.id.wRow1Ch);
            row(ctx, v, series, 2, R.id.wRow2, R.id.wRow2Title, R.id.wRow2Ch);
        }
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    private static void row(Context ctx, RemoteViews v, List<DbHelper.Series> series, int i,
                            int rowId, int titleId, int chId) {
        if (i >= series.size()) {
            v.setViewVisibility(rowId, View.INVISIBLE);
            return;
        }
        DbHelper.Series s = series.get(i);
        v.setViewVisibility(rowId, View.VISIBLE);
        v.setTextViewText(titleId, s.title);
        v.setTextViewText(chId, "Ch. " + Detector.formatChapter(s.lastChapter) + " · " + when(s.lastReadAt));
        v.setOnClickPendingIntent(rowId, pending(ctx, 10 + i, resumeIntent(ctx, s.title)));
    }

    private static Intent resumeIntent(Context ctx, String title) {
        return new Intent(ctx, ResumeActivity.class)
                .putExtra(ResumeActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static PendingIntent pending(Context ctx, int code, Intent i) {
        return PendingIntent.getActivity(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** "aujourd'hui 21:40", "hier", "12 nov." */
    static String when(long t) {
        long today = DbHelper.dayStart(System.currentTimeMillis());
        if (t >= today) return "aujourd'hui " + new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(t));
        if (t >= today - 24L * 3600 * 1000) return "hier";
        return new SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(t));
    }
}
