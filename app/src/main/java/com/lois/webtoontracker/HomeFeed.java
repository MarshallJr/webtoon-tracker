package com.lois.webtoontracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accueil : carrousels « Pour toi » et « Explorer ». Données AniList réelles (les mêmes que Découvrir),
 * affichées depuis le cache tout de suite, rafraîchies au plus toutes les 12 h : l'accueil n'ajoute
 * presque aucune requête. Les séries que tu lis déjà n'y apparaissent jamais.
 */
final class HomeFeed {

    /** Genres d'Explorer : {genre AniList (null = tous), libellé, icône, teinte}. */
    private static final Object[][] GENRES = {
            {null, "Tous", R.drawable.ic_grid, 0xFFFFFFFF},
            {"Action", "Action", R.drawable.ic_swords, 0xFFFBBF24},
            {"Romance", "Romance", R.drawable.ic_heart, 0xFFF472B6},
            {"Fantasy", "Fantasy", R.drawable.ic_castle, 0xFFFBBF24},
            {"Drama", "Drame", R.drawable.ic_masks, 0xFFC4B5FD}};
    private static final long EXPLORE_MS = 12L * 3600 * 1000;
    private static final int EXPLORE_MAX = 12;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final AppCompatActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CoverCards.Adapter forYou, explore;
    private Discover.Profile me;
    private Set<Integer> wishIds = new HashSet<>();
    private String genre;   // null = Tous
    private int exploreGen;
    private long lastRefresh;

    HomeFeed(AppCompatActivity act, CoverCards.Listener listener) {
        this.act = act;
        forYou = new CoverCards.Adapter(R.layout.item_discover_card, listener);
        explore = new CoverCards.Adapter(R.layout.item_discover, listener)
                .width(act.getResources().getDimensionPixelSize(R.dimen.tile_width));
        RecyclerView fy = act.findViewById(R.id.forYouList);
        fy.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        fy.setAdapter(forYou);
        fy.setItemAnimator(null);
        RecyclerView ex = act.findViewById(R.id.exploreList);
        ex.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        ex.setAdapter(explore);
        ex.setItemAnimator(null);
        buildChips();
    }

    private void buildChips() {
        ChipGroup group = act.findViewById(R.id.exploreChips);
        for (Object[] g : GENRES) {
            Chip c = (Chip) LayoutInflater.from(act).inflate(R.layout.chip_filter, group, false);
            c.setId(View.generateViewId());
            c.setText((String) g[1]);
            c.setChipIconResource((Integer) g[2]);
            c.setChipIconVisible(true);
            c.setChipIconTint(ColorStateList.valueOf((Integer) g[3]));
            c.setTag(g[0]);
            group.addView(c);
            c.setChecked(g[0] == null);
            c.setOnClickListener(v -> {
                Anim.pop(c);
                String chosen = (String) c.getTag();
                if (chosen == null ? genre == null : chosen.equals(genre)) {
                    c.setChecked(true);
                    return;
                }
                genre = chosen;
                loadExplore();
            });
        }
        Anim.pressAll(group);
    }

