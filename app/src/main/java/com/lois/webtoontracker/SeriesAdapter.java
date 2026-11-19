package com.lois.webtoontracker;

import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SeriesAdapter extends RecyclerView.Adapter<SeriesAdapter.VH> {

    public interface OnSeries {
        void on(DbHelper.Series s);
    }

    /** Palette des pastilles (une couleur stable par série). */
    private static final int[] COLORS = {
            0xFF00B26A, 0xFFFF7A45, 0xFF5B6CFF, 0xFFE5484D, 0xFF9B5DE5,
            0xFF00A3C4, 0xFFF5A524, 0xFF2EB67D, 0xFFD6409F, 0xFF3E8E7E
    };

    private final OnSeries onClick;
    private final OnSeries onLongClick;
    private OnSeries onPlay;
    private String sort = "recent";
    private boolean onlyFollowed, onlyNew;
    private List<DbHelper.Series> all = new ArrayList<>();
    private final List<DbHelper.Series> shown = new ArrayList<>();
    private String query = "";
    private String tag = null; // null = toutes
    private java.util.Map<String, Double> newChapters = new java.util.HashMap<>();

    public void setOnPlay(OnSeries l) {
        onPlay = l;
    }

    /** Tri : "recent", "score", "title", "count". */
    public void setSort(String s) {
        sort = s;
        applyFilter();
    }

    public void setFilters(boolean followedOnly, boolean newOnly, java.util.Set<String> followed) {
        onlyFollowed = followedOnly;
        onlyNew = newOnly;
        this.followed = followed;
        applyFilter();
    }

    private java.util.Set<String> followed = new java.util.HashSet<>();

    public String getSort() {
        return sort;
    }

    public boolean isOnlyFollowed() {
        return onlyFollowed;
    }

    public boolean isOnlyNew() {
        return onlyNew;
    }

    public int totalCount() {
        return all.size();
    }

    /** Séries pour lesquelles un chapitre plus récent que ton dernier lu est sorti. */
    public void setNewChapters(java.util.Map<String, Double> map) {
        newChapters = map;
        applyFilter();
    }

    public SeriesAdapter(OnSeries onClick, OnSeries onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
    }

    public static int colorFor(String title) {
        return COLORS[Math.abs(title.toLowerCase(Locale.ROOT).hashCode() % COLORS.length)];
    }

    public static String initialFor(String title) {
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(Character.toUpperCase(c));
        }
        return "?";
    }

    public static void paintAvatar(TextView avatar, String title) {
        avatar.setText(initialFor(title));
        GradientDrawable bg = (GradientDrawable) avatar.getBackground().mutate();
        bg.setColor(colorFor(title));
    }

    /** Note publique /100 → "8,4" */
    public static String formatScore(int score) {
        return String.format(Locale.FRANCE, "%.1f", score / 10.0);
    }

    /** Affiche la miniature si elle existe, sinon la pastille avec l'initiale. */
    public static void bindCover(TextView avatar, android.widget.ImageView cover, String title) {
        paintAvatar(avatar, title);
        android.graphics.Bitmap b = Covers.get(avatar.getContext(), title);
        if (b != null) {
            cover.setImageBitmap(b);
            cover.setVisibility(View.VISIBLE);
            avatar.setVisibility(View.INVISIBLE);
        } else {
            cover.setImageDrawable(null);
            cover.setVisibility(View.GONE);
            avatar.setVisibility(View.VISIBLE);
        }
    }

    public void setData(List<DbHelper.Series> data) {
        all = data;
        applyFilter();
    }

    public void filter(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public int shownCount() {
        return shown.size();
    }

    public void filterTag(String t) {
        tag = t;
        applyFilter();
    }

    public boolean isFiltering() {
        return !query.isEmpty() || tag != null || onlyFollowed || onlyNew;
    }

    private void applyFilter() {
        shown.clear();
        for (DbHelper.Series s : all) {
            if (!query.isEmpty() && !s.title.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (tag != null) {
                boolean has = false;
                for (String t : Titles.splitTags(s.tags)) if (t.equalsIgnoreCase(tag)) has = true;
                if (!has) continue;
            }
            String k = s.title.toLowerCase(Locale.ROOT);
            if (onlyNew && !newChapters.containsKey(k)) continue;
            if (onlyFollowed && !followed.contains(k)) continue;
            shown.add(s);
        }
        switch (sort) {
            case "score":
                shown.sort((a, b) -> Integer.compare(b.score, a.score));
                break;
            case "title":
                shown.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                break;
            case "count":
                shown.sort((a, b) -> Integer.compare(b.count, a.count));
                break;
            default:
                break; // déjà du plus récent au plus ancien
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_series, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DbHelper.Series s = shown.get(position);
        bindCard(h.itemView, s, newChapters.get(s.title.toLowerCase(Locale.ROOT)), onClick, onLongClick, onPlay);
        // petite animation d'apparition
        h.itemView.setAlpha(0f);
        h.itemView.setTranslationY(18f);
        h.itemView.animate().alpha(1f).translationY(0f).setDuration(260)
                .setStartDelay(Math.min(position, 8) * 30L).start();
    }

    /** Remplit une carte "série" (utilisée par la bibliothèque ET par l'accueil). */
    public static void bindCard(View v, DbHelper.Series s, Double newChapter,
                                OnSeries click, OnSeries longClick, OnSeries play) {
        ((TextView) v.findViewById(R.id.title)).setText(s.title);
        bindCover(v.findViewById(R.id.avatar), v.findViewById(R.id.cover), s.title);
        TextView rating = v.findViewById(R.id.rating);
        rating.setVisibility(s.score > 0 ? View.VISIBLE : View.GONE);
        if (s.score > 0) rating.setText("★ " + formatScore(s.score));
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                s.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        ((TextView) v.findViewById(R.id.subtitle)).setText("Chapitre " + Detector.formatChapter(s.lastChapter) + " · " + when);
        ((TextView) v.findViewById(R.id.tagLine)).setText(s.count + (s.count > 1 ? " chapitres lus" : " chapitre lu"));
        TextView nw = v.findViewById(R.id.newBadge);
        nw.setVisibility(newChapter == null ? View.GONE : View.VISIBLE);
        if (newChapter != null) nw.setText("Nouveau · Ch. " + Detector.formatChapter(newChapter));
        v.setOnClickListener(x -> click.on(s));
        v.setOnLongClickListener(x -> {
            if (longClick != null) longClick.on(s);
            return true;
        });
        View p = v.findViewById(R.id.play);
        p.setOnClickListener(x -> {
            if (play != null) play.on(s);
        });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        VH(View v) {
            super(v);
        }
    }
}
