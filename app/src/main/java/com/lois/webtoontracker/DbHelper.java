package com.lois.webtoontracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Stockage local (SQLite) de l'historique de lecture + infos par série. */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "webtoons.db";
    private static final int DB_VERSION = 8;
    private static DbHelper instance;

    public static synchronized DbHelper get(Context ctx) {
        if (instance == null) instance = new DbHelper(ctx.getApplicationContext());
        return instance;
    }

    private final Context appCtx;

    private DbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        appCtx = ctx;
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
        createMeta(db);
        upgradeTo3(db);
        upgradeTo4(db);
        upgradeTo5(db);
        upgradeTo6(db);
        upgradeTo7(db);
        upgradeTo8(db);
    }

    /** v8 : chapitres payants repérés sur les sites où tu lis (pour te prévenir quand ils deviennent gratuits). */
    private void upgradeTo8(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS locks (" +
                "title TEXT NOT NULL COLLATE NOCASE," +
                "host TEXT NOT NULL," +
                "chapter REAL NOT NULL," +
                "url TEXT," +
                "free_at INTEGER DEFAULT 0," +
                "seen_at INTEGER DEFAULT 0," +
                "checked_at INTEGER DEFAULT 0," +
                "freed_at INTEGER DEFAULT 0," +
                "notified INTEGER DEFAULT 0," +
                "PRIMARY KEY (title, host, chapter))");
    }

    /**
     * v7 : éditions officielles / autorisées de chaque série, dans toutes les langues (« Langues & sources »).
     * Les chapitres hébergés par MangaDex (traductions de fans, non officielles) ne sont plus proposés.
     */
    private void upgradeTo7(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE series_meta ADD COLUMN editions_on INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN editions_at INTEGER DEFAULT 0");
        db.execSQL("CREATE TABLE IF NOT EXISTS editions (" +
                "title TEXT NOT NULL COLLATE NOCASE," +
                "url TEXT NOT NULL," +
                "platform TEXT," +
                "lang TEXT," +
                "category INTEGER NOT NULL," +
                "evidence TEXT," +
                "proof TEXT," +
                "latest REAL DEFAULT 0," +
                "free_upto REAL DEFAULT 0," +
                "model TEXT," +
                "chapter_tpl TEXT," +
                "ep_offset INTEGER DEFAULT 0," +
                "chapters TEXT," +
                "user_added INTEGER DEFAULT 0," +
                "checked_at INTEGER DEFAULT 0," +
                "PRIMARY KEY (title, url))");
        db.execSQL("DELETE FROM releases WHERE source = 'MangaDex'");
    }

    /** v6 : lien de chaque chapitre (pour le rouvrir), nombre de lectures et dernière visite. */
    private void upgradeTo6(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE reads ADD COLUMN url TEXT");
        db.execSQL("ALTER TABLE reads ADD COLUMN last_at INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE reads ADD COLUMN times INTEGER DEFAULT 1");
    }

    /** v5 : alias (tous les noms d'une série → un seul titre) + note publique (AniList). */
    private void upgradeTo5(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE series_meta ADD COLUMN score INTEGER DEFAULT 0");
        db.execSQL("CREATE TABLE IF NOT EXISTS aliases (alias_key TEXT PRIMARY KEY, title TEXT NOT NULL)");
    }

    /** v4 : cloche (suivi des nouveaux chapitres), note perso, fiche détaillée en cache. */
    private void upgradeTo4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE series_meta ADD COLUMN follow INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN rating INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN note TEXT");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN details_json TEXT");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN details_at INTEGER DEFAULT 0");
    }

    /** v3 : sorties de chapitres trouvées sur les sources (MangaDex, MangaUpdates, Webtoon). */
    private void upgradeTo3(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE series_meta ADD COLUMN mdx_id TEXT");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN mu_id TEXT");
        db.execSQL("ALTER TABLE series_meta ADD COLUMN notified_ch REAL DEFAULT 0");
        db.execSQL("CREATE TABLE IF NOT EXISTS releases (" +
                "title TEXT NOT NULL COLLATE NOCASE," +
                "source TEXT NOT NULL," +
                "lang TEXT NOT NULL," +
                "chapter REAL NOT NULL," +
                "url TEXT," +
                "label TEXT," +
                "found_at INTEGER," +
                "PRIMARY KEY (title, source, lang))");
    }

    private void createMeta(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS series_meta (" +
                "title TEXT PRIMARY KEY COLLATE NOCASE," +
                "tags TEXT," +
                "last_url TEXT," +
                "last_pkg TEXT," +
                "title_no TEXT," +
                "rss_base TEXT," +
                "rss_last_ep INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createMeta(db); // v1 → v2 : on garde toutes les lectures
        if (oldV < 3) upgradeTo3(db);
        if (oldV < 4) upgradeTo4(db);
        if (oldV < 5) upgradeTo5(db);
        if (oldV < 6) upgradeTo6(db);
        if (oldV < 7) upgradeTo7(db);
        if (oldV < 8) upgradeTo8(db);
    }

    // ------------------------------------------------------------------ Modèles

    public static final class Series {
        public final String title;
        public final double lastChapter;
        public final int count;
        public final long lastReadAt;
        public final String tags;
        public int score; // note publique /100 (AniList), 0 = inconnue

        Series(String title, double lastChapter, int count, long lastReadAt, String tags) {
            this.title = title;
            this.lastChapter = lastChapter;
            this.count = count;
            this.lastReadAt = lastReadAt;
            this.tags = tags == null ? "" : tags;
        }
    }

    public static final class Entry {
        public final long id;
        public final double chapter;
        public final long readAt;
        public final String source;
        /** Lien exact du chapitre (null si lu avant la v4.3 ou dans une appli sans lien). */
        public String url;
        /** Dernière fois que tu l'as ouvert (0 = jamais rouvert). */
        public long lastAt;
        /** Nombre de lectures (1 = lu une fois). */
        public int times = 1;

        Entry(long id, double chapter, long readAt, String source) {
            this.id = id;
            this.chapter = chapter;
            this.readAt = readAt;
            this.source = source;
        }
    }

    public static final class Meta {
        public String title, tags, lastUrl, lastPkg, titleNo, rssBase, mdxId, muId;
        public long rssLastEp;
        public double notifiedCh;
        public boolean follow;
        public int rating;
        public String note, detailsJson;
        public long detailsAt;
        public int score;
        /** « Langues & sources » activé pour cette série, et dernière recherche des éditions. */
        public boolean editionsOn;
        public long editionsAt;
    }

    /** Un chapitre disponible quelque part (site + langue). */
    public static final class Release {
        public String title, source, lang, url, label;
        public double chapter;
        public long foundAt;
    }

    /**
     * Une édition de la série : une plateforme, une langue. Seules les catégories 1 (officielle) et
     * 2 (autorisée) sont enregistrées ; les sources inconnues ou non autorisées ne le sont jamais.
     */
    public static final class Edition {
        public String title, url, platform, lang, evidence, proof, model, chapterTpl, chapters;
        public int category, epOffset;
        public double latest, freeUpto;   // 0 = inconnu
        public boolean userAdded;
        public long checkedAt;

        /** Adresse du chapitre dans cette édition, si elle se devine (sinon null). */
        public String chapterUrl(double ch) {
            if (chapters != null && !chapters.isEmpty()) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(chapters);
                    String u = o.optString(Detector.formatChapter(ch), "");
                    if (!u.isEmpty()) return u;
                } catch (Exception ignored) {
                }
            }
            if (chapterTpl == null || ch != Math.floor(ch) || ch < 1 || (latest > 0 && ch > latest)) return null;
            long ep = (long) ch + epOffset;
            return ep < 1 ? null : chapterTpl.replace("{e}", String.valueOf(ep));
        }

        /** Le chapitre existe-t-il dans cette édition (numéro connu) ? */
        public boolean has(double ch) {
            return chapterUrl(ch) != null || (latest > 0 && ch <= latest);
        }

        /** Gratuit pour ce chapitre : true / false / null (inconnu). */
        public Boolean freeFor(double ch) {
            if (freeUpto > 0 && ch <= freeUpto) return Boolean.TRUE;
            if (SourceRegistry.FREE.equals(model)) return Boolean.TRUE;
            if (SourceRegistry.PAID.equals(model)) return Boolean.FALSE;
            return null;
        }
    }

    /** Un chapitre payant repéré sur un site (ou une appli) où tu lis. */
    public static final class Lock {
        public String title, host, url;
        public double chapter;
        public long freeAt, seenAt, checkedAt, freedAt;
        public boolean notified;

        public boolean active() {
            return freedAt == 0;
        }
    }

    // ------------------------------------------------------------------ Lectures

    /** @return true si c'est un nouveau chapitre (pas déjà enregistré). */
    public synchronized boolean insertRead(String title, double chapter, long readAt, String source) {
        return insertRead(title, chapter, readAt, source, null);
    }

    public synchronized boolean insertRead(String title, double chapter, long readAt, String source, String url) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("chapter", chapter);
        v.put("read_at", readAt);
        v.put("source", source);
        if (url != null) v.put("url", url);
        v.put("last_at", readAt);
        long id = getWritableDatabase().insertWithOnConflict("reads", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    /**
     * Chapitre déjà lu, rouvert : compte une relecture (au plus une par demi-heure, pour ne pas
     * compter chaque aller-retour) et complète son lien s'il manquait.
     */
    public synchronized void markReopened(String title, double chapter, long now, String url, long gapMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE reads SET times = IFNULL(times, 1) + 1 WHERE title = ? COLLATE NOCASE AND chapter = ? " +
                        "AND MAX(IFNULL(last_at, 0), read_at) < ?",
                new Object[]{title, chapter, now - gapMs});
        db.execSQL("UPDATE reads SET last_at = ? WHERE title = ? COLLATE NOCASE AND chapter = ?",
                new Object[]{now, title, chapter});
        if (url != null) {   // le site où tu l'as relu en dernier
            db.execSQL("UPDATE reads SET url = ? WHERE title = ? COLLATE NOCASE AND chapter = ?",
                    new Object[]{url, title, chapter});
        }
    }

    /** Chapitres lus avant l'appli : comptés comme lus, mais hors des statistiques datées. */
    public static final String SOURCE_BEFORE = "avant";
    private static final String NOT_BEFORE = " IFNULL(source, '') != '" + SOURCE_BEFORE + "' ";

    /** « J'avais déjà lu ces chapitres avant l'appli » : marque from → to comme lus (entiers). */
    public synchronized int markRangeRead(String title, int from, int to, long readAt) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            for (int c = from; c <= to; c++) {
                ContentValues v = new ContentValues();
                v.put("title", title);
                v.put("chapter", (double) c);
                v.put("read_at", readAt);
                v.put("source", SOURCE_BEFORE);
                if (db.insertWithOnConflict("reads", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    public synchronized void restoreReadStats(String title, double chapter, int times, long lastAt) {
        getWritableDatabase().execSQL("UPDATE reads SET times = ?, last_at = ? WHERE title = ? COLLATE NOCASE AND chapter = ?",
                new Object[]{Math.max(1, times), lastAt, title, chapter});
    }

    /** Plus petit chapitre lu (0 si aucun). */
    public double firstRead(String title) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(chapter) FROM reads WHERE title = ? COLLATE NOCASE", new String[]{title})) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        }
    }

    public List<Series> getSeries() {
        List<Series> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT r.title, MAX(r.chapter), COUNT(*), MAX(r.read_at) AS last, m.tags, m.score FROM reads r " +
                        "LEFT JOIN series_meta m ON m.title = r.title COLLATE NOCASE " +
                        "GROUP BY r.title COLLATE NOCASE ORDER BY last DESC", null)) {
            while (c.moveToNext()) {
                Series se = new Series(c.getString(0), c.getDouble(1), c.getInt(2), c.getLong(3), c.getString(4));
                se.score = c.isNull(5) ? 0 : c.getInt(5);
                out.add(se);
            }
        }
        return out;
    }

    public List<Entry> getHistory(String title) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, chapter, read_at, source, url, last_at, times FROM reads WHERE title = ? COLLATE NOCASE " +
                        "ORDER BY chapter DESC", new String[]{title})) {
            while (c.moveToNext()) {
                Entry e = new Entry(c.getLong(0), c.getDouble(1), c.getLong(2), c.getString(3));
                e.url = c.isNull(4) ? null : c.getString(4);
                e.lastAt = c.isNull(5) ? 0 : c.getLong(5);
                e.times = c.isNull(6) ? 1 : Math.max(1, c.getInt(6));
                out.add(e);
            }
        }
        return out;
    }

    /** @return la date de lecture de ce chapitre, ou -1 s'il n'a jamais été lu. */
    public long getReadAt(String title, double chapter) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT read_at FROM reads WHERE title = ? COLLATE NOCASE AND chapter = ? LIMIT 1",
                new String[]{title, String.valueOf(chapter)})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    /** Source d'un chapitre lu (« avant » = lu avant l'appli), ou null. */
    public String getSource(String title, double chapter) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT source FROM reads WHERE title = ? COLLATE NOCASE AND chapter = ? LIMIT 1",
                new String[]{title, String.valueOf(chapter)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public boolean seriesExists(String title) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM reads WHERE title = ? COLLATE NOCASE LIMIT 1", new String[]{title})) {
            return c.moveToFirst();
        }
    }

    public int totalChapters() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM reads", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countSince(long since) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM reads WHERE read_at >= ? AND" + NOT_BEFORE, new String[]{String.valueOf(since)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<Long> allReadTimes() {
        List<Long> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT read_at FROM reads WHERE" + NOT_BEFORE + "ORDER BY read_at", null)) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    /** Dernières lectures, toutes séries confondues : {titre, chapitre, date}. */
    public List<Object[]> recentReads(int limit) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, chapter, read_at FROM reads WHERE" + NOT_BEFORE + "ORDER BY read_at DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new Object[]{c.getString(0), c.getDouble(1), c.getLong(2)});
        }
        return out;
    }

    public synchronized void deleteSeries(String title) {
        ChapterThumbs.deleteAll(appCtx, title);
        SQLiteDatabase db = getWritableDatabase();
        db.delete("releases", "title = ? COLLATE NOCASE", new String[]{title});
        db.delete("editions", "title = ? COLLATE NOCASE", new String[]{title});
        db.delete("locks", "title = ? COLLATE NOCASE", new String[]{title});
        db.delete("reads", "title = ? COLLATE NOCASE", new String[]{title});
        db.delete("series_meta", "title = ? COLLATE NOCASE", new String[]{title});
    }

    public synchronized void deleteEntry(long id) {
        getWritableDatabase().delete("reads", "id = ?", new String[]{String.valueOf(id)});
    }

    /** Fusionne la série "from" dans "into" (chapitres, tags, liens). Les doublons disparaissent. */
    public synchronized void mergeSeries(String from, String into) {
        if (from.equalsIgnoreCase(into)) return;
        ChapterThumbs.move(appCtx, from, into);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE OR IGNORE reads SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{into, from});
            db.delete("releases", "title = ? COLLATE NOCASE", new String[]{from});
            db.execSQL("UPDATE OR IGNORE editions SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{into, from});
            db.delete("editions", "title = ? COLLATE NOCASE", new String[]{from});
            db.execSQL("UPDATE OR IGNORE locks SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{into, from});
            db.delete("locks", "title = ? COLLATE NOCASE", new String[]{from});
            db.delete("reads", "title = ? COLLATE NOCASE", new String[]{from});

            Meta a = getMeta(from), b = getMeta(into);
            if (a != null) {
                Meta m = b != null ? b : new Meta();
                m.title = into;
                m.tags = Titles.normalizeTags((m.tags == null ? "" : m.tags) + "," + (a.tags == null ? "" : a.tags));
                if (m.lastUrl == null) m.lastUrl = a.lastUrl;
                if (m.lastPkg == null) m.lastPkg = a.lastPkg;
                if (m.titleNo == null) {
                    m.titleNo = a.titleNo;
                    m.rssBase = a.rssBase;
                    m.rssLastEp = a.rssLastEp;
                }
                m.follow = m.follow || a.follow;
                if (m.rating == 0) m.rating = a.rating;
                if (m.note == null) m.note = a.note;
                if (m.detailsJson == null) {
                    m.detailsJson = a.detailsJson;
                    m.detailsAt = a.detailsAt;
                }
                if (m.score == 0) m.score = a.score;
                if (m.mdxId == null) m.mdxId = a.mdxId;
                if (m.muId == null) m.muId = a.muId;
                m.editionsOn = m.editionsOn || a.editionsOn;
                saveMeta(db, m);
                db.delete("series_meta", "title = ? COLLATE NOCASE", new String[]{from});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ------------------------------------------------------------------ Infos par série

    public Meta getMeta(String title) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, tags, last_url, last_pkg, title_no, rss_base, rss_last_ep, mdx_id, mu_id, notified_ch, " +
                        "follow, rating, note, details_json, details_at, score, editions_on, editions_at FROM series_meta " +
                        "WHERE title = ? COLLATE NOCASE", new String[]{title})) {
            if (!c.moveToFirst()) return null;
            Meta m = new Meta();
            m.title = c.getString(0);
            m.tags = c.getString(1);
            m.lastUrl = c.getString(2);
            m.lastPkg = c.getString(3);
            m.titleNo = c.getString(4);
            m.rssBase = c.getString(5);
            m.rssLastEp = c.getLong(6);
            m.mdxId = c.getString(7);
            m.muId = c.getString(8);
            m.notifiedCh = c.getDouble(9);
            m.follow = c.getInt(10) == 1;
            m.rating = c.getInt(11);
            m.note = c.getString(12);
            m.detailsJson = c.getString(13);
            m.detailsAt = c.getLong(14);
            m.score = c.getInt(15);
            m.editionsOn = c.getInt(16) == 1;
            m.editionsAt = c.getLong(17);
            return m;
        }
    }

    public List<Meta> getAllMeta() {
        List<Meta> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title FROM series_meta", null)) {
            while (c.moveToNext()) {
                Meta m = getMeta(c.getString(0));
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    private void saveMeta(SQLiteDatabase db, Meta m) {
        ContentValues v = new ContentValues();
        v.put("title", m.title);
        v.put("tags", m.tags);
        v.put("last_url", m.lastUrl);
        v.put("last_pkg", m.lastPkg);
        v.put("title_no", m.titleNo);
        v.put("rss_base", m.rssBase);
        v.put("rss_last_ep", m.rssLastEp);
        v.put("mdx_id", m.mdxId);
        v.put("mu_id", m.muId);
        v.put("notified_ch", m.notifiedCh);
        v.put("follow", m.follow ? 1 : 0);
        v.put("rating", m.rating);
        v.put("note", m.note);
        v.put("details_json", m.detailsJson);
        v.put("details_at", m.detailsAt);
        v.put("score", m.score);
        v.put("editions_on", m.editionsOn ? 1 : 0);
        v.put("editions_at", m.editionsAt);
        db.insertWithOnConflict("series_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void saveMeta(Meta m) {
        saveMeta(getWritableDatabase(), m);
    }

    private Meta metaOrNew(String title) {
        Meta m = getMeta(title);
        if (m == null) {
            m = new Meta();
            m.title = title;
        }
        return m;
    }

    /** Mémorise où le chapitre a été lu (pour "Reprendre") et l'identifiant webtoons.com. */
    public synchronized void rememberSource(Detector.Result r) {
        Meta m = metaOrNew(r.title);
        // « Reprendre » vise le chapitre le plus avancé : relire un vieux chapitre ne le déplace pas.
        boolean furthest = r.chapter >= lastRead(r.title);
        if (r.url != null && (furthest || m.lastUrl == null)) m.lastUrl = r.url;
        if (r.pkg != null && (furthest || m.lastPkg == null)) m.lastPkg = r.pkg;
        if (r.titleNo != null && !r.titleNo.equals(m.titleNo)) {
            m.titleNo = r.titleNo;
            m.rssBase = r.rssBase;
            m.rssLastEp = 0;
        }
        saveMeta(m);
    }

    public synchronized void setTags(String title, String tags) {
        Meta m = metaOrNew(title);
        m.tags = Titles.normalizeTags(tags);
        saveMeta(m);
    }

    public synchronized void linkWebtoons(String title, String rssBase, String titleNo) {
        Meta m = metaOrNew(title);
        m.rssBase = rssBase;
        m.titleNo = titleNo;
        m.rssLastEp = 0;
        saveMeta(m);
    }

    public synchronized void setRssLastEp(String title, long ep) {
        Meta m = metaOrNew(title);
        m.rssLastEp = ep;
        saveMeta(m);
    }

    public Set<String> allTags() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Series s : getSeries()) out.addAll(Titles.splitTags(s.tags));
        return out;
    }

    // ------------------------------------------------------------------ Sorties de chapitres

    public synchronized void saveRelease(Release r) {
        ContentValues v = new ContentValues();
        v.put("title", r.title);
        v.put("source", r.source);
        v.put("lang", r.lang);
        v.put("chapter", r.chapter);
        v.put("url", r.url);
        v.put("label", r.label);
        v.put("found_at", r.foundAt);
        getWritableDatabase().insertWithOnConflict("releases", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Dernier chapitre lu (le plus grand numéro), ou 0. */
    public double lastRead(String title) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(chapter) FROM reads WHERE title = ? COLLATE NOCASE", new String[]{title})) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        }
    }

    /** Sorties PLUS RÉCENTES que ton dernier chapitre lu, la plus avancée d'abord. */
    public List<Release> newReleases(String titleOrNull) {
        List<Release> out = new ArrayList<>();
        String where = titleOrNull == null ? "" : " AND r.title = ? COLLATE NOCASE";
        String[] args = titleOrNull == null ? null : new String[]{titleOrNull};
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT r.title, r.source, r.lang, r.chapter, r.url, r.label, r.found_at FROM releases r " +
                        "WHERE r.chapter > IFNULL((SELECT MAX(chapter) FROM reads x WHERE x.title = r.title COLLATE NOCASE), 0)" +
                        where + " ORDER BY r.chapter DESC, r.found_at DESC", args)) {
            while (c.moveToNext()) {
                Release r = new Release();
                r.title = c.getString(0);
                r.source = c.getString(1);
                r.lang = c.getString(2);
                r.chapter = c.getDouble(3);
                r.url = c.getString(4);
                r.label = c.getString(5);
                r.foundAt = c.getLong(6);
                out.add(r);
            }
        }
        return out;
    }

    /** Supprime les sorties dont la source commence par ce préfixe (ex. « Édition » quand tu coupes « Langues & sources »). */
    public synchronized void deleteReleases(String title, String sourcePrefix) {
        getWritableDatabase().delete("releases", "title = ? COLLATE NOCASE AND source LIKE ?",
                new String[]{title, sourcePrefix + "%"});
    }

    // ------------------------------------------------------------------ Éditions (Langues & sources)

    public List<Edition> getEditions(String title) {
        List<Edition> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, url, platform, lang, category, evidence, proof, latest, free_upto, model, chapter_tpl, " +
                        "ep_offset, chapters, user_added, checked_at FROM editions WHERE title = ? COLLATE NOCASE " +
                        "ORDER BY category, latest DESC, lang", new String[]{title})) {
            while (c.moveToNext()) {
                Edition e = new Edition();
                e.title = c.getString(0);
                e.url = c.getString(1);
                e.platform = c.getString(2);
                e.lang = c.getString(3);
                e.category = c.getInt(4);
                e.evidence = c.getString(5);
                e.proof = c.getString(6);
                e.latest = c.getDouble(7);
                e.freeUpto = c.getDouble(8);
                e.model = c.getString(9);
                e.chapterTpl = c.getString(10);
                e.epOffset = c.getInt(11);
                e.chapters = c.getString(12);
                e.userAdded = c.getInt(13) == 1;
                e.checkedAt = c.getLong(14);
                out.add(e);
            }
        }
        return out;
    }

    private static ContentValues values(Edition e) {
        ContentValues v = new ContentValues();
        v.put("title", e.title);
        v.put("url", e.url);
        v.put("platform", e.platform);
        v.put("lang", e.lang);
        v.put("category", e.category);
        v.put("evidence", e.evidence);
        v.put("proof", e.proof);
        v.put("latest", e.latest);
        v.put("free_upto", e.freeUpto);
        v.put("model", e.model);
        v.put("chapter_tpl", e.chapterTpl);
        v.put("ep_offset", e.epOffset);
        v.put("chapters", e.chapters);
        v.put("user_added", e.userAdded ? 1 : 0);
        v.put("checked_at", e.checkedAt);
        return v;
    }

    /**
     * Remplace les éditions trouvées automatiquement (celles que tu as ajoutées avec une preuve restent).
     * Catégories 1 et 2 seulement : tout le reste est refusé ici, par sécurité.
     */
    public synchronized void replaceEditions(String title, List<Edition> found) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("editions", "title = ? COLLATE NOCASE AND IFNULL(user_added, 0) = 0", new String[]{title});
            for (Edition e : found) {
                if (e.category != SourceRegistry.OFFICIAL && e.category != SourceRegistry.AUTHORIZED) continue;
                e.title = title;
                db.insertWithOnConflict("editions", null, values(e), SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void addUserEdition(Edition e) {
        if (e.category != SourceRegistry.AUTHORIZED) return;
        e.userAdded = true;
        getWritableDatabase().insertWithOnConflict("editions", null, values(e), SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteEdition(String title, String url) {
        getWritableDatabase().delete("editions", "title = ? COLLATE NOCASE AND url = ?", new String[]{title, url});
    }

    public synchronized void setEditionsOn(String title, boolean on) {
        Meta m = metaOrNew(title);
        m.editionsOn = on;
        if (!on) m.editionsAt = 0;
        saveMeta(m);
    }

    public synchronized void setEditionsAt(String title, long at) {
        Meta m = metaOrNew(title);
        m.editionsAt = at;
        saveMeta(m);
    }

    // ------------------------------------------------------------------ Chapitres payants

    /** Chapitre payant repéré (ou toujours payant) : on garde la 1re date de repérage. */
    public synchronized void saveLock(String title, String host, double chapter, String url, long freeAt, long now) {
        if (title == null || host == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("host", host);
        v.put("chapter", chapter);
        v.put("url", url);
        v.put("free_at", Math.max(0, freeAt));
        v.put("seen_at", now);
        v.put("checked_at", now);
        v.put("freed_at", 0);
        v.put("notified", 0);
        if (db.insertWithOnConflict("locks", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            db.execSQL("UPDATE locks SET checked_at = ?, freed_at = 0, notified = 0, url = IFNULL(?, url), " +
                            "free_at = CASE WHEN ? > 0 THEN ? ELSE free_at END " +
                            "WHERE title = ? COLLATE NOCASE AND host = ? AND chapter = ?",
                    new Object[]{now, url, freeAt, freeAt, title, host, chapter});
        }
    }

    /** Le site l'a rendu gratuit. */
    public synchronized void markFreed(String title, String host, double chapter, long now) {
        getWritableDatabase().execSQL("UPDATE locks SET freed_at = ?, checked_at = ? " +
                "WHERE title = ? COLLATE NOCASE AND host = ? AND chapter = ? AND freed_at = 0", new Object[]{now, now, title, host, chapter});
    }

    public synchronized void markLockNotified(String title, String host, double chapter) {
        getWritableDatabase().execSQL("UPDATE locks SET notified = 1 WHERE title = ? COLLATE NOCASE AND host = ? AND chapter = ?",
                new Object[]{title, host, chapter});
    }

    public synchronized void touchLock(String title, String host, double chapter, long now, long freeAt) {
        getWritableDatabase().execSQL("UPDATE locks SET checked_at = ?, free_at = CASE WHEN ? > 0 THEN ? ELSE free_at END " +
                "WHERE title = ? COLLATE NOCASE AND host = ? AND chapter = ?", new Object[]{now, freeAt, freeAt, title, host, chapter});
    }

    public synchronized void deleteLock(String title, String host, double chapter) {
        getWritableDatabase().delete("locks", "title = ? COLLATE NOCASE AND host = ? AND chapter = ?",
                new String[]{title, host, String.valueOf(chapter)});
    }

    private static Lock lockFrom(Cursor c) {
        Lock l = new Lock();
        l.title = c.getString(0);
        l.host = c.getString(1);
        l.chapter = c.getDouble(2);
        l.url = c.getString(3);
        l.freeAt = c.getLong(4);
        l.seenAt = c.getLong(5);
        l.checkedAt = c.getLong(6);
        l.freedAt = c.getLong(7);
        l.notified = c.getInt(8) == 1;
        return l;
    }

    private static final String LOCK_COLS = "title, host, chapter, url, free_at, seen_at, checked_at, freed_at, notified";

    /** Verrous d'une série (actifs, et débloqués depuis moins de 14 jours), du plus petit chapitre au plus grand. */
    public List<Lock> getLocks(String title) {
        List<Lock> out = new ArrayList<>();
        long since = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOCK_COLS + " FROM locks WHERE title = ? COLLATE NOCASE " +
                "AND (freed_at = 0 OR freed_at > ?) ORDER BY chapter", new String[]{title, String.valueOf(since)})) {
            while (c.moveToNext()) out.add(lockFrom(c));
        }
        return out;
    }

    /** Tous les verrous encore actifs (pour la vérification périodique). */
    public List<Lock> activeLocks() {
        List<Lock> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOCK_COLS + " FROM locks WHERE freed_at = 0 " +
                "ORDER BY title, host, chapter", null)) {
            while (c.moveToNext()) out.add(lockFrom(c));
        }
        return out;
    }

    public int countActiveLocks() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM locks WHERE freed_at = 0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Ménage : verrous débloqués depuis longtemps, ou de chapitres que tu as lus depuis. */
    public synchronized void purgeLocks(long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM locks WHERE freed_at > 0 AND freed_at < ?", new Object[]{now - 14L * 24 * 3600 * 1000});
        db.execSQL("DELETE FROM locks WHERE seen_at > 0 AND seen_at < ? AND freed_at = 0", new Object[]{now - 90L * 24 * 3600 * 1000});
        db.execSQL("DELETE FROM locks WHERE EXISTS (SELECT 1 FROM reads r WHERE r.title = locks.title COLLATE NOCASE " +
                "AND r.chapter = locks.chapter)");
    }

    /**
     * Annule une lecture enregistrée par erreur (écran de paiement) : la ligne disparaît et
     * « Reprendre » revient sur le dernier chapitre vraiment lu.
     */
    public synchronized void undoRead(String title, double chapter) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("reads", "title = ? COLLATE NOCASE AND chapter = ?", new String[]{title, String.valueOf(chapter)});
        Meta m = getMeta(title);
        if (m == null) return;
        try (Cursor c = db.rawQuery("SELECT url FROM reads WHERE title = ? COLLATE NOCASE AND url IS NOT NULL " +
                "ORDER BY chapter DESC LIMIT 1", new String[]{title})) {
            if (c.moveToFirst()) {
                m.lastUrl = c.getString(0);
                saveMeta(db, m);
            }
        }
    }

    // ------------------------------------------------------------------ Cloche, note, fiche

    public synchronized void setFollow(String title, boolean on) {
        Meta m = metaOrNew(title);
        m.follow = on;
        if (on) m.notifiedCh = lastRead(title); // on ne signale que ce qui sortira après ton dernier lu
        saveMeta(m);
    }

    public boolean isFollowed(String title) {
        Meta m = getMeta(title);
        return m != null && m.follow;
    }

    public int countFollowed() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM series_meta WHERE follow = 1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void setRating(String title, int stars) {
        Meta m = metaOrNew(title);
        m.rating = stars;
        saveMeta(m);
    }

    public synchronized void setNote(String title, String note) {
        Meta m = metaOrNew(title);
        m.note = note;
        saveMeta(m);
    }

    public synchronized void setDetails(String title, String json) {
        Meta m = metaOrNew(title);
        m.detailsJson = json;
        m.detailsAt = System.currentTimeMillis();
        saveMeta(m);
    }

    // ------------------------------------------------------------------ Alias & titre officiel

    public synchronized void setScore(String title, int score) {
        Meta m = metaOrNew(title);
        m.score = score;
        saveMeta(m);
    }

    /** Enregistre d'autres noms (anglais, romanisé, synonymes…) qui désignent la série "title". */
    public synchronized void addAliases(String title, List<String> names) {
        SQLiteDatabase db = getWritableDatabase();
        for (String n : names) {
            String k = Titles.key(n == null ? "" : n);
            if (k.length() < 3) continue;
            ContentValues v = new ContentValues();
            v.put("alias_key", k);
            v.put("title", title);
            db.insertWithOnConflict("aliases", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /**
     * Titre sous lequel ranger une lecture : alias connu, sinon série existante au nom équivalent
     * ("Tower Of God" = "Tower of God (S3)"), sinon le titre tel quel.
     */
    public String resolveTitle(String title) {
        String k = Titles.key(title);
        if (k.isEmpty()) return title;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title FROM aliases WHERE alias_key = ?", new String[]{k})) {
            if (c.moveToFirst()) return c.getString(0);
        }
        for (Series s : getSeries()) if (Titles.key(s.title).equals(k)) return s.title;
        return title;
    }

    /** Le titre vient-il uniquement d'URL (slug) ? Alors on peut le remplacer par le titre officiel. */
    public boolean isUrlDerived(String title) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM reads WHERE title = ? COLLATE NOCASE AND (source IS NULL OR source NOT LIKE '%.%')",
                new String[]{title})) {
            return c.moveToFirst() && c.getInt(0) == 0;
        }
    }

    /** Renomme une série (fusionne si le nouveau titre existe déjà) et garde l'ancien nom en alias. */
    public synchronized void renameSeries(String from, String to) {
        if (from.equals(to)) return;
        if (from.equalsIgnoreCase(to)) {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("UPDATE reads SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
            db.execSQL("UPDATE series_meta SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
            db.execSQL("UPDATE releases SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
            db.execSQL("UPDATE editions SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
            db.execSQL("UPDATE locks SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
        } else {
            mergeSeries(from, to);
        }
        getWritableDatabase().execSQL("UPDATE aliases SET title = ? WHERE title = ? COLLATE NOCASE", new Object[]{to, from});
        List<String> one = new ArrayList<>();
        one.add(from);
        one.add(to);
        addAliases(to, one);
    }

    public synchronized void setNotified(String title, double ch) {
        Meta m = metaOrNew(title);
        m.notifiedCh = ch;
        saveMeta(m);
    }

    public synchronized void setSourceIds(String title, String mdxId, String muId) {
        Meta m = metaOrNew(title);
        if (mdxId != null) m.mdxId = mdxId;
        if (muId != null) m.muId = muId;
        saveMeta(m);
    }

    /** Début du jour (minuit local) pour un instant donné. */
    public static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