    /** Au retour sur l'accueil (au plus une fois par minute) : cache d'abord, réseau seulement s'il est périmé. */
    void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 60_000) return;
        lastRefresh = now;
        Context app = act.getApplicationContext();
        IO.execute(() -> {
            Discover.Profile p;
            Set<Integer> w;
            try {
                p = Discover.profile(app);
                w = DbHelper.get(app).wishIds();
            } catch (Exception e) {
                return;
            }
            ui.post(() -> {
                me = p;
                wishIds = w;
                forYou.setWished(w);
                explore.setWished(w);
                loadExplore();
            });
            List<Discover.Media> fy;
            try {
                fy = Discover.forYou(app, p, false);
            } catch (Exception e) {
                fy = new ArrayList<>();   // pas de réseau : la section reste cachée, rien ne casse
            }
            final List<Discover.Media> l = fy;
            ui.post(() -> bindForYou(l));
        });
    }

    /** « Ma liste à lire » a changé (signet touché) : les signets suivent. */
    void wishesChanged() {
        Context app = act.getApplicationContext();
        IO.execute(() -> {
            Set<Integer> w = DbHelper.get(app).wishIds();
            ui.post(() -> {
                wishIds = w;
                forYou.setWished(w);
                explore.setWished(w);
            });
        });
    }

    private void bindForYou(List<Discover.Media> l) {
        if (act.isDestroyed()) return;
        List<Discover.Media> shown = new ArrayList<>();
        for (Discover.Media m : l) if (me == null || !me.has(m)) shown.add(m);
        View section = act.findViewById(R.id.forYouSection);
        boolean was = section.getVisibility() == View.VISIBLE;
        section.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        if (!was || !forYou.sameAs(shown)) forYou.setItems(shown, wishIds);
        else forYou.setWished(wishIds);
    }

    private void loadExplore() {
        final String g = genre;
        final int gen = ++exploreGen;
        final Discover.Profile p = me;
        Context app = act.getApplicationContext();
        View loading = act.findViewById(R.id.exploreLoading);
        TextView empty = act.findViewById(R.id.exploreEmpty);
        IO.execute(() -> {
            SharedPreferences prefs = app.getSharedPreferences("discover", Context.MODE_PRIVATE);
            String key = "explore_" + (g == null ? "all" : g);
            List<Discover.Media> cached = readCache(prefs.getString(key, null), p);
            boolean fresh = System.currentTimeMillis() - prefs.getLong(key + "_at", 0) < EXPLORE_MS;
            ui.post(() -> {
                if (gen != exploreGen) return;
                if (!cached.isEmpty()) bindExplore(cached, null);
                loading.setVisibility(cached.isEmpty() && !fresh ? View.VISIBLE : View.GONE);
                if (cached.isEmpty()) empty.setVisibility(View.GONE);
            });
            if (fresh && !cached.isEmpty()) return;
            try {
                Discover.Query q = new Discover.Query();
                if (g != null) q.genres.add(g);
                Discover.Result r = Discover.browse(q, 1, p);
                JSONArray a = new JSONArray();
                List<Discover.Media> items = new ArrayList<>();
                for (Discover.Media m : r.items) {
                    if (items.size() >= EXPLORE_MAX) break;
                    items.add(m);
                    a.put(m.toJson());
                }
                prefs.edit().putString(key, a.toString()).putLong(key + "_at", System.currentTimeMillis()).apply();
                ui.post(() -> {
                    if (gen != exploreGen) return;
                    loading.setVisibility(View.GONE);
                    bindExplore(items, items.isEmpty() ? "Rien à proposer dans ce genre pour l'instant." : null);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != exploreGen) return;
                    loading.setVisibility(View.GONE);
                    if (cached.isEmpty()) bindExplore(new ArrayList<>(), "AniList ne répond pas : réessaie plus tard.");
                });
            }
        });
    }

    private void bindExplore(List<Discover.Media> items, String message) {
        if (act.isDestroyed()) return;
        TextView empty = act.findViewById(R.id.exploreEmpty);
        empty.setVisibility(message == null ? View.GONE : View.VISIBLE);
        empty.setText(message);
        if (explore.sameAs(items)) {
            explore.setWished(wishIds);
            return;
        }
        explore.setItems(items, wishIds);
        ((RecyclerView) act.findViewById(R.id.exploreList)).scrollToPosition(0);
    }

    private static List<Discover.Media> readCache(String json, Discover.Profile p) {
        List<Discover.Media> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                Discover.Media m = Discover.Media.fromJson(a.getJSONObject(i));
                if (p == null || !p.has(m)) out.add(m);   // lue depuis : on ne la propose plus
            }
        } catch (Exception ignored) {
            // cache illisible : on repart du réseau
        }
        return out;
    }
}
