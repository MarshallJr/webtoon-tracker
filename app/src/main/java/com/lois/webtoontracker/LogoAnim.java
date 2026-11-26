package com.lois.webtoontracker;

import android.content.res.Resources;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.RequiresApi;

/**
 * Logo animé du menu (res/raw/logo_anim.webp : éclairs jaunes, puis le perso se transforme et reste violet).
 * Joué une fois à chaque fois, jamais en boucle : pas de batterie gâchée.
 */
@RequiresApi(28)
final class LogoAnim {

    private LogoAnim() {
    }

    /** Décode l'animation (fil d'arrière-plan conseillé). */
    static Drawable decode(Resources res) throws java.io.IOException {
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(res, R.raw.logo_anim));
    }

    /** Lance l'animation une seule fois ; elle s'arrête sur la dernière image (logo illuminé). */
    static void start(Drawable d) {
        if (d instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable a = (AnimatedImageDrawable) d;
            a.setRepeatCount(0);
            a.start();
        }
    }
}
