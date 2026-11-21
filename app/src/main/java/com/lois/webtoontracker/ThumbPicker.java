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
 *  4. un PERSONNAGE de préférence : zone de peau avec des traits dedans (yeux, bouche, sourcils —
 *     un mur beige n'en a pas), bien centrée dans le cadre (pas un visage coupé au bord) ;
 *  5. puis le plus coloré, contrasté et détaillé ; cadres larges privilégiés (zoom limité à 80 %).
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
        public boolean hasChar;
        public String why = "";
    }

    static final int SW = 180;              // largeur de travail (cadrages)
    static final int SW2 = SW * 2;          // largeur d'analyse du texte (traits fins des bulles)
    static final float ASPECT = 330f / 236f; // format des vignettes (largeur / hauteur)

    /**
     * @param argb   pixels de la zone à analyser (déjà réduite à SW de large), ligne par ligne
     * @param w      largeur (SW)
     * @param h      hauteur
     * @param scale  facteur pour revenir aux coordonnées de la capture d'origine
     * @param offX   décalage X de la zone dans la capture d'origine
     * @param offY   décalage Y
     */
    public static Pick pick(int[] argb2, int w2, int h2, float scale2, int offX, int offY) {
        // Texte et bulles : cherchés en double résolution (les lettres des bulles sont fines).
        int w = w2 / 2, h = h2 / 2;
        float scale = scale2 * 2;
        int[] argb = half(argb2, w2, h2, w, h);
        int[] text = bubbles(textMap(argb2, w2, h2, w, h), argb, w, h, gutterRows(argb, w, h));
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
        int[] skinX = new int[n], skinY = new int[n], paper = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int L = lum[i];
                white[i] = L > 228 && sat[i] < 28 ? 1 : 0;
                paper[i] = L > 208 && sat[i] < 36 && !isSkin(argb[i]) ? 1 : 0;   // fond de bulle, marge, papier (pas une peau claire)
                black[i] = L < 38 ? 1 : 0;
                int gx = x > 0 && x < w - 1 ? Math.abs(lum[i + 1] - lum[i - 1]) : 0;
                int gy = y > 0 && y < h - 1 ? Math.abs(lum[i + w] - lum[i - w]) : 0;
                edge[i] = gx + gy > 70 ? 1 : 0;
                skin[i] = isSkin(argb[i]) ? 1 : 0;
                skinX[i] = skin[i] * x;
                skinY[i] = skin[i] * y;
            }
        }
        // Peau : on ne garde que les zones pleines (visage, mains), pas les traits orangés épars
        // (lignes de vitesse, flammes, onomatopées) — « ouverture » : érosion puis dilatation.
        skin = dilate(erode(skin, w, h, 2), w, h, 2);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                skinX[i] = skin[i] * x;
                skinY[i] = skin[i] * y;
            }
        }
        // Traits du visage : contours situés DANS ou au bord d'une zone de peau.
        int[] skinZone = dilate(skin, w, h, 2);
        int[] faceEdge = new int[n];
        for (int i = 0; i < n; i++) faceEdge[i] = edge[i] & skinZone[i];

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
        Sat sZone = new Sat(skinZone, w, h), sFaceEdge = new Sat(faceEdge, w, h), sPaper = new Sat(paper, w, h);
        LSat sSkinX = new LSat(skinX, w, h, false), sSkinY = new LSat(skinY, w, h, false);
        LSat sL = new LSat(lum, w, h, false), sL2 = new LSat(lum, w, h, true);
        LSat sRg = new LSat(rgA, w, h, false), sRg2 = new LSat(rgA, w, h, true);
        LSat sYb = new LSat(ybA, w, h, false), sYb2 = new LSat(ybA, w, h, true);
        LSat sS = new LSat(sat, w, h, false);

        Pick best = new Pick();
        best.score = Float.NEGATIVE_INFINITY;
        boolean bestHasChar = false;
        float[] fracs = {1.0f, 0.9f, 0.8f};   // pas trop de zoom
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
                    if (whiteF > 0.38f || blackF > 0.38f || textF > 0.012f) continue;   // (quasi) aucun texte
                    float edgeF = sEdge.sum(x, y, cw, ch) / area;
                    if (edgeF < 0.035f) continue;                               // flou, aplat : rien à montrer
                    float skinF = sSkin.sum(x, y, cw, ch) / area;
                    double face = character(skinF, sZone.sum(x, y, cw, ch), sFaceEdge.sum(x, y, cw, ch),
                            sSkin.sum(x, y, cw, ch), sSkinX.sum(x, y, cw, ch), sSkinY.sum(x, y, cw, ch), x, y, cw, ch);
                    // Un fond clair est accepté derrière un vrai personnage (gros plan), pas autour de rien.
                    float paperF = sPaper.sum(x, y, cw, ch) / area;
                    if (whiteF > 0.22f + 0.16f * face || paperF > 0.25f + 0.12f * face) continue;
                    double mL = sL.sum(x, y, cw, ch) / area, vL = sL2.sum(x, y, cw, ch) / area - mL * mL;
                    double mRg = sRg.sum(x, y, cw, ch) / area, vRg = sRg2.sum(x, y, cw, ch) / area - mRg * mRg;
                    double mYb = sYb.sum(x, y, cw, ch) / area, vYb = sYb2.sum(x, y, cw, ch) / area - mYb * mYb;
                    double mS = sS.sum(x, y, cw, ch) / area;
                    double colorful = Math.sqrt(Math.max(0, vRg) + Math.max(0, vYb)) + 0.3 * Math.sqrt(mRg * mRg + mYb * mYb);
                    double contrast = Math.sqrt(Math.max(0, vL));
                    double detail = Math.min(edgeF, 0.22f) / 0.22f;          // assez de dessin, sans privilégier le bruit
                    double s = colorful * 0.7 + contrast * 0.5 + mS * 0.2
                            + detail * 14 + face * 140 + (f - 0.8f) * 60
                            - whiteF * 90 - paperF * 40 - blackF * 60 - textF * 1500;
                    // Personnage d'abord : dès qu'un cadre en montre un, seuls ces cadres-là comptent.
                    boolean hasChar = face >= 0.55;
                    if (hasChar && !bestHasChar) {
                        bestHasChar = true;
                        best.score = Float.NEGATIVE_INFINITY;
                    } else if (!hasChar && bestHasChar) {
                        continue;
                    }
                    if (s > best.score) {
                        best.score = (float) s;
                        best.left = offX + Math.round(x * scale);
                        best.top = offY + Math.round(y * scale);
                        best.width = Math.round(cw * scale);
                        best.height = Math.round(ch * scale);
                        best.why = String.format(java.util.Locale.ROOT,
                                "f=%.2f blanc=%.2f noir=%.2f texte=%.3f peau=%.2f perso=%.2f coul=%.0f contr=%.0f",
                                f, whiteF, blackF, textF, skinF, face, colorful, contrast);
                    }
                }
            }
        }
        if (best.score == Float.NEGATIVE_INFINITY) {
            best.score = 0;
            best.why = "aucun cadre propre";
        } else if (!bestHasChar) {
            best.score *= 0.6f;   // sans personnage : gardé faute de mieux, une autre capture le remplacera
            best.why += " (sans personnage)";
        }
        best.hasChar = bestHasChar;
        return best;
    }

    /**
     * Présence d'un personnage dans le cadre (0 → 1) :
     * assez de peau (sans que le cadre ne soit QUE de la peau), des traits à l'intérieur
     * (yeux, bouche… : distingue un visage d'un mur beige), et une peau centrée (pas coupée au bord).
     */
    static double character(float skinF, int zone, int faceEdges, int skinPx, double sumX, double sumY,
                            int x, int y, int cw, int ch) {
        if (skinF < 0.05f || skinPx == 0 || zone == 0) return 0;
        double amount = Math.min(skinF, 0.28f) / 0.28f;
        if (skinF > 0.55f) amount *= 0.5;                       // zoom sur une joue : trop près
        double features = Math.min(faceEdges / (double) zone, 0.16) / 0.16;
        double cx = sumX / skinPx, cy = sumY / skinPx;
        double dx = Math.abs(cx - (x + cw / 2.0)) / (cw / 2.0);
        double dy = Math.abs((cy - y) / ch - 0.45) / 0.55;            // visage plutôt au milieu-haut
        double centered = Math.max(0, 1 - Math.max(dx, dy) * 1.1);
        double v = amount * 0.4 + features * 0.35 + centered * 0.25;
        return features < 0.25 ? v * 0.3 : v;                  // de la peau sans traits : sans doute un décor
    }

    /** Réduction ×2 (moyenne de 4 pixels). */
    static int[] half(int[] a, int w2, int h2, int w, int h) {
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int c = a[(y * 2 + (k >> 1)) * w2 + x * 2 + (k & 1)];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                o[y * w + x] = 0xFF000000 | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
            }
        }
        return o;
    }

    /**
     * Lettres : petites cellules (6 px en double résolution) pleines de traits nets, à deux tons
     * opposés autour de leur moyenne (encre / fond), et presque sans couleur. Rendu en 180 px.
     */
    static int[] textMap(int[] a, int w2, int h2, int w, int h) {
        int n2 = w2 * h2;
        int[] lum = new int[n2], sat = new int[n2];
        for (int i = 0; i < n2; i++) {
            int c = a[i], r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            lum[i] = (r * 3 + g * 6 + b) / 10;
            sat[i] = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        int cs = 6, m = cs * cs;
        int[] out = new int[w * h];
        for (int cy = 0; cy + cs <= h2; cy += cs) {
            for (int cx = 0; cx + cs <= w2; cx += cs) {
                int sum = 0, e = 0, s = 0;
                for (int y = cy; y < cy + cs; y++) {
                    for (int x = cx; x < cx + cs; x++) {
                        int i = y * w2 + x;
                        sum += lum[i];
                        s += sat[i];
                        int gx = x > 0 && x < w2 - 1 ? Math.abs(lum[i + 1] - lum[i - 1]) : 0;
                        int gy = y > 0 && y < h2 - 1 ? Math.abs(lum[i + w2] - lum[i - w2]) : 0;
                        if (gx + gy > 60) e++;
                    }
                }
                int mean = sum / m, hi = 0, lo = 0;
                for (int y = cy; y < cy + cs; y++) {
                    for (int x = cx; x < cx + cs; x++) {
                        int d = lum[y * w2 + x] - mean;
                        if (d > 40) hi++;
                        else if (d < -40) lo++;
                    }
                }
                // Lettres : on traverse plusieurs traits dans les DEUX sens (un simple contour, un seul).
                int crossH = 0, crossV = 0;
                for (int k = 0; k < cs; k++) {
                    boolean pa = lum[(cy + k) * w2 + cx] > mean, pb = lum[cy * w2 + cx + k] > mean;
                    for (int j = 1; j < cs; j++) {
                        boolean a1 = lum[(cy + k) * w2 + cx + j] > mean, b1 = lum[(cy + j) * w2 + cx + k] > mean;
                        if (a1 != pa) crossH++;
                        if (b1 != pb) crossV++;
                        pa = a1;
                        pb = b1;
                    }
                }
                boolean strokes = crossH >= cs * 1.3f && crossV >= cs * 1.0f;
                boolean twoTone = hi + lo >= m * 0.55f && hi >= m * 0.12f && lo >= m * 0.10f;
                if (e >= m * 0.22f && twoTone && strokes && s / m < 70) {   // texte noir, blanc ou coloré
                    for (int y = cy / 2; y < Math.min(h, (cy + cs) / 2 + 1); y++) {
                        for (int x = cx / 2; x < Math.min(w, (cx + cs) / 2 + 1); x++) out[y * w + x] = 1;
                    }
                }
            }
        }
        // Une ligne de texte = plusieurs cellules voisines : on ignore les cellules isolées (traits de dessin).
        int[] keep = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (out[y * w + x] == 0) continue;
                int nb = 0;
                for (int yy = Math.max(0, y - 4); yy <= Math.min(h - 1, y + 4); yy++) {
                    for (int xx = Math.max(0, x - 6); xx <= Math.min(w - 1, x + 6); xx++) nb += out[yy * w + xx];
                }
                if (nb >= 14) keep[y * w + x] = 1;
            }
        }
        return keep;
    }

    /**
     * Bulles et encarts : une zone unie claire (ou sombre), bien pleine, qui ne traverse pas toute
     * la largeur (sinon c'est une marge), et qui contient plusieurs petites taches du ton opposé
     * (des mots). Un œil n'a qu'une ou deux taches (iris, pupille) : pas confondu.
     * Toute la bulle est alors marquée comme texte, en plus des lettres trouvées par {@link #textMap}.
     */
    /** Lignes de marge entre deux cases (presque d'une seule couleur sur toute la largeur). */
    static boolean[] gutterRows(int[] argb, int w, int h) {
        boolean[] g = new boolean[h];
        for (int y = 0; y < h; y++) {
            int min = 255, max = 0, sMax = 0;
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x], r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, b = c & 0xFF;
                int L = (r * 3 + gg * 6 + b) / 10;
                min = Math.min(min, L);
                max = Math.max(max, L);
                sMax = Math.max(sMax, Math.max(r, Math.max(gg, b)) - Math.min(r, Math.min(gg, b)));
            }
            g[y] = max - min < 16 && sMax < 40;
        }
        return g;
    }

    static int[] bubbles(int[] text, int[] argb, int w, int h, boolean[] gutter) {
        int n = w * h;
        int[] lum = new int[n], sat = new int[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i], r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            lum[i] = (r * 3 + g * 6 + b) / 10;
            sat[i] = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        }
        int[] out = dilate(text, w, h, 3);
        int[] comp = new int[n];
        int[] queue = new int[n];
        int label = 0;
        for (int tone = 0; tone < 2; tone++) {
            java.util.Arrays.fill(comp, 0);
            for (int start = 0; start < n; start++) {
                if (comp[start] != 0 || gutter[start / w] || !flatTone(lum[start], sat[start], tone)) continue;
                label++;
                int qh = 0, qt = 0, minX = w, maxX = 0, minY = h, maxY = 0;
                queue[qt++] = start;
                comp[start] = label;
                while (qh < qt) {
                    int i = queue[qh++], x = i % w, y = i / w;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    int[] nbs = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                    for (int j : nbs) {
                        // une bulle s'arrête à la marge entre deux cases (même blanc, mais ce n'est plus la bulle)
                        if (j < 0 || comp[j] != 0 || gutter[j / w] || !flatTone(lum[j], sat[j], tone) || Math.abs(lum[j] - lum[i]) > 14) continue;
                        comp[j] = label;
                        queue[qt++] = j;
                    }
                }
                int area = qt;
                long lsum = 0, lsq = 0;
                for (int q = 0; q < qt; q++) {
                    lsum += lum[queue[q]];
                    lsq += (long) lum[queue[q]] * lum[queue[q]];
                }
                int mean = (int) (lsum / Math.max(1, qt));
                double std = Math.sqrt(Math.max(0, lsq / (double) Math.max(1, qt) - (double) mean * mean));
                if (std > 9) continue;                                     // fond de bulle : quasi uni (une ombre ne l'est pas)
                int bw = maxX - minX + 1, bh = maxY - minY + 1, box = bw * bh;
                if (area < n * 0.006f || area > n * 0.25f) continue;
                if (minX == 0 && maxX == w - 1) continue;                 // traverse toute la largeur : une marge
                if (area < box * 0.40f) continue;                          // pas une forme pleine
                // Taches du ton opposé à l'intérieur (les mots)
                int opp = 0;
                boolean[] seen = new boolean[box];
                int blobs = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        int i = y * w + x;
                        if (comp[i] == label || !opposite(lum[i], mean, tone) || isSkin(argb[i])) continue;
                        opp++;
                        int bi = (y - minY) * bw + (x - minX);
                        if (seen[bi]) continue;
                        // nouvelle tache : on la parcourt (8 voisins) pour mesurer sa taille
                        int size = 0, q0 = 0, q1 = 0, rimIn = 0, rimOut = 0;
                        int[] bq = new int[Math.min(box, 4096)];
                        bq[q1++] = bi;
                        seen[bi] = true;
                        while (q0 < q1) {
                            int k = bq[q0++], kx = k % bw, ky = k / bw;
                            size++;
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    int nx = kx + dx, ny = ky + dy;
                                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                                    int nb = ny * bw + nx, gi = (ny + minY) * w + nx + minX;
                                    if (comp[gi] == label) {
                                        rimIn++;            // bordée par le fond de la bulle
                                        continue;
                                    }
                                    boolean ink = opposite(lum[gi], mean, tone) && !isSkin(argb[gi]);
                                    if (!ink) {
                                        rimOut++;           // bordée par autre chose (peau, dessin…)
                                        continue;
                                    }
                                    if (seen[nb] || q1 >= bq.length) continue;
                                    seen[nb] = true;
                                    bq[q1++] = nb;
                                }
                            }
                        }
                        // un mot dans une bulle est entouré par le fond de la bulle ; un sourcil, par la peau
                        if (size >= 2 && size <= box * 0.03f && rimIn >= (rimIn + rimOut) * 0.7f) blobs++;
                    }
                }
                float oppF = opp / (float) box;
                if (blobs >= 3 && oppF >= 0.02f && oppF <= 0.45f) {
                    for (int y = Math.max(0, minY - 2); y <= Math.min(h - 1, maxY + 2); y++) {
                        for (int x = Math.max(0, minX - 2); x <= Math.min(w - 1, maxX + 2); x++) out[y * w + x] = 1;
                    }
                }
            }
        }
        return out;
    }

    /** Fond de bulle : blanc/crème (tone 0) ou noir (tone 1), peu coloré. */
    private static boolean flatTone(int lum, int sat, int tone) {
        return tone == 0 ? lum > 212 && sat < 42 : lum < 52 && sat < 46;
    }

    /** Encre sur ce fond : nettement plus sombre qu'une bulle claire, plus claire qu'une bulle noire. */
    private static boolean opposite(int lum, int bubbleMean, int tone) {
        return tone == 0 ? lum < bubbleMean - 70 : lum > bubbleMean + 60;
    }

    /** Teinte « peau » de manhwa (claire à mate) : orangé, peu saturé. */
    static boolean isSkin(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (r < 110 || r <= g || g <= b) return false;
        int max = r, min = b;
        float s = (max - min) / (float) max;
        float hue = 60f * (g - b) / (max - min);       // r est le max : teinte entre 0 et 60°
        return s > 0.10f && s < 0.62f && hue > 6 && hue < 45 && r - b > 22;
    }

    private static int[] erode(int[] m, int w, int h, int r) {
        int[] out = new int[m.length];
        for (int y = r; y < h - r; y++) {
            for (int x = r; x < w - r; x++) {
                boolean all = true;
                for (int yy = y - r; yy <= y + r && all; yy++) {
                    for (int xx = x - r; xx <= x + r; xx++) {
                        if (m[yy * w + xx] == 0) {
                            all = false;
                            break;
                        }
                    }
                }
                if (all) out[y * w + x] = 1;
            }
        }
        return out;
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
