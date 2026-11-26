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
import android.os.Build;
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
import android.widget.FrameLayout;
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
        findViewById(R.id.resumeMore).setOnClickListener(v -> showPage(PAGE_LIBRARY));
        setupHomeDiscovery();
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
        fitLargeScreens();
    }

    // ------------------------------------------------------------------ Accueil : découverte

    private HomeFeed feed;

    /** Recherche du catalogue, raccourcis, « Pour toi » et « Explorer » (données de Découvrir). */
    private void setupHomeDiscovery() {
        EditText input = findViewById(R.id.homeSearchInput);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enter) {
                searchCatalog(v.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.homeSearchGo).setOnClickListener(v -> searchCatalog(input.getText().toString()));
        findViewById(R.id.homeSearch).setOnClickListener(v -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        findViewById(R.id.quickPopular).setOnClickListener(v -> {
            showPage(PAGE_DISCOVER);
            discoverPage().sortBy("POPULARITY_DESC");
        });
        findViewById(R.id.quickTrending).setOnClickListener(v -> {
            showPage(PAGE_DISCOVER);
            discoverPage().sortBy("TRENDING_DESC");
        });
        findViewById(R.id.quickGenres).setOnClickListener(v -> {
            showPage(PAGE_DISCOVER);
            v.postDelayed(() -> {
                if (!isFinishing()) discoverPage().openFilters();
            }, 220);
        });
        findViewById(R.id.quickHistory).setOnClickListener(v -> showPage(PAGE_ACTIVITY));
        findViewById(R.id.forYouMore).setOnClickListener(v -> showPage(PAGE_DISCOVER));
        findViewById(R.id.exploreMore).setOnClickListener(v -> showPage(PAGE_DISCOVER));

        feed = new HomeFeed(this, new CoverCards.Listener() {
            @Override
            public void open(Discover.Media m) {
                discoverPage().openMedia(m);
            }

            @Override
            public void toggleWish(Discover.Media m) {
                discoverPage().toggleWish(m);
            }
        });

        // « lectures » : dégradé violet, comme sur la maquette
        TextView accent = findViewById(R.id.heroAccent);
        accent.post(() -> {
            float w = accent.getPaint().measureText(accent.getText().toString());
            accent.getPaint().setShader(new android.graphics.LinearGradient(0, 0, w, 0,
                    new int[]{0xFFD8B4FE, 0xFFA78BFA, 0xFF8B5CF6}, null, android.graphics.Shader.TileMode.CLAMP));
            accent.invalidate();
        });
    }

    /** Recherche tapée sur l'accueil : elle part dans Découvrir (AniList + MangaDex). */
    private void searchCatalog(String text) {
        EditText input = findViewById(R.id.homeSearchInput);
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        showPage(PAGE_DISCOVER);
        if (text != null && !text.trim().isEmpty()) discoverPage().searchFor(text);
        input.setText("");
    }

    DiscoverPage discoverPage() {
        if (discover == null) {
            discover = new DiscoverPage(this);
            discover.setOnWishChanged(() -> {
                if (feed != null) feed.wishesChanged();
            });
        }
        return discover;
    }

    /** Grands écrans : colonnes de contenu centrées et barre du bas bornée en largeur. */
    private void fitLargeScreens() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int max = getResources().getDimensionPixelSize(R.dimen.content_max);
        if (dm.widthPixels > max + 48 * dm.density) {
            for (int id : new int[]{R.id.homeColumn, R.id.libraryColumn, R.id.activityContent}) {
                View col = findViewById(id);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) col.getLayoutParams();
                lp.width = max;
                lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                col.setLayoutParams(lp);
            }
        }
        int bar = Math.round(560 * dm.density);
        if (dm.widthPixels > bar + 28 * dm.density) {
            View nav = findViewById(R.id.bottomNav);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) nav.getLayoutParams();
            lp.width = bar;
            nav.setLayoutParams(lp);
        }
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
        findViewById(R.id.navLogo).postDelayed(this::playLogo, 380);   // le perso s'illumine à l'arrivée
    }

    // ------------------------------------------------------------------ Logo animé (centre du menu)

    private String resumeTitle;   // série de la carte « Reprendre » (le logo la relance)

    /** Le logo s'illumine (montée en puissance, éclairs) puis reste allumé. Android 9+ ; avant : image fixe. */
    private void playLogo() {
        if (Build.VERSION.SDK_INT < 28 || !Anim.on() || isFinishing()) return;
        android.widget.ImageView logo = findViewById(R.id.navLogo);
        android.content.res.Resources res = getResources();
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT < 28) return;
                android.graphics.drawable.Drawable d = LogoAnim.decode(res);
                runOnUiThread(() -> {
                    if (isDestroyed() || d == null) return;
                    logo.setImageDrawable(d);
                    if (Build.VERSION.SDK_INT >= 28) LogoAnim.start(d);
                });
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** Toucher le logo : il s'illumine et la lecture reprend là où tu t'es arrêté. */
    private void onLogo() {
        View logo = findViewById(R.id.navLogo);
        logo.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Anim.pop(logo);
        playLogo();
        if (resumeTitle == null) {
            Toast.makeText(this, "Rien à reprendre pour l'instant : lis un chapitre et je m'en souviens", Toast.LENGTH_SHORT).show();
            return;
        }
        final String t = resumeTitle;
        logo.postDelayed(() -> {
            if (!isFinishing()) Resume.open(this, t);
        }, 420);
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
        Progress resume;      // où tu t'es arrêté dans la série à reprendre
        String resumeUrl;     // adresse du dernier chapitre lu de cette série
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
        if (!d.series.isEmpty()) {
            String top = d.series.get(0).title;
            d.resume = Progress.forResume(ctx, top);
            DbHelper.Meta m = db.getMeta(top);
            d.resumeUrl = m == null ? null : m.lastUrl;
        }
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
        if (feed != null) feed.refresh();
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
            bindHome(d.series, d.fresh, d.resume, d.resumeUrl);
            resumeTitle = d.series.isEmpty() ? null : d.series.get(0).title;
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

    // Dans l'ordre du menu (le sens de glissement en dépend).
    private static final int PAGE_HOME = 0, PAGE_LIBRARY = 1, PAGE_DISCOVER = 2, PAGE_ACTIVITY = 3;
    private static final int[] PAGES = {R.id.pageHome, R.id.pageLibrary, R.id.pageDiscover, R.id.pageActivity};
    private static final int[][] NAV = {
            {R.id.navHomePill, R.id.navHomeIcon, R.id.navHomeLabel},
            {R.id.navLibraryPill, R.id.navLibraryIcon, R.id.navLibraryLabel},
            {R.id.navDiscoverPill, R.id.navDiscoverIcon, R.id.navDiscoverLabel},
            {R.id.navActivityPill, R.id.navActivityIcon, R.id.navActivityLabel}};
    private int page = PAGE_HOME;
    private DiscoverPage discover;

    private void setupNavigation() {
        ((GlassBar) findViewById(R.id.bottomNav)).setSource(findViewById(R.id.pages));   // effet verre
        findViewById(R.id.navHome).setOnClickListener(v -> showPage(PAGE_HOME));
        findViewById(R.id.navLibrary).setOnClickListener(v -> showPage(PAGE_LIBRARY));
        findViewById(R.id.navDiscover).setOnClickListener(v -> showPage(PAGE_DISCOVER));
        findViewById(R.id.navActivity).setOnClickListener(v -> showPage(PAGE_ACTIVITY));
        findViewById(R.id.homeBell).setOnClickListener(v -> {
            Anim.ring(v);
            showUpdates(null);
        });
        findViewById(R.id.navLogo).setOnClickListener(v -> onLogo());
        styleNav();
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
        if (p == page) {   // onglet déjà ouvert : retour en haut
            if (p == PAGE_DISCOVER) {
                if (discover != null) discover.scrollTop();
            } else {
                ((androidx.core.widget.NestedScrollView) pageView(p)).smoothScrollTo(0, 0);
            }
            return;
        }
        View from = pageView(page), to = pageView(p);
        int dir = p > page ? 1 : -1;   // vers la droite ou la gauche selon l'onglet choisi
        page = p;
        for (int i = 0; i < PAGES.length; i++) {
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
        } else if (p == PAGE_DISCOVER) {
            discoverPage().onShow();
        }
        styleNav();
        Anim.pop(findViewById(NAV[p][1]));
        Anim.fadeInBackground(findViewById(NAV[p][0]));
    }

    private View pageView(int p) {
        return findViewById(PAGES[p]);
    }

    /** Entrée active : pastille lavande derrière l'icône, icône et libellé en couleur. */
    private void styleNav() {
        int[][] items = NAV;
        for (int i = 0; i < items.length; i++) {
            boolean on = i == page;
            View pill = findViewById(items[i][0]);
            pill.setBackgroundResource(on ? R.drawable.bg_nav_active : 0);   // halo violet derrière l'icône
            int c = ContextCompat.getColor(this, on ? R.color.primary_bright : R.color.nav_idle);
            ((android.widget.ImageView) findViewById(items[i][1])).setImageTintList(android.content.res.ColorStateList.valueOf(c));
            TextView label = findViewById(items[i][2]);
            label.setTextColor(ContextCompat.getColor(this, on ? R.color.nav_label_on : R.color.nav_idle));
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

    private void bindHome(List<DbHelper.Series> series, java.util.Map<String, Double> fresh, Progress pr, String lastUrl) {
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
        // « FR · WEBTOON » + « Épisode 32 · arrêté à 63 % » + barre qui se remplit
        String unit = SourceRegistry.unit(lastUrl), ch = Detector.formatChapter(top.lastChapter);
        boolean inProgress = pr != null && !pr.finished();
        String ago = DateUtils.getRelativeTimeSpanString(top.lastReadAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString().toLowerCase(Locale.FRANCE);
        ((TextView) findViewById(R.id.resumeLast)).setText((pr == null ? unit + " " + ch
                : !inProgress ? unit + " " + ch + " · terminé"
                : unit + " " + ch + " · " + (pr.hasPanel() ? pr.where() : "arrêté à " + pr.percent() + " %")) + " · " + ago);
        TextView source = findViewById(R.id.resumeSource);
        String badge = SourceRegistry.badge(lastUrl);
        source.setVisibility(badge == null ? View.GONE : View.VISIBLE);
        source.setText(badge);
        com.google.android.material.progressindicator.LinearProgressIndicator bar = findViewById(R.id.resumeProgress);
        bar.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        if (inProgress) {
            int v = Math.max(20, Math.round(pr.pct * 1000));
            Object was = bar.getTag(R.id.anim_count);
            if (!(was instanceof Integer) || (Integer) was != v || !top.title.equals(bar.getTag())) {
                bar.setTag(top.title);
                bar.setTag(R.id.anim_count, v);
                bar.setProgressCompat(0, false);
                bar.postDelayed(() -> bar.setProgressCompat(v, Anim.on()), 260);   // se remplit sous tes yeux
            }
        }
        String when = DateUtils.getRelativeTimeSpanString(top.lastReadAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        ((TextView) findViewById(R.id.resumeWhen)).setText(cap(when));
        SeriesAdapter.paintAvatar(findViewById(R.id.resumeLetter), top.title);
        android.graphics.Bitmap cover = Covers.get(this, top.title);
        com.google.android.material.imageview.ShapeableImageView img = findViewById(R.id.resumeCover);
        img.setVisibility(cover == null ? View.GONE : View.VISIBLE);
        if (cover != null) {
            img.setImageBitmap(cover);
            cropTop(img);
        }
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

    /** Vignette paysage : la couverture (en portrait) est cadrée sur son haut, là où sont les visages. */
    private static void cropTop(android.widget.ImageView v) {
        v.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
        Runnable fit = () -> {
            android.graphics.drawable.Drawable d = v.getDrawable();
            int vw = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
            int vh = v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
            if (d == null || vw <= 0 || vh <= 0 || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
            float sc = Math.max(vw / (float) d.getIntrinsicWidth(), vh / (float) d.getIntrinsicHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(sc, sc);
            m.postTranslate((vw - d.getIntrinsicWidth() * sc) / 2f, (vh - d.getIntrinsicHeight() * sc) * 0.2f);
            v.setImageMatrix(m);
        };
        if (v.getTag(R.id.crop_top) == null) {
            v.setTag(R.id.crop_top, Boolean.TRUE);
            v.addOnLayoutChangeListener((x, l, t, r, b, ol, ot, or, ob) -> fit.run());
        }
        if (v.getWidth() > 0) fit.run();
        else v.post(fit);
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

    private int shownUpdates = -1;

    /** Carte « Nouveautés » : couvertures empilées, « Ch. 25 · ép. 36 · ch. 61 t'attendent », pastille. */
    private void bindUpdates(java.util.LinkedHashMap<String, List<DbHelper.Release>> map) {
        View card = findViewById(R.id.updatesCard);
        boolean wasShown = card.getVisibility() == View.VISIBLE;
        card.setVisibility(map.isEmpty() ? View.GONE : View.VISIBLE);
        int n = map.size();
        TextView navBadge = findViewById(R.id.homeBellBadge);
        navBadge.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        navBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        if (n > 0 && shownUpdates >= 0 && n > shownUpdates) {
            Anim.pop(navBadge);
            Anim.ring(findViewById(R.id.homeBell));   // une nouveauté de plus : la cloche sonne
        }
        if (n == 0) {
            shownUpdates = 0;
            return;
        }
        ((TextView) findViewById(R.id.updatesTitle)).setText(n + (n > 1 ? " séries mises à jour" : " série mise à jour"));
        TextView count = findViewById(R.id.updatesCount);
        count.setText(String.valueOf(n));
        StringBuilder sub = new StringBuilder();
        int k = 0;
        for (List<DbHelper.Release> l : map.values()) {
            DbHelper.Release r = l.get(0);
            if (k == 3) {
                sub.append(" · +").append(n - 3);
                break;
            }
            if (k++ > 0) sub.append(" · ");
            sub.append(SourceRegistry.unitShort(r.url)).append(' ').append(Detector.formatChapter(r.chapter));
        }
        DbHelper.Release first = map.values().iterator().next().get(0);
        ((TextView) findViewById(R.id.updatesSub)).setText(n > 1 ? cap(sub.toString()) + " t'attendent"
                : first.title + " · " + SourceRegistry.unitShort(first.url) + " " + Detector.formatChapter(first.chapter));

        // Couvertures empilées (3 au plus), qui glissent en place l'une après l'autre.
        FrameLayout covers = findViewById(R.id.updatesCovers);
        covers.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        int i = 0;
        for (List<DbHelper.Release> l : map.values()) {
            if (i == 3) break;
            View mc = getLayoutInflater().inflate(R.layout.item_mini_cover, covers, false);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mc.getLayoutParams();
            lp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START;
            lp.setMarginStart((int) (i * 17 * dp));
            mc.setLayoutParams(lp);
            SeriesAdapter.bindCover(mc.findViewById(R.id.mcLetter), mc.findViewById(R.id.mcCover), l.get(0).title);
            covers.addView(mc);
            if (Anim.on() && (!wasShown || shownUpdates != n)) {
                mc.setAlpha(0f);
                mc.setTranslationX(-14 * dp);
                mc.animate().alpha(1f).translationX(0f).setStartDelay(120 + i * 90L).setDuration(320)
                        .setInterpolator(Anim.EASE).start();
            }
            i++;
        }
        ViewGroup.LayoutParams clp = covers.getLayoutParams();
        clp.width = (int) ((34 + (i - 1) * 17) * dp);
        covers.setLayoutParams(clp);
        if (!wasShown) Anim.enter(card, 0);
        if (shownUpdates >= 0 && shownUpdates != n) Anim.pop(count);   // une série de plus : la pastille rebondit
        else Anim.attention(count);
        shownUpdates = n;
    }

    private static final int CHIP_SITE = 0, CHIP_PAID = 1, CHIP_FREE = 2;

    /** Panneau « Nouveaux chapitres » : une carte par série, les sites et éditions où lire, les payants. */
    private void showUpdates(String onlyTitle) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = groupedUpdates(this, onlyTitle);
        DbHelper db = DbHelper.get(this);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_updates, null);
        LinearLayout box = content.findViewById(R.id.suList);
        int n = map.size();
        ((TextView) content.findViewById(R.id.suTitle)).setText(onlyTitle == null ? "Nouveaux chapitres" : onlyTitle);
        ((TextView) content.findViewById(R.id.suSub)).setText(n == 0 ? "Rien de neuf pour l'instant : tu es à jour"
                : (n > 1 ? n + " séries · " : "") + "touche un site pour ouvrir le chapitre");

        int index = 0;
        for (List<DbHelper.Release> list : map.values()) {
            DbHelper.Release top = list.get(0);
            View row = getLayoutInflater().inflate(R.layout.item_update, box, false);
            SeriesAdapter.bindCover(row.findViewById(R.id.uAvatar), row.findViewById(R.id.uCover), top.title);
            ((TextView) row.findViewById(R.id.uTitle)).setText(top.title);
            double last = db.lastRead(top.title);
            ((TextView) row.findViewById(R.id.uSub)).setText(cap(SourceRegistry.unitShort(top.url)) + " "
                    + Detector.formatChapter(top.chapter) + " dispo · tu en es au " + Detector.formatChapter(last));
            com.google.android.material.chip.ChipGroup sites = row.findViewById(R.id.uSites);
            List<DbHelper.Edition> eds = db.getEditions(top.title);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (DbHelper.Release r : list) {
                if (r.url == null || !seen.add(r.label + "|" + r.lang)) continue;
                String ch = SourceRegistry.unitShort(r.url) + " " + Detector.formatChapter(r.chapter);
                int kind = CHIP_SITE;
                String text;
                if ("Site".equals(r.source)) {
                    text = "Nouveau sur " + r.label + " · " + ch;
                } else {
                    text = (r.lang == null || r.lang.isEmpty() ? "" : r.lang + " · ") + r.label + " · " + ch;
                    Boolean free = null;
                    for (DbHelper.Edition e : eds) {
                        if (e.platform != null && e.platform.equals(r.label) && e.lang != null && e.lang.equalsIgnoreCase(r.lang)) {
                            free = e.freeFor(r.chapter);
                        }
                    }
                    if (free != null) {
                        kind = free ? CHIP_FREE : CHIP_PAID;
                        text += free ? " · gratuit" : " · payant";
                    }
                }
                final String url = r.url;
                sites.addView(siteChip(sites, text, kind, () -> openLink(url)));
            }
            // Payants repérés sur tes sites : « WEBTOON · 33 → 36 payants »
            java.util.TreeMap<String, java.util.TreeSet<Double>> locked = new java.util.TreeMap<>();
            for (DbHelper.Lock l : db.getLocks(top.title)) {
                if (l.active() && l.chapter > last) locked.computeIfAbsent(l.host, k -> new java.util.TreeSet<>()).add(l.chapter);
            }
            for (java.util.Map.Entry<String, java.util.TreeSet<Double>> e : locked.entrySet()) {
                java.util.TreeSet<Double> c = e.getValue();
                String range = c.size() == 1 ? Detector.formatChapter(c.first()) + " payant"
                        : Detector.formatChapter(c.first()) + " → " + Detector.formatChapter(c.last()) + " payants";
                sites.addView(siteChip(sites, SourceRegistry.name("https://" + e.getKey() + "/") + " · " + range, CHIP_PAID, () -> {
                    sheet.dismiss();
                    DetailActivity.open(this, top.title);
                }));
            }
            row.findViewById(R.id.uResume).setOnClickListener(x -> Resume.open(this, top.title));
            row.setOnClickListener(x -> {
                sheet.dismiss();
                DetailActivity.open(this, top.title);
            });
            box.addView(row);
            Anim.pressAll(row);
            Anim.enter(row, index++);
        }

        content.findViewById(R.id.suCheck).setOnClickListener(x -> {
            ChapterCheckWorker.checkNow(this);
            Toast.makeText(this, "Vérification lancée…", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        long at = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE).getLong("last_check", 0);
        ((TextView) content.findViewById(R.id.suLast)).setText((at == 0 ? "Pas encore vérifié"
                : "Vérifié " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString().toLowerCase(Locale.FRANCE)) + " · listes complètes en Wi-Fi");
        Anim.press(content.findViewById(R.id.suCheck));
        sheet.setContentView(content);
        sheet.show();
        View bell = content.findViewById(R.id.suBell);
        bell.postDelayed(() -> Anim.ring(bell), 280);   // la cloche sonne à l'ouverture
    }

    /** Pastille d'un site ou d'une édition : lavande (site), orange (payant), vert (gratuit). */
    private com.google.android.material.chip.Chip siteChip(ViewGroup parent, String text, int kind, Runnable click) {
        com.google.android.material.chip.Chip c = (com.google.android.material.chip.Chip)
                getLayoutInflater().inflate(R.layout.chip_site, parent, false);
        c.setText(text);
        if (kind != CHIP_SITE) {
            int bg = ContextCompat.getColor(this, kind == CHIP_PAID ? R.color.ed_paid_bg : R.color.ed_free_bg);
            int fg = ContextCompat.getColor(this, kind == CHIP_PAID ? R.color.ed_paid_text : R.color.ed_free_text);
            c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(bg));
            c.setTextColor(fg);
            c.setChipIconTint(android.content.res.ColorStateList.valueOf(fg));
            if (kind == CHIP_PAID) c.setChipIconResource(R.drawable.ic_lock);
            else c.setChipIconVisible(false);
        }
        c.setOnClickListener(x -> click.run());
        return c;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
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
