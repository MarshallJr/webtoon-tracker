package com.lois.webtoontracker;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cartes de couverture partagées par l'accueil (Pour toi, Explorer) et Découvrir (carrousels, grille) :
 * même rendu partout, quelle que soit la source de la série (AniList ou MangaDex).
 *
 * <ul>
 * <li>{@code item_discover_card} : carte (couverture, note, signet, titre, type · année, genre) ;</li>
 * <li>{@code item_discover} : tuile (titre et type posés sur la couverture).</li>
 * </ul>
 */
final class CoverCards {

    private CoverCards() {
    }

    /** Ce qu'on fait d'un appui : ouvrir la fiche, ajouter / retirer de « Ma liste à lire ». */
    interface Listener {
        void open(Discover.Media m);

        void toggleWish(Discover.Media m);
    }

    /** Remplit une carte ou une tuile. */
    static void bind(View v, Discover.Media m, boolean wished, Listener l) {
        NetImages.load(v.findViewById(R.id.dcCover), m.cover, m.color);
        TextView score = v.findViewById(R.id.dcScore);
        score.setVisibility(m.score > 0 ? View.VISIBLE : View.GONE);
        score.setText(m.scoreText());
        ((TextView) v.findViewById(R.id.dcTitle)).setText(m.title);
        TextView meta = v.findViewById(R.id.dcMeta);
        if (meta != null) meta.setText(m.meta());
        TextView genre = v.findViewById(R.id.dcGenre);
        if (genre != null) {
            String g = m.genres.isEmpty() ? null : m.genres.get(0);
            genre.setVisibility(g == null ? View.GONE : View.VISIBLE);
            if (g != null) tintGenre(genre, g);
        }
        ImageView wish = v.findViewById(R.id.dcWish);
        wish.setImageResource(wished ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border);
        wish.setImageTintList(ColorStateList.valueOf(wished
                ? ContextCompat.getColor(v.getContext(), R.color.primary_bright) : 0xFFFFFFFF));
        wish.setContentDescription(wished ? "Retirer de ma liste à lire" : "Ajouter à ma liste à lire");
        if (l != null) {
            v.setOnClickListener(x -> l.open(m));
            wish.setOnClickListener(x -> {
                Anim.pop(wish);
                l.toggleWish(m);
            });
        }
    }

    /** Étiquette de genre : libellé français et teinte de sa famille (Action violet, Aventure bleu…). */
    static void tintGenre(TextView tag, String genre) {
        tag.setText(Discover.genreFr(genre));
        int[] c = genreColors(genre);
        tag.setTextColor(ContextCompat.getColor(tag.getContext(), c[0]));
        tag.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(tag.getContext(), c[1])));
    }

    /** {texte, fond} d'un genre AniList. */
    static int[] genreColors(String genre) {
        if (genre == null) return new int[]{R.color.genre_violet, R.color.genre_violet_bg};
        switch (genre) {
            case "Adventure":
            case "Mystery":
                return new int[]{R.color.genre_blue, R.color.genre_blue_bg};
            case "Comedy":
            case "Music":
                return new int[]{R.color.genre_orange, R.color.genre_orange_bg};
            case "Slice of Life":
            case "Sports":
                return new int[]{R.color.genre_green, R.color.genre_green_bg};
            case "Romance":
            case "Drama":
            case "Psychological":
            case "Mahou Shoujo":
                return new int[]{R.color.genre_pink, R.color.genre_pink_bg};
            case "Sci-Fi":
            case "Mecha":
            case "Fantasy":
                return new int[]{R.color.genre_teal, R.color.genre_teal_bg};
            case "Horror":
            case "Thriller":
            case "Ecchi":
                return new int[]{R.color.genre_red, R.color.genre_red_bg};
            default:
                return new int[]{R.color.genre_violet, R.color.genre_violet_bg};
        }
    }

    /** Carrousel de cartes ou de tuiles (les premières arrivent en cascade). */
    static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final int layout;
        private final Listener listener;
        private final List<Discover.Media> items = new ArrayList<>();
        private Set<Integer> wished = new HashSet<>();
        private int width;          // largeur fixe d'une tuile (0 = celle du gabarit)
        private boolean animate = true;

        Adapter(int layout, Listener listener) {
            this.layout = layout;
            this.listener = listener;
        }

        /** Tuiles de largeur fixe (carrousel Explorer). */
        Adapter width(int px) {
            this.width = px;
            return this;
        }

        void setItems(List<Discover.Media> l, Set<Integer> wishIds) {
            items.clear();
            items.addAll(l);
            wished = wishIds == null ? new HashSet<>() : wishIds;
            animate = true;
            notifyDataSetChanged();
        }

        void setWished(Set<Integer> wishIds) {
            wished = wishIds == null ? new HashSet<>() : wishIds;
            notifyItemRangeChanged(0, items.size());
        }

        List<Discover.Media> items() {
            return items;
        }

        /** Mêmes séries, dans le même ordre ? (évite de rejouer l'animation pour rien) */
        boolean sameAs(List<Discover.Media> l) {
            if (l.size() != items.size()) return false;
            for (int i = 0; i < l.size(); i++) if (l.get(i).id != items.get(i).id) return false;
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            if (width > 0) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                lp.width = width;
                v.setLayoutParams(lp);
            }
            Anim.pressAll(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Discover.Media m = items.get(position);
            bind(h.itemView, m, wished.contains(m.id), listener);
            if (animate && position < 4) {
                Anim.enter(h.itemView, position);
            } else {
                h.itemView.animate().cancel();
                h.itemView.setAlpha(1f);
                h.itemView.setTranslationY(0f);
            }
            if (position >= 3) animate = false;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View v) {
                super(v);
            }
        }
    }
}
