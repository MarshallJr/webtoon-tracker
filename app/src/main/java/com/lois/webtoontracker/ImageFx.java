package com.lois.webtoontracker;

import android.graphics.Bitmap;

/** Petits effets d'image calculés une fois (pas d'effet en direct qui déborde de son cadre). */
final class ImageFx {

    private ImageFx() {
    }

    /**
     * Flou doux (3 passes de flou « boîte » ≈ flou gaussien) sur une version réduite de l'image.
     * Le résultat a exactement les bords de l'image : pas de liseré clair en bas du bandeau.
     *
     * @param width  largeur de travail (ex. 160 px) — plus petit = plus flou et plus rapide
     * @param radius rayon en pixels de travail
     */
    static Bitmap blur(Bitmap src, int width, int radius) {
        int w = Math.max(8, Math.min(width, src.getWidth()));
        int h = Math.max(8, Math.round(src.getHeight() * w / (float) src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, radius);
            boxV(tmp, px, w, h, radius);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Math.min(w - 1, Math.max(0, i))];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int a = in[row + Math.min(w - 1, x + r + 1)], b = in[row + Math.max(0, x - r)];
                sr += ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                sg += ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                sb += (a & 0xFF) - (b & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int a = in[Math.min(h - 1, y + r + 1) * w + x], b = in[Math.max(0, y - r) * w + x];
                sr += ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                sg += ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                sb += (a & 0xFF) - (b & 0xFF);
            }
        }
    }
}
