package com.lois.webtoontracker;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** "Reprendre la lecture" : rouvre le dernier chapitre lu, au bon endroit. */
public final class Resume {

    private Resume() {}

    /**
     * 1. Dernière URL connue (navigateur, ou lien webtoons.com que l'appli WEBTOON sait ouvrir)
     * 2. Sinon l'appli dans laquelle tu lisais (WEBTOON, Mihon…)
     * 3. Sinon une recherche du titre sur webtoons.com
     */
    public static Intent intentFor(Context ctx, String title) {
        DbHelper.Meta m = DbHelper.get(ctx).getMeta(title);
        Intent i = null;
        if (m != null && m.lastUrl != null && !m.lastUrl.isEmpty()) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(m.lastUrl));
        } else if (m != null && m.lastPkg != null) {
            i = ctx.getPackageManager().getLaunchIntentForPackage(m.lastPkg);
        }
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.webtoons.com/fr/search?keyword=" + Uri.encode(title)));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public static void open(Context ctx, String title) {
        try {
            ctx.startActivity(intentFor(ctx, title));
        } catch (Exception e) {
            App.saveError(ctx, "Reprendre", e);
        }
    }
}
