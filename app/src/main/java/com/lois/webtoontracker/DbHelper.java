package com.lois.webtoontracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Stockage local (SQLite) de l'historique de lecture. */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "webtoons.db";
    private static final int DB_VERSION = 1;
    private static DbHelper instance;

    public static synchronized DbHelper get(Context ctx) {
        if (instance == null) instance = new DbHelper(ctx.getApplicationContext());
        return instance;
    }

    private DbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reads (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "chapter REAL NOT NULL," +
                "read_at INTEGER NOT NULL," +
                "source TEXT)");
        db.execSQL("CREATE UNIQUE INDEX idx_reads_unique ON reads(title COLLATE NOCASE, chapter)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Rien pour l'instant (v1).
    }

    // ------------------------------------------------------------------ Modèles

    public static final class Series {
        public final String title;
        public final double lastChapter;
        public final int count;
        public final long lastReadAt;

        Series(String title, double lastChapter, int count, long lastReadAt) {
            this.title = title;
            this.lastChapter = lastChapter;
            this.count = count;
            this.lastReadAt = lastReadAt;
        }
    }

    public static final class Entry {
        public final long id;
        public final double chapter;
        public final long readAt;
        public final String source;

        Entry(long id, double chapter, long readAt, String source) {
            this.id = id;
            this.chapter = chapter;
            this.readAt = readAt;
            this.source = source;
        }
    }

    // ------------------------------------------------------------------ Requêtes

    /** @return true si c'est un nouveau chapitre (pas déjà enregistré). */
    public synchronized boolean insertRead(String title, double chapter, long readAt, String source) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("chapter", chapter);
        v.put("read_at", readAt);
        v.put("source", source);
        long id = getWritableDatabase().insertWithOnConflict("reads", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    public List<Series> getSeries() {
        List<Series> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, MAX(chapter), COUNT(*), MAX(read_at) AS last FROM reads " +
                        "GROUP BY title COLLATE NOCASE ORDER BY last DESC", null)) {
            while (c.moveToNext()) {
                out.add(new Series(c.getString(0), c.getDouble(1), c.getInt(2), c.getLong(3)));
            }
        }
        return out;
    }

    public List<Entry> getHistory(String title) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, chapter, read_at, source FROM reads WHERE title = ? COLLATE NOCASE " +
                        "ORDER BY chapter DESC", new String[]{title})) {
            while (c.moveToNext()) {
                out.add(new Entry(c.getLong(0), c.getDouble(1), c.getLong(2), c.getString(3)));
            }
        }
        return out;
    }

    public int totalChapters() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reads", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countSince(long since) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM reads WHERE read_at >= ?", new String[]{String.valueOf(since)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void deleteSeries(String title) {
        getWritableDatabase().delete("reads", "title = ? COLLATE NOCASE", new String[]{title});
    }

    public synchronized void deleteEntry(long id) {
        getWritableDatabase().delete("reads", "id = ?", new String[]{String.valueOf(id)});
    }
}
