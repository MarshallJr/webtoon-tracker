package com.lois.webtoontracker;

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

    private final OnSeries onClick;
    private final OnSeries onLongClick;
    private List<DbHelper.Series> all = new ArrayList<>();
    private final List<DbHelper.Series> shown = new ArrayList<>();
    private String query = "";

    public SeriesAdapter(OnSeries onClick, OnSeries onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
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

    private void applyFilter() {
        shown.clear();
        for (DbHelper.Series s : all) {
            if (query.isEmpty() || s.title.toLowerCase(Locale.ROOT).contains(query)) shown.add(s);
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
        h.title.setText(s.title);
        h.chapter.setText(Detector.formatChapter(s.lastChapter));
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                s.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        h.subtitle.setText(s.count + (s.count > 1 ? " chapitres lus" : " chapitre lu") + " · " + when);
        h.itemView.setOnClickListener(v -> onClick.on(s));
        h.itemView.setOnLongClickListener(v -> {
            onLongClick.on(s);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, subtitle, chapter;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            chapter = v.findViewById(R.id.chapter);
        }
    }
}
