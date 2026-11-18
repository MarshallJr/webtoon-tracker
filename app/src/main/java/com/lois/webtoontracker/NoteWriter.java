package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Génère le fichier note (Markdown) récapitulant toutes les lectures.
 * - Toujours écrit dans Android/data/com.lois.webtoontracker/files/webtoons.md
 * - Et, si l'utilisateur a choisi un fichier (Documents, Obsidian, Drive…), dedans aussi.
 */
public final class NoteWriter {

    private static final String TAG = "NoteWriter";
    public static final String PREFS = "settings";
    public static final String KEY_NOTE_URI = "note_uri";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private NoteWriter() {}

    public static void writeAsync(Context ctx) {
        Context app = ctx.getApplicationContext();
        IO.execute(() -> write(app));
    }

    public static String buildMarkdown(Context ctx) {
        DbHelper db = DbHelper.get(ctx);
        SimpleDateFormat day = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
        SimpleDateFormat full = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);

        List<DbHelper.Series> series = db.getSeries();
        StringBuilder sb = new StringBuilder();
        sb.append("# 📚 Mes webtoons lus\n\n");
        sb.append("_Mis à jour le ").append(full.format(new Date())).append("_  \n");
        sb.append("**").append(series.size()).append(" séries · ")
                .append(db.totalChapters()).append(" chapitres**\n\n");

        sb.append("| Série | Dernier chapitre | Lu le | Chapitres lus |\n");
        sb.append("|---|---|---|---|\n");
        for (DbHelper.Series s : series) {
            sb.append("| ").append(s.title.replace("|", "/"))
                    .append(" | ").append(Detector.formatChapter(s.lastChapter))
                    .append(" | ").append(day.format(new Date(s.lastReadAt)))
                    .append(" | ").append(s.count).append(" |\n");
        }

        sb.append("\n---\n");
        for (DbHelper.Series s : series) {
            sb.append("\n## ").append(s.title).append("\n");
            for (DbHelper.Entry e : db.getHistory(s.title)) {
                sb.append("- Chapitre ").append(Detector.formatChapter(e.chapter))
                        .append(" — ").append(full.format(new Date(e.readAt)));
                if (e.source != null && !e.source.isEmpty()) sb.append(" _(").append(e.source).append(")_");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static synchronized void write(Context ctx) {
        byte[] bytes = buildMarkdown(ctx).getBytes(StandardCharsets.UTF_8);

        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, "webtoons.md"))) {
                out.write(bytes);
            } catch (Exception e) {
                Log.w(TAG, "Écriture locale impossible", e);
            }
        }

        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uri = p.getString(KEY_NOTE_URI, null);
        if (uri != null) {
            try (OutputStream out = ctx.getContentResolver().openOutputStream(Uri.parse(uri), "wt")) {
                if (out != null) out.write(bytes);
            } catch (Exception e) {
                Log.w(TAG, "Écriture dans le fichier choisi impossible", e);
            }
        }
    }
}
