package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

/** Petit graphique en barres arrondies, animé à l'apparition. */
public class BarChartView extends View {

    private float[] values = new float[0];
    private String[] labels = new String[0];
    private int highlight = -1;
    private float progress = 1f;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;

    public BarChartView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        track.setColor(ContextCompat.getColor(c, R.color.chart_grid));
        label.setColor(ContextCompat.getColor(c, R.color.text_soft));
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        value.setColor(ContextCompat.getColor(c, R.color.text_main));
        value.setTextSize(11 * dp);
        value.setFakeBoldText(true);
        value.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(float[] values, String[] labels, int highlight) {
        this.values = values;
        this.labels = labels;
        this.highlight = highlight;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(700);
        va.setInterpolator(new DecelerateInterpolator(2f));
        va.addUpdateListener(an -> {
            progress = (float) an.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = values.length;
        if (n == 0) return;
        float max = 1;
        for (float v : values) max = Math.max(max, v);

        float top = 18 * dp, bottom = getHeight() - 18 * dp;
        float slot = (float) getWidth() / n;
        float w = Math.min(slot * 0.56f, 22 * dp);
        float radius = w / 2;
        int brand = ContextCompat.getColor(getContext(), R.color.brand);
        int accent = ContextCompat.getColor(getContext(), R.color.accent);

        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            r.set(cx - w / 2, top, cx + w / 2, bottom);
            canvas.drawRoundRect(r, radius, radius, track);

            float h = (bottom - top) * (values[i] / max) * progress;
            if (values[i] > 0) {
                h = Math.max(h, w);
                r.set(cx - w / 2, bottom - h, cx + w / 2, bottom);
                int c = i == highlight ? accent : brand;
                bar.setShader(new LinearGradient(0, r.top, 0, r.bottom, c, (c & 0x00FFFFFF) | 0x99000000, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(r, radius, radius, bar);
                canvas.drawText(String.valueOf((int) values[i]), cx, r.top - 5 * dp, value);
            }
            if (i < labels.length && labels[i] != null) canvas.drawText(labels[i], cx, getHeight() - 3 * dp, label);
        }
    }
}
