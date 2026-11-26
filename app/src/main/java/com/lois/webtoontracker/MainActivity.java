package com.lois.webtoontracker;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.format.DateUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private SeriesAdapter adapter;
    private TextView statusPill, statSeries, statChapters, statWeek, greeting, sectionTitle;
    private View emptyState;
    private ActivityResultLauncher<String> pickNoteFile;
    private ActivityResultLauncher<String> pickAutoBackup, pickExport;
    private ActivityResultLauncher<String[]> pickRestore;
    private TextView backupStatusView; // dans la boîte Réglages quand elle est ouverte
    private ActivityResultLauncher<String> notifPermission;
    private String selectedTag = null;
    private List<List<String>> duplicates = new java.util.ArrayList<>();

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    private long createdAt;
    private boolean introPlayed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Écran de démarrage : le logo reste un court instant, puis s'envole pendant que l'accueil apparaît.
        androidx.core.splashscreen.SplashScreen splash = androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        createdAt = android.os.SystemClock.uptimeMillis();
        // Le logo reste au moins 380 ms, et jusqu'à ce que l'accueil soit rempli (1,5 s au plus).
        splash.setKeepOnScreenCondition(() -> {
            long t = android.os.SystemClock.uptimeMillis() - createdAt;
            return t < 380 || (!firstBound && t < 1500);
        });
        splash.setOnExitAnimationListener(this::animateSplashExit);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        introPlayed = savedInstanceState != null; // rotation, etc. : pas de nouvelle intro

        // Android 15 affiche l'appli bord à bord : on décale le contenu sous la barre d'état.
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setupNavigation();

        statusPill = findViewById(R.id.statusPill);
        statSeries = findViewById(R.id.statSeries);
        statChapters = findViewById(R.id.statChapters);
        statWeek = findViewById(R.id.statWeek);
        greeting = findViewById(R.id.greeting);
        emptyState = findViewById(R.id.emptyState);

        RecyclerView list = findViewById(R.id.seriesList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SeriesAdapter(s -> DetailActivity.open(this, s.title), this::confirmDeleteSeries);
        adapter.setOnPlay(s -> Resume.open(this, s.title));
        list.setAdapter(adapter);

        statusPill.setOnClickListener(v -> {
            if (!isServiceEnabled()) {
                Toast.makeText(this, "Cherche « Webtoon Tracker » et active-le", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        pickNoteFile = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/markdown"), this::onNoteFilePicked);
        pickAutoBackup = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"), this::onAutoBackupPicked);
        pickExport = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"), this::onExportPicked);
        pickRestore = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onRestorePicked);
        findViewById(R.id.emptyRestoreButton).setOnClickListener(v ->
                pickRestore.launch(new String[]{"application/json", "text/*", "application/octet-stream"}));
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettings());
        findViewById(R.id.addButton).setOnClickListener(v -> showAddDialog(null));
        findViewById(R.id.libAddIcon).setOnClickListener(v -> showAddDialog(null));
        findViewById(R.id.homeStatsLink).setOnClickListener(v -> showPage(PAGE_ACTIVITY));
        findViewById(R.id.libRhythm).setOnClickListener(v -> showPage(PAGE_ACTIVITY));
        findViewById(R.id.homeSeeAll).setOnClickListener(v -> showPage(PAGE_LIBRARY));
        findViewById(R.id.homeSearch).setOnClickListener(v -> {
            showPage(PAGE_LIBRARY);
            openSearch();
        });
        findViewById(R.id.homeFilter).setOnClickListener(v -> {
            showPage(PAGE_LIBRARY);
            showFilterDialog();
        });
        findViewById(R.id.libSearchButton).setOnClickListener(v -> {
            View field = findViewById(R.id.searchInput);
            if (field.getVisibility() == View.VISIBLE && ((EditText) field).getText().length() == 0) {
                field.setVisibility(View.GONE);
            } else {
                openSearch();
            }
        });
        findViewById(R.id.filterChip).setOnClickListener(v -> showFilterDialog());
        findViewById(R.id.dupButton).setOnClickListener(v -> showMergeSuggestions());
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) Toast.makeText(this, "Sans l'autorisation de notifications, impossible de t'alerter", Toast.LENGTH_LONG).show();
        });
        Follow.sync(this);

        ((EditText) findViewById(R.id.searchInput)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                updateEmpty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.updatesCard).setOnClickListener(v -> showUpdates(null));
        showPendingCrash();
        handleIntent(getIntent());
        new Thread(() -> {
            if (Repair.run(this) > 0) runOnUiThread(this::refresh); // titres incohérents corrigés
            CoverWorker.cleanupBadImages(this);                       // images prises sur l'écran de veille
            CoverWorker.cleanupDuplicateThumbs(this);                 // même image sur plusieurs chapitres
            SeriesResolver.rematchAllOnce(this);                      // une fois : titres traduits (EN ↔ FR…) recherchés à nouveau
        }).start();
        Anim.pressAll(root);
    }

    private void animateSplashExit(androidx.core.splashscreen.SplashScreenViewProvider provider) {
        View screen = provider.getView();
        try {
            View icon = provider.getIconView();
            icon.animate().scaleX(1.25f).scaleY(1.25f).translationY(-icon.getHeight() * 0.08f).alpha(0f)
                    .setDuration(380).setInterpolator(Anim.EASE).start();
        } catch (Throwable ignored) {
        }
        screen.animate().alpha(0f).setStartDelay(60).setDuration(340).setInterpolator(Anim.EASE)
                .withEndAction(provider::remove).start();
        playIntro();
    }

    /** Arrivée sur l'accueil : les blocs apparaissent en cascade (une fois par lancement). */
    private void playIntro() {
        if (introPlayed) return;
        introPlayed = true;
        Anim.stagger(((ViewGroup) findViewById(R.id.pageHome)).getChildAt(0));
        Anim.enter(findViewById(R.id.bottomNav), 3);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(ChapterCheckWorker.EXTRA_SHOW_UPDATES)) return;
        String t = intent.getStringExtra(ChapterCheckWorker.EXTRA_SHOW_UPDATES);
        intent.removeExtra(ChapterCheckWorker.EXTRA_SHOW_UPDATES);
        findViewById(R.id.root).post(() -> showUpdates(t));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, updateReceiver,
                new IntentFilter(ReadingTrackerService.ACTION_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(updateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ Affichage

    /** Lectures de la base pour l'accueil : sur un fil à part, l'écran reste fluide. */
    private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger refreshGen = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean firstBound;

    /** Tout ce qu'affiche l'accueil, lu en une fois. */
    private static final class HomeData {
        List<DbHelper.Series> series;
        final java.util.Set<String> tags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        java.util.LinkedHashMap<String, List<DbHelper.Release>> updates;
        final java.util.Map<String, Double> fresh = new java.util.HashMap<>();
        List<List<String>> duplicates;
        int week, total;
    }

    private static HomeData loadHome(Context ctx) {
        DbHelper db = DbHelper.get(ctx);
        HomeData d = new HomeData();
        d.series = db.getSeries();
        List<String> titles = new java.util.ArrayList<>();
        for (DbHelper.Series s : d.series) {
            d.tags.addAll(Titles.splitTags(s.tags));
            titles.add(s.title);
        }
        d.updates = groupedUpdates(ctx, null);
        for (java.util.Map.Entry<String, List<DbHelper.Release>> e : d.updates.entrySet()) {
            d.fresh.put(e.getKey(), e.getValue().get(0).chapter);
        }
        d.duplicates = Titles.duplicateGroups(titles);
        d.week = db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        d.total = db.totalChapters();
        return d;
    }

    private void refresh() {
        boolean on = isServiceEnabled();
        statusPill.setText(on ? "Analyse active" : "Analyse désactivée · activer");
        android.graphics.drawable.Drawable dot = statusPill.getCompoundDrawablesRelative()[0];
        if (dot != null) dot.mutate().setTint(on ? 0xFF3DDC84 : 0xFFFF6B6B);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 || h >= 18 ? "Bonsoir 🌙" : "Bonjour ☀️");

        final int gen = refreshGen.incrementAndGet();
        final Context app = getApplicationContext();
        LOADER.execute(() -> {
            if (gen != refreshGen.get()) return;   // une actualisation plus récente suit : inutile de lire deux fois
            try {
                HomeData d = loadHome(app);
                runOnUiThread(() -> {
                    if (gen == refreshGen.get() && !isDestroyed()) bind(d);
                });
            } catch (Throwable t) {
                App.saveError(app, "Accueil", t);
                firstBound = true;
            }
            WidgetProvider.updateAllAsync(app);
            CoverWorker.enqueueIfNeeded(app);
        });
    }

    private void bind(HomeData d) {
        try {
            adapter.setData(d.series);
            adapter.setNewChapters(d.fresh);
            buildTagChips(d.tags);
            bindUpdates(d.updates);
            bindDuplicates(d.duplicates);
            Anim.count(statSeries, d.series.size());
            Anim.count(statChapters, d.total);
            Anim.count(statWeek, d.week);
            bindHome(d.series, d.fresh);
            ((TextView) findViewById(R.id.libCount)).setText(d.series.size() + (d.series.size() > 1 ? " séries" : " série")
                    + " · " + d.total + " chapitres");
            ((TextView) findViewById(R.id.libRhythmSub)).setText(d.week + (d.week > 1 ? " chapitres" : " chapitre") + " cette semaine");
            if (page == PAGE_ACTIVITY) StatsBinder.bind(this, findViewById(R.id.activityContent));
            updateEmpty();
        } catch (Throwable t) {
            App.saveError(this, "Affichage", t);
            Toast.makeText(this, "Erreur d'affichage : voir Diagnostic (ⓘ)", Toast.LENGTH_LONG).show();
        } finally {
            firstBound = true;
        }
    }

    private void updateEmpty() {
        boolean none = adapter.totalCount() == 0;
        emptyState.setVisibility(none ? View.VISIBLE : View.GONE);
        for (int id : new int[]{R.id.resumeSection, R.id.resumeCard, R.id.homeRecentHeader, R.id.homeRecent}) {
            if (none) findViewById(id).setVisibility(View.GONE);
        }
        findViewById(R.id.libEmpty).setVisibility(!none && adapter.shownCount() == 0 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.libEmpty)).setText(none ? "" : "Aucune série ne correspond à ta recherche ou à tes filtres.");
        updateFilterChip();
    }

    // ------------------------------------------------------------------ Navigation

    private static final int PAGE_HOME = 0, PAGE_LIBRARY = 1, PAGE_ACTIVITY = 2;
    private int page = PAGE_HOME;

    private void setupNavigation() {
        findViewById(R.id.navHome).setOnClickListener(v -> showPage(PAGE_HOME));
        findViewById(R.id.navLibrary).setOnClickListener(v -> showPage(PAGE_LIBRARY));
        findViewById(R.id.navActivity).setOnClickListener(v -> showPage(PAGE_ACTIVITY));
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (page != PAGE_HOME) {
                    showPage(PAGE_HOME);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        styleNav();
    }

    private void showPage(int p) {
        if (p == page) {
            ((androidx.core.widget.NestedScrollView) pageView(p)).smoothScrollTo(0, 0);
            return;
        }
        View from = pageView(page), to = pageView(p);
        int dir = p > page ? 1 : -1;   // vers la droite ou la gauche selon l'onglet choisi
        page = p;
        for (int i = 0; i < 3; i++) {
            View pv = pageView(i);
            pv.animate().cancel();
            if (pv != from && pv != to) pv.setVisibility(View.GONE);
        }
        if (Anim.on()) {
            from.animate().alpha(0f).translationX(-dir * 18 * getResources().getDisplayMetrics().density)
                    .setDuration(140).setInterpolator(Anim.EASE).withEndAction(() -> {
                        from.setVisibility(View.GONE);
                        from.setAlpha(1f);
                        from.setTranslationX(0f);
                    }).start();
        } else {
            from.setVisibility(View.GONE);
        }
        to.setVisibility(View.VISIBLE);
        to.setTranslationY(0f);
        Anim.slideIn(to, dir);
        if (p == PAGE_ACTIVITY) {
            View content = findViewById(R.id.activityContent);
            StatsBinder.bind(this, content);
            Anim.stagger(content);
        } else if (p == PAGE_LIBRARY) {
            RecyclerView list = findViewById(R.id.seriesList);
            if (list.getAdapter() != null) list.getAdapter().notifyDataSetChanged(); // les cartes arrivent en cascade
        }
        styleNav();
        int[] icons = {R.id.navHomeIcon, R.id.navLibraryIcon, R.id.navActivityIcon};
        Anim.pop(findViewById(icons[p]));
        Anim.fadeInBackground(findViewById(p == PAGE_LIBRARY ? R.id.navLibrary : p == PAGE_ACTIVITY ? R.id.navActivity : R.id.navHome));
    }

    private View pageView(int p) {
        return findViewById(p == PAGE_LIBRARY ? R.id.pageLibrary : p == PAGE_ACTIVITY ? R.id.pageActivity : R.id.pageHome);
    }

    private void styleNav() {
        int[][] items = {
                {R.id.navHome, R.id.navHomeIcon, R.id.navHomeLabel},
                {R.id.navLibrary, R.id.navLibraryIcon, R.id.navLibraryLabel},
                {R.id.navActivity, R.id.navActivityIcon, R.id.navActivityLabel}};
        for (int i = 0; i < 3; i++) {
            boolean on = i == page;
            View item = findViewById(items[i][0]);
            item.setBackgroundResource(on ? R.drawable.bg_nav_selected : 0);
            int c = ContextCompat.getColor(this, on ? R.color.badge_text : R.color.text_soft);
            ((android.widget.ImageView) findViewById(items[i][1])).setImageTintList(android.content.res.ColorStateList.valueOf(c));
            TextView label = findViewById(items[i][2]);
            label.setTextColor(c);
        }
    }

    private void openSearch() {
        EditText field = findViewById(R.id.searchInput);
        field.setVisibility(View.VISIBLE);
        field.requestFocus();
        field.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ------------------------------------------------------------------ Accueil

    private void bindHome(List<DbHelper.Series> series, java.util.Map<String, Double> fresh) {
        boolean any = !series.isEmpty();
        findViewById(R.id.resumeSection).setVisibility(any ? View.VISIBLE : View.GONE);
        findViewById(R.id.resumeCard).setVisibility(any ? View.VISIBLE : View.GONE);
        findViewById(R.id.homeRecentHeader).setVisibility(series.size() > 1 ? View.VISIBLE : View.GONE);
        LinearLayout recent = findViewById(R.id.homeRecent);
        recent.removeAllViews();
        recent.setVisibility(series.size() > 1 ? View.VISIBLE : View.GONE);
        if (!any) return;

        DbHelper.Series top = series.get(0);
        ((TextView) findViewById(R.id.resumeTitle)).setText(top.title);
        TextView rating = findViewById(R.id.resumeRating);
        rating.setVisibility(top.score > 0 ? View.VISIBLE : View.GONE);
        rating.setText("★ " + SeriesAdapter.formatScore(top.score));
        Progress pr = Progress.forResume(this, top.title);
        ((TextView) findViewById(R.id.resumeLast)).setText("Dernier chapitre lu : " + Detector.formatChapter(top.lastChapter)
                + (pr != null && !pr.finished() ? " · " + pr.where() : ""));
        String when = DateUtils.getRelativeTimeSpanString(top.lastReadAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        ((TextView) findViewById(R.id.resumeWhen)).setText(cap(when));
        SeriesAdapter.paintAvatar(findViewById(R.id.resumeLetter), top.title);
        android.graphics.Bitmap cover = Covers.get(this, top.title);
        com.google.android.material.imageview.ShapeableImageView img = findViewById(R.id.resumeCover);
        img.setVisibility(cover == null ? View.GONE : View.VISIBLE);
        if (cover != null) img.setImageBitmap(cover);
        findViewById(R.id.resumeButton).setOnClickListener(v -> Resume.open(this, top.title));
        findViewById(R.id.resumeCard).setOnClickListener(v -> DetailActivity.open(this, top.title));

        for (int i = 1; i < Math.min(4, series.size()); i++) {
            DbHelper.Series s = series.get(i);
            View card = getLayoutInflater().inflate(R.layout.item_series, recent, false);
            SeriesAdapter.bindCard(card, s, fresh.get(s.title.toLowerCase(Locale.ROOT)),
                    x -> DetailActivity.open(this, x.title), this::confirmDeleteSeries, x -> Resume.open(this, x.title));
            recent.addView(card);
            Anim.pressAll(card);
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ Filtres & tri (bibliothèque)

    private void showFilterDialog() {
        String[] sorts = {"recent", "score", "title", "count"};
        String[] labels = {"Lus récemment", "Mieux notées", "Titre (A → Z)", "Plus de chapitres lus"};
        int cur = java.util.Arrays.asList(sorts).indexOf(adapter.getSort());
        final int[] choice = {Math.max(0, cur)};
        final boolean[] flags = {adapter.isOnlyFollowed(), adapter.isOnlyNew()};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        TextView t1 = new TextView(this);
        t1.setText("Trier par");
        t1.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        box.addView(t1);
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            com.google.android.material.radiobutton.MaterialRadioButton rb =
                    new com.google.android.material.radiobutton.MaterialRadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            final int idx = i;
            rb.setOnCheckedChangeListener((b, c) -> {
                if (c) choice[0] = idx;
            });
            rg.addView(rb);
            if (i == choice[0]) rb.setChecked(true);
        }
        box.addView(rg);
        TextView t2 = new TextView(this);
        t2.setText("Afficher seulement");
        t2.setPadding(0, pad / 2, 0, 0);
        t2.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        box.addView(t2);
        com.google.android.material.checkbox.MaterialCheckBox cbF = new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbF.setText("🔔 Les séries suivies");
        cbF.setChecked(flags[0]);
        box.addView(cbF);
        com.google.android.material.checkbox.MaterialCheckBox cbN = new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbN.setText("✨ Celles avec un nouveau chapitre");
        cbN.setChecked(flags[1]);
        box.addView(cbN);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Filtres")
                .setView(box)
                .setPositiveButton("Appliquer", (d, w) -> {
                    java.util.Set<String> followed = new java.util.HashSet<>();
                    for (DbHelper.Meta m : DbHelper.get(this).getAllMeta()) {
                        if (m.follow) followed.add(m.title.toLowerCase(Locale.ROOT));
                    }
                    adapter.setSort(sorts[choice[0]]);
                    adapter.setFilters(cbF.isChecked(), cbN.isChecked(), followed);
                    updateEmpty();
                })
                .setNeutralButton("Réinitialiser", (d, w) -> {
                    adapter.setSort("recent");
                    adapter.setFilters(false, false, new java.util.HashSet<>());
                    updateEmpty();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void updateFilterChip() {
        com.google.android.material.chip.Chip c = findViewById(R.id.filterChip);
        boolean active = adapter.isOnlyFollowed() || adapter.isOnlyNew() || !"recent".equals(adapter.getSort());
        c.setText(active ? "Filtres •" : "Filtres");
        c.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, active ? R.color.brand : R.color.hairline)));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, ReadingTrackerService.class);
        String lower = enabled.toLowerCase(Locale.ROOT);
        return lower.contains(me.flattenToString().toLowerCase(Locale.ROOT))
                || lower.contains(me.flattenToShortString().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ Diagnostic

    private void showPendingCrash() {
        SharedPreferences p = getSharedPreferences(App.PREFS_CRASH, MODE_PRIVATE);
        if (p.getBoolean("seen", true)) return;
        p.edit().putBoolean("seen", true).apply();
        showDiagnostic();
    }

    private void showDiagnostic() {
        SharedPreferences d = getSharedPreferences(ReadingTrackerService.PREFS_DEBUG, MODE_PRIVATE);
        SharedPreferences c = getSharedPreferences(App.PREFS_CRASH, MODE_PRIVATE);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE);

        StringBuilder sb = new StringBuilder();
        sb.append("Service : ").append(isServiceEnabled() ? "actif ✅" : "désactivé ❌").append("\n\n");
        long at = d.getLong("at", 0);
        if (at == 0) {
            sb.append("Aucun écran analysé pour l'instant.\n");
        } else {
            sb.append("Dernier écran analysé (").append(fmt.format(new Date(at))).append(")\n")
                    .append("App : ").append(d.getString("pkg", "")).append("\n")
                    .append("Résultat : ").append(d.getString("result", "")).append("\n")
                    .append(d.getString("texts", "")).append("\n");
        }
        long scrollAt = d.getLong("scroll_at", 0);
        if (scrollAt != 0) {
            sb.append("\nDernier guidage vers la case (").append(fmt.format(new Date(scrollAt))).append(")\n")
                    .append(d.getString("scroll", "")).append("\n");
        }
        long crashAt = c.getLong("at", 0);
        if (crashAt != 0) {
            sb.append("\nDernière erreur (").append(fmt.format(new Date(crashAt))).append(")\n")
                    .append(c.getString("trace", ""));
        }
        String text = sb.toString();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Diagnostic")
                .setMessage(text)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Copier", (dlg, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("diagnostic", text));
                    Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Effacer l'erreur", (dlg, w) -> c.edit().clear().apply())
                .show();
    }

    // ------------------------------------------------------------------ Fichier note

    private void onNoteButton() {
        SharedPreferences p = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
        String current = p.getString(NoteWriter.KEY_NOTE_URI, null);
        String local = getExternalFilesDir(null) + "/webtoons.md";
        String msg = "Le récap est toujours écrit ici :\n" + local
                + (current != null ? "\n\nEt aussi dans le fichier choisi :\n" + Uri.parse(current).getLastPathSegment() : "")
                + "\n\nTu peux choisir un fichier dans Documents, Drive, ton coffre Obsidian…";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Fichier note")
                .setMessage(msg)
                .setPositiveButton("Choisir un fichier", (d, w) -> pickNoteFile.launch("webtoons.md"))
                .setNeutralButton("Partager", (d, w) -> shareNote())
                .setNegativeButton("Fermer", null)
                .show();
        NoteWriter.writeAsync(this);
    }

    // ------------------------------------------------------------------ Réglages

    private void showSettings() {
        SharedPreferences p = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        android.widget.RadioGroup group = v.findViewById(R.id.modeGroup);
        com.google.android.material.materialswitch.MaterialSwitch already = v.findViewById(R.id.alreadySwitch);

        int mode = p.getInt(ReadingTrackerService.KEY_NOTIFY_MODE, NotifyPolicy.MODE_SMART);
        group.check(mode == NotifyPolicy.MODE_ALL ? R.id.modeAll
                : mode == NotifyPolicy.MODE_OFF ? R.id.modeOff : R.id.modeSmart);
        already.setChecked(p.getBoolean(ReadingTrackerService.KEY_NOTIFY_ALREADY, true));

        group.setOnCheckedChangeListener((g, id) -> p.edit().putInt(ReadingTrackerService.KEY_NOTIFY_MODE,
                id == R.id.modeAll ? NotifyPolicy.MODE_ALL
                        : id == R.id.modeOff ? NotifyPolicy.MODE_OFF : NotifyPolicy.MODE_SMART).apply());
        already.setOnCheckedChangeListener((b, checked) ->
                p.edit().putBoolean(ReadingTrackerService.KEY_NOTIFY_ALREADY, checked).apply());

        v.findViewById(R.id.testButton).setOnClickListener(b -> {
            if (!isServiceEnabled()) {
                Toast.makeText(this, "Active d'abord l'analyse en fond", Toast.LENGTH_LONG).show();
                return;
            }
            sendBroadcast(new Intent(ReadingTrackerService.ACTION_TEST_BANNER).setPackage(getPackageName()));
        });

        TextView alertsInfo = v.findViewById(R.id.alertsInfo);
        int followed = DbHelper.get(this).countFollowed();
        ((TextView) v.findViewById(R.id.alertsTitle)).setText("🔔 Alertes nouveaux chapitres · "
                + followed + (followed > 1 ? " séries suivies" : " série suivie"));
        alertsInfo.setText(alertsDescription());
        v.findViewById(R.id.alertsCheckNow).setOnClickListener(b -> {
            ChapterCheckWorker.checkNow(this);
            Toast.makeText(this, "Vérification lancée (quelques secondes)", Toast.LENGTH_SHORT).show();
        });

        backupStatusView = v.findViewById(R.id.backupStatus);
        updateBackupStatus();
        v.findViewById(R.id.backupAutoButton).setOnClickListener(b ->
                pickAutoBackup.launch("webtoon-tracker-sauvegarde.json"));
        v.findViewById(R.id.backupExportButton).setOnClickListener(b ->
                pickExport.launch("webtoon-tracker-" + new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
                        .format(new Date()) + ".json"));
        v.findViewById(R.id.backupRestoreButton).setOnClickListener(b ->
                pickRestore.launch(new String[]{"application/json", "text/*", "application/octet-stream"}));

        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Réglages")
                .setView(v)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> {
                    backupStatusView = null;
                })
                .show();
        v.findViewById(R.id.settingsNote).setOnClickListener(b -> {
            dlg.dismiss();
            onNoteButton();
        });
        v.findViewById(R.id.settingsDiag).setOnClickListener(b -> {
            dlg.dismiss();
            showDiagnostic();
        });
    }

    // ------------------------------------------------------------------ Alertes nouveaux chapitres

    private String alertsDescription() {
        SharedPreferences p = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
        long last = p.getLong("last_check", 0);
        return "Active la cloche 🔔 dans la page d'une série pour la surveiller. "
                + "Environ 2 fois par jour, l'appli regarde si un chapitre plus récent que ton dernier lu "
                + "est sorti en anglais ou en français, sur MangaDex (scans + sites officiels), "
                + "MangaUpdates (toutes les sorties, tous groupes) et WEBTOON. "
                + "La notification te dit où le lire et ouvre le chapitre en un appui. "
                + "Seulement avec du réseau et une batterie non faible."
                + (last > 0 ? "\nDernière vérification : "
                + new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date(last)) : "");
    }

    // ------------------------------------------------------------------ Nouveaux chapitres

    /** Regroupe les sorties plus récentes que ton dernier chapitre lu, par série. */
    private static java.util.LinkedHashMap<String, List<DbHelper.Release>> groupedUpdates(Context ctx, String onlyTitle) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = new java.util.LinkedHashMap<>();
        for (DbHelper.Release r : DbHelper.get(ctx).newReleases(onlyTitle)) {
            String k = r.title.toLowerCase(Locale.ROOT);
            List<DbHelper.Release> l = map.get(k);
            if (l == null) {
                l = new java.util.ArrayList<>();
                map.put(k, l);
            }
            l.add(r);
        }
        return map;
    }

    private void bindUpdates(java.util.LinkedHashMap<String, List<DbHelper.Release>> map) {
        View card = findViewById(R.id.updatesCard);
        card.setVisibility(map.isEmpty() ? View.GONE : View.VISIBLE);
        if (!map.isEmpty()) {
            int n = map.size();
            ((TextView) findViewById(R.id.updatesTitle)).setText(n + (n > 1 ? " séries mises à jour" : " série mise à jour"));
            ((TextView) findViewById(R.id.updatesCount)).setText(String.valueOf(n));
            ((TextView) findViewById(R.id.updatesSub)).setText(n > 1
                    ? "De nouveaux chapitres t'attendent"
                    : map.values().iterator().next().get(0).title + " · ch. "
                    + Detector.formatChapter(map.values().iterator().next().get(0).chapter));
        }
    }

    /** Panneau "Nouveaux chapitres" : pour chaque série, les sites où lire, en EN et FR. */
    private void showUpdates(String onlyTitle) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = groupedUpdates(this, onlyTitle);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView head = new TextView(this);
        head.setText(onlyTitle == null ? "🔔 Nouveaux chapitres" : "🔔 " + onlyTitle);
        head.setTextSize(20);
        head.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        head.setTextColor(ContextCompat.getColor(this, R.color.text_main));
        box.addView(head);
        TextView hint = new TextView(this);
        hint.setText("Touche un site pour ouvrir le chapitre dans ton navigateur (nouvel onglet) ou dans l'appli du site.");
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        hint.setTextSize(13);
        hint.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        box.addView(hint);

        if (map.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Rien de neuf pour l'instant : tu es à jour 🎉");
            none.setTextColor(ContextCompat.getColor(this, R.color.text_main));
            none.setPadding(0, (int) (12 * dp), 0, 0);
            box.addView(none);
        }

        for (List<DbHelper.Release> list : map.values()) {
            DbHelper.Release top = list.get(0);
            View row = getLayoutInflater().inflate(R.layout.item_update, box, false);
            SeriesAdapter.bindCover(row.findViewById(R.id.uAvatar), row.findViewById(R.id.uCover), top.title);
            ((TextView) row.findViewById(R.id.uTitle)).setText(top.title);
            double last = DbHelper.get(this).lastRead(top.title);
            ((TextView) row.findViewById(R.id.uSub)).setText("Ch. " + Detector.formatChapter(top.chapter)
                    + " dispo · tu en es au " + Detector.formatChapter(last));
            com.google.android.material.chip.ChipGroup sites = row.findViewById(R.id.uSites);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (DbHelper.Release r : list) {
                if (r.url == null || !seen.add(r.label + r.lang)) continue;
                com.google.android.material.chip.Chip c = (com.google.android.material.chip.Chip)
                        getLayoutInflater().inflate(R.layout.chip_site, sites, false);
                c.setText((r.lang == null || r.lang.isEmpty() ? "" : r.lang + " · ") + r.label + " · ch. "
                        + Detector.formatChapter(r.chapter));
                c.setOnClickListener(x -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.url)));
                    } catch (Exception e) {
                        Toast.makeText(this, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
                    }
                });
                sites.addView(c);
            }
            row.findViewById(R.id.uResume).setOnClickListener(x -> Resume.open(this, top.title));
            row.setOnClickListener(x -> {
                sheet.dismiss();
                DetailActivity.open(this, top.title);
            });
            box.addView(row);
        }

        com.google.android.material.button.MaterialButton check = new com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        check.setText("Vérifier maintenant");
        check.setOnClickListener(x -> {
            ChapterCheckWorker.checkNow(this);
            Toast.makeText(this, "Vérification lancée…", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (12 * dp);
        box.addView(check, lp);

        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.addView(box);
        sheet.setContentView(scroll);
        sheet.show();
    }

    // ------------------------------------------------------------------ Tags

    private static final String[] SUGGESTED_TAGS = {"Action", "Fantasy", "Romance", "Comédie", "Drame",
            "Murim", "Isekai", "Thriller", "Horreur", "Sport", "Slice of life", "En cours", "Terminé", "Favori ⭐"};

    private void buildTagChips(java.util.Set<String> tags) {
        com.google.android.material.chip.ChipGroup group = findViewById(R.id.tagChips);
        findViewById(R.id.tagScroll).setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        if (selectedTag != null && !tags.contains(selectedTag)) {
            selectedTag = null;
            adapter.filterTag(null);
        }
        group.setOnCheckedStateChangeListener(null);
        group.removeAllViews();
        group.addView(makeChip(group, "Tout", selectedTag == null));
        for (String t : tags) group.addView(makeChip(group, t, t.equalsIgnoreCase(selectedTag == null ? "" : selectedTag)));
        Anim.pressAll(group);
        group.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            com.google.android.material.chip.Chip c = g.findViewById(ids.get(0));
            String label = c.getText().toString();
            selectedTag = "Tout".equals(label) ? null : label;
            adapter.filterTag(selectedTag);
            updateEmpty();
        });
    }

    private com.google.android.material.chip.Chip makeChip(ViewGroup parent, String label, boolean checked) {
        com.google.android.material.chip.Chip c = (com.google.android.material.chip.Chip)
                getLayoutInflater().inflate(R.layout.chip_filter, parent, false);
        c.setId(View.generateViewId());
        c.setText(label);
        c.setChecked(checked);
        return c;
    }

    // ------------------------------------------------------------------ Fusion des doublons

    private void bindDuplicates(List<List<String>> groups) {
        duplicates = groups;
        View card = findViewById(R.id.dupCard);
        card.setVisibility(duplicates.isEmpty() ? View.GONE : View.VISIBLE);
        if (!duplicates.isEmpty()) {
            List<String> g = duplicates.get(0);
            ((TextView) findViewById(R.id.dupText)).setText("🔗 Doublon probable : « " + g.get(0) + " » et « "
                    + g.get(1) + " »" + (duplicates.size() > 1 ? " (+" + (duplicates.size() - 1) + " autres)" : ""));
        }
    }

    private void showMergeSuggestions() {
        if (duplicates.isEmpty()) return;
        List<String> group = duplicates.get(0);
        // On garde par défaut le titre qui a le plus de chapitres.
        String keep = group.get(0);
        int best = -1;
        for (DbHelper.Series s : DbHelper.get(this).getSeries()) {
            for (String t : group) if (t.equalsIgnoreCase(s.title) && s.count > best) {
                best = s.count;
                keep = s.title;
            }
        }
        String[] items = group.toArray(new String[0]);
        int checked = group.indexOf(keep);
        final int[] choice = {Math.max(0, checked)};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Fusionner sous quel nom ?")
                .setSingleChoiceItems(items, choice[0], (d, w) -> choice[0] = w)
                .setPositiveButton("Fusionner", (d, w) -> {
                    String into = items[choice[0]];
                    for (String t : group) if (!t.equalsIgnoreCase(into)) {
                        DbHelper.get(this).mergeSeries(t, into);
                        Covers.merge(this, t, into);
                    }
                    NoteWriter.writeAsync(this);
                    Toast.makeText(this, "Fusionné dans « " + into + " » ✅", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Plus tard", null)
                .show();
    }

    // ------------------------------------------------------------------ Sauvegarde

    private void updateBackupStatus() {
        if (backupStatusView != null) backupStatusView.setText(BackupManager.describeAuto(this));
    }

    private void keepPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void onAutoBackupPicked(Uri uri) {
        if (uri == null) return;
        keepPermission(uri);
        getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE).edit()
                .putString(BackupManager.KEY_BACKUP_URI, uri.toString()).apply();
        runIo(() -> {
            BackupManager.autoBackup(this);
            return "Sauvegarde auto activée : mise à jour à chaque chapitre";
        });
    }

    private void onExportPicked(Uri uri) {
        if (uri == null) return;
        runIo(() -> {
            BackupManager.writeTo(this, uri);
            return "Sauvegarde exportée ✅";
        });
    }

    private void onRestorePicked(Uri uri) {
        if (uri == null) return;
        runIo(() -> {
            int n = BackupManager.restore(this, uri);
            return n == 0 ? "Rien de nouveau : tout était déjà là"
                    : n + (n > 1 ? " chapitres restaurés ✅" : " chapitre restauré ✅");
        });
    }

    interface IoTask {
        String run() throws Exception;
    }

    /** Exécute en arrière-plan puis affiche le résultat et rafraîchit l'écran. */
    private void runIo(IoTask task) {
        new Thread(() -> {
            String msg;
            try {
                msg = task.run();
            } catch (Exception e) {
                App.saveError(this, "Sauvegarde", e);
                msg = "Échec : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                updateBackupStatus();
                refresh();
            });
        }).start();
    }

    private void onNoteFilePicked(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE).edit()
                .putString(NoteWriter.KEY_NOTE_URI, uri.toString()).apply();
        NoteWriter.writeAsync(this);
        Toast.makeText(this, "Le fichier sera mis à jour à chaque chapitre lu", Toast.LENGTH_LONG).show();
    }

    private void shareNote() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Mes webtoons lus")
                .putExtra(Intent.EXTRA_TEXT, NoteWriter.buildMarkdown(this));
        startActivity(Intent.createChooser(send, "Envoyer le récap vers…"));
    }

    // ------------------------------------------------------------------ Dialogues

    private void confirmDeleteSeries(DbHelper.Series s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Supprimer la série ?")
                .setMessage("« " + s.title + " » et ses " + s.count + " chapitres seront supprimés.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    DbHelper.get(this).deleteSeries(s.title);
                    Covers.delete(this, s.title);
                    Follow.sync(this);
                    NoteWriter.writeAsync(this);
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showAddDialog(String presetTitle) {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 3, pad, 0);

        EditText title = new EditText(this);
        title.setHint("Titre de la série");
        if (presetTitle != null) title.setText(presetTitle);
        box.addView(title);

        EditText chapter = new EditText(this);
        chapter.setHint("Chapitre (ex : 42)");
        chapter.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(chapter);
        if (presetTitle != null) chapter.requestFocus();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Ajouter une lecture")
                .setView(box)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String t = title.getText().toString().trim();
                    String c = chapter.getText().toString().trim().replace(',', '.');
                    if (t.isEmpty() || c.isEmpty()) return;
                    try {
                        DbHelper.get(this).insertRead(t, Double.parseDouble(c), System.currentTimeMillis(), "manuel");
                        NoteWriter.writeAsync(this);
                        refresh();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Numéro de chapitre invalide", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
