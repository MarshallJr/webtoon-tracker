package com.lois.webtoontracker;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.testing.WorkManagerTestInitHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/** Outils des tests de captures : préparation, attente, enregistrement en PNG (build/shots). */
final class Shots {

    private Shots() {
    }

    static final File OUT = new File("build/shots");

    /** Appli propre + données d'essai, animations coupées (captures stables). */
    static Context prepare() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        OUT.mkdirs();
        try {
            android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 0f);
        } catch (Throwable ignored) {
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx);
        java.lang.reflect.Field f = DbHelper.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        Fixtures.seed(ctx);
        return ctx;
    }

    /** Laisse tourner les tâches de fond et le fil principal. */
    static void idle(long ms) {
        long end = System.currentTimeMillis() + ms;
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (System.currentTimeMillis() < end);
    }

    /** La fenêtre telle qu'à l'écran. */
    static void screen(Activity a, String name) throws Exception {
        View d = a.getWindow().getDecorView();
        save(d, name, d.getWidth(), d.getHeight(), 0);
    }

    /** Toute une page défilante, dépliée de haut en bas. */
    static void page(View scroll, String name, int bg) throws Exception {
        if (!(scroll instanceof ViewGroup) || ((ViewGroup) scroll).getChildCount() == 0) {
            save(scroll, name, scroll.getWidth(), scroll.getHeight(), bg);
            return;
        }
        View child = ((ViewGroup) scroll).getChildAt(0);
        int w = scroll.getWidth();
        int h = Math.max(1, child.getHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom());
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.min(h, 12000), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(bg);
        c.translate(child.getLeft(), scroll.getPaddingTop());
        child.draw(c);
        write(b, name);
    }

    /** Une boîte de dialogue ou un panneau. */
    static void dialog(Dialog d, Activity a, String name) throws Exception {
        View v = d.getWindow().getDecorView();
        int w = a.getWindow().getDecorView().getWidth(), h = a.getWindow().getDecorView().getHeight();
        if (v.getWidth() == 0 || v.getHeight() == 0) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        }
        save(v, name, v.getWidth(), v.getHeight(), 0xFF000000);
    }

    static void save(View v, String name, int w, int h, int bg) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (bg != 0) c.drawColor(bg);
        v.draw(c);
        write(b, name);
    }

    private static void write(Bitmap b, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
