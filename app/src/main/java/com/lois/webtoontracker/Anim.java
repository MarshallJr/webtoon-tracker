package com.lois.webtoontracker;

import android.animation.AnimatorInflater;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntFunction;

/**
 * Petites animations de l'appli : courtes (≤ 0,4 s), jamais en boucle (pas de batterie gâchée),
 * et coupées automatiquement si « Supprimer les animations » est activé dans Android.
 */
final class Anim {

    /** Courbe « emphasized » de Material : démarre vite, se pose en douceur. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator DECEL = new DecelerateInterpolator(2f);

    private Anim() {
    }

    static boolean on() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private static float dp(View v, float d) {
        return d * v.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ Toucher

    /** La vue s'enfonce un peu quand on appuie, puis rebondit en la relâchant. */
    static void press(View v) {
        if (v == null) return;
        boolean small = v instanceof ImageButton || v instanceof ImageView
                || v instanceof com.google.android.material.chip.Chip;
        v.setStateListAnimator(AnimatorInflater.loadStateListAnimator(v.getContext(),
                small ? R.animator.press_small : R.animator.press));
    }

    /** {@link #press} sur toutes les cartes et boutons cliquables d'un écran. */
    static void pressAll(View v) {
        if (v == null) return;
        boolean container = v instanceof EditText || v instanceof RecyclerView || v instanceof AbsListView
                || v instanceof ScrollView || v instanceof NestedScrollView || v instanceof HorizontalScrollView
                || v instanceof com.google.android.material.tabs.TabLayout;
        if (!container && v.isClickable() && v.getTag(R.id.anim_press) == null) {
            v.setTag(R.id.anim_press, Boolean.TRUE);
            press(v);
        }
        if (v instanceof ViewGroup && !(v instanceof com.google.android.material.tabs.TabLayout)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) pressAll(g.getChildAt(i));
        }
    }

    // ------------------------------------------------------------------ Apparitions

    /** Fondu + légère montée, décalé selon la position (effet cascade). */
    static void enter(View v, int index) {
        if (v == null || !on()) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 16));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(index, 10) * 45L)
                .setDuration(340).setInterpolator(EASE).start();
    }

    /** {@link #enter} en cascade sur les enfants visibles d'un groupe. */
    static void stagger(View group) {
        if (!(group instanceof ViewGroup) || !on()) return;
        ViewGroup g = (ViewGroup) group;
        int k = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) enter(c, k++);
        }
    }

    /** Apparition « zoom » (miniature, logo). */
    static void zoomIn(View v, long delay) {
        if (v == null || !on()) return;
        v.setAlpha(0f);
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay)
                .setDuration(420).setInterpolator(new OvershootInterpolator(1.4f)).start();
    }

    /** Glissement latéral d'un contenu qui remplace un autre (onglets, pages). */
    static void slideIn(View v, int direction) {
        if (v == null) return;
        if (!on()) {
            v.setAlpha(1f);
            v.setTranslationX(0f);
            return;
        }
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(direction * dp(v, 28));
        v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(280).setInterpolator(EASE).start();
    }

    // ------------------------------------------------------------------ Accents

    /** Petit « pop » : la vue rétrécit puis rebondit (sélection, activation). */
    static void pop(View v) {
        if (v == null || !on()) return;
        v.animate().cancel();
        v.setScaleX(0.78f);
        v.setScaleY(0.78f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** La cloche sonne. */
    static void ring(View v) {
        if (v == null || !on()) return;
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() * 0.1f);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -24f, 20f, -14f, 9f, -4f, 0f);
        a.setDuration(650);
        a.start();
        pop(v);
    }

    /** Fond qui apparaît en fondu (pastille de la barre de navigation). */
    static void fadeInBackground(View v) {
        Drawable d = v.getBackground();
        if (d == null || !on()) return;
        d = d.mutate();
        ObjectAnimator a = ObjectAnimator.ofInt(d, "alpha", 0, 255);
        a.setDuration(240);
        a.start();
    }

    /** Pastille « Nouveau » : deux petites pulsations, une seule fois. */
    static void attention(View v) {
        if (v == null || !on() || v.getTag(R.id.anim_seen) != null) return;
        v.setTag(R.id.anim_seen, Boolean.TRUE);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f, 1.12f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f, 1.12f, 1f));
        a.setStartDelay(500);
        a.setDuration(800);
        a.start();
    }

    // ------------------------------------------------------------------ Chiffres

    /**
     * Le chiffre défile jusqu'à sa valeur. Ne rejoue que si la valeur a changé depuis la dernière fois.
     */
    static void count(TextView tv, int value, IntFunction<String> format) {
        if (tv == null) return;
        Object last = tv.getTag(R.id.anim_count);
        int from = last instanceof Integer ? (Integer) last : 0;
        tv.setTag(R.id.anim_count, value);
        Object running = tv.getTag(R.id.anim_counter);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        if (!on() || from == value) {
            tv.setText(format.apply(value));
            return;
        }
        ValueAnimator va = ValueAnimator.ofInt(from, value);
        va.setDuration(Math.min(900, 380 + Math.abs(value - from) * 8L));
        va.setInterpolator(DECEL);
        va.addUpdateListener(x -> tv.setText(format.apply((Integer) x.getAnimatedValue())));
        tv.setTag(R.id.anim_counter, va);
        va.start();
    }

    static void count(TextView tv, int value) {
        count(tv, value, String::valueOf);
    }
}
