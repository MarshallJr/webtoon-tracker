package com.lois.webtoontracker;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Onglet « Découvrir » : recherche AniList (+ catalogue MangaDex pour une recherche texte, voir
 * {@link MultiSearch}), tri (note, popularité, tendance, date, coups de cœur),
 * filtres (type, note, statut, date de diffusion, genres, tes thèmes), « Pour toi » d'après ce que tu
 * lis, « Ma liste à lire ». Les séries que tu lis déjà n'apparaissent jamais.
 */
final class DiscoverPage {

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final int T_HEADER = 0, T_ITEM = 1, T_FOOTER = 2;
    private static final int MAX_PAGES = 20;

    private final AppCompatActivity act;
    private final RecyclerView list;
    private final GridAdapter grid = new GridAdapter();
    /** Appui sur une carte : la fiche ; sur son signet : « Ma liste à lire ». */
    private final CoverCards.Listener cards = new CoverCards.Listener() {
        @Override
        public void open(Discover.Media m) {
            openMedia(m);
        }

        @Override
        public void toggleWish(Discover.Media m) {
            DiscoverPage.this.toggleWish(m);
        }
    };
    private final CoverCards.Adapter forYouAdapter = new CoverCards.Adapter(R.layout.item_discover_card, cards);
    private final CoverCards.Adapter wishAdapter = new CoverCards.Adapter(R.layout.item_discover_card, cards);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Discover.Query query = new Discover.Query();
    private Discover.Profile me;
    private final List<Discover.Media> results = new ArrayList<>();
    private final List<Discover.Media> forYou = new ArrayList<>();
    private final List<Discover.Media> wishes = new ArrayList<>();
    private Set<Integer> wishIds = new HashSet<>();
    private int nextPage = 1, generation, animatedUpTo;
    private boolean loading, hasNext = true, started, forYouLoading;
    private String error, forYouError;
    private View header;
    private Runnable pendingSearch;
    private MultiSearch multi;   // recherche texte en cours : AniList + MangaDex (null sinon)
    private Runnable onWishChanged;

    /** Prévenu quand « Ma liste à lire » change (l'accueil met ses signets à jour). */
    void setOnWishChanged(Runnable r) {
        onWishChanged = r;
    }

