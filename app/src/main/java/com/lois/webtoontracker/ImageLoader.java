package com.lois.webtoontracker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mini chargeur d'images web (recommandations) avec cache mémoire. */
public final class ImageLoader {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private ImageLoader() {}

    public static void load(String url, ImageView target) {
        if (url == null) return;
        target.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
                if (b == null) return;
                CACHE.put(url, b);
                MAIN.post(() -> {
                    if (url.equals(target.getTag())) {
                        target.setImageBitmap(b);
                        target.setAlpha(0f);
                        target.animate().alpha(1f).setDuration(250).start();
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }
}
