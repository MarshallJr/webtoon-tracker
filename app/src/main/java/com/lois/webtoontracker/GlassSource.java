package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Conteneur des pages, vu « à travers » la barre en verre ({@link GlassBar}).
 * Android 12+ : ses enfants sont enregistrés dans un RenderNode, dessiné normalement ici ET, flouté,
 * par la barre. Le RenderNode est partagé : quand une page défile, la barre suit sans rien recalculer.
 */
public class GlassSource extends FrameLayout {

    private RenderNode content;

    public GlassSource(Context context) {
        super(context);
    }

    public GlassSource(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassSource(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Le contenu enregistré (Android 12+, dessin matériel), sinon null. */
    RenderNode node() {
        return Build.VERSION.SDK_INT >= 31 && content != null && content.hasDisplayList() ? content : null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            if (content == null) content = new RenderNode("glass-source");
            content.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = content.beginRecording(getWidth(), getHeight());
            try {
                super.dispatchDraw(rc);
            } finally {
                content.endRecording();
            }
            canvas.drawRenderNode(content);
        } else {
            super.dispatchDraw(canvas);
        }
    }
}
