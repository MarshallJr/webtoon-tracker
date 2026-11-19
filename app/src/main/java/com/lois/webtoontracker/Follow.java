package com.lois.webtoontracker;

import android.content.Context;

/**
 * La cloche 🔔 : seules les séries "suivies" sont surveillées pour les nouveaux chapitres.
 * La vérification périodique ne tourne que s'il y a au moins une série suivie (zéro coût sinon).
 */
public final class Follow {

    private Follow() {}

    public static void set(Context ctx, String title, boolean on) {
        DbHelper.get(ctx).setFollow(title, on);
        sync(ctx);
        if (on) {
            CoverWorker.enqueueIfNeeded(ctx);   // trouve aussi le lien WEBTOON si besoin
            ChapterCheckWorker.checkNow(ctx);   // première vérification tout de suite
        }
    }

    /** Active / coupe la vérification périodique selon qu'il reste des séries suivies. */
    public static void sync(Context ctx) {
        if (DbHelper.get(ctx).countFollowed() > 0) {
            ChapterCheckWorker.createChannel(ctx);
            ChapterCheckWorker.enable(ctx);
        } else {
            ChapterCheckWorker.disable(ctx);
        }
    }
}
