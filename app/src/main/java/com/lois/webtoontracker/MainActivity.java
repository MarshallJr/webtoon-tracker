package com.lois.webtoontracker;

import android.content.BroadcastReceiver;
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
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private SeriesAdapter adapter;
    private TextView statusText, statsText, emptyText, debugText;
    private MaterialButton enableButton;
    private boolean debugExpanded = false;
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

        statusText = findViewById(R.id.statusText);
        statsText = findViewById(R.id.statsText);
        emptyText = findViewById(R.id.emptyText);
        debugText = findViewById(R.id.debugText);
        enableButton = findViewById(R.id.enableButton);

        RecyclerView list = findViewById(R.id.seriesList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SeriesAdapter(this::showHistory, this::confirmDeleteSeries);
        list.setAdapter(adapter);

        enableButton.setOnClickListener(v -> {
            Toast.makeText(this, "Cherche « Webtoon Tracker » et active-le", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        pickNoteFile = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/markdown"), this::onNoteFilePicked);
        findViewById(R.id.noteButton).setOnClickListener(v -> onNoteButton());
        findViewById(R.id.shareButton).setOnClickListener(v -> shareNote());
        findViewById(R.id.addButton).setOnClickListener(v -> showAddDialog(null));

        ((EditText) findViewById(R.id.searchInput)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                updateEmpty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        debugText.setOnClickListener(v -> {
            debugExpanded = !debugExpanded;
            refreshDebug();
        });
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
        unregisterReceiver(updateReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ Affichage

    private void refresh() {
        boolean on = isServiceEnabled();
        statusText.setText(on ? "🟢 Analyse en fond active" : "🔴 Analyse en fond désactivée");
        enableButton.setVisibility(on ? View.GONE : View.VISIBLE);

        DbHelper db = DbHelper.get(this);
        List<DbHelper.Series> series = db.getSeries();
        adapter.setData(series);
        statsText.setText(series.size() + " séries · " + db.totalChapters() + " chapitres lus");
        updateEmpty();
        refreshDebug();
    }

    private void updateEmpty() {
        emptyText.setVisibility(adapter.shownCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void refreshDebug() {
        if (!debugExpanded) {
            debugText.setText("▸ Dernier écran analysé (toucher pour voir)");
            return;
        }
        SharedPreferences p = getSharedPreferences(ReadingTrackerService.PREFS_DEBUG, MODE_PRIVATE);
        long at = p.getLong("at", 0);
        if (at == 0) {
            debugText.setText("▾ Rien analysé pour l'instant.");
            return;
        }
        String when = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(new Date(at));
        debugText.setText("▾ " + when + " — " + p.getString("pkg", "") + "\nRésultat : "
                + p.getString("result", "") + "\n" + p.getString("texts", ""));
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

    // ------------------------------------------------------------------ Fichier note

    private void onNoteButton() {
        SharedPreferences p = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
        String current = p.getString(NoteWriter.KEY_NOTE_URI, null);
        String local = getExternalFilesDir(null) + "/webtoons.md";
        String msg = "Le récap est toujours écrit ici :\n" + local
                + (current != null ? "\n\nEt aussi dans le fichier que tu as choisi :\n" + Uri.parse(current).getLastPathSegment() : "")
                + "\n\nTu peux choisir un fichier dans Documents, Drive, ton coffre Obsidian…";
        new AlertDialog.Builder(this)
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
            // Certains fournisseurs ne gardent pas la permission ; on écrit quand même maintenant.
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

    private void showHistory(DbHelper.Series s) {
        List<DbHelper.Entry> entries = DbHelper.get(this).getHistory(s.title);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            DbHelper.Entry e = entries.get(i);
            items[i] = "Chapitre " + Detector.formatChapter(e.chapter) + "  —  " + fmt.format(new Date(e.readAt));
        }
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setItems(items, (d, which) -> confirmDeleteEntry(s, entries.get(which)))
                .setPositiveButton("Ajouter un chapitre", (d, w) -> showAddDialog(s.title))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void confirmDeleteEntry(DbHelper.Series s, DbHelper.Entry e) {
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
                .setMessage("Supprimer « " + s.title + " » et ses " + s.count + " chapitres ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    DbHelper.get(this).deleteSeries(s.title);
                    NoteWriter.writeAsync(this);
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showAddDialog(String presetTitle) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText title = new EditText(this);
        title.setHint("Titre de la série");
        if (presetTitle != null) title.setText(presetTitle);
        box.addView(title);

        EditText chapter = new EditText(this);
        chapter.setHint("Chapitre (ex : 42)");
        chapter.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(chapter);

        new AlertDialog.Builder(this)
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