    DiscoverPage(AppCompatActivity act) {
        this.act = act;
        list = act.findViewById(R.id.pageDiscover);
        // Colonnes selon la largeur : 3 sur téléphone, davantage sur tablette.
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int spans = Math.max(3, Math.round(dm.widthPixels / dm.density / 150f));
        GridLayoutManager lm = new GridLayoutManager(act, spans);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return grid.getItemViewType(position) == T_ITEM ? 1 : spans;
            }
        });
        list.setLayoutManager(lm);
        list.setAdapter(grid);
        list.setItemAnimator(null);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && lm.findLastVisibleItemPosition() >= grid.getItemCount() - 10) loadMore();
            }
        });
    }

    /** Onglet affiché : la 1re fois, ton profil puis « Pour toi » et les premiers résultats. */
    void onShow() {
        if (started) {
            refreshWishes();
            return;
        }
        started = true;
        forYouLoading = true;
        bindHeader();
        android.content.Context app = act.getApplicationContext();
        IO.execute(() -> {
            Discover.Profile p = Discover.profile(app);
            ui.post(() -> {
                me = p;
                bindHeader();
                refreshWishes();
                loadForYou(false);
                reload();
            });
        });
    }

    /** Remonte en haut (appui sur l'onglet déjà ouvert). */
    /** Recherche lancée depuis l'accueil : le texte arrive dans le champ de Découvrir. */
    void searchFor(String text) {
        String t = text == null ? "" : text.trim();
        if (header == null) {   // en-tête pas encore construit : la première recherche partira avec ce texte
            query.search = t.isEmpty() ? null : t;
            return;
        }
        EditText field = header.findViewById(R.id.dqSearch);
        field.setText(t);
        field.setSelection(field.length());
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        search(t);
    }

    /** Raccourcis de l'accueil (Populaires, Tendances) : tri choisi, sans texte de recherche. */
    void sortBy(String sort) {
        boolean changed = !sort.equals(query.sort) || query.searching();
        query.sort = sort;
        query.search = null;
        if (header != null) {
            ((EditText) header.findViewById(R.id.dqSearch)).setText("");
            if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
            ChipGroup g = header.findViewById(R.id.dqSort);
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof Chip) ((Chip) c).setChecked(sort.equals(c.getTag()));
            }
        }
        if (changed && started && me != null) reload();
    }

    void scrollTop() {
        GridLayoutManager lm = (GridLayoutManager) list.getLayoutManager();
        if (lm != null && lm.findFirstVisibleItemPosition() > 30) list.scrollToPosition(15);   // pas 300 cases d'animation
        list.smoothScrollToPosition(0);
    }

    // ------------------------------------------------------------------ Chargements

    private void reload() {
        generation++;
        int old = results.size();
        results.clear();
        nextPage = 1;
        hasNext = true;
        error = null;
        loading = false;
        animatedUpTo = 0;
        multi = null;   // recréée au premier chargement d'une recherche texte
        // Pas de notifyDataSetChanged : l'en-tête (et le clavier de la recherche) reste en place.
        if (old > 0) grid.notifyItemRangeRemoved(1, old);
        grid.notifyItemChanged(grid.getItemCount() - 1);
        bindHeader();
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasNext || me == null) return;
        if (query.searching()) {
            loadMoreSearch();
            return;
        }
        loading = true;
        error = null;
        grid.notifyItemChanged(grid.getItemCount() - 1);
        final int gen = generation, page = nextPage;
        final Discover.Query q = query.copy();
        final Discover.Profile p = me;
        IO.execute(() -> {
            try {
                Discover.Result r = Discover.browse(q, page, p);
                ui.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    int start = 1 + results.size();
                    results.addAll(r.items);
                    hasNext = r.hasNext && page < MAX_PAGES;
                    nextPage = page + 1;
                    grid.notifyItemRangeInserted(start, r.items.size());
                    grid.notifyItemChanged(grid.getItemCount() - 1);
                    // Page entière déjà lue (toutes écartées) : on enchaîne.
                    if (r.items.size() < 6 && hasNext) loadMore();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    error = friendly(e);
                    grid.notifyItemChanged(grid.getItemCount() - 1);
                });
            }
        });
    }

    /**
     * Recherche texte : la page AniList habituelle et une page MangaDex en parallèle, fusionnées sans
     * doublons. Une source en panne n'empêche pas d'afficher l'autre.
     */
    private void loadMoreSearch() {
        if (multi == null) multi = MultiSearch.of(query, me);
        final MultiSearch ms = multi;
        loading = true;
        error = null;
        grid.notifyItemChanged(grid.getItemCount() - 1);
        final int gen = generation;
        IO.execute(() -> {
            try {
                SearchMerge.Page r = ms.next();
                ui.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    for (Map.Entry<Integer, Discover.Media> e : r.replaced.entrySet()) {   // AniList prend la place de MangaDex
                        if (e.getKey() >= results.size()) continue;
                        results.set(e.getKey(), e.getValue());
                        grid.notifyItemChanged(1 + e.getKey());
                    }
                    int start = 1 + results.size();
                    results.addAll(r.added);
                    hasNext = ms.hasMore() && ms.loads() < MAX_PAGES;
                    grid.notifyItemRangeInserted(start, r.added.size());
                    grid.notifyItemChanged(grid.getItemCount() - 1);
                    bindHeader();
                    if (r.added.size() < 6 && hasNext) loadMore();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    error = friendly(e);
                    grid.notifyItemChanged(grid.getItemCount() - 1);
                    bindHeader();
                });
            }
        });
    }

    private void loadForYou(boolean force) {
        if (me == null) return;
        forYouLoading = true;
        forYouError = null;
        bindHeader();
        final Discover.Profile p = me;
        android.content.Context app = act.getApplicationContext();
        IO.execute(() -> {
            try {
                List<Discover.Media> l = Discover.forYou(app, p, force);
                ui.post(() -> {
                    forYouLoading = false;
                    forYou.clear();
                    forYou.addAll(l);
                    forYouAdapter.setItems(forYou, wishIds);
                    bindHeader();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    forYouLoading = false;
                    forYouError = friendly(e);
                    bindHeader();
                });
            }
        });
    }

    private void refreshWishes() {
        android.content.Context app = act.getApplicationContext();
        IO.execute(() -> {
            DbHelper db = DbHelper.get(app);
            List<Discover.Media> l = new ArrayList<>();
            for (String[] w : db.wishes()) {
                Discover.Media m;
                try {
                    m = w[2] == null ? null : Discover.Media.fromJson(new org.json.JSONObject(w[2]));
                } catch (Exception e) {
                    m = null;
                }
                if (m == null) {
                    m = new Discover.Media();
                    m.id = Integer.parseInt(w[0]);
                    m.title = w[1];
                    m.names.add(w[1]);
                }
                m.because = null;
                l.add(m);
            }
            Set<Integer> ids = db.wishIds();
            ui.post(() -> {
                wishIds = ids;
                wishes.clear();
                for (Discover.Media m : l) if (me == null || !me.has(m)) wishes.add(m);   // commencée : plus « à lire »
                wishAdapter.setItems(wishes, wishIds);
                bindHeader();
                grid.notifyItemRangeChanged(1, results.size());
                forYouAdapter.setWished(wishIds);
            });
        });
    }

    /** Message d'erreur lisible (AniList par défaut, ou la source nommée par la recherche multi-sources). */
    private static String friendly(Exception e) {
        Throwable c = e;
        String src = Discover.Source.ANILIST.label;
        if (e instanceof MultiSearch.SourceError && e.getCause() != null) {
            src = ((MultiSearch.SourceError) e).source.label;
            c = e.getCause();
        }
        String m = String.valueOf(c.getMessage());
        if (m.contains("429") || m.toLowerCase(Locale.ROOT).contains("too many")) {
            return src + " reçoit trop de demandes : réessaie dans une minute.";
        }
        if (c instanceof java.net.UnknownHostException || c instanceof java.net.SocketTimeoutException
                || c instanceof java.net.ConnectException) {
            return "Pas de connexion à " + src + ".";
        }
        return src + " ne répond pas pour l'instant.";
    }

    // ------------------------------------------------------------------ En-tête

    private void setupHeader(View h) {
        header = h;
        EditText search = h.findViewById(R.id.dqSearch);
        View clear = h.findViewById(R.id.dqClear);
        if (query.search != null) {   // en-tête recréé : on remet le texte cherché
            search.setText(query.search);
            clear.setVisibility(View.VISIBLE);
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE);
                if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
                String text = e.toString();
                pendingSearch = () -> search(text);
                ui.postDelayed(pendingSearch, 550);   // on attend que tu aies fini de taper
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || ev != null) {
                if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
                search(v.getText().toString());
                InputMethodManager im = act.getSystemService(InputMethodManager.class);
                if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });
        clear.setOnClickListener(v -> search.setText(""));

        RecyclerView fy = h.findViewById(R.id.dqForYou);
        fy.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        fy.setAdapter(forYouAdapter);
        RecyclerView wl = h.findViewById(R.id.dqWishList);
        wl.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        wl.setAdapter(wishAdapter);
        h.findViewById(R.id.dqForYouRefresh).setOnClickListener(v -> {
            Anim.spin(v);
            loadForYou(true);
        });

        ChipGroup sort = h.findViewById(R.id.dqSort);
        for (String[] s : Discover.SORTS) {
            Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_filter, sort, false);
            c.setId(View.generateViewId());
            c.setText(s[0]);
            c.setTag(s[1]);
            c.setChecked(s[1].equals(query.sort));
            c.setOnClickListener(v -> {
                if (s[1].equals(query.sort)) {
                    c.setChecked(true);
                    return;
                }
                query.sort = s[1];
                Anim.pop(c);
                reload();
            });
            sort.addView(c);
        }
        MaterialButton filters = h.findViewById(R.id.dqFilters);
        filters.setOnClickListener(v -> openFilters());
        Anim.press(filters);
        bindHeader();
    }

    private void search(String text) {
        String t = text == null ? "" : text.trim();
        String was = query.search == null ? "" : query.search.trim();
        if (t.equals(was)) return;
        query.search = t.isEmpty() ? null : t;
        reload();
    }

    /** Remet l'en-tête à jour (profil, carrousels, tri, filtres actifs, ligne d'info). */
    private void bindHeader() {
        View h = header;
        if (h == null) return;
        ((TextView) h.findViewById(R.id.dqForYouSub)).setText(me == null ? "Je regarde ce que tu lis…"
                : me.genres.isEmpty() ? "Les séries incontournables, en attendant de mieux connaître tes goûts"
                : "D'après ce que tu lis le plus : " + me.summary());

        // Pour toi
        View loadingView = h.findViewById(R.id.dqForYouLoading);
        TextView empty = h.findViewById(R.id.dqForYouEmpty);
        loadingView.setVisibility(forYouLoading && forYou.isEmpty() ? View.VISIBLE : View.GONE);
        boolean none = !forYouLoading && forYou.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) empty.setText(forYouError != null ? forYouError : "Pas encore assez de lectures pour te conseiller.");

        // Ma liste à lire
        h.findViewById(R.id.dqWishSection).setVisibility(wishes.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) h.findViewById(R.id.dqWishTitle)).setText("Ma liste à lire · " + wishes.size());

        // Tri (inutile pendant une recherche : les résultats sont classés par pertinence)
        View sort = h.findViewById(R.id.dqSort);
        sort.setAlpha(query.searching() ? 0.45f : 1f);
        ChipGroup sg = (ChipGroup) sort;
        for (int i = 0; i < sg.getChildCount(); i++) sg.getChildAt(i).setEnabled(!query.searching());

        // Filtres actifs, retirables d'un appui
        int n = query.filters();
        ((MaterialButton) h.findViewById(R.id.dqFilters)).setText(n == 0 ? "Filtres" : "Filtres · " + n);
        ChipGroup active = h.findViewById(R.id.dqActive);
        active.removeAllViews();
        if (query.country != null) addActive(active, typeLabel(query.country), () -> query.country = null);
        if (query.minScore > 0) addActive(active, "★ " + scoreLabel(query.minScore), () -> query.minScore = 0);
        if (query.status != null) addActive(active, "RELEASING".equals(query.status) ? "En cours" : "Terminé", () -> query.status = null);
        if (query.fromYear > 0 || query.toYear > 0) addActive(active, yearLabel(query.fromYear, query.toYear), () -> {
            query.fromYear = 0;
            query.toYear = 0;
        });
        for (String g : new ArrayList<>(query.genres)) addActive(active, Discover.genreFr(g), () -> query.genres.remove(g));
        for (String t : new ArrayList<>(query.tags)) addActive(active, Discover.tagFr(t), () -> query.tags.remove(t));

        // Ligne d'info
        String sortName = "";
        for (String[] s : Discover.SORTS) if (s[1].equals(query.sort)) sortName = s[0].toLowerCase(Locale.FRANCE);
        int read = me == null ? 0 : me.series;
        ((TextView) h.findViewById(R.id.dqInfo)).setText(query.searching()
                ? "Résultats pour « " + query.search.trim() + " »" + (read > 0 ? " · sans tes " + read + " séries" : "")
                + (multi == null ? "" : multi.sourcesNote())
                : "Classées : " + sortName + (read > 0 ? " · sans les " + read + " séries que tu lis" : ""));
    }

    private void addActive(ChipGroup group, String label, Runnable remove) {
        Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_filter, group, false);
        c.setText(label);
        c.setCheckable(false);
        c.setCloseIconVisible(true);
        c.setCloseIconTint(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(act, R.color.text_soft)));
        View.OnClickListener off = v -> {
            remove.run();
            reload();
        };
        c.setOnClickListener(off);
        c.setOnCloseIconClickListener(off);
        group.addView(c);
    }

    // ------------------------------------------------------------------ Filtres

    private static final String[][] TYPES = {{"Tous", null}, {"Manhwa", "KR"}, {"Manga", "JP"}, {"Manhua", "CN"}};
    private static final int[][] SCORES = {{0}, {70}, {80}, {85}};
    private static final String[][] STATUSES = {{"Tous", null}, {"En cours", "RELEASING"}, {"Terminé", "FINISHED"}};

    private static String typeLabel(String country) {
        for (String[] t : TYPES) if (t[1] != null && t[1].equals(country)) return t[0];
        return country;
    }

    private static String scoreLabel(int s) {
        return s == 0 ? "Toutes" : String.format(Locale.FRANCE, "%s+", s % 10 == 0 ? String.valueOf(s / 10) : String.format(Locale.FRANCE, "%.1f", s / 10f));
    }

    /** {de, à} : périodes de diffusion proposées (0 = pas de borne). */
    private static int[][] years() {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        return new int[][]{{0, 0}, {now, 0}, {now - 3, 0}, {2015, 0}, {2010, 2019}, {0, 2009}};
    }

    private static String yearLabel(int from, int to) {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        if (from == 0 && to == 0) return "Toutes";
        if (from == now && to == 0) return "Cette année";
        if (to == 0) return "Depuis " + from;
        if (from == 0) return "Avant " + (to + 1);
        return from + "–" + to;
    }

    void openFilters() {
        BottomSheetDialog sheet = new BottomSheetDialog(act);
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_discover_filters, null);
        ChipGroup type = v.findViewById(R.id.dfType), score = v.findViewById(R.id.dfScore),
                status = v.findViewById(R.id.dfStatus), year = v.findViewById(R.id.dfYear),
                genres = v.findViewById(R.id.dfGenres), tags = v.findViewById(R.id.dfTags);
        for (ChipGroup g : new ChipGroup[]{type, score, status, year}) {
            g.setSingleSelection(true);
            g.setSelectionRequired(true);
        }
        Discover.Query q = query.copy();
        for (String[] t : TYPES) choice(type, t[0], java.util.Objects.equals(t[1], q.country), t[1]);
        for (int[] s : SCORES) choice(score, s[0] == 0 ? "Toutes" : "★ " + scoreLabel(s[0]), s[0] == q.minScore, s[0]);
        for (String[] s : STATUSES) choice(status, s[0], java.util.Objects.equals(s[1], q.status), s[1]);
        for (int[] y : years()) choice(year, yearLabel(y[0], y[1]), y[0] == q.fromYear && y[1] == q.toYear, y);
        // Genres : tes préférés d'abord.
        List<String> order = new ArrayList<>();
        if (me != null) for (String g : me.topGenres(4)) if (!order.contains(g)) order.add(g);
        for (String[] g : Discover.GENRES) if (!order.contains(g[0])) order.add(g[0]);
        for (String g : order) choice(genres, Discover.genreFr(g), q.genres.contains(g), g);
        // Tes thèmes : ceux qui reviennent le plus dans tes lectures.
        List<String> themes = new ArrayList<>();
        if (me != null) for (String t : me.topTags(14)) {
            if (themes.size() >= 10) break;
            if (!t.equals("Male Protagonist") && !t.equals("Full Color") && !t.equals("Long Strip") && !t.equals("Web Comic")) themes.add(t);
        }
        for (String t : q.tags) if (!themes.contains(t)) themes.add(t);
        for (String t : themes) choice(tags, Discover.tagFr(t), q.tags.contains(t), t);
        v.findViewById(R.id.dfTagsTitle).setVisibility(themes.isEmpty() ? View.GONE : View.VISIBLE);
        tags.setVisibility(themes.isEmpty() ? View.GONE : View.VISIBLE);

        v.findViewById(R.id.dfReset).setOnClickListener(x -> {
            for (ChipGroup g : new ChipGroup[]{type, score, status, year}) ((Chip) g.getChildAt(0)).setChecked(true);
            for (ChipGroup g : new ChipGroup[]{genres, tags}) for (int i = 0; i < g.getChildCount(); i++) ((Chip) g.getChildAt(i)).setChecked(false);
        });
        MaterialButton apply = v.findViewById(R.id.dfApply);
        Anim.press(apply);
        apply.setOnClickListener(x -> {
            Discover.Query n = query.copy();
            n.country = (String) checkedValue(type);
            Object s = checkedValue(score);
            n.minScore = s == null ? 0 : (Integer) s;
            n.status = (String) checkedValue(status);
            int[] y = (int[]) checkedValue(year);
            n.fromYear = y == null ? 0 : y[0];
            n.toYear = y == null ? 0 : y[1];
            n.genres.clear();
            n.tags.clear();
            for (int i = 0; i < genres.getChildCount(); i++) {
                Chip c = (Chip) genres.getChildAt(i);
                if (c.isChecked()) n.genres.add((String) c.getTag());
            }
            for (int i = 0; i < tags.getChildCount(); i++) {
                Chip c = (Chip) tags.getChildAt(i);
                if (c.isChecked()) n.tags.add((String) c.getTag());
            }
            query = n;
            sheet.dismiss();
            reload();
        });
        sheet.setContentView(v);
        sheet.setOnShowListener(x -> sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));
        sheet.show();
    }

    private void choice(ChipGroup g, String label, boolean checked, Object value) {
        Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_filter, g, false);
        c.setId(View.generateViewId());
        c.setText(label);
        c.setTag(value);
        g.addView(c);
        c.setChecked(checked);
        c.setOnClickListener(x -> Anim.pop(c));
    }

    private static Object checkedValue(ChipGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            Chip c = (Chip) g.getChildAt(i);
            if (c.isChecked()) return c.getTag();
        }
        return null;
    }

    // ------------------------------------------------------------------ Fiche d'une série

    /** La fiche d'une série du catalogue (aussi ouverte depuis l'accueil). */
    /** Fonds illustrés de la fiche (images originales, voir tools/sheet_bg.py) : un par série, au hasard stable. */
    private static final int[] SHEET_BGS = {R.drawable.sheet_bg_castle, R.drawable.sheet_bg_peaks, R.drawable.sheet_bg_ruins};

    /** Chapitres publiés sur WEBTOON (flux officiel), par série : {nombre, langue}. */
    private final java.util.Map<Integer, String[]> webtoonCount = new java.util.HashMap<>();

    void openMedia(Discover.Media m) {
        if (!started) wishIds = DbHelper.get(act).wishIds();
        BottomSheetDialog sheet = new BottomSheetDialog(act);
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_discover, null);
        android.widget.ImageView bg = v.findViewById(R.id.dsBg);
        bg.setImageResource(SHEET_BGS[Math.floorMod(m.id != 0 ? m.id : m.title.hashCode(), SHEET_BGS.length)]);
        CoverCards.cropTop(bg, 0f);
        NetImages.load(v.findViewById(R.id.dsCover), m.cover, m.color);
        bindSheet(v, m);
        MaterialButton wish = v.findViewById(R.id.dsWish);
        bindWishButton(wish, wishIds.contains(m.id));
        wish.setOnClickListener(x -> {
            boolean now = toggleWish(m);
            bindWishButton(wish, now);
            Anim.pop(wish);
        });
        View anilist = v.findViewById(R.id.dsAnilist);
        anilist.setVisibility(m.id > 0 ? View.VISIBLE : View.GONE);   // fiche MangaDex absente d'AniList : pas de page
        anilist.setOnClickListener(x -> open(m.siteUrl != null ? m.siteUrl : "https://anilist.co/manga/" + m.id));
        TextView desc = v.findViewById(R.id.dsDesc), more = v.findViewById(R.id.dsMore);
        more.setOnClickListener(x -> {
            desc.setMaxLines(Integer.MAX_VALUE);
            more.setVisibility(View.GONE);
        });
        sheet.setContentView(v);
        sheet.show();
        Anim.pop(v.findViewById(R.id.dsCover));
        if (m.full) {
            bindDetails(v, m);
            loadWebtoonCount(v, sheet, m);
        } else {
            IO.execute(() -> {
                Discover.Media full;
                try {
                    full = m.source == Discover.Source.MANGADEX ? MangaDex.details(m.extId) : Discover.details(m.id);
                } catch (Exception e) {
                    full = null;
                }
                if (full != null && m.source == Discover.Source.MANGADEX) {   // note et abonnés gardés de la liste
                    if (full.score == 0) full.score = m.score;
                    if (full.popularity == 0) full.popularity = m.popularity;
                }
                final Discover.Media f = full;
                ui.post(() -> {
                    if (!sheet.isShowing()) return;
                    if (f == null) {
                        v.findViewById(R.id.dsLoading).setVisibility(View.GONE);
                        desc.setVisibility(View.VISIBLE);
                        desc.setText("Résumé indisponible pour l'instant (" + m.source.label + " ne répond pas).");
                        return;
                    }
                    f.because = m.because;
                    bindSheet(v, f);
                    bindDetails(v, f);
                    loadWebtoonCount(v, sheet, f);
                });
            });
        }
    }

    private void bindSheet(View v, Discover.Media m) {
        ((TextView) v.findViewById(R.id.dsTitle)).setText(m.title);
        TextView alt = v.findViewById(R.id.dsAlt);
        alt.setVisibility(m.alt == null ? View.GONE : View.VISIBLE);
        alt.setText(m.alt);
        TextView score = v.findViewById(R.id.dsScore);
        score.setVisibility(m.score > 0 ? View.VISIBLE : View.GONE);
        score.setText("★ " + m.scoreText());
        ((TextView) v.findViewById(R.id.dsType)).setText(m.type());
        TextView status = v.findViewById(R.id.dsStatus);
        String st = m.statusFr();
        status.setVisibility(st == null ? View.GONE : View.VISIBLE);
        status.setText(st);
        bindFacts(v, m);
        TextView authors = v.findViewById(R.id.dsAuthors);
        authors.setVisibility(m.authors.isEmpty() ? View.GONE : View.VISIBLE);
        if (!m.authors.isEmpty()) authors.setText("de " + String.join(", ", m.authors.subList(0, Math.min(2, m.authors.size()))));
        TextView because = v.findViewById(R.id.dsBecause);
        because.setVisibility(m.because == null ? View.GONE : View.VISIBLE);
        if (m.because != null) {
            because.setText(m.because.startsWith("Comme ")
                    ? "Recommandée par les lecteurs de " + m.because.substring(6) : m.because);
        }
        ((TextView) v.findViewById(R.id.dsCredit)).setText("Données : " + m.source.label);   // MangaDex demande d'être crédité
        ChipGroup genres = v.findViewById(R.id.dsGenres);
        genres.removeAllViews();
        for (String g : m.genres) genres.addView(genreChip(genres, g, me != null && me.topGenres(3).contains(g)));
        for (String t : m.tags) {
            if (genres.getChildCount() >= 9) break;
            if ("Male Protagonist".equals(t) || "Full Color".equals(t) || "Long Strip".equals(t) || "Web Comic".equals(t)) continue;
            genres.addView(tagChip(genres, Discover.tagFr(t), me != null && me.tags.containsKey(t) && me.topTags(8).contains(t)));
        }
    }

    /**
     * « 2021 · 39 k lecteurs · 201 chapitres » : chapitres en violet souligné. Le nombre vient d'AniList
     * (séries terminées) ou, pour une série en cours publiée sur WEBTOON, de son flux officiel.
     */
    private void bindFacts(View v, Discover.Media m) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (m.year > 0) sb.append(String.valueOf(m.year));
        if (m.popularity > 0) sb.append(sb.length() > 0 ? " · " : "").append(readers(m.popularity)).append(" lecteurs");
        String[] wt = webtoonCount.get(countKey(m));
        String ch = m.chapters > 0 ? m.chapters + " chapitres"
                : wt != null ? wt[0] + " chapitres sur WEBTOON" + (wt[1] == null ? "" : " " + wt[1]) : null;
        if (ch != null) {
            if (sb.length() > 0) sb.append(" · ");
            int start = sb.length();
            sb.append(ch);
            sb.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(act, R.color.primary_bright)),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.UnderlineSpan(), start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView facts = v.findViewById(R.id.dsFacts);
        facts.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        facts.setText(sb);
    }

    /**
     * Série en cours (AniList ne donne pas son nombre de chapitres) publiée sur WEBTOON : le dernier
     * épisode public se lit dans le flux officiel de la série (le même que pour tes éditions).
     */
    private static int countKey(Discover.Media m) {
        return m.id != 0 ? m.id : m.title.hashCode();   // fiche MangaDex seule : pas d'identifiant AniList
    }

    private void loadWebtoonCount(View v, BottomSheetDialog sheet, Discover.Media m) {
        if (m.chapters > 0 || webtoonCount.containsKey(countKey(m))) return;
        String url = null;
        for (String[] l : m.links) {
            if (!SourceRegistry.isWebtoons(l[1]) || Detector.parseSeriesLink(l[1]) == null) continue;
            String lang = SourceRegistry.langFromUrl(l[1]);
            if (url == null || "fr".equals(lang) || ("en".equals(lang) && !"fr".equals(SourceRegistry.langFromUrl(url)))) url = l[1];
        }
        if (url == null) return;
        final String link = url;
        IO.execute(() -> {
            try {
                DbHelper.Edition e = Editions.edition(link, "WEBTOON", SourceRegistry.langFromUrl(link),
                        SourceRegistry.classify(link, true), "Fiche Découvrir", SourceRegistry.FASTPASS);
                Editions.webtoonEpisodes(e);
                if (e.latest < 1) return;
                String lang = e.lang == null ? null : e.lang.toUpperCase(java.util.Locale.ROOT);
                ui.post(() -> {
                    webtoonCount.put(countKey(m), new String[]{Detector.formatChapter(e.latest), lang});
                    if (sheet.isShowing()) bindFacts(v, m);
                });
            } catch (Exception ignored) {
                // pas de flux lisible : on n'affiche simplement pas de nombre
            }
        });
    }

    private void bindDetails(View v, Discover.Media m) {
        v.findViewById(R.id.dsLoading).setVisibility(View.GONE);
        TextView desc = v.findViewById(R.id.dsDesc), more = v.findViewById(R.id.dsMore);
        desc.setVisibility(View.VISIBLE);
        desc.setText(m.description == null ? "Pas de résumé sur " + m.source.label + "." : m.description);
        desc.post(() -> {
            if (desc.getLayout() != null && desc.getLayout().getEllipsisCount(desc.getLineCount() - 1) > 0) more.setVisibility(View.VISIBLE);
        });
        ChipGroup links = v.findViewById(R.id.dsLinks);
        links.removeAllViews();
        for (String[] l : m.links) {
            Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_link, links, false);
            c.setText(l[0]);
            c.setOnClickListener(x -> open(l[1]));
            links.addView(c);
        }
        v.findViewById(R.id.dsNoLink).setVisibility(m.links.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < links.getChildCount(); i++) Anim.enter(links.getChildAt(i), i);
    }

    /** Pastille de genre ou de thème ; en couleur si c'est l'un de tes préférés. */
    private Chip tagChip(ChipGroup parent, String label, boolean mine) {
        Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_sheet_tag, parent, false);
        c.setText(label);
        c.setCheckable(false);
        c.setClickable(false);
        if (mine) {
            c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(act, R.color.brand)));
            c.setChipStrokeColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(act, R.color.primary_bright)));
            c.setTextColor(0xFFFFFFFF);
        }
        return c;
    }

    /** Genre de la fiche ; plein violet si c'est l'un de tes genres préférés. */
    private Chip genreChip(ChipGroup parent, String genre, boolean mine) {
        return tagChip(parent, Discover.genreFr(genre), mine);
    }

    private void bindWishButton(MaterialButton b, boolean in) {
        b.setText(in ? "Dans ma liste" : "Ajouter à ma liste");
        b.setIconResource(in ? R.drawable.ic_check : R.drawable.ic_bookmark_border);
        b.setBackgroundTintList(null);
        b.setBackgroundResource(in ? R.drawable.bg_button_glass : R.drawable.bg_primary_button);
        int fg = in ? ContextCompat.getColor(act, R.color.badge_text) : 0xFFFFFFFF;
        b.setTextColor(fg);
        b.setIconTint(android.content.res.ColorStateList.valueOf(fg));
    }

    /** @return true si la série est maintenant dans « Ma liste à lire » */
    boolean toggleWish(Discover.Media m) {
        if (!started) wishIds = DbHelper.get(act).wishIds();
        DbHelper db = DbHelper.get(act);
        boolean in = wishIds.contains(m.id);
        if (in) {
            db.removeWish(m.id);
            wishIds.remove(m.id);
            Toast.makeText(act, "Retirée de ta liste à lire", Toast.LENGTH_SHORT).show();
        } else {
            db.addWish(m.id, m.title, m.toJson().toString(), System.currentTimeMillis());
            wishIds.add(m.id);
            Toast.makeText(act, "Ajoutée à ta liste à lire", Toast.LENGTH_SHORT).show();
        }
        BackupManager.autoBackup(act);
        refreshWishes();
        if (onWishChanged != null) onWishChanged.run();
        return !in;
    }

    private void open(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(act, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
    }

    /** « 12 k », « 1,2 M » */
    private static String readers(int n) {
        if (n >= 1_000_000) return String.format(Locale.FRANCE, "%.1f M", n / 1_000_000f);
        if (n >= 1000) return (n / 1000) + " k";
        return String.valueOf(n);
    }

    // ------------------------------------------------------------------ Couvertures

    /** Vue recyclée au milieu d'une animation d'entrée : on la remet d'aplomb. */
    private static void settle(View v) {
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationY(0f);
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    /** Grille : en-tête, séries, bas (chargement, fin, erreur). */
    private final class GridAdapter extends RecyclerView.Adapter<Holder> {

        @Override
        public int getItemCount() {
            return results.size() + 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? T_HEADER : position == getItemCount() - 1 ? T_FOOTER : T_ITEM;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (type == T_HEADER) {
                Holder h = new Holder(inf.inflate(R.layout.header_discover, parent, false));
                setupHeader(h.itemView);
                return h;
            }
            if (type == T_FOOTER) return new Holder(inf.inflate(R.layout.item_discover_footer, parent, false));
            View v = inf.inflate(R.layout.item_discover, parent, false);
            Anim.pressAll(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            int type = getItemViewType(position);
            if (type == T_HEADER) return;
            if (type == T_FOOTER) {
                bindFooter(h.itemView);
                return;
            }
            Discover.Media m = results.get(position - 1);
            CoverCards.bind(h.itemView, m, wishIds.contains(m.id), cards);
            if (position > animatedUpTo) {   // les nouvelles couvertures arrivent en cascade
                animatedUpTo = position;
                Anim.enter(h.itemView, (position - 1) % 3);
            } else {
                settle(h.itemView);
            }
        }
    }

    private void bindFooter(View f) {
        View spin = f.findViewById(R.id.dfLoading);
        TextView text = f.findViewById(R.id.dfText);
        View retry = f.findViewById(R.id.dfRetry);
        spin.setVisibility(loading || (me == null && error == null) ? View.VISIBLE : View.GONE);
        String partial = !loading && error == null && !hasNext && multi != null ? multi.failureNote() : null;
        retry.setVisibility(error != null || partial != null ? View.VISIBLE : View.GONE);
        retry.setOnClickListener(v -> {
            error = null;
            if (multi != null && multi.hasFailed()) {   // relance la source en échec, à sa page
                multi.retryFailed();
                hasNext = true;
            }
            loadMore();
        });
        String msg = null;
        if (error != null) msg = error;
        else if (partial != null) msg = partial;
        else if (!loading && me != null && !hasNext) {
            msg = results.isEmpty() ? (query.searching() ? "Aucune série trouvée (ou tu les lis déjà)."
                    : "Aucune série ne correspond : élargis les filtres.") : "C'est tout pour ces critères.";
        }
        text.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        text.setText(msg);
    }
}
