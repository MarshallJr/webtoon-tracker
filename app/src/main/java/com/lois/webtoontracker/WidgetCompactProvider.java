package com.lois.webtoontracker;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget COMPACT (4×2) : dernière lecture + gros bouton Reprendre. */
public class WidgetCompactProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        WidgetProvider.renderCompact(ctx, mgr, ids);
    }
}
