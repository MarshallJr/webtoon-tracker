package com.lois.webtoontracker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Onglet « Épisodes » de la fiche d'une série, façon liste d'épisodes de WEBTOON :
 * vignette du chapitre, « Épisode N », date de lecture, relectures, bouton Relire / Reprendre,
 * le chapitre en cours surligné (coin replié + barre de progression), et en tête
 * les nouveaux chapitres repérés en ligne (« NOUVEAU » → Lire).
 */
final class EpisodesTab {

    private static final int PAGE = 30;
    private static final String PREFS = "episodes";

    interface Host {
        void onEpisodesChanged();

        void confirmDelete(DbHelper.Entry e);

        void showAddDialog();
    }

    private final Activity act;
    private final Host host;
    private final DbHelper db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMM yyyy", Locale.FRANCE);
    private String title;
    private int shown = PAGE;
    private boolean animateNext = true;

    EpisodesTab(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.db = DbHelper.get(act);
        act.findViewById(R.id.eSort).setOnClickListener(v -> {
            prefs().edit().putBoolean("newest", !newestFirst()).apply();
            shown = PAGE;
            animateNext = true;
            bind(title);
        });
        act.findViewById(R.id.eAdd).setOnClickListener(v -> host.showAddDialog());
        act.findViewById(R.id.eJump).setOnClickListener(v -> showJumpDialog());
        Anim.press(act.findViewById(R.id.eAdd));
        Anim.press(act.findViewById(R.id.eJump));
        act.findViewById(R.id.eMore).setOnClickListener(v -> {
            shown += PAGE;
            bind(title);
        });
        act.findViewById(R.id.eBackfillClose).setOnClickListener(v -> {
            prefs().edit().putBoolean("backfill_off_" + Titles.key(title), true).apply();
            act.findViewById(R.id.eBackfill).setVisibility(View.GONE);
        });
        Anim.press(act.findViewById(R.id.eSort));
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean newestFirst() {
        return prefs().getBoolean("newest", true);
    }

    /** Les lignes entrent en cascade la prochaine fois (quand on ouvre l'onglet). */
    void animateNextBind() {
        animateNext = true;
    }

    // ------------------------------------------------------------------ Données

    private static final class Row {
        DbHelper.Entry entry;          // chapitre lu
        DbHelper.Release release;      // ou nouveau chapitre repéré en ligne
        double chapter;
    }

    /** Lignes de la dernière liste affichée (pour « Aller au ch. » et « Marquer 1 → N »). */
    private List<Row> lastRows = new ArrayList<>();

    void bind(String title) {
        this.title = title;
        List<DbHelper.Entry> entries = db.getHistory(title);          // du plus grand au plus petit
        DbHelper.Meta meta = db.getMeta(title);
        double furthest = entries.isEmpty() ? 0 : entries.get(0).chapter;

        // Tous les chapitres de 1 au dernier connu (lu ou sorti), comme la liste d'épisodes de WEBTOON :
        // les lus sont grisés, les autres restent en clair.
        java.util.Map<Double, DbHelper.Entry> read = new java.util.HashMap<>();
        for (DbHelper.Entry e : entries) read.put(e.chapter, e);
        java.util.Map<Double, DbHelper.Release> fresh = new java.util.HashMap<>();
        double latest = furthest;
        for (DbHelper.Release r : db.newReleases(title)) {
            if (r.chapter <= furthest || fresh.containsKey(r.chapter)) continue;
            fresh.put(r.chapter, r);
            latest = Math.max(latest, r.chapter);
        }
        // Éditions officielles (« Langues & sources ») : leurs chapitres comptent aussi dans la liste.
        List<DbHelper.Edition> editions = meta != null && meta.editionsOn ? db.getEditions(title) : new ArrayList<>();
        for (DbHelper.Edition e : editions) latest = Math.max(latest, e.latest);
        // Chapitres payants repérés sur le site où tu lis (celui qu'ouvre le bouton « Lire »).
        String site = currentSite(meta, entries, furthest);
        java.util.Map<Double, DbHelper.Lock> locks = new java.util.HashMap<>();
        for (DbHelper.Lock l : db.getLocks(title)) {
            if ((site != null && !site.equals(l.host)) || read.containsKey(l.chapter)) continue;
            DbHelper.Lock cur = locks.get(l.chapter);
            if (cur == null || (!cur.active() && l.active())) locks.put(l.chapter, l);
            latest = Math.max(latest, l.chapter);
        }
        // Liste complète du site où tu lis (du 1er au dernier chapitre publié), avec l'adresse exacte de chacun.
        siteList = db.siteChapters(title, site);
        if (!siteList.isEmpty()) latest = Math.max(latest, siteList.lastKey());
        refreshSiteList(site, entries);
        java.util.TreeSet<Double> all = new java.util.TreeSet<>(read.keySet());
        all.addAll(fresh.keySet());
        all.addAll(siteList.keySet());
        if (latest <= 3000) for (int c = 1; c <= (int) Math.floor(latest); c++) all.add((double) c);

        boolean newest = newestFirst();
        List<Row> rows = new ArrayList<>();
        for (Double ch : newest ? all.descendingSet() : all) {
            Row row = new Row();
            row.chapter = ch;
            row.entry = read.get(ch);
            row.release = row.entry == null ? fresh.get(ch) : null;
            rows.add(row);
        }
        // Le chapitre en cours doit être visible sans avoir à charger plus.
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).chapter == furthest && shown < i + 6) shown = ((i + 6) / PAGE + 1) * PAGE;
        }
        lastRows = rows;

        // En-tête
        TextView count = act.findViewById(R.id.eCount);
        String c = all.size() + (all.size() > 1 ? " épisodes" : " épisode") + "  ·  " + entries.size()
                + (entries.size() > 1 ? " lus" : " lu");
        count.setText(c);
        bindSiteLine(site, entries);
        act.findViewById(R.id.eJump).setVisibility(rows.size() > PAGE ? View.VISIBLE : View.GONE);
        ((TextView) act.findViewById(R.id.eSort)).setText(newest ? "Plus récents" : "Plus anciens");
        bindBackfill(entries);
        bindLockCard(locks);

        // Liste
        LinearLayout list = act.findViewById(R.id.eList);
        list.removeAllViews();
        Progress progress = Progress.get(act, title);
        Bitmap strip = ChapterThumbs.placeholder(act, title);
        int n = Math.min(shown, rows.size());
        for (int i = 0; i < n; i++) {
            View v = rowView(list, rows.get(i), furthest, progress, meta, entries, strip, editions, locks);
            list.addView(v);
            if (animateNext && i < 10) Anim.enter(v, i);
        }
        animateNext = false;

        // Vignettes officielles WEBTOON pour les épisodes affichés (si la série y existe).
        List<Double> visible = new ArrayList<>();
        for (int i = 0; i < n; i++) visible.add(rows.get(i).chapter);
        final String t = title;
        io.execute(() -> {
            // 1. vignettes officielles (WEBTOON) ; 2. sinon images tirées des pages du chapitre sur ton site
            boolean changed = WebtoonThumbs.fetch(act, t, visible);
            if (SiteThumbs.fetch(act, t, visible)) changed = true;
            if (changed) act.runOnUiThread(() -> {
                if (t.equals(this.title) && !act.isFinishing()) reloadThumbs();   // les images seulement, pas toute la liste
            });
        });

        TextView more = act.findViewById(R.id.eMore);
        int left = rows.size() - n;
        more.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        more.setText("Afficher " + Math.min(PAGE, left) + " de plus  (" + left + " restants)");
        act.findViewById(R.id.eHint).setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Nouvelles vignettes arrivées : on les pose sur les lignes déjà affichées. */
    private void reloadThumbs() {
        LinearLayout list = act.findViewById(R.id.eList);
        final String t = title;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            Object tag = v.getTag(R.id.eNum);
            if (!(tag instanceof Double)) continue;
            final double c = (Double) tag;
            ImageView thumb = v.findViewById(R.id.eThumb);
            TextView num = v.findViewById(R.id.eNum);
            io.execute(() -> {
                Bitmap b = ChapterThumbs.get(act, t, c);
                if (b != null) act.runOnUiThread(() -> {
                    thumb.setImageBitmap(b);
                    num.setVisibility(View.GONE);
                });
            });
        }
    }

    /** « Tu as commencé au ch. 38 ? » → marquer 1 à 37 comme lus (pour être prévenu si tu les rouvres). */
    private void bindBackfill(List<DbHelper.Entry> entries) {
        View card = act.findViewById(R.id.eBackfill);
        double first = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).chapter;
        int upTo = (int) Math.ceil(first) - 1;
        boolean off = prefs().getBoolean("backfill_off_" + Titles.key(title), false);
        if (entries.isEmpty() || upTo < 1 || upTo > 3000 || off) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        ((TextView) act.findViewById(R.id.eBackfillText)).setText("Tu avais lu le début ailleurs ? Marque les chapitres 1 à "
                + upTo + " comme déjà lus.");
        long oldest = Long.MAX_VALUE;
        for (DbHelper.Entry e : entries) oldest = Math.min(oldest, e.readAt);
        final long at = oldest - 60_000;
        act.findViewById(R.id.eBackfillBtn).setOnClickListener(v -> new MaterialAlertDialogBuilder(act)
                .setTitle("Chapitres 1 à " + upTo)
                .setMessage("Ils seront comptés comme lus : tu seras prévenu si tu tombes à nouveau dessus.")
                .setPositiveButton("Marquer", (d, w) -> {
                    int added = db.markRangeRead(title, 1, upTo, at);
                    Toast.makeText(act, added + " chapitres ajoutés", Toast.LENGTH_SHORT).show();
                    animateNext = true;
                    host.onEpisodesChanged();
                })
                .setNegativeButton("Annuler", null)
                .show());
    }

    // ------------------------------------------------------------------ Une ligne

    private View rowView(LinearLayout parent, Row r, double furthest, Progress progress, DbHelper.Meta meta,
                         List<DbHelper.Entry> entries, Bitmap strip, List<DbHelper.Edition> editions,
                         java.util.Map<Double, DbHelper.Lock> locks) {
        View v = LayoutInflater.from(act).inflate(R.layout.item_episode, parent, false);
        v.setTag(R.id.anim_press, Boolean.TRUE); // ligne pleine largeur : effet d'appui classique (pas de zoom)
        TextView name = v.findViewById(R.id.eName), badge = v.findViewById(R.id.eBadge);
        TextView date = v.findViewById(R.id.eDate), extra = v.findViewById(R.id.eExtra);
        TextView btn = v.findViewById(R.id.eBtn), num = v.findViewById(R.id.eNum);
        ImageView thumb = v.findViewById(R.id.eThumb);
        LinearProgressIndicator bar = v.findViewById(R.id.eBar);
        String ch = Detector.formatChapter(r.chapter);
        name.setText("Épisode " + ch);
        v.setTag(R.id.eNum, r.chapter);

        // Vignette : officielle WEBTOON ou capture du chapitre si on l'a, sinon fond flouté + numéro.
        thumb.setImageBitmap(strip);
        num.setText(ch);
        num.setVisibility(View.VISIBLE);
        {
            final String t = title;
            io.execute(() -> {
                Bitmap b = ChapterThumbs.get(act, t, r.chapter);
                if (b != null) act.runOnUiThread(() -> {
                    thumb.setImageBitmap(b);
                    num.setVisibility(View.GONE);
                });
            });
        }

        Runnable action;
        if (r.release != null) {
            // Nouveau chapitre repéré en ligne
            badge.setText("NOUVEAU");
            badge.setVisibility(View.VISIBLE);
            date.setText("Sorti · " + dayFmt.format(new Date(r.release.foundAt)));
            extra.setText("Sur " + (r.release.label == null ? r.release.source : r.release.label)
                    + (r.release.lang == null ? "" : " · " + r.release.lang.toUpperCase(Locale.ROOT)));
            extra.setTextColor(ContextCompat.getColor(act, R.color.text_soft));
            extra.setVisibility(View.VISIBLE);
            btn.setText("Lire");
            btn.setBackgroundResource(R.drawable.bg_episode_btn_new);
            btn.setTextColor(0xFFFFFFFF);
            String url = r.release.url;
            action = url == null ? null : () -> open(url);
            v.setOnLongClickListener(x -> markRead(x, r.chapter));
            bindAlso(v, r.chapter, editions);
        } else if (r.entry == null && locks.get(r.chapter) != null) {
            // Payant sur ton site (ou débloqué depuis peu)
            DbHelper.Lock l = locks.get(r.chapter);
            Runnable go = linkAction(meta, entries, r.chapter);
            if (go == null && l.url != null && l.url.startsWith("http")) go = () -> open(l.url);
            final Runnable openIt = go;
            long now = System.currentTimeMillis();
            badge.setVisibility(View.VISIBLE);
            extra.setVisibility(View.VISIBLE);
            if (l.active()) {
                badge.setText("PAYANT");
                badge.setTextColor(ContextCompat.getColor(act, R.color.ed_paid_text));
                date.setText("Payant sur " + l.host);
                extra.setText(l.freeAt > now ? "Gratuit " + ReadingTrackerService.inWords(l.freeAt - now)
                        : "Je te préviens dès qu'il est gratuit");
                extra.setTextColor(ContextCompat.getColor(act, R.color.text_soft));
                btn.setText("Payant");
                btn.setTextColor(ContextCompat.getColor(act, R.color.ed_paid_text));
                action = () -> showLockDialog(l, openIt);
            } else {
                badge.setText("GRATUIT");
                badge.setTextColor(ContextCompat.getColor(act, R.color.online));
                date.setText("Payant avant · " + l.host);
                extra.setText("Gratuit depuis le " + dayFmt.format(new Date(l.freedAt)));
                extra.setTextColor(ContextCompat.getColor(act, R.color.online));
                btn.setText("Lire");
                btn.setBackgroundResource(R.drawable.bg_episode_btn_new);
                btn.setTextColor(0xFFFFFFFF);
                action = openIt;
            }
            v.setOnLongClickListener(x -> markRead(x, r.chapter));
            bindAlso(v, r.chapter, editions);
        } else if (r.entry == null) {
            // Pas encore lu : en clair
            date.setText(r.chapter > furthest ? "À lire" : "Pas lu");
            btn.setText("Lire");
            action = linkAction(meta, entries, r.chapter);
            v.setOnLongClickListener(x -> markRead(x, r.chapter));
            bindAlso(v, r.chapter, editions);
        } else {
            DbHelper.Entry e = r.entry;
            boolean current = e.chapter == furthest;
            boolean before = DbHelper.SOURCE_BEFORE.equals(e.source);
            date.setText(before ? "Lu ailleurs" : dayFmt.format(new Date(e.readAt)));
            if (current) {
                v.setBackgroundColor(ContextCompat.getColor(act, R.color.episode_current));
                v.findViewById(R.id.eFold).setVisibility(View.VISIBLE);
                badge.setText("EN COURS");
                badge.setTextColor(ContextCompat.getColor(act, R.color.brand));
                badge.setVisibility(View.VISIBLE);
            } else {
                // Déjà lu : grisé, comme sur WEBTOON
                v.findViewById(R.id.eContent).setAlpha(0.42f);
                name.setTextColor(ContextCompat.getColor(act, R.color.text_soft));
            }
            boolean inProgress = current && progress != null && progress.chapter == e.chapter
                    && progress.pct > 0.02f && !progress.finished();
            if (inProgress) {
                int pct = Math.round(progress.pct * 100);
                bar.setProgress(pct);
                bar.setVisibility(View.VISIBLE);
                extra.setText("Arrêté à " + progress.where());
                extra.setTextColor(ContextCompat.getColor(act, R.color.brand));
                extra.setVisibility(View.VISIBLE);
            } else if (e.times > 1) {
                extra.setText("Lu " + e.times + " fois" + (e.lastAt > 0 ? "  ·  revu le " + dayFmt.format(new Date(e.lastAt)) : ""));
                extra.setVisibility(View.VISIBLE);
            } else if ("manuel".equals(e.source)) {
                extra.setText("Ajouté à la main");
                extra.setTextColor(ContextCompat.getColor(act, R.color.text_soft));
                extra.setVisibility(View.VISIBLE);
            }
            if (current) {
                btn.setText("Reprendre");
                btn.setBackgroundResource(R.drawable.bg_episode_btn_new);
                btn.setTextColor(0xFFFFFFFF);
                action = () -> Resume.open(act, title);
            } else {
                action = linkAction(meta, entries, e.chapter);
            }
            v.setOnLongClickListener(x -> {
                PopupMenu pm = new PopupMenu(act, x);
                pm.getMenu().add("Marquer comme non lu");
                pm.setOnMenuItemClickListener(it -> {
                    host.confirmDelete(e);
                    return true;
                });
                pm.show();
                return true;
            });
        }

        if (action == null) {
            btn.setVisibility(View.GONE);
            v.setOnClickListener(x -> Toast.makeText(act, "Lien de ce chapitre inconnu", Toast.LENGTH_SHORT).show());
        } else {
            final Runnable a = action;
            btn.setOnClickListener(x -> a.run());
            v.setOnClickListener(x -> a.run());
            Anim.press(btn);
            btn.setTag(R.id.anim_press, Boolean.TRUE);
        }
        return v;
    }

    /**
     * Ouvre le chapitre sur le site du dernier chapitre consulté (même site, numéro changé).
     * Si ce site ne permet pas de deviner l'adresse : la page de la série sur ce site.
     */
    private Runnable linkAction(DbHelper.Meta meta, List<DbHelper.Entry> entries, double chapter) {
        String exact = siteList == null ? null : siteList.get(chapter);   // adresse lue dans la liste du site
        if (exact != null) return () -> open(exact);
        ChapterLinks.Link link = ChapterLinks.linkFor(act, title, meta, entries, chapter);
        if (link == null) return null;
        if (!link.seriesPage) return () -> open(link.url);
        return () -> {
            Toast.makeText(act, "Adresse du ch. " + Detector.formatChapter(chapter) + " impossible à deviner sur "
                    + link.host() + " : page de la série", Toast.LENGTH_LONG).show();
            open(link.url);
        };
    }

    /**
     * « Aussi en 🇪🇸 🇰🇷 » : les éditions officielles (ou autorisées) où ce chapitre existe.
     * Un appui liste les langues ; chacune ouvre le chapitre (ou la série si l'adresse ne se devine pas).
     */
    private void bindAlso(View v, double ch, List<DbHelper.Edition> editions) {
        if (editions == null || editions.isEmpty()) return;
        List<DbHelper.Edition> here = new ArrayList<>();
        for (DbHelper.Edition e : editions) if (e.has(ch)) here.add(e);
        if (here.isEmpty()) return;
        java.util.LinkedHashSet<String> flags = new java.util.LinkedHashSet<>();
        for (DbHelper.Edition e : here) flags.add(SourceRegistry.flag(e.lang));
        StringBuilder sb = new StringBuilder("Aussi en ");
        int k = 0;
        for (String f : flags) {
            if (k++ == 4) {
                sb.append(" +").append(flags.size() - 4);
                break;
            }
            sb.append(f).append(' ');
        }
        TextView also = v.findViewById(R.id.eAlso);
        also.setText(sb.toString().trim());
        also.setVisibility(View.VISIBLE);
        also.setOnClickListener(x -> {
            PopupMenu pm = new PopupMenu(act, x);
            for (int i = 0; i < here.size(); i++) {
                DbHelper.Edition e = here.get(i);
                Boolean free = e.freeFor(ch);
                pm.getMenu().add(0, i, i, SourceRegistry.flag(e.lang) + "  " + SourceRegistry.langName(e.lang) + " · "
                        + (e.platform == null ? SourceRegistry.name(e.url) : e.platform)
                        + (free == null ? "" : free ? " · gratuit" : " · payant"));
            }
            pm.setOnMenuItemClickListener(it -> {
                DbHelper.Edition e = here.get(it.getItemId());
                String u = e.chapterUrl(ch);
                open(u != null ? u : e.url);
                return true;
            });
            pm.show();
        });
    }

    // ------------------------------------------------------------------ Liste du site

    /** Chapitres de la série sur le site où tu lis (numéro → adresse exacte). */
    private java.util.TreeMap<Double, String> siteList = new java.util.TreeMap<>();
    private final java.util.Set<String> siteRefreshing = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** « Liste de en-thunderscans.com · à jour il y a 2 heures » ; un appui la relit tout de suite. */
    private void bindSiteLine(String site, List<DbHelper.Entry> entries) {
        TextView line = act.findViewById(R.id.eSite);
        if (site == null || (siteList.isEmpty() && !siteRefreshing.contains(title + "|" + site))) {
            line.setVisibility(View.GONE);
            return;
        }
        long at = db.siteListAt(title, site);
        String text;
        if (siteRefreshing.contains(title + "|" + site)) {
            text = "Liste de " + site + " · mise à jour…";
        } else {
            long now = System.currentTimeMillis();
            text = "Liste de " + site + " · " + (now - at < 60_000 ? "à jour à l'instant" : "à jour "
                    + android.text.format.DateUtils.getRelativeTimeSpanString(at, now,
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase(Locale.FRANCE)) + "  ·  ↻";
        }
        line.setText(text);
        line.setVisibility(View.VISIBLE);
        line.setOnClickListener(v -> {
            if (refreshSiteList(site, entries, true)) bindSiteLine(site, entries);
        });
    }

    /** Met la liste du site à jour en arrière-plan si elle manque ou date de plus de 6 h. */
    private void refreshSiteList(String site, List<DbHelper.Entry> entries) {
        refreshSiteList(site, entries, false);
    }

    /** @return true si une lecture de la liste vient d'être lancée */
    private boolean refreshSiteList(String site, List<DbHelper.Entry> entries, boolean force) {
        if (site == null) return false;
        long age = SiteChapters.age(act, title, site);
        if (!force && age >= 0 && age < 6L * 3600 * 1000) return false;
        String url = null;
        double ch = 0;
        for (DbHelper.Entry e : entries) {                 // le chapitre le plus récent lu sur ce site
            if (e.url != null && site.equals(ChapterLinks.host(e.url))) {
                url = e.url;
                ch = e.chapter;
                break;
            }
        }
        if (url == null || !siteRefreshing.add(title + "|" + site)) return false;
        final String t = title, u = url;
        final double c = ch;
        io.execute(() -> {
            int n = SiteChapters.refresh(act, t, u, c, force);
            siteRefreshing.remove(t + "|" + site);
            act.runOnUiThread(() -> {
                if (!t.equals(this.title) || act.isFinishing()) return;
                if (force && n <= 0) Toast.makeText(act, "Liste illisible sur " + site + " pour l'instant", Toast.LENGTH_SHORT).show();
                if (n > 0) {
                    host.onEpisodesChanged();   // sorties, retard, compteur : toute la fiche
                } else {
                    bindSiteLine(site, entries);
                }
            });
        });
        return true;
    }

    // ------------------------------------------------------------------ Chapitres payants

    /** Le site qu'ouvre « Lire » pour la suite (le dernier où tu as lu cette série). */
    private String currentSite(DbHelper.Meta meta, List<DbHelper.Entry> entries, double furthest) {
        ChapterLinks.Link link = ChapterLinks.linkFor(act, title, meta, entries, Math.floor(furthest) + 1);
        return link == null ? null : link.host();
    }

    /** « 4 chapitres payants sur en-thunderscans.com · 33 → 36 · je te préviens dès qu'ils sont gratuits » */
    private void bindLockCard(java.util.Map<Double, DbHelper.Lock> locks) {
        View card = act.findViewById(R.id.eLockCard);
        List<DbHelper.Lock> active = new ArrayList<>();
        for (DbHelper.Lock l : locks.values()) if (l.active()) active.add(l);
        if (active.isEmpty()) {
            card.setVisibility(View.GONE);
            return;
        }
        active.sort((a, b) -> Double.compare(a.chapter, b.chapter));
        DbHelper.Lock first = active.get(0), last = active.get(active.size() - 1);
        int n = active.size();
        ((TextView) act.findViewById(R.id.eLockTitle)).setText(n + (n > 1 ? " chapitres payants" : " chapitre payant")
                + " sur " + first.host);
        long now = System.currentTimeMillis();
        DbHelper.Lock soon = null;
        for (DbHelper.Lock l : active) if (l.freeAt > now && (soon == null || l.freeAt < soon.freeAt)) soon = l;
        String range = n == 1 ? "Ch. " + Detector.formatChapter(first.chapter)
                : "Ch. " + Detector.formatChapter(first.chapter) + " → " + Detector.formatChapter(last.chapter);
        ((TextView) act.findViewById(R.id.eLockSub)).setText(range + " · " + (soon != null
                ? "le " + Detector.formatChapter(soon.chapter) + " devient gratuit " + ReadingTrackerService.inWords(soon.freeAt - now)
                : (n > 1 ? "je te préviens dès qu'ils deviennent gratuits" : "je te préviens dès qu'il devient gratuit")));
        card.setVisibility(View.VISIBLE);
    }

    private void showLockDialog(DbHelper.Lock l, Runnable openAnyway) {
        long now = System.currentTimeMillis();
        String msg = "Repéré le " + dayFmt.format(new Date(l.seenAt)) + " sur " + l.host + ". Il n'est pas compté comme lu.\n\n"
                + (l.freeAt > now ? "Le site annonce qu'il sera gratuit " + ReadingTrackerService.inWords(l.freeAt - now) + ". " : "")
                + "Je le revérifie régulièrement et je te préviens dès qu'il devient gratuit.";
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(act)
                .setTitle("Chapitre " + Detector.formatChapter(l.chapter) + " · payant")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Vérifier maintenant", (d, w) -> verifyLock(l));
        if (openAnyway != null) b.setNegativeButton("Ouvrir quand même", (d, w) -> openAnyway.run());
        b.show();
    }

    private void verifyLock(DbHelper.Lock l) {
        if (l.url == null || !l.url.startsWith("http")) {
            Toast.makeText(act, "Vérification impossible : chapitre repéré dans une appli", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(act, "Vérification…", Toast.LENGTH_SHORT).show();
        final String t = title;
        io.execute(() -> {
            String ctl = null;
            double ctlCh = 0;
            for (DbHelper.Entry e : db.getHistory(t)) {
                if (e.url != null && l.host.equals(ChapterLinks.host(e.url))) {
                    ctl = e.url;
                    ctlCh = e.chapter;
                    break;
                }
            }
            Paywall.Probe p = Paywall.probe(l.url, l.chapter, ctl, ctlCh);
            long now = System.currentTimeMillis();
            String msg;
            if (p.state == Paywall.FREE) {
                db.markFreed(t, l.host, l.chapter, now);
                db.markLockNotified(t, l.host, l.chapter);
                msg = "Le ch. " + Detector.formatChapter(l.chapter) + " est gratuit maintenant 🎉";
            } else if (p.state == Paywall.LOCKED_STATE) {
                db.touchLock(t, l.host, l.chapter, now, p.freeAt);
                msg = "Toujours payant" + (p.freeAt > now ? " · gratuit " + ReadingTrackerService.inWords(p.freeAt - now) : "");
            } else {
                msg = "Impossible de vérifier depuis l'appli : ce site construit ses pages par script. "
                        + "Je te préviendrai si tu le rouvres gratuit.";
            }
            final String m = msg;
            act.runOnUiThread(() -> {
                Toast.makeText(act, m, Toast.LENGTH_LONG).show();
                if (t.equals(this.title) && !act.isFinishing()) bind(t);
            });
        });
    }

    /** Appui long sur un chapitre pas encore lu → le marquer comme lu, seul ou avec tous ceux d'avant. */
    private boolean markRead(View anchor, double chapter) {
        List<Double> before = new ArrayList<>();
        for (Row r : lastRows) if (r.entry == null && r.chapter < chapter) before.add(r.chapter);
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Marquer comme lu");
        if (!before.isEmpty()) {
            pm.getMenu().add(0, 2, 1, "Marquer jusqu'ici comme lus (" + (before.size() + 1) + " chapitres)");
        }
        pm.setOnMenuItemClickListener(it -> {
            long now = System.currentTimeMillis();
            if (it.getItemId() == 2) {
                // « Lu ailleurs » : ne gonfle pas ton rythme de lecture de la semaine.
                int added = db.markChaptersRead(title, before, now - 60_000, DbHelper.SOURCE_BEFORE);
                db.insertRead(title, chapter, now, "manuel");
                Toast.makeText(act, (added + 1) + " chapitres marqués comme lus", Toast.LENGTH_SHORT).show();
            } else {
                db.insertRead(title, chapter, now, "manuel");
            }
            host.onEpisodesChanged();
            return true;
        });
        pm.show();
        return true;
    }

    // ------------------------------------------------------------------ Aller au chapitre

    private void showJumpDialog() {
        if (lastRows.isEmpty()) return;
        float dp = act.getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(act);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Numéro du chapitre");
        input.setSingleLine(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(act);
        box.setPadding((int) (24 * dp), (int) (8 * dp), (int) (24 * dp), 0);
        box.addView(input);
        double lo = Double.MAX_VALUE, hi = 0;
        for (Row r : lastRows) {
            lo = Math.min(lo, r.chapter);
            hi = Math.max(hi, r.chapter);
        }
        final boolean[] went = {false};
        Runnable go = () -> {
            if (went[0]) return;   // Entrée du clavier physique : appelé à l'appui ET au relâchement
            went[0] = true;
            jumpTo(input.getText().toString());
        };
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(act)
                .setTitle("Aller au chapitre")
                .setMessage("De " + Detector.formatChapter(lo) + " à " + Detector.formatChapter(hi))
                .setView(box)
                .setPositiveButton("Aller", (x, w) -> go.run())
                .setNegativeButton("Annuler", null)
                .create();
        input.setOnEditorActionListener((v, actionId, ev) -> {
            go.run();
            d.dismiss();
            return true;
        });
        d.setOnShowListener(x -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager im = act.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) input.postDelayed(() -> im.showSoftInput(input, 0), 120);
        });
        d.show();
    }

    private void jumpTo(String text) {
        double want;
        try {
            want = Double.parseDouble(text.trim().replace(',', '.'));
        } catch (Exception e) {
            return;
        }
        int idx = -1;
        double gap = Double.MAX_VALUE;
        for (int i = 0; i < lastRows.size(); i++) {      // le chapitre demandé, sinon le plus proche
            double g = Math.abs(lastRows.get(i).chapter - want);
            if (g < gap) {
                gap = g;
                idx = i;
            }
        }
        if (idx < 0) return;
        if (gap > 0.001) Toast.makeText(act, "Pas de ch. " + Detector.formatChapter(want) + " : le plus proche est le "
                + Detector.formatChapter(lastRows.get(idx).chapter), Toast.LENGTH_SHORT).show();
        if (idx >= shown) shown = ((idx + 6) / PAGE + 1) * PAGE;
        bind(title);
        final int target = idx;
        LinearLayout list = act.findViewById(R.id.eList);
        androidx.core.view.OneShotPreDrawListener.add(list, () -> scrollToRow(target));
    }

    /** Fait défiler la fiche jusqu'à la ligne, puis la fait clignoter doucement. */
    private void scrollToRow(int idx) {
        LinearLayout list = act.findViewById(R.id.eList);
        if (idx >= list.getChildCount()) return;
        View row = list.getChildAt(idx);
        View sv = act.findViewById(R.id.dScroll);
        int y = 0;
        for (View cur = row; cur != null && cur != sv; cur = cur.getParent() instanceof View ? (View) cur.getParent() : null) {
            y += cur.getTop();
        }
        View top = act.findViewById(R.id.dTopBar);
        int pad = (top == null ? 0 : top.getHeight()) + (int) (12 * act.getResources().getDisplayMetrics().density);
        if (sv instanceof androidx.core.widget.NestedScrollView) {
            ((androidx.core.widget.NestedScrollView) sv).smoothScrollTo(0, Math.max(0, y - pad));
        }
        android.graphics.drawable.ColorDrawable glow = new android.graphics.drawable.ColorDrawable(
                ContextCompat.getColor(act, R.color.brand));
        glow.setAlpha(0);
        row.setForeground(glow);
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofInt(glow, "alpha", 0, 64, 0, 64, 0);
        a.setStartDelay(250);
        a.setDuration(1300);
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                row.setForeground(null);
            }
        });
        a.start();
    }

    private void open(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            Toast.makeText(act, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
    }
}
