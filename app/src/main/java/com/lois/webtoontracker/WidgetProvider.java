package com.lois.webtoontracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // ------------------------------------------------------------------ Tailles

    /**
     * Variantes selon la place réelle sur l'écran d'accueil (chaque lanceur a des cases de taille
     * différente) : on retire d'abord le carrousel, puis l'en-tête, plutôt que de tout tasser.
     */
    private static final int XS = 0, M = 1, L = 2, XL = 3;

    /** Hauteur minimale (dp) de chaque variante, Confort puis Compact. */
    private static final float[] CONFORT_H = {0, 128, 158, 182};
    private static final float[] COMPACT_H = {0, 146, 146, 172};

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        renderConfort(ctx, mgr, new int[]{id});
    }

    /** Variante pour un widget (Android 11 et moins : on choisit nous-mêmes d'après sa taille). */
    static int tierFor(AppWidgetManager mgr, int id, float[] heights) {
        Bundle o = mgr.getAppWidgetOptions(id);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (h <= 0) return L;
        int t = XS;
        for (int i = 1; i < heights.length; i++) if (h >= heights[i]) t = i;
        return t;
    }

    /** Envoie les variantes : Android 12+ choisit seul la bonne selon la taille (même en redimensionnant). */
    private static void push(AppWidgetManager mgr, int[] ids, float[] heights, java.util.function.IntFunction<RemoteViews> build) {
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> m = new ArrayMap<>();
            m.put(new SizeF(0f, 0f), build.apply(XS));
            m.put(new SizeF(0f, heights[M]), build.apply(M));
            if (heights[L] > heights[M]) m.put(new SizeF(0f, heights[L]), build.apply(L));
            m.put(new SizeF(0f, heights[XL]), build.apply(XL));
            mgr.updateAppWidget(ids, new RemoteViews(m));
        } else {
            for (int id : ids) mgr.updateAppWidget(id, build.apply(tierFor(mgr, id, heights)));
        }
    }

    // ------------------------------------------------------------------ Confort

    static void renderConfort(Context ctx, AppWidgetManager mgr, int[] ids) {
        DbHelper db = DbHelper.get(ctx);
        List<DbHelper.Series> series = db.getSeries();
        int week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        float d = ctx.getResources().getDisplayMetrics().density;

        // Images préparées une seule fois, partagées par toutes les variantes.
        DbHelper.Series top = series.isEmpty() ? null : series.get(0);
        Bitmap cover = Covers.widgetCover(ctx, top == null ? "?" : top.title, (int) (66 * d), (int) (106 * d), 11);
        int others = Math.max(0, series.size() - 1);
        int pages = Math.max(1, (others + 1) / 2);
        int page = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("page", 0) % pages;
        int first = 1 + page * 2;
        Bitmap[] thumbs = new Bitmap[2];
        for (int k = 0; k < 2; k++) {
            int i = first + k;
            if (i < series.size()) thumbs[k] = Covers.widgetCover(ctx, series.get(i).title, (int) (30 * d), (int) (40 * d), 6);
        }
        String weekText = week + (week > 1 ? " chapitres" : " chapitre") + " cette semaine";

        push(mgr, ids, CONFORT_H, tier -> {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_glass);
            v.setViewVisibility(R.id.wHeader, tier == XS ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.wWeek, weekText);
            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.wHeader, activity(ctx, 0, open));
            v.setImageViewBitmap(R.id.wCover, cover);
            v.setInt(R.id.wTitle, "setMaxLines", tier == L && others > 0 ? 1 : 2); // 1 ligne si le carrousel manque de place

            if (top == null) {
                v.setTextViewText(R.id.wTitle, "Aucune lecture");
                v.setTextViewText(R.id.wSub, "Lis un chapitre pour le voir ici");
                v.setViewVisibility(R.id.wResume, View.GONE);
                v.setViewVisibility(R.id.wRows, View.GONE);
                v.setOnClickPendingIntent(R.id.wMain, activity(ctx, 1, open));
                return v;
            }
            v.setTextViewText(R.id.wTitle, top.title);
            v.setTextViewText(R.id.wSub, "Chapitre " + Detector.formatChapter(top.lastChapter) + "  ·  " + day(top.lastReadAt));
            v.setViewVisibility(R.id.wResume, View.VISIBLE);
            PendingIntent resume = activity(ctx, 10, resumeIntent(ctx, top.title));
            v.setOnClickPendingIntent(R.id.wResume, resume);
            v.setOnClickPendingIntent(R.id.wMain, resume);

            boolean carousel = others > 0 && tier >= L;
            v.setViewVisibility(R.id.wRows, carousel ? View.VISIBLE : View.GONE);
            if (carousel) {
                row(ctx, v, series, first, thumbs[0], R.id.wRow1, R.id.wRow1Cover, R.id.wRow1Title, R.id.wRow1Ch);
                row(ctx, v, series, first + 1, thumbs[1], R.id.wRow2, R.id.wRow2Cover, R.id.wRow2Title, R.id.wRow2Ch);
                boolean paging = pages > 1;
                v.setViewVisibility(R.id.wPrev, paging ? View.VISIBLE : View.INVISIBLE);
                v.setViewVisibility(R.id.wNext, paging ? View.VISIBLE : View.INVISIBLE);
                v.setOnClickPendingIntent(R.id.wPrev, pageIntent(ctx, pages - 1)); // -1 modulo pages
                v.setOnClickPendingIntent(R.id.wNext, pageIntent(ctx, 1));
            }
            return v;
        });
    }

    private static void row(Context ctx, RemoteViews v, List<DbHelper.Series> series, int i, Bitmap thumb,
                            int rowId, int coverId, int titleId, int chId) {
        if (i >= series.size()) {
            v.setViewVisibility(rowId, View.INVISIBLE);
            return;
        }
        DbHelper.Series s = series.get(i);
        v.setViewVisibility(rowId, View.VISIBLE);
        v.setImageViewBitmap(coverId, thumb);
        v.setTextViewText(titleId, s.title);
        v.setTextViewText(chId, "Ch. " + Detector.formatChapter(s.lastChapter) + "  ·  " + when(s.lastReadAt, false));
        v.setOnClickPendingIntent(rowId, activity(ctx, 20 + i, resumeIntent(ctx, s.title)));
    }

    // ------------------------------------------------------------------ Compact

    static void renderCompact(Context ctx, AppWidgetManager mgr, int[] ids) {
        DbHelper db = DbHelper.get(ctx);
        List<DbHelper.Series> series = db.getSeries();
        int week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        float d = ctx.getResources().getDisplayMetrics().density;
        DbHelper.Series top = series.isEmpty() ? null : series.get(0);
        Bitmap cover = Covers.widgetCover(ctx, top == null ? "?" : top.title, (int) (66 * d), (int) (106 * d), 11);

        push(mgr, ids, COMPACT_H, tier -> {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_compact);
            v.setViewVisibility(R.id.cHeader, tier == XS ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.cWeek, week + " cette semaine");
            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.cHeader, activity(ctx, 100, open));
            v.setImageViewBitmap(R.id.cCover, cover);
            v.setInt(R.id.cTitle, "setMaxLines", tier >= XL ? 2 : 1);
            if (top == null) {
                v.setTextViewText(R.id.cTitle, "Aucune lecture");
                v.setTextViewText(R.id.cSub, "Lis un chapitre pour le voir ici");
                v.setViewVisibility(R.id.cResume, View.GONE);
                v.setOnClickPendingIntent(R.id.cMain, activity(ctx, 101, open));
                return v;
            }
            v.setTextViewText(R.id.cTitle, top.title);
            v.setTextViewText(R.id.cSub, "Dernier chapitre lu : " + Detector.formatChapter(top.lastChapter));
            v.setViewVisibility(R.id.cResume, View.VISIBLE);
            PendingIntent resume = activity(ctx, 110, resumeIntent(ctx, top.title));
            v.setOnClickPendingIntent(R.id.cResume, resume);
            v.setOnClickPendingIntent(R.id.cMain, resume);
            return v;
        });
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

    /** "Aujourd'hui" / "Hier" / "12 nov." (sous le titre du widget Confort, où la place est comptée) */
    static String day(long t) {
        long today = DbHelper.dayStart(System.currentTimeMillis());
        if (t >= today) return "Aujourd'hui";
        if (t >= today - 24L * 3600 * 1000) return "Hier";
        return new SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(t));
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
