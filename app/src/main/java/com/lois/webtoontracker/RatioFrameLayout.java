package com.lois.webtoontracker;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Cadre au format couverture : hauteur = largeur × 1,45. */
public class RatioFrameLayout extends FrameLayout {

    private static final float RATIO = 1.45f;

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = View.MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(Math.round(w * RATIO), View.MeasureSpec.EXACTLY));
    }
}
