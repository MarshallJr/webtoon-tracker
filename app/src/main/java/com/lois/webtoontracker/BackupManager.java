package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sauvegarde / restauration.
 *  - Sauvegarde auto : un fichier JSON choisi une fois par l'utilisateur (hors de l'appli,
 *    donc il survit à une désinstallation), réécrit à chaque changement.
 *  - Restauration : depuis ce JSON, OU depuis le fichier note Markdown (webtoons.md)
 *    des anciennes versions. La restauration FUSIONNE : rien n'est écrasé ni dupliqué.
 */
public final class BackupManager {

    private static final String TAG = "Backup";
    public static final String KEY_BACKUP_URI = "backup_uri";
    public static final String KEY_BACKUP_AT = "backup_at";

    private BackupManager() {}

    public static String toJson(Context ctx) throws Exception {
        DbHelper db = DbHelper.get(ctx);
        JSONArray reads = new JSONArray();
        for (DbHelper.Series s : db.getSeries()) {
            for (DbHelper.Entry e : db.getHistory(s.title)) {
                JSONObject o = new JSONObject();
                o.put("title", s.title);
                o.put("chapter", e.chapter);
                o.put("readAt", e.readAt);
                o.put("source", e.source == null ? "" : e.source);
                if (e.url != null) o.put("url", e.url);
                if (e.times > 1) o.put("times", e.times);
                if (e.lastAt > 0) o.put("lastAt", e.lastAt);
                reads.put(o);
            }
        }
        SharedPreferences p = ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE);
        JSONObject settings = new JSONObject();
        settings.put(ReadingTrackerService.KEY_NOTIFY_MODE,
                p.getInt(ReadingTrackerService.KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART));
        settings.put(ReadingTrackerService.KEY_NOTIFY_ALREADY,
                p.getBoolean(ReadingTrackerService.KEY_NOTIFY_ALREADY, true));

        JSONArray metas = new JSONArray();
        for (DbHelper.Meta m : db.getAllMeta()) {
            JSONObject o = new JSONObject();
            o.put("title", m.title);
            o.put("tags", m.tags == null ? "" : m.tags);
            o.put("lastUrl", m.lastUrl == null ? "" : m.lastUrl);
            o.put("lastPkg", m.lastPkg == null ? "" : m.lastPkg);
            o.put("titleNo", m.titleNo == null ? "" : m.titleNo);
            o.put("rssBase", m.rssBase == null ? "" : m.rssBase);
            o.put("rssLastEp", m.rssLastEp);
            o.put("follow", m.follow);
            o.put("editions", m.editionsOn);
            o.put("rating", m.rating);
            o.put("note", m.note == null ? "" : m.note);
            metas.put(o);
        }

