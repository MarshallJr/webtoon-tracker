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
        java.util.TreeSet<Double> all = new java.util.TreeSet<>(read.keySet());
        all.addAll(fresh.keySet());
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

        // En-tête
        TextView count = act.findViewById(R.id.eCount);
        String c = all.size() + (all.size() > 1 ? " épisodes" : " épisode") + "  ·  " + entries.size()
                + (entries.size() > 1 ? " lus" : " lu");
        count.setText(c);
        ((TextView) act.findViewById(R.id.eSort)).setText(newest ? "Plus récents" : "Plus anciens");
        bindBackfill(entries);

        // Liste
        LinearLayout list = act.findViewById(R.id.eList);
        list.removeAllViews();
        Progress progress = Progress.get(act, title);
        Bitmap strip = ChapterThumbs.coverStrip(act, title);
        int n = Math.min(shown, rows.size());
        for (int i = 0; i < n; i++) {
            View v = rowView(list, rows.get(i), furthest, progress, meta, entries, strip);
            list.addView(v);
            if (animateNext && i < 10) Anim.enter(v, i);
        }
        animateNext = false;

        TextView more = act.findViewById(R.id.eMore);
        int left = rows.size() - n;
        more.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        more.setText("Afficher " + Math.min(PAGE, left) + " de plus  (" + left + " restants)");
        act.findViewById(R.id.eHint).setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
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
                         List<DbHelper.Entry> entries, Bitmap strip) {
        View v = LayoutInflater.from(act).inflate(R.layout.item_episode, parent, false);
        v.setTag(R.id.anim_press, Boolean.TRUE); // ligne pleine largeur : effet d'appui classique (pas de zoom)
        TextView name = v.findViewById(R.id.eName), badge = v.findViewById(R.id.eBadge);
        TextView date = v.findViewById(R.id.eDate), extra = v.findViewById(R.id.eExtra);
        TextView btn = v.findViewById(R.id.eBtn), num = v.findViewById(R.id.eNum);
        ImageView thumb = v.findViewById(R.id.eThumb);
        LinearProgressIndicator bar = v.findViewById(R.id.eBar);
        String ch = Detector.formatChapter(r.chapter);
        name.setText("Épisode " + ch);

        // Vignette : capture du chapitre si on l'a, sinon la couverture avec le numéro.
        thumb.setImageBitmap(strip);
        num.setText(ch);
        num.setVisibility(View.VISIBLE);
        if (r.entry != null) {
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
        } else if (r.entry == null) {
            // Pas encore lu : en clair
            date.setText(r.chapter > furthest ? "À lire" : "Pas lu");
            btn.setText("Lire");
            String url = ChapterLinks.urlFor(meta, entries, r.chapter);
            action = url == null ? null : () -> open(url);
            v.setOnLongClickListener(x -> markRead(x, r.chapter));
        } else {
            DbHelper.Entry e = r.entry;
            boolean current = e.chapter == furthest;
            boolean before = DbHelper.SOURCE_BEFORE.equals(e.source);
            date.setText(before ? "Lu avant l'appli" : dayFmt.format(new Date(e.readAt)));
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
                String url = ChapterLinks.urlFor(meta, entries, e.chapter);
                action = url == null ? null : () -> open(url);
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

    /** Appui long sur un chapitre pas encore lu → le marquer comme lu. */
    private boolean markRead(View anchor, double chapter) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add("Marquer comme lu");
        pm.setOnMenuItemClickListener(it -> {
            db.insertRead(title, chapter, System.currentTimeMillis(), "manuel");
            host.onEpisodesChanged();
            return true;
        });
        pm.show();
        return true;
    }

    private void open(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            Toast.makeText(act, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
    }
}
