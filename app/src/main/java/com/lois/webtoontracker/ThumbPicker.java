package com.lois.webtoontracker;

/**
 * Choisit, dans une capture de la page lue, le plus beau cadrage pour la vignette d'un chapitre.
 *
 * Règles (dans cet ordre) :
 *  1. jamais à cheval sur deux scènes : aucune marge unie (blanc / noir entre deux cases) ni
 *     trait de bordure de case à l'intérieur du cadre ;
 *  2. pas de texte : ni bulles, ni encarts de narration, ni gros effets sonores (zones à traits
 *     noirs sur blanc ou blancs sur noir, très contrastées et sans couleur) ;
 *  3. pas de vide : peu de blanc (fond de bulle, marge), peu de noir ;
 *  4. parmi ce qui reste : le plus coloré, contrasté et détaillé, avec un bonus pour les tons
 *     chair (les visages font les meilleures vignettes, comme sur WEBTOON) et pour les grands cadres.
 *
 * Travaille sur une version réduite (180 px de large) avec des tables de sommes cumulées :
 * des centaines de cadrages sont évalués en quelques millisecondes. Sans dépendance Android
 * (pixels ARGB en entrée) pour pouvoir être testé hors du téléphone.
 */
public final class ThumbPicker {

    private ThumbPicker() {
    }

    /** Résultat : cadre dans l'image d'origine, et sa note (≤ 0 : rien de présentable). */
    public static final class Pick {
        public int left, top, width, height;
        public float score;
        public String why = "";
    }

    static final int SW = 180;              // largeur de travail
    static final float ASPECT = 330f / 236f; // format des vignettes (largeur / hauteur)

