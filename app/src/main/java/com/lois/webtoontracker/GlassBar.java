package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

/**
 * Barre « verre » : ce qui défile derrière apparaît flouté (Android 12+), sous un voile teinté et un
 * liseré clair. Avant Android 12 : voile seul, plus couvrant. Les boutons se dessinent par-dessus.
 *
 * Le flou est un RenderNode qui dessine celui des pages ({@link GlassSource}) décalé sous la barre.
 * Il est réenregistré juste avant chaque image (quelques opérations), sans redessiner la barre : il suit
 * le défilement image par image, et ne coûte rien quand l'écran est immobile.
 */
public class GlassBar extends LinearLayout {

    private static final float RADIUS_DP = 32f, BLUR_DP = 24f;

    private GlassSource source;
    private RenderNode blur;
    private final Path shape = new Path();
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int ground;
    private final float density;
    private final ViewTreeObserver.OnDrawListener follow = this::record;

    public GlassBar(Context context) {
        this(context, null);
    }

    public GlassBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;
        tint.setColor(ContextCompat.getColor(context, R.color.glass_tint));
        flat.setColor(ContextCompat.getColor(context, R.color.glass_flat));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density));
        stroke.setColor(ContextCompat.getColor(context, R.color.glass_stroke));
        ground = ContextCompat.getColor(context, R.color.screen_bg);
    }

    /** Ce qui est derrière la barre (les pages). */
    public void setSource(GlassSource source) {
        this.source = source;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 31) getViewTreeObserver().addOnDrawListener(follow);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) getViewTreeObserver().removeOnDrawListener(follow);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float r = RADIUS_DP * density, inset = stroke.getStrokeWidth() / 2f;
        shape.reset();
        shape.addRoundRect(inset, inset, w - inset, h - inset, r, r, Path.Direction.CW);
    }

    /** Réenregistre le flou avec le contenu actuel des pages. @return false si impossible ici */
    private boolean record() {
        if (Build.VERSION.SDK_INT < 31 || source == null || getWidth() == 0 || getHeight() == 0) return false;
        RenderNode content = source.node();
        if (content == null) return false;
        if (blur == null) {
            blur = new RenderNode("glass-blur");
            float r = BLUR_DP * density;
            blur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        }
        blur.setPosition(0, 0, getWidth(), getHeight());
        RecordingCanvas rc = blur.beginRecording(getWidth(), getHeight());
        try {
            rc.drawColor(ground);   // fond opaque : le net de derrière ne transparaît pas
            rc.translate(source.getLeft() - getLeft(), source.getTop() - getTop());
            rc.drawRenderNode(content);
        } finally {
            blur.endRecording();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated() && record()) {
            canvas.save();
            canvas.clipPath(shape);
            canvas.drawRenderNode(blur);
            canvas.drawPath(shape, tint);
            canvas.restore();
        } else {
            canvas.drawPath(shape, flat);
        }
        canvas.drawPath(shape, stroke);
        super.onDraw(canvas);
    }
}
