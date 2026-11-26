package com.lois.webtoontracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Couvertures de « Découvrir » : téléchargées une fois (cache disque), gardées en mémoire (cache
 * limité), réduites à la taille d'affichage. En attendant : la couleur dominante de la couverture.
 */
final class NetImages {

    private NetImages() {
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** Affiche l'image de l'adresse dans la vue (couleur d'attente, fondu à l'arrivée). */
    static void load(ImageView v, String url, String color) {
        v.setTag(R.id.net_url, url);
        int placeholder = 0xFF2A2450;
        try {
            if (color != null) placeholder = Color.parseColor(color);
        } catch (Exception ignored) {
        }
        if (url == null) {
            v.setImageDrawable(new ColorDrawable(placeholder));
            return;
        }
        Bitmap hit = MEM.get(url);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(new ColorDrawable(placeholder));
        Context app = v.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = fetch(app, url);
            if (b == null) return;
            MEM.put(url, b);
            v.post(() -> {
                if (!url.equals(v.getTag(R.id.net_url))) return;   // vue réutilisée entre-temps
                v.setImageBitmap(b);
                if (Anim.on()) {
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(220).start();
                }
            });
        });
    }

    private static Bitmap fetch(Context ctx, String url) {
        try {
            File dir = new File(ctx.getCacheDir(), "net_images");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, sha1(url));
            byte[] data;
            if (f.exists() && f.length() > 0) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
            } else {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "WebtoonTracker (Android; usage personnel)");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                } finally {
                    c.disconnect();
                }
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            // Réduite à ~360 px de large (largeur d'une couverture à l'écran), en 565 : 2× moins de mémoire.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 360) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : h) b.append(String.format("%02x", x));
        return b.toString();
    }
}
