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
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Données d'essai des tests de captures (jamais dans l'appli) : une bibliothèque de lectures, des
 * nouveautés, une lecture en cours, un cache « Pour toi ». Les couvertures sont les vraies affiches
 * AniList quand le réseau répond (une seule requête), sinon des dégradés dessinés.
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

    private static final String[][] FOR_YOU = {{"Kill the Hero", "2020", "KR", "86", "Action"},
            {"Swordmaster's Youngest Son", "2022", "KR", "85", "Fantasy"}, {"Vinland Saga", "2005", "JP", "90", "Drama"},
            {"The Boxer", "2019", "KR", "87", "Sports"}, {"Lout of Count's Family", "2021", "KR", "84", "Fantasy"},
            {"Blue Lock", "2018", "JP", "83", "Sports"}};

    static void seed(Context ctx) throws Exception {
        String[] all = new String[TITLES.length + FOR_YOU.length];
        System.arraycopy(TITLES, 0, all, 0, TITLES.length);
        for (int i = 0; i < FOR_YOU.length; i++) all[TITLES.length + i] = FOR_YOU[i][0];
        JSONObject real = anilist(all);
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
            Bitmap poster = real == null ? null : download(real.optJSONObject("a" + i));
            if (poster != null) Covers.savePoster(ctx, t, poster);
            else Covers.save(ctx, t, cover(i, t, 460, 650));
        }
        Progress.save(ctx, TITLES[0], LAST[0], 0.72f);   // « arrêté à 72 % » sur la carte Reprendre
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
        seedDiscover(ctx, real);
    }

    /** Cache « Pour toi » : des fiches de catalogue avec couvertures (dans le cache disque de NetImages). */
    static void seedDiscover(Context ctx, JSONObject real) throws Exception {
        String[][] fy = FOR_YOU;
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
            JSONObject r = real == null ? null : real.optJSONObject("a" + (TITLES.length + i));
            Bitmap b = download(r);
            if (b != null) {
                JSONObject img = r.optJSONObject("coverImage");
                m.cover = img.optString("extraLarge", img.optString("large"));
                m.color = img.optString("color", m.color);
                if (r.optInt("averageScore") > 0) m.score = r.optInt("averageScore");
            }
            netImage(ctx, m.cover, b != null ? b : cover(i + 2, m.title, 300, 430));
            a.put(m.toJson());
        }
        Discover.Profile p = Discover.profile(ctx);
        ctx.getSharedPreferences("discover", Context.MODE_PRIVATE).edit()
                .putString("for_you", a.toString())
                .putLong("for_you_at", System.currentTimeMillis())
                .putString("for_you_sig", p.series + ":" + p.ids.size())
                .commit();
    }

    /** Cache disque partagé par tous les tests (une seule requête AniList par build, pas de limite atteinte). */
    private static final File NET_CACHE = new File("build/fixture-net");
    private static JSONObject anilistMemo;

    /** Fiches AniList des titres (alias a0, a1…) en une requête ; null sans réseau. */
    static synchronized JSONObject anilist(String[] titles) {
        if (anilistMemo != null) return anilistMemo;
        File memo = new File(NET_CACHE, "anilist.json");
        try {
            if (memo.exists()) {
                anilistMemo = new JSONObject(new String(java.nio.file.Files.readAllBytes(memo.toPath()), StandardCharsets.UTF_8));
                return anilistMemo;
            }
        } catch (Exception ignored) {
        }
        try {
            StringBuilder q = new StringBuilder("query{");
            for (int i = 0; i < titles.length; i++) {
                q.append('a').append(i).append(":Media(search:").append(JSONObject.quote(titles[i]))
                        .append(",type:MANGA){id averageScore coverImage{extraLarge large color}}");
            }
            q.append('}');
            HttpURLConnection c = (HttpURLConnection) new URL("https://graphql.anilist.co").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "WebtoonTracker-tests");
            try (OutputStream o = c.getOutputStream()) {
                o.write(new JSONObject().put("query", q.toString()).toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) {
                System.out.println("Fixtures : AniList HTTP " + code);
                return null;
            }
            JSONObject data = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).optJSONObject("data");
            if (data != null) {
                NET_CACHE.mkdirs();
                try (FileOutputStream out = new FileOutputStream(memo)) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                anilistMemo = data;
            }
            return data;
        } catch (Exception e) {
            System.out.println("Fixtures : AniList injoignable " + e);
            return null;
        }
    }

    /** L'affiche d'une fiche AniList, ou null. */
    static Bitmap download(JSONObject media) {
        if (media == null || media.optJSONObject("coverImage") == null) return null;
        JSONObject img = media.optJSONObject("coverImage");
        String url = img.optString("extraLarge", img.optString("large", ""));
        if (url.isEmpty()) return null;
        try {
            File f = new File(NET_CACHE, Integer.toHexString(url.hashCode()) + ".img");
            byte[] data;
            if (f.exists()) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
            } else {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "WebtoonTracker-tests");
                data = readAll(c.getInputStream());
                NET_CACHE.mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            System.out.println("Fixtures : affiche injoignable " + url + " " + e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
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
