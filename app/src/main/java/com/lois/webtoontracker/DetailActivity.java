package com.lois.webtoontracker;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Page "Détails" d'une série. */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    private static final int HISTORY_PREVIEW = 8;

    private String title;
    private DbHelper db;
    private boolean historyExpanded = false;
    private boolean summaryExpanded = false;
    private ActivityResultLauncher<PickVisualMediaRequest> pickCover;
    private ActivityResultLauncher<String> notifPermission;

    public static void open(Context ctx, String title) {
        ctx.startActivity(new Intent(ctx, DetailActivity.class).putExtra(EXTRA_TITLE, title));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        db = DbHelper.get(this);
        if (title == null) {
            finish();
            return;
        }

        View topBar = findViewById(R.id.dTopBar);
        View content = findViewById(R.id.dContent);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dRoot), (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(topBar.getPaddingLeft(), b.top + dp(8), topBar.getPaddingRight(), 0);
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), b.bottom + dp(32));
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.dBack).setOnClickListener(v -> finish());
        findViewById(R.id.dMore).setOnClickListener(this::showMoreMenu);
        findViewById(R.id.dResume).setOnClickListener(v -> Resume.open(this, title));
        findViewById(R.id.dBell).setOnClickListener(v -> toggleBell());
        findViewById(R.id.dAdd).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.dSummary).setOnClickListener(v -> toggleSummary());
        findViewById(R.id.dSummaryMore).setOnClickListener(v -> toggleSummary());
        findViewById(R.id.dHistoryMore).setOnClickListener(v -> {
            historyExpanded = !historyExpanded;
            bindHistory();
        });

        pickCover = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) return;
            io(() -> {
                Covers.saveFromUri(this, title, uri);
                Covers.setCustom(this, title, true);
                WidgetProvider.updateAll(this);
            }, "Miniature mise à jour 🖼");
        });
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) Toast.makeText(this, "Autorise les notifications dans les réglages Android pour recevoir les alertes", Toast.LENGTH_LONG).show();
        });

        bindAll();
        loadInfo(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (title != null && db != null && findViewById(R.id.dTitle) != null) bindAll(); // retour de lecture : progression à jour
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (title == null) return;
        String note = ((EditText) findViewById(R.id.dNote)).getText().toString().trim();
        DbHelper.Meta m = db.getMeta(title);
        String old = m == null || m.note == null ? "" : m.note;
        if (!note.equals(old)) {
            db.setNote(title, note.isEmpty() ? null : note);
            NoteWriter.writeAsync(this);
        }
    }

    // ------------------------------------------------------------------ Affichage

    private DbHelper.Series series() {
        for (DbHelper.Series s : db.getSeries()) if (s.title.equalsIgnoreCase(title)) return s;
        return null;
    }

    private void bindAll() {
        DbHelper.Series s = series();
        if (s == null) {
            finish();
            return;
        }
        title = s.title;
        ((TextView) findViewById(R.id.dTitle)).setText(s.title);
        SeriesAdapter.bindCover(findViewById(R.id.dAvatar), findViewById(R.id.dCover), s.title);
        bindHeader();
        Progress pr = Progress.forResume(this, s.title);
        ((MaterialButton) findViewById(R.id.dResume)).setText(pr == null
                ? "▶  Reprendre la lecture"
                : pr.finished() ? "▶  Chapitre suivant"
                : "▶  Reprendre · " + pr.hint());
        bindTags(s);
        bindBell();
        bindProgress(s);
        bindWhere();
        bindStars();
        bindStats(s);
        bindHistory();
        DbHelper.Meta m = db.getMeta(title);
        EditText note = findViewById(R.id.dNote);
        if (note.getText().length() == 0 && m != null && m.note != null) note.setText(m.note);
        bindInfo(m == null ? null : SeriesInfo.parse(m.detailsJson));
    }

    private void bindHeader() {
        ImageView bg = findViewById(R.id.dHeaderBg);
        Bitmap cover = Covers.get(this, title);
        if (cover == null) {
            bg.setImageDrawable(null);
            bg.setBackgroundColor(SeriesAdapter.colorFor(title));
            return;
        }
        bg.setImageBitmap(cover);
        if (Build.VERSION.SDK_INT >= 31) {
            bg.setRenderEffect(RenderEffect.createBlurEffect(dp(28), dp(28), Shader.TileMode.CLAMP));
            bg.setAlpha(0.85f);
        } else {
            bg.setAlpha(0.45f); // pas de flou avant Android 12 : on atténue simplement
        }
    }

    private void bindTags(DbHelper.Series s) {
        ChipGroup g = findViewById(R.id.dTags);
        g.removeAllViews();
        for (String t : Titles.splitTags(s.tags)) {
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_site, g, false);
            c.setText(t);
            c.setChipIcon(null);
            c.setOnClickListener(v -> showTagEditor());
            g.addView(c);
        }
        Chip edit = (Chip) getLayoutInflater().inflate(R.layout.chip_site, g, false);
        edit.setText(s.tags.isEmpty() ? "＋ Ajouter des tags" : "＋");
        edit.setChipIcon(null);
        edit.setOnClickListener(v -> showTagEditor());
        g.addView(edit);
    }

    private void bindBell() {
        boolean on = db.isFollowed(title);
        MaterialButton bell = findViewById(R.id.dBell);
        bell.setIconResource(on ? R.drawable.ic_bell_on : R.drawable.ic_bell);
        bell.setIconTint(android.content.res.ColorStateList.valueOf(
                on ? ContextCompat.getColor(this, R.color.on_accent) : ContextCompat.getColor(this, R.color.badge_text)));
        bell.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.accent : R.color.badge_bg)));
        ((TextView) findViewById(R.id.dBellHint)).setText(on
                ? "🔔 Surveillée : tu seras prévenu dès qu'un nouveau chapitre sort (EN / FR)."
                : "Active la cloche pour être prévenu des nouveaux chapitres de cette série.");
    }

    private void toggleBell() {
        boolean on = !db.isFollowed(title);
        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        Follow.set(this, title, on);
        bindBell();
        View bell = findViewById(R.id.dBell);
        bell.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                .withEndAction(() -> bell.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
        bell.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Toast.makeText(this, on ? "🔔 Alertes activées pour « " + title + " »" : "Alertes désactivées", Toast.LENGTH_SHORT).show();
    }

    private void bindProgress(DbHelper.Series s) {
        double last = s.lastChapter;
        List<DbHelper.Release> fresh = db.newReleases(title);
        DbHelper.Meta m = db.getMeta(title);
        SeriesInfo info = m == null ? null : SeriesInfo.parse(m.detailsJson);
        double latest = !fresh.isEmpty() ? fresh.get(0).chapter : (info != null && info.chapters > 0 ? info.chapters : 0);

        TextView value = findViewById(R.id.dProgValue);
        TextView sub = findViewById(R.id.dProgSub);
        Progress pr = Progress.forResume(this, title);
        String where = pr == null ? "" : "\n📍 " + (pr.finished()
                ? "Chapitre " + Detector.formatChapter(pr.chapter) + " terminé"
                : "Arrêté au ch. " + Detector.formatChapter(pr.chapter) + ", " + pr.where());
        LinearProgressIndicator bar = findViewById(R.id.dProgress);
        if (latest > last) {
            int behind = (int) Math.ceil(latest - last);
            value.setText(Detector.formatChapter(last) + " / " + Detector.formatChapter(latest));
            bar.setProgressCompat((int) (1000 * last / latest), true);
            sub.setText(behind + (behind > 1 ? " chapitres" : " chapitre") + " de retard · ≈ "
                    + StatsCalc.formatDuration((long) behind * StatsCalc.MINUTES_PER_CHAPTER) + " de lecture" + where);
        } else if (latest > 0) {
            value.setText("À jour ✓");
            bar.setProgressCompat(1000, true);
            sub.setText("Tu as lu le dernier chapitre connu (ch. " + Detector.formatChapter(last) + ")." + where);
        } else {
            value.setText("Ch. " + Detector.formatChapter(last));
            bar.setProgressCompat(1000, true);
            sub.setText((db.isFollowed(title)
                    ? "Dernière sortie en cours de recherche…"
                    : "Active la cloche 🔔 pour connaître la dernière sortie.") + where);
        }
    }

    private void bindWhere() {
        List<DbHelper.Release> fresh = db.newReleases(title);
        View card = findViewById(R.id.dWhereCard);
        card.setVisibility(fresh.isEmpty() ? View.GONE : View.VISIBLE);
        ChipGroup g = findViewById(R.id.dWhere);
        g.removeAllViews();
        Set<String> seen = new HashSet<>();
        for (DbHelper.Release r : fresh) {
            if (r.url == null || !seen.add(r.label + r.lang)) continue;
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_site, g, false);
            c.setText(r.lang + " · " + r.label + " · ch. " + Detector.formatChapter(r.chapter));
            c.setOnClickListener(v -> openUrl(r.url));
            g.addView(c);
        }
    }

    private void bindStars() {
        LinearLayout box = findViewById(R.id.dStars);
        box.removeAllViews();
        DbHelper.Meta m = db.getMeta(title);
        int rating = m == null ? 0 : m.rating;
        for (int i = 1; i <= 5; i++) {
            ImageView star = new ImageView(this);
            star.setImageResource(i <= rating ? R.drawable.ic_star : R.drawable.ic_star_outline);
            star.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, i <= rating ? R.color.accent : R.color.text_soft)));
            star.setPadding(dp(2), 0, dp(6), 0);
            final int value = i;
            star.setOnClickListener(v -> {
                int cur = db.getMeta(title) == null ? 0 : db.getMeta(title).rating;
                db.setRating(title, cur == value ? 0 : value);
                NoteWriter.writeAsync(this);
                bindStars();
                v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
            });
            box.addView(star, new LinearLayout.LayoutParams(dp(40), dp(34)));
        }
    }

    private void bindStats(DbHelper.Series s) {
        List<DbHelper.Entry> h = db.getHistory(title);
        long first = Long.MAX_VALUE;
        for (DbHelper.Entry e : h) first = Math.min(first, e.readAt);
        long weeks = Math.max(1, (System.currentTimeMillis() - first) / (7L * 24 * 3600 * 1000));
        String pace = h.size() < 2 ? "—" : String.format(Locale.FRANCE, "%.1f", h.size() / (double) weeks) + " / sem.";
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy", Locale.FRANCE);

        LinearLayout box = findViewById(R.id.dStats);
        box.removeAllViews();
        addStatRow(box,
                new String[]{String.valueOf(s.count), s.count > 1 ? "chapitres lus" : "chapitre lu"},
                new String[]{"≈ " + StatsCalc.formatDuration((long) s.count * StatsCalc.MINUTES_PER_CHAPTER), "de lecture"});
        addStatRow(box,
                new String[]{first == Long.MAX_VALUE ? "—" : fmt.format(new Date(first)), "première lecture"},
                new String[]{pace, "rythme"});
        addStatRow(box,
                new String[]{DateUtils.getRelativeTimeSpanString(s.lastReadAt, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString(), "dernière lecture"},
                new String[]{"Ch. " + Detector.formatChapter(s.lastChapter), "dernier chapitre lu"});
    }

    private void addStatRow(LinearLayout box, String[] a, String[] b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(statBlock(a), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(statBlock(b), new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
    }

    private View statBlock(String[] v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView big = new TextView(this);
        big.setText(v[0]);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        big.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        big.setTextColor(ContextCompat.getColor(this, R.color.text_main));
        TextView small = new TextView(this);
        small.setText(v[1]);
        small.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        small.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        l.addView(big);
        l.addView(small);
        return l;
    }

    private void bindHistory() {
        LinearLayout box = findViewById(R.id.dHistory);
        box.removeAllViews();
        List<DbHelper.Entry> entries = db.getHistory(title);
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE);
        int n = historyExpanded ? entries.size() : Math.min(HISTORY_PREVIEW, entries.size());
        for (int i = 0; i < n; i++) {
            DbHelper.Entry e = entries.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_history, box, false);
            ((TextView) row.findViewById(R.id.hChapter)).setText("Ch. " + Detector.formatChapter(e.chapter));
            ((TextView) row.findViewById(R.id.hDate)).setText(fmt.format(new Date(e.readAt)));
            ((TextView) row.findViewById(R.id.hSource)).setText(e.source == null ? "" : e.source);
            row.setOnClickListener(v -> confirmDeleteEntry(e));
            box.addView(row);
        }
        TextView more = findViewById(R.id.dHistoryMore);
        more.setVisibility(entries.size() > HISTORY_PREVIEW ? View.VISIBLE : View.GONE);
        more.setText(historyExpanded ? "Réduire" : "Voir les " + entries.size() + " chapitres");
    }

    // ------------------------------------------------------------------ Infos en ligne (résumé…)

    private void loadInfo(boolean force) {
        DbHelper.Meta m = db.getMeta(title);
        boolean fresh = m != null && m.detailsJson != null
                && System.currentTimeMillis() - m.detailsAt < SeriesInfo.MAX_AGE_MS;
        if (fresh && !force) return;
        new Thread(() -> {
            SeriesInfo info = null;
            try {
                info = SeriesInfo.fetch(this, title);
            } catch (Exception ignored) {
            }
            final SeriesInfo i = info;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (i != null) {
                    bindInfo(i);
                    DbHelper.Series s = series();
                    if (s != null) bindProgress(s);
                } else if (db.getMeta(title) == null || db.getMeta(title).detailsJson == null) {
                    ((TextView) findViewById(R.id.dSummary)).setText(
                            "Pas de résumé trouvé (vérifie ta connexion, ou le titre est peut-être trop différent du titre officiel).");
                }
            });
        }).start();
    }

    private void bindInfo(SeriesInfo i) {
        TextView meta = findViewById(R.id.dMeta);
        if (i == null) {
            meta.setText("");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (i.country != null) parts.add(i.country);
        if (i.year > 0) parts.add(String.valueOf(i.year));
        if (i.status != null) parts.add(i.status);
        if (i.score > 0) parts.add("★ " + String.format(Locale.FRANCE, "%.1f", i.score / 10.0));
        meta.setText(String.join("  ·  ", parts));
        ((TextView) findViewById(R.id.dAlt)).setText(i.altTitle == null ? "" : i.altTitle);

        TextView sum = findViewById(R.id.dSummary);
        sum.setText(i.summary == null ? "Pas de résumé disponible pour cette série." : i.summary);
        ((TextView) findViewById(R.id.dSummarySrc)).setText(i.summarySource == null ? "" : i.summarySource);
        sum.post(() -> {
            boolean longText = sum.getLayout() != null && sum.getLayout().getEllipsisCount(sum.getLineCount() - 1) > 0;
            findViewById(R.id.dSummaryMore).setVisibility(longText || summaryExpanded ? View.VISIBLE : View.GONE);
        });

        LinearLayout infos = findViewById(R.id.dInfos);
        infos.removeAllViews();
        addInfo(infos, "Auteurs", i.authors);
        addInfo(infos, "Origine", i.country);
        addInfo(infos, "Début", i.year > 0 ? String.valueOf(i.year) : null);
        addInfo(infos, "Statut", i.status);
        addInfo(infos, "Chapitres", i.chapters > 0 ? String.valueOf(i.chapters) : null);
        addInfo(infos, "Note", i.score > 0 ? i.score + " / 100 (AniList)" : null);
        addInfo(infos, "Genres", i.genres.isEmpty() ? null : String.join(", ", i.genres));
        findViewById(R.id.dInfoCard).setVisibility(infos.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        MaterialButton ani = findViewById(R.id.dAniList);
        ani.setVisibility(i.siteUrl == null ? View.GONE : View.VISIBLE);
        ani.setOnClickListener(v -> openUrl(i.siteUrl));

        LinearLayout recos = findViewById(R.id.dRecos);
        recos.removeAllViews();
        for (SeriesInfo.Reco r : i.recos) recos.addView(recoCard(r));
        int vis = i.recos.isEmpty() ? View.GONE : View.VISIBLE;
        findViewById(R.id.dRecoTitle).setVisibility(vis);
        findViewById(R.id.dRecoScroll).setVisibility(vis);
    }

    private void addInfo(LinearLayout box, String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(ContextCompat.getColor(this, R.color.text_main));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(l, new LinearLayout.LayoutParams(dp(92), -2));
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
    }

    private View recoCard(SeriesInfo.Reco r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(108), -2);
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setElevation(dp(5));
        if (Build.VERSION.SDK_INT >= 28) {
            frame.setOutlineSpotShadowColor(ContextCompat.getColor(this, R.color.card_shadow));
            frame.setOutlineAmbientShadowColor(ContextCompat.getColor(this, R.color.card_shadow));
        }
        frame.setBackgroundResource(R.drawable.bg_card);
        ShapeableImageView img = new ShapeableImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setShapeAppearanceModel(img.getShapeAppearanceModel().toBuilder().setAllCornerSizes(dp(16)).build());
        img.setBackgroundColor(ContextCompat.getColor(this, R.color.badge_bg));
        frame.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
        ImageLoader.load(r.cover, img);
        if (r.score > 0) {
            TextView score = new TextView(this);
            score.setText("★ " + SeriesAdapter.formatScore(r.score));
            score.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            score.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
            score.setTextColor(0xFF1A1203);
            score.setBackgroundResource(R.drawable.bg_new_badge);
            score.setPadding(dp(7), dp(2), dp(7), dp(2));
            android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(-2, -2);
            sp.leftMargin = dp(6);
            sp.topMargin = dp(6);
            frame.addView(score, sp);
        }
        card.addView(frame, new LinearLayout.LayoutParams(dp(108), dp(152)));

        TextView t = new TextView(this);
        t.setText(r.title);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setTextColor(ContextCompat.getColor(this, R.color.text_main));
        t.setPadding(0, dp(7), 0, 0);
        card.addView(t);

        List<String> hint = new ArrayList<>();
        if (r.country != null) hint.add(r.country);
        if (!r.links.isEmpty()) hint.add(r.links.get(0)[0]);
        if (!hint.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(String.join(" · ", hint));
            h.setMaxLines(1);
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
            card.addView(h);
        }
        card.setOnClickListener(v -> showRecoSites(r));
        return card;
    }

    /** Où lire / découvrir une recommandation : sites officiels connus + bases de recherche. */
    private void showRecoSites(SeriesInfo.Reco r) {
        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] l : r.links) {
            if (!seen.add(l[0].toLowerCase(Locale.ROOT))) continue;
            labels.add("📖  Lire sur " + l[0]);
            urls.add(l[1]);
        }
        String q = Uri.encode(r.title);
        if (r.siteUrl != null) {
            labels.add("ℹ️  Fiche, avis et note (AniList)");
            urls.add(r.siteUrl);
        }
        labels.add("🔎  Chercher sur MangaDex (scans EN / FR)");
        urls.add("https://mangadex.org/search?q=" + q);
        labels.add("🔎  Chercher sur MangaUpdates (toutes les sorties)");
        urls.add("https://www.mangaupdates.com/search.html?search=" + q);
        if (!seen.contains("webtoon")) {
            labels.add("🔎  Chercher sur WEBTOON");
            urls.add("https://www.webtoons.com/fr/search?keyword=" + q);
        }
        labels.add("🌐  Rechercher sur le web");
        urls.add("https://www.google.com/search?q=" + Uri.encode(r.title + " webtoon"));

        String sub = (r.score > 0 ? "★ " + SeriesAdapter.formatScore(r.score) + " / 10" : "")
                + (r.country != null ? (r.score > 0 ? "  ·  " : "") + r.country : "");
        new MaterialAlertDialogBuilder(this)
                .setTitle(sub.isEmpty() ? r.title : r.title + "\n" + sub)
                .setItems(labels.toArray(new String[0]), (d, w) -> openUrl(urls.get(w)))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void toggleSummary() {
        summaryExpanded = !summaryExpanded;
        TextView sum = findViewById(R.id.dSummary);
        sum.setMaxLines(summaryExpanded ? Integer.MAX_VALUE : 6);
        ((TextView) findViewById(R.id.dSummaryMore)).setText(summaryExpanded ? "Réduire" : "Lire la suite");
        findViewById(R.id.dSummaryMore).setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ Menu ⋮

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "🏷  Modifier les tags");
        menu.getMenu().add(0, 2, 1, "🔗  Fusionner avec une autre série");
        menu.getMenu().add(0, 3, 2, "🖼  Changer la miniature");
        menu.getMenu().add(0, 4, 3, "🌐  Lier manuellement à WEBTOON");
        menu.getMenu().add(0, 5, 4, "🔄  Actualiser les infos");
        menu.getMenu().add(0, 6, 5, "🗑  Supprimer la série");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showTagEditor(); break;
                case 2: showMergePicker(); break;
                case 3: showCoverMenu(); break;
                case 4: showLinkDialog(); break;
                case 5:
                    ((TextView) findViewById(R.id.dSummary)).setText("Chargement du résumé…");
                    loadInfo(true);
                    break;
                case 6: confirmDeleteSeries(); break;
            }
            return true;
        });
        menu.show();
    }

    // ------------------------------------------------------------------ Actions

    private static final String[] SUGGESTED_TAGS = {"Action", "Fantasy", "Romance", "Comédie", "Drame",
            "Murim", "Isekai", "Régression", "Thriller", "Horreur", "Sport", "Slice of life", "En cours", "Terminé", "Favori ⭐"};

    private void showTagEditor() {
        DbHelper.Series s = series();
        if (s == null) return;
        Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(all, SUGGESTED_TAGS);
        all.addAll(db.allTags());
        List<String> current = Titles.splitTags(s.tags);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        ChipGroup group = new ChipGroup(this);
        for (String t : all) {
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_filter, group, false);
            c.setId(View.generateViewId());
            c.setText(t);
            for (String cur : current) if (cur.equalsIgnoreCase(t)) c.setChecked(true);
            group.addView(c);
        }
        box.addView(group);
        EditText custom = new EditText(this);
        custom.setHint("Autre tag (ex : Lecture du dimanche)");
        custom.setSingleLine(true);
        box.addView(custom);

        new MaterialAlertDialogBuilder(this)
                .setTitle("🏷 Tags")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < group.getChildCount(); i++) {
                        Chip c = (Chip) group.getChildAt(i);
                        if (c.isChecked()) sb.append(c.getText()).append(',');
                    }
                    sb.append(custom.getText());
                    db.setTags(title, sb.toString());
                    NoteWriter.writeAsync(this);
                    bindAll();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showMergePicker() {
        List<String> others = new ArrayList<>();
        for (DbHelper.Series o : db.getSeries()) if (!o.title.equalsIgnoreCase(title)) others.add(o.title);
        if (others.isEmpty()) {
            Toast.makeText(this, "Aucune autre série à fusionner", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = others.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Fusionner « " + title + " » avec…")
                .setItems(items, (d, w) -> new MaterialAlertDialogBuilder(this)
                        .setMessage("Les chapitres de « " + items[w] + " » seront rangés dans « " + title + " ».")
                        .setPositiveButton("Fusionner", (d2, w2) -> {
                            db.mergeSeries(items[w], title);
                            Covers.merge(this, items[w], title);
                            NoteWriter.writeAsync(this);
                            WidgetProvider.updateAll(this);
                            bindAll();
                        })
                        .setNegativeButton("Annuler", null)
                        .show())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showCoverMenu() {
        String[] items = {"🖼 Choisir une image dans la galerie", "🔄 Revenir à la miniature automatique", "🗑 Supprimer la miniature"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Miniature")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        pickCover.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
                        return;
                    }
                    Covers.delete(this, title);
                    getSharedPreferences("covers_meta", MODE_PRIVATE).edit()
                            .putLong("try_" + title.toLowerCase(Locale.ROOT), w == 1 ? 0 : System.currentTimeMillis())
                            .apply();
                    if (w == 1) CoverWorker.enqueueIfNeeded(this);
                    WidgetProvider.updateAll(this);
                    bindAll();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showLinkDialog() {
        EditText input = new EditText(this);
        input.setHint("https://www.webtoons.com/fr/…/list?title_no=…");
        input.setSingleLine(true);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip != null && clip.toString().contains("webtoons.com")) input.setText(clip);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        TextView help = new TextView(this);
        help.setText("Rarement utile : la série est trouvée automatiquement. Sinon, colle ici le lien de la série sur webtoons.com.");
        help.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        box.addView(help);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Lier à WEBTOON")
                .setView(box)
                .setPositiveButton("Lier", (d, w) -> {
                    String[] parsed = Detector.parseSeriesLink(input.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, "Lien non reconnu (il doit contenir title_no=…)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    db.linkWebtoons(title, parsed[0], parsed[1]);
                    if (db.isFollowed(title)) ChapterCheckWorker.checkNow(this);
                    Toast.makeText(this, "Série liée ✅", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showAddDialog() {
        EditText chapter = new EditText(this);
        chapter.setHint("Chapitre (ex : 42)");
        chapter.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(chapter, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Ajouter un chapitre lu")
                .setView(box)
                .setPositiveButton("Ajouter", (d, w) -> {
                    try {
                        double c = Double.parseDouble(chapter.getText().toString().trim().replace(',', '.'));
                        db.insertRead(title, c, System.currentTimeMillis(), "manuel");
                        NoteWriter.writeAsync(this);
                        WidgetProvider.updateAll(this);
                        bindAll();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Numéro de chapitre invalide", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDeleteEntry(DbHelper.Entry e) {
        new MaterialAlertDialogBuilder(this)
                .setMessage("Retirer le chapitre " + Detector.formatChapter(e.chapter) + " de l'historique ?")
                .setPositiveButton("Retirer", (d, w) -> {
                    db.deleteEntry(e.id);
                    NoteWriter.writeAsync(this);
                    WidgetProvider.updateAll(this);
                    bindAll();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDeleteSeries() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Supprimer la série ?")
                .setMessage("« " + title + " » et tout son historique seront supprimés.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    db.deleteSeries(title);
                    Covers.delete(this, title);
                    Follow.sync(this);
                    NoteWriter.writeAsync(this);
                    WidgetProvider.updateAll(this);
                    finish();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------ Utilitaires

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
    }

    interface IoTask {
        void run() throws Exception;
    }

    private void io(IoTask task, String okMessage) {
        new Thread(() -> {
            String msg = okMessage;
            try {
                task.run();
            } catch (Exception e) {
                msg = "Échec : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
                bindAll();
            });
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