        JSONObject root = new JSONObject();
        root.put("app", "webtoon-tracker");
        root.put("format", 2);
        root.put("meta", metas);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("settings", settings);
        root.put("reads", reads);
        return root.toString(1);
    }

    /** Écrit une sauvegarde complète dans le fichier donné. */
    public static void writeTo(Context ctx, Uri uri) throws Exception {
        byte[] bytes = toJson(ctx).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Fichier inaccessible");
            out.write(bytes);
        }
    }

    /** Sauvegarde auto (si activée). Appelée après chaque changement. */
    public static void autoBackup(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE);
        String uri = p.getString(KEY_BACKUP_URI, null);
        if (uri == null) return;
        try {
            writeTo(ctx, Uri.parse(uri));
            p.edit().putLong(KEY_BACKUP_AT, System.currentTimeMillis()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Sauvegarde auto impossible", e);
        }
    }

    /** @return nombre de chapitres ajoutés (les doublons sont ignorés). */
    public static int restore(Context ctx, Uri uri) throws Exception {
        String text = readAll(ctx, uri).trim();
        int added = text.startsWith("{") ? restoreJson(ctx, text) : restoreMarkdown(ctx, text);
        NoteWriter.write(ctx); // réécrit aussi la sauvegarde auto
        WidgetProvider.updateAll(ctx);
        return added;
    }

    private static int restoreJson(Context ctx, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        DbHelper db = DbHelper.get(ctx);
        JSONArray reads = root.getJSONArray("reads");
        int added = 0;
        for (int i = 0; i < reads.length(); i++) {
            JSONObject o = reads.getJSONObject(i);
            String url = o.optString("url", "");
            if (db.insertRead(o.getString("title"), o.getDouble("chapter"),
                    o.optLong("readAt", System.currentTimeMillis()), o.optString("source", "sauvegarde"),
                    url.isEmpty() ? null : url)) {
                added++;
                if (o.has("times") || o.has("lastAt")) {
                    db.restoreReadStats(o.getString("title"), o.getDouble("chapter"),
                            o.optInt("times", 1), o.optLong("lastAt", 0));
                }
            }
        }
        JSONArray metas = root.optJSONArray("meta");
        if (metas != null) {
            for (int i = 0; i < metas.length(); i++) {
                JSONObject o = metas.getJSONObject(i);
                DbHelper.Meta m = db.getMeta(o.getString("title"));
                if (m == null) {
                    m = new DbHelper.Meta();
                    m.title = o.getString("title");
                }
                m.tags = Titles.normalizeTags((m.tags == null ? "" : m.tags) + "," + o.optString("tags"));
                if (m.lastUrl == null) m.lastUrl = emptyToNull(o.optString("lastUrl"));
                if (m.lastPkg == null) m.lastPkg = emptyToNull(o.optString("lastPkg"));
                if (m.titleNo == null) {
                    m.titleNo = emptyToNull(o.optString("titleNo"));
                    m.rssBase = emptyToNull(o.optString("rssBase"));
                    m.rssLastEp = o.optLong("rssLastEp", 0);
                }
                if (o.optBoolean("follow", false)) m.follow = true;
                if (o.optBoolean("editions", false)) m.editionsOn = true;   // « Langues & sources »
                if (m.rating == 0) m.rating = o.optInt("rating", 0);
                if (m.note == null || m.note.isEmpty()) m.note = emptyToNull(o.optString("note"));
                db.saveMeta(m);
            }
        }
        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(ReadingTrackerService.KEY_NOTIFY_MODE,
                            s.optInt(ReadingTrackerService.KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART))
                    .putBoolean(ReadingTrackerService.KEY_NOTIFY_ALREADY,
                            s.optBoolean(ReadingTrackerService.KEY_NOTIFY_ALREADY, true))
                    .apply();
        }
        return added;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // "- Chapitre 12 — 18/11/2026 21:40 _(webtoons.com)_"
    private static final Pattern MD_ENTRY = Pattern.compile(
            "^- Chapitre (\\S+) — (\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})(?: _\\((.*)\\)_)?\\s*$");

    private static int restoreMarkdown(Context ctx, String text) throws Exception {
        DbHelper db = DbHelper.get(ctx);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        String title = null;
        int added = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                title = line.substring(3).trim();
                continue;
            }
            if (title == null) continue;
            Matcher m = MD_ENTRY.matcher(line);
            if (!m.matches()) continue;
            double chapter;
            try {
                chapter = Double.parseDouble(m.group(1).replace(',', '.'));
            } catch (NumberFormatException e) {
                continue;
            }
            long at;
            try {
                Date d = fmt.parse(m.group(2));
                at = d == null ? System.currentTimeMillis() : d.getTime();
            } catch (Exception e) {
                at = System.currentTimeMillis();
            }
            String source = m.group(3) == null ? "sauvegarde" : m.group(3);
            if (db.insertRead(title, chapter, at, source)) added++;
        }
        return added;
    }

    private static String readAll(Context ctx, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Fichier illisible");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static String describeAuto(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(NoteWriter.PREFS, Context.MODE_PRIVATE);
        String uri = p.getString(KEY_BACKUP_URI, null);
        if (uri == null) return "Sauvegarde auto : désactivée";
        long at = p.getLong(KEY_BACKUP_AT, 0);
        String name = Uri.parse(uri).getLastPathSegment();
        return "Sauvegarde auto : activée ✅\n" + name
                + (at > 0 ? "\nDernière : " + new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date(at)) : "");
    }
}
