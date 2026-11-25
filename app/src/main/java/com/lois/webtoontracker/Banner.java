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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Petite bannière animée affichée par-dessus les autres applis (overlay d'accessibilité :
 * aucune permission en plus). Elle glisse depuis le haut, l'icône rebondit, puis elle repart.
 * Non cliquable par défaut (elle ne gêne jamais la lecture), sauf si elle porte un bouton.
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

    /** Une bannière est-elle à l'écran ? (fil principal) */
    public boolean isShowing() {
        return current != null;
    }

    public void show(NotifyPolicy.Kind kind, String title, String subtitle) {
        show(kind, title, subtitle, VISIBLE_MS, 0);
    }

    /** @param delayMs attendre avant d'afficher (ex : le temps que le chapitre s'ouvre) */
    public void show(NotifyPolicy.Kind kind, String title, String subtitle, long visibleMs, long delayMs) {
        show(kind, title, subtitle, visibleMs, delayMs, null, null);
    }

    /**
     * Variante avec bouton (ex : "Y aller"). La bannière devient touchable, mais seulement sur
     * sa propre surface : le reste de l'écran reste utilisable normalement.
     * visibleMs <= 0 : reste affichée jusqu'à hide().
     */
    public void show(NotifyPolicy.Kind kind, String title, String subtitle, long visibleMs, long delayMs,
                     String actionLabel, Runnable action) {
        handler.postDelayed(() -> {
            try {
                showNow(kind, title, subtitle, visibleMs, actionLabel, action);
            } catch (Throwable t) {
                App.saveError(service, "Bannière", t);
            }
        }, delayMs);
    }

    /** Met à jour le texte de la bannière affichée (ex : progression du défilement). */
    public void update(String title, String subtitle) {
        handler.post(() -> {
            if (current == null) return;
            ((TextView) current.findViewById(R.id.bTitle)).setText(title);
            ((TextView) current.findViewById(R.id.bSubtitle)).setText(subtitle);
        });
    }

    public void hide() {
        handler.post(() -> {
            if (hideTask != null) {
                handler.removeCallbacks(hideTask);
                hideTask.run();
            } else {
                removeNow();
            }
        });
    }

    private void showNow(NotifyPolicy.Kind kind, String title, String subtitle, long visibleMs,
                         String actionLabel, Runnable action) {
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
            case RESUME:
                color = 0xFF6D4AFF; iconRes = R.drawable.ic_bookmark; break;
            case LOCKED:
                color = 0xFFFFA62B; iconRes = R.drawable.ic_lock; break;
            case ADDED:
            default:
                color = 0xFF00B26A; iconRes = R.drawable.ic_check; break;
        }
        icon.setImageResource(iconRes);
        TextView actionView = root.findViewById(R.id.bAction);
        if (action != null) {
            actionView.setVisibility(actionLabel == null ? View.GONE : View.VISIBLE);
            actionView.setText(actionLabel);
            banner.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                action.run();
            });
        }
        // Pastille teintée (couleur à 18 %) + icône dans la couleur : plus doux qu'un rond plein.
        ((GradientDrawable) iconBg.getBackground().mutate()).setColor((color & 0x00FFFFFF) | 0x2E000000);
        icon.setColorFilter(lighter(color));

        float density = service.getResources().getDisplayMetrics().density;
        // Largeur fixe : écran − marges (440 dp max) → le bouton ne se fait jamais écraser.
        int screenW = service.getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams blp = banner.getLayoutParams();
        blp.width = (int) Math.min(screenW - 24 * density, 440 * density);
        banner.setLayoutParams(blp);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (action == null ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        : WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (24 * density);
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
        final View b = banner;
        hideTask = () -> b.animate().alpha(0f).translationY(-40 * density)
                .setDuration(260).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (current == shown) removeNow();
                }).start();
        if (visibleMs > 0) handler.postDelayed(hideTask, visibleMs);
    }

    /** Version plus claire d'une couleur (icône sur fond sombre). */
    private static int lighter(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r += (255 - r) * 35 / 100;
        g += (255 - g) * 35 / 100;
        b += (255 - b) * 35 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
