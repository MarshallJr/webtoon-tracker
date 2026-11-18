package com.lois.webtoontracker;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.TextView;

/**
 * Petite bannière animée affichée par-dessus les autres applis (overlay d'accessibilité :
 * aucune permission en plus). Elle glisse depuis le haut, l'icône rebondit, puis elle repart.
 * Non cliquable : elle ne gêne jamais la lecture.
 */
public final class Banner {

    private static final long VISIBLE_MS = 2300;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View current;
    private Runnable hideTask;

    public Banner(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    public void show(NotifyPolicy.Kind kind, String title, String subtitle) {
        handler.post(() -> {
            try {
                showNow(kind, title, subtitle);
            } catch (Throwable t) {
                App.saveError(service, "Bannière", t);
            }
        });
    }

    private void showNow(NotifyPolicy.Kind kind, String title, String subtitle) {
        removeNow();

        View root = LayoutInflater.from(service).inflate(R.layout.overlay_banner, null);
        View banner = root.findViewById(R.id.banner);
        View iconBg = root.findViewById(R.id.iconBg);
        ImageView icon = root.findViewById(R.id.icon);
        ((TextView) root.findViewById(R.id.bTitle)).setText(title);
        ((TextView) root.findViewById(R.id.bSubtitle)).setText(subtitle);

        int color;
        int iconRes;
        switch (kind) {
            case NEW_SERIES:
                color = 0xFFFF7A45; iconRes = R.drawable.ic_star; break;
            case STREAK:
                color = 0xFFF5A524; iconRes = R.drawable.ic_fire; break;
            case ALREADY_READ:
                color = 0xFF5B6CFF; iconRes = R.drawable.ic_eye; break;
            case ADDED:
            default:
                color = 0xFF00B26A; iconRes = R.drawable.ic_check; break;
        }
        icon.setImageResource(iconRes);
        ((GradientDrawable) iconBg.getBackground().mutate()).setColor(color);

        float density = service.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (36 * density);
        wm.addView(root, lp);
        current = root;

        // Entrée : glisse + fondu, puis l'icône "pop" avec un petit rebond.
        banner.setAlpha(0f);
        banner.setTranslationY(-60 * density);
        banner.setScaleX(0.9f);
        banner.setScaleY(0.9f);
        banner.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();

        iconBg.setScaleX(0f);
        iconBg.setScaleY(0f);
        iconBg.animate().scaleX(1f).scaleY(1f).setStartDelay(180).setDuration(420)
                .setInterpolator(new OvershootInterpolator(3f)).start();
        icon.setRotation(kind == NotifyPolicy.Kind.NEW_SERIES ? -90 : 0);
        icon.animate().rotation(0).setStartDelay(220).setDuration(420).start();

        banner.performHapticFeedback(HapticFeedbackConstants.CONFIRM);

        final View shown = root;
        hideTask = () -> banner.animate().alpha(0f).translationY(-40 * density)
                .setDuration(260).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (current == shown) removeNow();
                }).start();
        handler.postDelayed(hideTask, VISIBLE_MS);
    }

    private void removeNow() {
        if (hideTask != null) handler.removeCallbacks(hideTask);
        hideTask = null;
        if (current != null) {
            try {
                wm.removeViewImmediate(current);
            } catch (Throwable ignored) {
            }
            current = null;
        }
    }

    public void release() {
        handler.post(this::removeNow);
    }
}