    /**
     * @param argb   pixels de la zone à analyser (déjà réduite à SW de large), ligne par ligne
     * @param w      largeur (SW)
     * @param h      hauteur
     * @param scale  facteur pour revenir aux coordonnées de la capture d'origine
     * @param offX   décalage X de la zone dans la capture d'origine
     * @param offY   décalage Y
     */
    public static Pick pick(int[] argb, int w, int h, float scale, int offX, int offY) {
        int n = w * h;
        int[] lum = new int[n], sat = new int[n];
        int[] rgA = new int[n], ybA = new int[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            lum[i] = (r * 3 + g * 6 + b) / 10;
            sat[i] = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            rgA[i] = r - g;
            ybA[i] = (r + g) / 2 - b;
        }

        // --- Cartes par pixel
        int[] white = new int[n], black = new int[n], edge = new int[n], skin = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int L = lum[i];
                white[i] = L > 228 && sat[i] < 28 ? 1 : 0;
                black[i] = L < 38 ? 1 : 0;
                int gx = x > 0 && x < w - 1 ? Math.abs(lum[i + 1] - lum[i - 1]) : 0;
                int gy = y > 0 && y < h - 1 ? Math.abs(lum[i + w] - lum[i - w]) : 0;
                edge[i] = gx + gy > 70 ? 1 : 0;
                skin[i] = isSkin(argb[i]) ? 1 : 0;
            }
        }

        // --- Texte : petites cellules très contrastées, presque sans couleur, faites de noir ET de blanc
        int cs = 5;
        int[] text = new int[n];
        for (int cy = 0; cy + cs <= h; cy += cs) {
            for (int cx = 0; cx + cs <= w; cx += cs) {
                int e = 0, wh = 0, bl = 0, light = 0, dark = 0, s = 0, m = cs * cs;
                for (int y = cy; y < cy + cs; y++) {
                    for (int x = cx; x < cx + cs; x++) {
                        int i = y * w + x;
                        e += edge[i];
                        wh += white[i];
                        bl += black[i];
                        if (lum[i] > 190) light++;
                        if (lum[i] < 80) dark++;
                        s += sat[i];
                    }
                }
                boolean bimodal = light + dark >= m * 0.8f && light >= m * 0.15f && dark >= m * 0.12f;
                boolean texty = e >= m * 0.28f && bimodal && s / m < 40;
                if (texty) {
                    for (int y = cy; y < cy + cs; y++) for (int x = cx; x < cx + cs; x++) text[y * w + x] = 1;
                }
            }
        }
        // Un encart de texte est entouré de son fond : on « gonfle » la zone texte d'une cellule.
        text = dilate(text, w, h, cs);

        // --- Lignes interdites à l'intérieur d'un cadre : marge unie ou bordure de case
        int[] badRow = new int[h];
        for (int y = 1; y < h - 1; y++) {
            int min = 255, max = 0, sMax = 0, borderPx = 0;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                min = Math.min(min, lum[i]);
                max = Math.max(max, lum[i]);
                sMax = Math.max(sMax, sat[i]);
                // trait horizontal net (bordure de case, découpe diagonale…)
                if (Math.abs(lum[i + w] - lum[i - w]) > 60) borderPx++;
            }
            boolean uniform = max - min < 16 && sMax < 40;
            boolean border = borderPx >= w * 0.55f;
            badRow[y] = uniform || border ? 1 : 0;
        }
        int[] badPre = new int[h + 1];
        for (int y = 0; y < h; y++) badPre[y + 1] = badPre[y] + badRow[y];

        // --- Tables de sommes cumulées
        Sat sWhite = new Sat(white, w, h), sBlack = new Sat(black, w, h), sEdge = new Sat(edge, w, h);
        Sat sSkin = new Sat(skin, w, h), sText = new Sat(text, w, h);
        LSat sL = new LSat(lum, w, h, false), sL2 = new LSat(lum, w, h, true);
        LSat sRg = new LSat(rgA, w, h, false), sRg2 = new LSat(rgA, w, h, true);
        LSat sYb = new LSat(ybA, w, h, false), sYb2 = new LSat(ybA, w, h, true);
        LSat sS = new LSat(sat, w, h, false);

        Pick best = new Pick();
        best.score = Float.NEGATIVE_INFINITY;
        float[] fracs = {1.0f, 0.82f, 0.66f};
        for (float f : fracs) {
            int cw = Math.round(w * f), ch = Math.round(cw / ASPECT);
            if (ch >= h) continue;
            int stepX = Math.max(4, (w - cw) / 4 + 1);
            for (int y = 0; y + ch <= h; y += 3) {
                // aucune ligne interdite à l'intérieur (2 lignes de tolérance sur les bords)
                if (badPre[y + ch - 2] - badPre[y + 2] > 0) continue;
                for (int x = 0; x + cw <= w; x += stepX) {
                    float area = cw * ch;
                    float whiteF = sWhite.sum(x, y, cw, ch) / area;
                    float blackF = sBlack.sum(x, y, cw, ch) / area;
                    float textF = sText.sum(x, y, cw, ch) / area;
                    if (whiteF > 0.30f || blackF > 0.45f || textF > 0.05f) continue;
                    float edgeF = sEdge.sum(x, y, cw, ch) / area;
                    float skinF = sSkin.sum(x, y, cw, ch) / area;
                    double mL = sL.sum(x, y, cw, ch) / area, vL = sL2.sum(x, y, cw, ch) / area - mL * mL;
                    double mRg = sRg.sum(x, y, cw, ch) / area, vRg = sRg2.sum(x, y, cw, ch) / area - mRg * mRg;
                    double mYb = sYb.sum(x, y, cw, ch) / area, vYb = sYb2.sum(x, y, cw, ch) / area - mYb * mYb;
                    double mS = sS.sum(x, y, cw, ch) / area;
                    double colorful = Math.sqrt(Math.max(0, vRg) + Math.max(0, vYb)) + 0.3 * Math.sqrt(mRg * mRg + mYb * mYb);
                    double contrast = Math.sqrt(Math.max(0, vL));
                    double detail = Math.min(edgeF, 0.22f) / 0.22f;          // assez de dessin, sans privilégier le bruit
                    double skinBonus = Math.min(skinF, 0.22f) / 0.22f;
                    double s = colorful * 0.9 + contrast * 0.7 + mS * 0.25
                            + detail * 18 + skinBonus * 26 + (f - 0.66f) * 20
                            - whiteF * 110 - blackF * 50 - textF * 500;
                    if (s > best.score) {
                        best.score = (float) s;
                        best.left = offX + Math.round(x * scale);
                        best.top = offY + Math.round(y * scale);
                        best.width = Math.round(cw * scale);
                        best.height = Math.round(ch * scale);
                        best.why = String.format(java.util.Locale.ROOT,
                                "f=%.2f blanc=%.2f noir=%.2f texte=%.3f peau=%.2f coul=%.0f contr=%.0f", f, whiteF, blackF, textF, skinF, colorful, contrast);
                    }
                }
            }
        }
        if (best.score == Float.NEGATIVE_INFINITY) {
            best.score = 0;
            best.why = "aucun cadre propre";
        }
        return best;
    }

    /** Teinte « peau » de manhwa : orangé clair, peu saturé, lumineux. */
    static boolean isSkin(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (r < 150 || r <= g || g <= b) return false;
        int max = r, min = b;
        float s = (max - min) / (float) max;
        float hue = 60f * (g - b) / (max - min);       // r est le max : teinte entre 0 et 60°
        return s > 0.10f && s < 0.55f && hue > 8 && hue < 45 && r - b > 25;
    }

    private static int[] dilate(int[] m, int w, int h, int r) {
        int[] out = new int[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (m[y * w + x] == 0) continue;
                for (int yy = Math.max(0, y - r); yy <= Math.min(h - 1, y + r); yy++) {
                    for (int xx = Math.max(0, x - r); xx <= Math.min(w - 1, x + r); xx++) out[yy * w + xx] = 1;
                }
            }
        }
        return out;
    }

    /** Table de sommes cumulées (entiers). */
    private static final class Sat {
        final int[] t;
        final int w;

        Sat(int[] v, int w, int h) {
            this.w = w + 1;
            t = new int[(w + 1) * (h + 1)];
            for (int y = 0; y < h; y++) {
                int row = 0;
                for (int x = 0; x < w; x++) {
                    row += v[y * w + x];
                    t[(y + 1) * this.w + x + 1] = t[y * this.w + x + 1] + row;
                }
            }
        }

        int sum(int x, int y, int cw, int ch) {
            return t[(y + ch) * w + x + cw] - t[y * w + x + cw] - t[(y + ch) * w + x] + t[y * w + x];
        }
    }

    /** Table de sommes cumulées (valeurs ou carrés, en long). */
    private static final class LSat {
        final long[] t;
        final int w;

        LSat(int[] v, int w, int h, boolean squares) {
            this.w = w + 1;
            t = new long[(w + 1) * (h + 1)];
            for (int y = 0; y < h; y++) {
                long row = 0;
                for (int x = 0; x < w; x++) {
                    long a = v[y * w + x];
                    row += squares ? a * a : a;
                    t[(y + 1) * this.w + x + 1] = t[y * this.w + x + 1] + row;
                }
            }
        }

        double sum(int x, int y, int cw, int ch) {
            return t[(y + ch) * w + x + cw] - t[y * w + x + cw] - t[(y + ch) * w + x] + t[y * w + x];
        }
    }
}
