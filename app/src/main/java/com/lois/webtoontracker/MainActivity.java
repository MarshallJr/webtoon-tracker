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

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 15 affiche l'appli bord à bord : on décale le contenu sous la barre d'état.
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        statusPill = findViewById(R.id.statusPill);
        statSeries = findViewById(R.id.statSeries);
        statChapters = findViewById(R.id.statChapters);
        statWeek = findViewById(R.id.statWeek);
        greeting = findViewById(R.id.greeting);
        sectionTitle = findViewById(R.id.sectionTitle);
        emptyState = findViewById(R.id.emptyState);

        RecyclerView list = findViewById(R.id.seriesList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SeriesAdapter(this::showHistory, this::confirmDeleteSeries);
        list.setAdapter(adapter);

        statusPill.setOnClickListener(v -> {
            if (!isServiceEnabled()) {
                Toast.makeText(this, "Cherche « Webtoon Tracker » et active-le", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        pickNoteFile = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/markdown"), this::onNoteFilePicked);
        findViewById(R.id.noteButton).setOnClickListener(v -> onNoteButton());
        findViewById(R.id.shareButton).setOnClickListener(v -> shareNote());
        findViewById(R.id.infoButton).setOnClickListener(v -> showDiagnostic());
        findViewById(R.id.addButton).setOnClickListener(v -> showAddDialog(null));

        ((EditText) findViewById(R.id.searchInput)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                updateEmpty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        showPendingCrash();
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

    private void refresh() {
        try {
            boolean on = isServiceEnabled();
            statusPill.setText(on ? "●  Analyse en fond active" : "○  Analyse désactivée — toucher pour activer");

            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greeting.setText(h < 5 || h >= 18 ? "Bonsoir 🌙 Bonne lecture" : "Bonjour ☀️ Bonne lecture");

            DbHelper db = DbHelper.get(this);
            List<DbHelper.Series> series = db.getSeries();
            adapter.setData(series);
            statSeries.setText(String.valueOf(series.size()));
            statChapters.setText(String.valueOf(db.totalChapters()));
            statWeek.setText(String.valueOf(db.countSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)));
            updateEmpty();
        } catch (Throwable t) {
            App.saveError(this, "Affichage", t);
            Toast.makeText(this, "Erreur d'affichage : voir Diagnostic (ⓘ)", Toast.LENGTH_LONG).show();
        }
    }

    private void updateEmpty() {
        boolean empty = adapter.shownCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        sectionTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.emptyTitle)).setText(
                adapter.isFiltering() ? "Aucun résultat" : "Aucune lecture pour l'instant");
        findViewById(R.id.emptyText).setVisibility(adapter.isFiltering() ? View.GONE : View.VISIBLE);
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
                .setNeutralButton("Régénérer", (d, w) -> {
                    NoteWriter.writeAsync(this);
                    Toast.makeText(this, "Fichier mis à jour", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Fermer", null)
                .show();
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

    // ------------------------------------------------------------------ Historique (bottom sheet)

    private void showHistory(DbHelper.Series s) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_history, null);
        sheet.setContentView(v);

        SeriesAdapter.paintAvatar(v.findViewById(R.id.sAvatar), s.title);
        ((TextView) v.findViewById(R.id.sTitle)).setText(s.title);
        ((TextView) v.findViewById(R.id.sSubtitle)).setText("Dernier : chapitre "
                + Detector.formatChapter(s.lastChapter) + " · " + s.count
                + (s.count > 1 ? " chapitres lus" : " chapitre lu"));

        List<DbHelper.Entry> entries = DbHelper.get(this).getHistory(s.title);
        RecyclerView rv = v.findViewById(R.id.sList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new HistoryAdapter(entries, e -> {
            sheet.dismiss();
            confirmDeleteEntry(s, e);
        }));

        v.findViewById(R.id.sAdd).setOnClickListener(b -> {
            sheet.dismiss();
            showAddDialog(s.title);
        });
        v.findViewById(R.id.sDelete).setOnClickListener(b -> {
            sheet.dismiss();
            confirmDeleteSeries(s);
        });
        sheet.show();
    }

    interface OnEntry {
        void on(DbHelper.Entry e);
    }

    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.H> {
        private final List<DbHelper.Entry> items;
        private final OnEntry onClick;
        private final SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE);

        HistoryAdapter(List<DbHelper.Entry> items, OnEntry onClick) {
            this.items = items;
            this.onClick = onClick;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DbHelper.Entry e = items.get(position);
            h.chapter.setText("Ch. " + Detector.formatChapter(e.chapter));
            h.date.setText(fmt.format(new Date(e.readAt)));
            h.source.setText(e.source == null ? "" : e.source);
            h.itemView.setOnClickListener(v -> onClick.on(e));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class H extends RecyclerView.ViewHolder {
            final TextView chapter, date, source;

            H(View v) {
                super(v);
                chapter = v.findViewById(R.id.hChapter);
                date = v.findViewById(R.id.hDate);
                source = v.findViewById(R.id.hSource);
            }
        }
    }

    // ------------------------------------------------------------------ Dialogues

    private void confirmDeleteEntry(DbHelper.Series s, DbHelper.Entry e) {
        new MaterialAlertDialogBuilder(this)
                .setMessage("Supprimer le chapitre " + Detector.formatChapter(e.chapter) + " de « " + s.title + " » ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    DbHelper.get(this).deleteEntry(e.id);
                    NoteWriter.writeAsync(this);
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDeleteSeries(DbHelper.Series s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Supprimer la série ?")
                .setMessage("« " + s.title + " » et ses " + s.count + " chapitres seront supprimés.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    DbHelper.get(this).deleteSeries(s.title);
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
