package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/**
 * Données d'essai des tests de captures (jamais dans l'appli) : une bibliothèque de lectures, des
 * nouveautés, un cache « Pour toi », et des couvertures dessinées (dégradés) à la place des vraies.
 */
final class Fixtures {

    private Fixtures() {
    }

    static final String[] TITLES = {"Solo Leveling", "Omniscient Reader's Viewpoint", "Nano Machine",
            "The Greatest Estate Developer", "Return of the Mount Hua Sect", "Eleceed", "Tower of God", "Lookism"};
    private static final int[] LAST = {179, 210, 187, 148, 128, 320, 624, 512};
    private static final int[] SCORE = {84, 89, 83, 85, 86, 85, 80, 81};
    private static final String[] TAGS = {"Action, Fantasy", "Action, Fantasy", "Action, Murim", "Comédie, Fantasy",
            "Action, Murim", "Action, Comédie", "Fantasy, Drame", "Action, Drame"};
    static final int[][] PALETTE = {
            {0xFF1B1446, 0xFF7C3AED}, {0xFF0B2545, 0xFF3A86FF}, {0xFF3D0C11, 0xFFE5383B}, {0xFF102A1F, 0xFF2DD4BF},
            {0xFF2A1B05, 0xFFF59E0B}, {0xFF1E1B4B, 0xFFEC4899}, {0xFF0F172A, 0xFF64748B}, {0xFF231942, 0xFFA78BFA},
            {0xFF0C1E2C, 0xFF22D3EE}, {0xFF2D0A31, 0xFFD946EF}};

    static void seed(Context ctx) throws Exception {
        DbHelper db = DbHelper.get(ctx);
        long now = System.currentTimeMillis(), hour = 3_600_000L;
        for (int i = 0; i < TITLES.length; i++) {
            String t = TITLES[i];
            for (int k = 0; k < 14; k++) {
                db.insertRead(t, LAST[i] - 13 + k, now - (i * 20L + (13 - k) * 9L) * hour, "test",
                        "https://www.webtoons.com/fr/action/x/episode-" + (LAST[i] - 13 + k) + "/viewer?title_no=" + i);
            }
            db.setScore(t, SCORE[i]);
            db.setTags(t, TAGS[i]);
            Covers.save(ctx, t, cover(i, t, 460, 650));
        }
        for (int i : new int[]{0, 2, 5}) {
            DbHelper.Release r = new DbHelper.Release();
            r.title = TITLES[i];
            r.source = "MangaDex";
            r.lang = "EN";
            r.chapter = LAST[i] + 1;
            r.url = "https://www.webtoons.com/en/x/list?title_no=" + i;
            r.label = "WEBTOON";
            r.foundAt = now - hour;
            db.saveRelease(r);
        }
        seedDiscover(ctx);
    }

    /** Cache « Pour toi » : des fiches de catalogue avec couvertures (dans le cache disque de NetImages). */
    static void seedDiscover(Context ctx) throws Exception {
        String[][] fy = {{"Kill the Hero", "2020", "KR", "86", "Action"}, {"Swordmaster's Youngest Son", "2022", "KR", "85", "Fantasy"},
                {"Vinland Saga", "2005", "JP", "90", "Drama"}, {"The Boxer", "2019", "KR", "87", "Sports"},
                {"Lout of Count's Family", "2021", "KR", "84", "Fantasy"}, {"Blue Lock", "2018", "JP", "83", "Sports"}};
        JSONArray a = new JSONArray();
        for (int i = 0; i < fy.length; i++) {
            Discover.Media m = new Discover.Media();
            m.id = 900 + i;
            m.title = fy[i][0];
            m.names.add(m.title);
            m.year = Integer.parseInt(fy[i][1]);
            m.country = fy[i][2];
            m.score = Integer.parseInt(fy[i][3]);
            m.genres.add(fy[i][4]);
            m.because = "Comme " + TITLES[i % 3];
            m.cover = "https://fixtures.test/cover/" + i + ".jpg";
            m.color = "#2A2450";
            netImage(ctx, m.cover, cover(i + 2, m.title, 300, 430));
            a.put(m.toJson());
        }
        Discover.Profile p = Discover.profile(ctx);
        ctx.getSharedPreferences("discover", Context.MODE_PRIVATE).edit()
                .putString("for_you", a.toString())
                .putLong("for_you_at", System.currentTimeMillis())
                .putString("for_you_sig", p.series + ":" + p.ids.size())
                .commit();
    }

    /** Met une image dans le cache disque de NetImages (même clé : SHA-1 de l'adresse). */
    static void netImage(Context ctx, String url, Bitmap b) throws Exception {
        File dir = new File(ctx.getCacheDir(), "net_images");
        dir.mkdirs();
        byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder();
        for (byte x : h) s.append(String.format("%02x", x));
        try (FileOutputStream out = new FileOutputStream(new File(dir, s.toString()))) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
    }

    /** Une « couverture » dessinée : dégradé, halos et titre (aucune image réelle dans les tests). */
    static Bitmap cover(int i, String title, int w, int h) {
        int[] c = PALETTE[i % PALETTE.length];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, c[1], c[0], Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.7f, h * 0.3f, w * 0.6f, 0x88FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(w * 0.7f, h * 0.3f, w * 0.6f, p);
        p.setShader(null);
        p.setColor(0x33000000);
        cv.drawCircle(w * 0.25f, h * 0.75f, w * 0.5f, p);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(w * 0.11f);
        String[] words = title.split(" ");
        float y = h * 0.82f - (words.length - 1) * w * 0.12f;
        for (String word : words) {
            cv.drawText(word.toUpperCase(), w * 0.08f, y, p);
            y += w * 0.12f;
        }
        return b;
    }
}
