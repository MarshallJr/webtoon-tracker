package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Carte de reprise affichée au-dessus du chapitre :
 * [miniature] REPRENDRE · Titre · "Ch. 345 · case 34 / 52" [barre dorée]  (↓)  ×
 * Le bouton rond devient ■ pendant le défilement, puis ✓ à l'arrivée.
 * Seule la carte capte le toucher : le reste de l'écran reste utilisable.
 */
public final class ResumeOverlay {

    public interface Actions {
        void onGo();

        void onStop();
    }

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View root;
    private Runnable autoHide;
    private ObjectAnimator pulse;
    private boolean running;

    public ResumeOverlay(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    /** Propose la reprise. Si progress est terminé, simple information (pas de bouton ↓). */
    public void offer(String title, Progress p, long delayMs, Actions actions) {
        main.postDelayed(() -> {
            try {
                show(title, p, actions);
            } catch (Throwable t) {
                App.saveError(svc, "Carte de reprise", t);
            }
        }, delayMs);
    }

    private void show(String title, Progress p, Actions actions) {
        dismissNow();
        running = false;
        root = LayoutInflater.from(svc).inflate(R.layout.overlay_resume, null);
        View card = root.findViewById(R.id.rCard);
        TextView letter = root.findViewById(R.id.rLetter);
        ImageView thumb = root.findViewById(R.id.rThumb);
        letter.setText(SeriesAdapter.initialFor(title));
        Bitmap cover = Covers.get(svc, title);
        if (cover != null) {
            thumb.setImageBitmap(cover);
            thumb.setClipToOutline(true);
            thumb.setVisibility(View.VISIBLE);
        }
        text(R.id.rTitle, title);
        boolean finished = p.finished();
        text(R.id.rKicker, finished ? "CHAPITRE TERMINÉ" : "REPRENDRE");
        text(R.id.rSub, finished ? "Ch. " + Detector.formatChapter(p.chapter) + " lu · passe au suivant 👉" : p.hint());
        ProgressBar bar = root.findViewById(R.id.rBar);
        animateBar(bar, finished ? 1f : p.pct);

        View go = root.findViewById(R.id.rGo);
        go.setVisibility(finished ? View.GONE : View.VISIBLE);
        go.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (running) actions.onStop();
            else actions.onGo();
        });
        root.findViewById(R.id.rClose).setOnClickListener(v -> {
            if (running) actions.onStop();
            hide();
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = dp(34);
        wm.addView(root, lp);

        // Entrée : glisse depuis le haut + léger zoom, puis le bouton "respire" pour inviter à toucher.
        card.setAlpha(0f);
        card.setTranslationY(-dp(70));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        if (!finished) {
            go.setScaleX(0f);
            go.setScaleY(0f);
            go.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(460)
                    .setInterpolator(new OvershootInterpolator(2.6f))
                    .withEndAction(() -> startPulse(go)).start();
        }
        card.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        scheduleHide(finished ? 5000 : 10000);
    }

    // ------------------------------------------------------------------ États pendant le défilement

    public void setRunning(String sub) {
        main.post(() -> {
            if (root == null) return;
            running = true;
            stopPulse();
            cancelHide();
            text(R.id.rKicker, "J'Y VAIS…");
            text(R.id.rSub, sub);
            ((ImageView) root.findViewById(R.id.rGoIcon)).setImageResource(R.drawable.ic_stop);
        });
    }

    public void progress(float pct, String sub) {
        main.post(() -> {
            if (root == null) return;
            if (pct >= 0) animateBar(root.findViewById(R.id.rBar), pct);
            if (sub != null) text(R.id.rSub, sub);
        });
    }

    public void done(boolean ok, String sub) {
        main.post(() -> {
            if (root == null) return;
            running = false;
            text(R.id.rKicker, ok ? "TU Y ES" : "PRESQUE");
            text(R.id.rSub, sub);
            ImageView icon = root.findViewById(R.id.rGoIcon);
            icon.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_arrow_down);
            View go = root.findViewById(R.id.rGo);
            go.animate().scaleX(1.15f).scaleY(1.15f).setDuration(140)
                    .withEndAction(() -> go.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
            scheduleHide(ok ? 2200 : 4500);
        });
    }

    public void hide() {
        main.post(() -> {
            cancelHide();
            stopPulse();
            if (root == null) return;
            View card = root.findViewById(R.id.rCard);
            View r = root;
            card.animate().alpha(0f).translationY(-dp(50)).setDuration(260)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (root == r) dismissNow();
                    }).start();
        });
    }

    public void release() {
        main.post(this::dismissNow);
    }

    // ------------------------------------------------------------------ Utilitaires

    private void animateBar(ProgressBar bar, float pct) {
        ValueAnimator va = ValueAnimator.ofInt(bar.getProgress(), (int) (1000 * Math.max(0, Math.min(1, pct))));
        va.setDuration(500);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> bar.setProgress((int) a.getAnimatedValue()));
        va.start();
    }

    private void startPulse(View v) {
        stopPulse();
        pulse = ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f));
        pulse.setDuration(1100);
        pulse.setRepeatCount(3);
        pulse.setStartDelay(300);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) pulse.cancel();
        pulse = null;
    }

    private void scheduleHide(long ms) {
        cancelHide();
        autoHide = this::hide;
        main.postDelayed(autoHide, ms);
    }

    private void cancelHide() {
        if (autoHide != null) main.removeCallbacks(autoHide);
        autoHide = null;
    }

    private void dismissNow() {
        cancelHide();
        stopPulse();
        if (root != null) {
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
    }

    private void text(int id, String s) {
        ((TextView) root.findViewById(id)).setText(s);
    }

    private int dp(int v) {
        return (int) (v * svc.getResources().getDisplayMetrics().density);
    }
}
