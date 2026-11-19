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
        adapter = new SeriesAdapter(s -> DetailActivity.open(this, s.title), this::confirmDeleteSeries);
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
        findViewById(R.id.noteButton).setOnClickListener(v -> onNoteButton());
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettings());
        findViewById(R.id.infoButton).setOnClickListener(v -> showDiagnostic());
        findViewById(R.id.addButton).setOnClickListener(v -> showAddDialog(null));
        findViewById(R.id.heroCard).setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
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
        }).start();
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

    private void refresh() {
        try {
            boolean on = isServiceEnabled();
            statusPill.setText(on ? "Analyse active" : "Analyse désactivée · activer");
            android.graphics.drawable.Drawable dot = statusPill.getCompoundDrawablesRelative()[0];
            if (dot != null) dot.mutate().setTint(on ? 0xFF3DDC84 : 0xFFFF6B6B);

            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greeting.setText(h < 5 || h >= 18 ? "Bonsoir 🌙" : "Bonjour ☀️");

            DbHelper db = DbHelper.get(this);
            List<DbHelper.Series> series = db.getSeries();
            adapter.setData(series);
            buildTagChips(db);
            refreshUpdates(db);
            updateDuplicates(series);
            WidgetProvider.updateAll(this);
            CoverWorker.enqueueIfNeeded(this);
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
        findViewById(R.id.emptyRestoreButton).setVisibility(adapter.isFiltering() ? View.GONE : View.VISIBLE);
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

        new MaterialAlertDialogBuilder(this)
                .setTitle("Réglages")
                .setView(v)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> {
                    backupStatusView = null;
                })
                .show();
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
    private java.util.LinkedHashMap<String, List<DbHelper.Release>> groupedUpdates(String onlyTitle) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = new java.util.LinkedHashMap<>();
        for (DbHelper.Release r : DbHelper.get(this).newReleases(onlyTitle)) {
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

    private void refreshUpdates(DbHelper db) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = groupedUpdates(null);
        java.util.Map<String, Double> best = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, List<DbHelper.Release>> e : map.entrySet()) {
            best.put(e.getKey(), e.getValue().get(0).chapter);
        }
        adapter.setNewChapters(best);
        View card = findViewById(R.id.updatesCard);
        card.setVisibility(map.isEmpty() ? View.GONE : View.VISIBLE);
        if (!map.isEmpty()) {
            int n = map.size();
            ((TextView) findViewById(R.id.updatesTitle)).setText(n + (n > 1 ? " séries ont du nouveau" : " série a du nouveau"));
            StringBuilder sb = new StringBuilder();
            for (List<DbHelper.Release> l : map.values()) {
                if (sb.length() > 40) break;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(l.get(0).title).append(" ").append(Detector.formatChapter(l.get(0).chapter));
            }
            ((TextView) findViewById(R.id.updatesSub)).setText(sb);
        }
    }

    /** Panneau "Nouveaux chapitres" : pour chaque série, les sites où lire, en EN et FR. */
    private void showUpdates(String onlyTitle) {
        java.util.LinkedHashMap<String, List<DbHelper.Release>> map = groupedUpdates(onlyTitle);
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
                c.setText(r.lang + " · " + r.label + " · ch. " + Detector.formatChapter(r.chapter));
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

    private void buildTagChips(DbHelper db) {
        com.google.android.material.chip.ChipGroup group = findViewById(R.id.tagChips);
        java.util.Set<String> tags = db.allTags();
        findViewById(R.id.tagScroll).setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        if (selectedTag != null && !tags.contains(selectedTag)) {
            selectedTag = null;
            adapter.filterTag(null);
        }
        group.setOnCheckedStateChangeListener(null);
        group.removeAllViews();
        group.addView(makeChip(group, "Tout", selectedTag == null));
        for (String t : tags) group.addView(makeChip(group, t, t.equalsIgnoreCase(selectedTag == null ? "" : selectedTag)));
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

    private void updateDuplicates(List<DbHelper.Series> series) {
        List<String> titles = new java.util.ArrayList<>();
        for (DbHelper.Series s : series) titles.add(s.title);
        duplicates = Titles.duplicateGroups(titles);
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
