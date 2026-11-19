package com.lois.webtoontracker;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Fiche série immersive : hero + onglets Aperçu / Mon suivi / Détails. */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";

    private String title;
    private DbHelper db;
    private EpisodesTab episodes;
    private boolean summaryExpanded = false;
    private boolean altExpanded = false;
    private ActivityResultLauncher<PickVisualMediaRequest> pickCover;
    private ActivityResultLauncher<String> notifPermission;

    public static void open(Context ctx, String title) {
        ctx.startActivity(new Intent(ctx, DetailActivity.class).putExtra(EXTRA_TITLE, title));
        if (ctx instanceof android.app.Activity && Build.VERSION.SDK_INT < 34) {
            ((android.app.Activity) ctx).overridePendingTransition(R.anim.detail_in, R.anim.hold_back);
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.hold_return, R.anim.detail_out);
    }

    private int currentTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 34) {
            // La fiche monte en apparaissant, et redescend en se fermant.
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.detail_in, R.anim.hold_back);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold_return, R.anim.detail_out);
        }
        setContentView(R.layout.activity_detail);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        db = DbHelper.get(this);
        if (title == null) {
            finish();
            return;
        }

        View topBar = findViewById(R.id.dTopBar);
        View sticky = findViewById(R.id.dSticky);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dRoot), (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(topBar.getPaddingLeft(), b.top + dp(8), topBar.getPaddingRight(), dp(8));
            sticky.setPadding(sticky.getPaddingLeft(), dp(14), sticky.getPaddingRight(), b.bottom + dp(14));
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.dBack).setOnClickListener(v -> finish());
        findViewById(R.id.dMore).setOnClickListener(this::showMoreMenu);
        findViewById(R.id.dResume).setOnClickListener(v -> Resume.open(this, title));
        findViewById(R.id.dStickyResume).setOnClickListener(v -> Resume.open(this, title));
        findViewById(R.id.dBellRow).setOnClickListener(v -> toggleBell(!db.isFollowed(title)));
        findViewById(R.id.dSummary).setOnClickListener(v -> toggleSummary());
        findViewById(R.id.dSummaryMore).setOnClickListener(v -> toggleSummary());
        episodes = new EpisodesTab(this, new EpisodesTab.Host() {
            @Override
            public void onEpisodesChanged() {
                NoteWriter.writeAsync(DetailActivity.this);
                WidgetProvider.updateAll(DetailActivity.this);
                bindAll();
            }

            @Override
            public void confirmDelete(DbHelper.Entry e) {
                confirmDeleteEntry(e);
            }

            @Override
            public void showAddDialog() {
                DetailActivity.this.showAddDialog();
            }
        });
        findViewById(R.id.dAltHeader).setOnClickListener(v -> {
            altExpanded = !altExpanded;
            findViewById(R.id.dAltList).setVisibility(altExpanded ? View.VISIBLE : View.GONE);
            findViewById(R.id.dAltIcon).animate().rotation(altExpanded ? 180 : 0).setDuration(200).start();
        });

        TabLayout tabs = findViewById(R.id.dTabs);
        tabs.addTab(tabs.newTab().setText("Aperçu"));
        tabs.addTab(tabs.newTab().setText("Épisodes"));
        tabs.addTab(tabs.newTab().setText("Mon suivi"));
        tabs.addTab(tabs.newTab().setText("Détails"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        ((NestedScrollView) findViewById(R.id.dScroll)).setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> onScrolled(y));

        pickCover = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) return;
            io(() -> {
                Covers.saveFromUri(this, title, uri);
                Covers.markUserPick(this, title);
                WidgetProvider.updateAll(this);
            }, "Miniature mise à jour 🖼");
        });
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) Toast.makeText(this, "Autorise les notifications dans les réglages Android pour recevoir les alertes", Toast.LENGTH_LONG).show();
        });

        bindAll();
        loadInfo(false);
        Anim.pressAll(findViewById(R.id.dRoot));
        if (savedInstanceState == null) playIntro();
    }

    /** Arrivée sur la fiche : la miniature « pop », puis le reste en cascade. */
    private void playIntro() {
        Anim.zoomIn(findViewById(R.id.dCover), 120);
        Anim.zoomIn(findViewById(R.id.dAvatar), 120);
        ViewGroup content = findViewById(R.id.dContent);
        int k = 1;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || i == 0) continue;
            Anim.enter(c, k++);
            if (k > 6) break;
        }
        Anim.enter(((ViewGroup) content.getChildAt(0)).getChildAt(1), 1); // titre & infos
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

    // ------------------------------------------------------------------ Onglets & défilement

    private void showTab(int i) {
        int[] ids = {R.id.tabOverview, R.id.tabEpisodes, R.id.tabTracking, R.id.tabDetails};
        if (i == 1 && currentTab != 1) {   // les épisodes arrivent en cascade
            episodes.animateNextBind();
            episodes.bind(title);
        }
        int dir = i >= currentTab ? 1 : -1;   // glisse dans le sens de l'onglet choisi
        currentTab = i;
        for (int k = 0; k < ids.length; k++) {
            View v = findViewById(ids[k]);
            if (k == i) {
                v.setVisibility(View.VISIBLE);
                Anim.slideIn(v, dir);
            } else {
                v.setVisibility(View.GONE);
            }
        }
    }

    /** Barre du haut : se remplit et affiche miniature + titre ; bouton Reprendre collant. */
    private void onScrolled(int y) {
        View hero = findViewById(R.id.dHero);
        View topBar = findViewById(R.id.dTopBar);
        float t = Math.max(0f, Math.min(1f, (y - hero.getHeight() * 0.35f) / (hero.getHeight() * 0.4f)));
        topBar.setBackgroundColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(this, R.color.screen_bg), (int) (245 * t)));
        topBar.setElevation(t * dp(4));
        float tt = Math.max(0f, (t - 0.6f) / 0.4f);
        findViewById(R.id.dBarThumb).setAlpha(tt);
        findViewById(R.id.dBarTitle).setAlpha(tt);

        View resume = findViewById(R.id.dResume);
        View content = findViewById(R.id.dContent);
        int resumeBottom = content.getTop() + resume.getBottom();
        boolean showSticky = y + topBar.getHeight() > resumeBottom;
        View sticky = findViewById(R.id.dSticky);
        if (showSticky && sticky.getVisibility() != View.VISIBLE) {
            sticky.setVisibility(View.VISIBLE);
            sticky.setAlpha(0f);
            sticky.setTranslationY(dp(30));
            sticky.animate().alpha(1f).translationY(0).setDuration(180).start();
        } else if (!showSticky && sticky.getVisibility() == View.VISIBLE) {
            sticky.setVisibility(View.GONE);
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
        ((TextView) findViewById(R.id.dBarTitle)).setText(s.title);
        SeriesAdapter.bindCover(findViewById(R.id.dAvatar), findViewById(R.id.dCover), s.title);
        Bitmap cover = Covers.get(this, s.title);
        ShapeableImageView thumb = findViewById(R.id.dBarThumb);
        if (cover != null) thumb.setImageBitmap(cover);
        else thumb.setBackgroundColor(SeriesAdapter.colorFor(s.title));
        bindHeader();
        bindTags(s);
        bindBell();
        bindResume(s);
        bindTracking(s);
        bindProgress(s);
        bindWhere();
        bindStars();
        episodes.bind(title);
        DbHelper.Meta m = db.getMeta(title);
        EditText note = findViewById(R.id.dNote);
        if (note.getText().length() == 0 && m != null && m.note != null) note.setText(m.note);
        bindInfo(m == null ? null : SeriesInfo.parse(m.detailsJson), s);
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
        // Léger flou : la couverture agrandie reste belle, sans pixels visibles.
        if (Build.VERSION.SDK_INT >= 31) {
            bg.setRenderEffect(RenderEffect.createBlurEffect(dp(5), dp(5), Shader.TileMode.CLAMP));
        }
        bg.setAlpha(0.9f);
    }

    private void bindTags(DbHelper.Series s) {
        ChipGroup g = findViewById(R.id.dTags);
        g.removeAllViews();
        List<String> tags = Titles.splitTags(s.tags);
        int shown = Math.min(3, tags.size());
        for (int i = 0; i < shown; i++) {
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_site, g, false);
            c.setText(tags.get(i));
            c.setChipIcon(null);
            c.setOnClickListener(v -> showTagEditor());
            g.addView(c);
        }
        Chip more = (Chip) getLayoutInflater().inflate(R.layout.chip_site, g, false);
        more.setChipIcon(null);
        more.setText(tags.size() > shown ? "+" + (tags.size() - shown) : tags.isEmpty() ? "＋ Ajouter des tags" : "＋");
        more.setOnClickListener(v -> showTagEditor());
        g.addView(more);
    }

    private void bindBell() {
        boolean on = db.isFollowed(title);
        ImageView icon = findViewById(R.id.dBellIcon);
        icon.setImageResource(on ? R.drawable.ic_bell_on : R.drawable.ic_bell);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, on ? R.color.accent : R.color.text_main)));
        TextView label = findViewById(R.id.dBellLabel);
        label.setText(on ? "Alertes activées" : "Alertes");
        label.setTextColor(ContextCompat.getColor(this, on ? R.color.accent : R.color.text_main));
        MaterialSwitch sw = findViewById(R.id.dFollowSwitch);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> toggleBell(checked));
        ((TextView) findViewById(R.id.dFollowSub)).setText(on
                ? "Tu seras prévenu dès qu'un chapitre sort (EN / FR)"
                : "Une notification à chaque nouveau chapitre");
    }

    private void toggleBell(boolean on) {
        if (on == db.isFollowed(title)) return;
        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        Follow.set(this, title, on);
        bindBell();
        View bell = findViewById(R.id.dBellIcon);
        if (on) Anim.ring(bell);
        else Anim.pop(bell);
        bell.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Toast.makeText(this, on ? "🔔 Alertes activées pour « " + title + " »" : "Alertes désactivées", Toast.LENGTH_SHORT).show();
    }

    private void bindResume(DbHelper.Series s) {
        Progress pr = Progress.forResume(this, s.title);
        String label = pr != null && pr.finished() ? "Chapitre suivant" : "Reprendre la lecture";
        ((MaterialButton) findViewById(R.id.dResume)).setText(label);
        ((MaterialButton) findViewById(R.id.dStickyResume)).setText(label);
        ((TextView) findViewById(R.id.dResumeHint)).setText("Dernier chapitre lu : " + Detector.formatChapter(s.lastChapter)
                + (pr != null && !pr.finished() ? " · " + pr.where() : ""));
    }

    private void bindTracking(DbHelper.Series s) {
        List<DbHelper.Entry> h = db.getHistory(title);
        long first = Long.MAX_VALUE;
        for (DbHelper.Entry e : h) first = Math.min(first, e.readAt);
        ((TextView) findViewById(R.id.dTrackLast)).setText("Chapitre " + Detector.formatChapter(s.lastChapter));
        ((TextView) findViewById(R.id.dTrackWhen)).setText("Repris " + DateUtils.getRelativeTimeSpanString(
                s.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase(Locale.FRANCE));
        ((TextView) findViewById(R.id.dTrackCount)).setText(String.valueOf(s.count));
        ((TextView) findViewById(R.id.dTrackCountLabel)).setText(s.count > 1 ? "chapitres lus" : "chapitre lu");
        ((TextView) findViewById(R.id.dTrackTime)).setText("≈ " + StatsCalc.formatDuration((long) s.count * StatsCalc.MINUTES_PER_CHAPTER));
        long weeks = first == Long.MAX_VALUE ? 1 : Math.max(1, (System.currentTimeMillis() - first) / (7L * 24 * 3600 * 1000));
        ((TextView) findViewById(R.id.dTrackPace)).setText(h.size() < 2 ? "—"
                : String.format(Locale.FRANCE, "%.1f", h.size() / (double) weeks));
        ((TextView) findViewById(R.id.dTrackFirst)).setText(first == Long.MAX_VALUE ? "—"
                : new SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(new Date(first)));
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
            star.setTag(R.id.anim_press, Boolean.TRUE); // les étoiles ont leur propre animation
            final int value = i;
            star.setOnClickListener(v -> {
                int cur = db.getMeta(title) == null ? 0 : db.getMeta(title).rating;
                int next = cur == value ? 0 : value;
                db.setRating(title, next);
                NoteWriter.writeAsync(this);
                bindStars();
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                // les étoiles s'allument une à une
                LinearLayout row = findViewById(R.id.dStars);
                for (int k = 0; k < next && k < row.getChildCount(); k++) {
                    View st = row.getChildAt(k);
                    st.postDelayed(() -> Anim.pop(st), k * 55L);
                }
            });
            box.addView(star, new LinearLayout.LayoutParams(dp(40), dp(34)));
        }
    }

    private static String sourceLabel(String s) {
        switch (s) {
            case "app": return "Appli de lecture";
            case "manuel": return "Ajouté à la main";
            case DbHelper.SOURCE_BEFORE: return "Lu avant l'appli";
            case "sauvegarde": return "Restauré";
            default: return s;
        }
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
                    DbHelper.Series s = series();
                    bindInfo(i, s);
                    if (s != null) bindProgress(s);
                } else if (db.getMeta(title) == null || db.getMeta(title).detailsJson == null) {
                    ((TextView) findViewById(R.id.dSummary)).setText(
                            "Pas de résumé trouvé (vérifie ta connexion, ou le titre est peut-être trop différent du titre officiel).");
                }
            });
        }).start();
    }

    private void bindInfo(SeriesInfo i, DbHelper.Series s) {
        TextView meta = findViewById(R.id.dMeta);
        TextView format = findViewById(R.id.dFormat);
        TextView year = findViewById(R.id.dYear);
        if (i == null) {
            meta.setText(s != null && s.score > 0 ? "★ " + SeriesAdapter.formatScore(s.score) + " / 10" : "");
            format.setVisibility(View.GONE);
            year.setText("");
            findViewById(R.id.dGenresTitle).setVisibility(View.GONE);
            findViewById(R.id.dCreditsTitle).setVisibility(View.GONE);
            findViewById(R.id.dCredits).setVisibility(View.GONE);
            return;
        }
        format.setVisibility(i.format == null ? View.GONE : View.VISIBLE);
        if (i.format != null) format.setText(i.format.toUpperCase(Locale.FRANCE));
        year.setText(i.year > 0 ? String.valueOf(i.year) : "");
        List<String> parts = new ArrayList<>();
        if (i.status != null) parts.add("● " + i.status);
        if (i.score > 0) parts.add("★ " + String.format(Locale.FRANCE, "%.1f", i.score / 10.0) + " / 10");
        meta.setText(String.join("   |   ", parts));

        TextView sum = findViewById(R.id.dSummary);
        sum.setText(i.summary == null ? "Pas de résumé disponible pour cette série." : i.summary);
        ((TextView) findViewById(R.id.dSummarySrc)).setText(i.summarySource == null ? "" : i.summarySource);
        sum.post(() -> {
            boolean longText = sum.getLayout() != null && sum.getLineCount() > 0
                    && sum.getLayout().getEllipsisCount(sum.getLineCount() - 1) > 0;
            findViewById(R.id.dSummaryMore).setVisibility(longText || summaryExpanded ? View.VISIBLE : View.GONE);
        });

        // À propos de la série
        LinearLayout infos = findViewById(R.id.dInfos);
        infos.removeAllViews();
        addInfo(infos, "Format", i.format);
        addInfo(infos, "Origine", i.origin);
        addInfo(infos, "Publication", i.year > 0 ? "Depuis " + i.year : null);
        addInfo(infos, "Statut", i.status == null ? null : "● " + i.status);
        addInfo(infos, "Chapitres", i.chapters > 0 ? String.valueOf(i.chapters) : null);
        addInfo(infos, "Note AniList", i.score > 0 ? "★ " + String.format(Locale.FRANCE, "%.1f", i.score / 10.0) + " / 10" : null);
        findViewById(R.id.dInfoCard).setVisibility(infos.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        // Genres
        ChipGroup genres = findViewById(R.id.dGenres);
        genres.removeAllViews();
        for (String g : i.genres) {
            Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_site, genres, false);
            c.setChipIcon(null);
            c.setText(g);
            c.setClickable(false);
            genres.addView(c);
        }
        findViewById(R.id.dGenresTitle).setVisibility(i.genres.isEmpty() ? View.GONE : View.VISIBLE);

        // Crédits
        LinearLayout credits = findViewById(R.id.dCredits);
        credits.removeAllViews();
        for (int k = 0; k < i.credits.size(); k++) {
            String[] c = i.credits.get(k);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_person);
            ic.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_soft)));
            row.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, 0, 0);
            TextView name = new TextView(this);
            name.setText(c[0]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setTextColor(ContextCompat.getColor(this, R.color.text_main));
            TextView role = new TextView(this);
            role.setText(c[1]);
            role.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            role.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
            col.addView(name);
            col.addView(role);
            row.addView(col);
            if (k > 0) credits.addView(divider());
            credits.addView(row);
        }
        boolean hasCredits = !i.credits.isEmpty();
        findViewById(R.id.dCreditsTitle).setVisibility(hasCredits ? View.VISIBLE : View.GONE);
        credits.setVisibility(hasCredits ? View.VISIBLE : View.GONE);

        MaterialButton ani = findViewById(R.id.dAniList);
        ani.setVisibility(i.siteUrl == null ? View.GONE : View.VISIBLE);
        ani.setOnClickListener(v -> openUrl(i.siteUrl));

        findViewById(R.id.dAltCard).setVisibility(i.altTitles.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.dAltList)).setText(String.join("\n", i.altTitles));

        LinearLayout recos = findViewById(R.id.dRecos);
        recos.removeAllViews();
        for (SeriesInfo.Reco r : i.recos) recos.addView(recoCard(r));
        Anim.pressAll(recos);
        if (recos.getChildCount() > 0 && recos.getTag() == null) {   // la 1re fois : les cartes glissent en cascade
            recos.setTag(Boolean.TRUE);
            Anim.stagger(recos);
        }
        int vis = i.recos.isEmpty() ? View.GONE : View.VISIBLE;
        findViewById(R.id.dRecoTitle).setVisibility(vis);
        findViewById(R.id.dRecoScroll).setVisibility(vis);
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(ContextCompat.getColor(this, R.color.hairline));
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return d;
    }

    private void addInfo(LinearLayout box, String label, String value) {
        if (value == null || value.isEmpty()) return;
        if (box.getChildCount() > 0) box.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(ContextCompat.getColor(this, value.startsWith("●") ? R.color.text_main : R.color.text_main));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (value.startsWith("●")) {
            android.text.SpannableString sp = new android.text.SpannableString(value);
            sp.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.online)),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            v.setText(sp);
        }
        if (value.startsWith("★")) {
            android.text.SpannableString sp = new android.text.SpannableString(value);
            sp.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent)),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            v.setText(sp);
        }
        row.addView(l, new LinearLayout.LayoutParams(dp(120), -2));
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
        sum.setMaxLines(summaryExpanded ? Integer.MAX_VALUE : 5);
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
        chapter.setHint("Chapitre (ex : 42) ou plage (ex : 1-37)");
        chapter.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(chapter, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Ajouter des chapitres lus")
                .setView(box)
                .setPositiveButton("Ajouter", (d, w) -> {
                    try {
                        String txt = chapter.getText().toString().trim().replace(',', '.').replace('→', '-');
                        java.util.regex.Matcher range = java.util.regex.Pattern
                                .compile("^(\\d+)\\s*(?:-|à|a)\\s*(\\d+)$").matcher(txt);
                        if (range.find()) {
                            int a = Integer.parseInt(range.group(1)), b = Integer.parseInt(range.group(2));
                            if (b < a || b - a > 3000) throw new NumberFormatException();
                            int added = db.markRangeRead(title, a, b, System.currentTimeMillis());
                            Toast.makeText(this, added + " chapitres ajoutés", Toast.LENGTH_SHORT).show();
                        } else {
                            db.insertRead(title, Double.parseDouble(txt), System.currentTimeMillis(), "manuel");
                        }
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
                    ChapterThumbs.delete(this, title, e.chapter);
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
