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
    private com.google.android.material.materialswitch.MaterialSwitch alertsSwitchView;
    private String selectedTag = null;
    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickCover;
    private String coverTarget;
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
        pickCover = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null || coverTarget == null) return;
            String t = coverTarget;
            runIo(() -> {
                Covers.saveFromUri(this, t, uri);
                Covers.setCustom(this, t, true);
                WidgetProvider.updateAll(this);
                return "Miniature mise à jour 🖼";
            });
        });
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                setAlerts(true);
            } else {
                Toast.makeText(this, "Sans l'autorisation de notifications, impossible de t'alerter", Toast.LENGTH_LONG).show();
                if (alertsSwitchView != null) alertsSwitchView.setChecked(false);
            }
        });

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
            statusPill.setText(on ? "Analyse active" : "Analyse désactivée · activer");
            android.graphics.drawable.Drawable dot = statusPill.getCompoundDrawablesRelative()[0];
            if (dot != null) dot.mutate().setTint(on ? 0xFF3DDC84 : 0xFFFF6B6B);

            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greeting.setText(h < 5 || h >= 18 ? "Bonsoir 🌙" : "Bonjour ☀️");

            DbHelper db = DbHelper.get(this);
            List<DbHelper.Series> series = db.getSeries();
            adapter.setData(series);
            buildTagChips(db);
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

        alertsSwitchView = v.findViewById(R.id.alertsSwitch);
        TextView alertsInfo = v.findViewById(R.id.alertsInfo);
        alertsSwitchView.setChecked(p.getBoolean(ChapterCheckWorker.KEY_ALERTS, false));
        alertsInfo.setText(alertsDescription());
        alertsSwitchView.setOnCheckedChangeListener((b, checked) -> {
            if (checked && android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
            setAlerts(checked);
            alertsInfo.setText(alertsDescription());
        });
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
                    alertsSwitchView = null;
                })
                .show();
    }

    // ------------------------------------------------------------------ Alertes nouveaux chapitres

    private void setAlerts(boolean on) {
        getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ChapterCheckWorker.KEY_ALERTS, on).apply();
        if (on) {
            ChapterCheckWorker.createChannel(this);
            ChapterCheckWorker.enable(this);
            ChapterCheckWorker.checkNow(this); // mémorise le dernier épisode actuel de chaque série
        } else {
            ChapterCheckWorker.disable(this);
        }
    }

    private String alertsDescription() {
        int linked = 0;
        for (DbHelper.Meta m : DbHelper.get(this).getAllMeta()) if (m.titleNo != null) linked++;
        SharedPreferences p = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE);
        long last = p.getLong("last_check", 0);
        return "Vérifie 2 fois par jour, seulement avec du réseau et une batterie non faible "
                + "(quasi aucun impact batterie). " + linked + (linked > 1 ? " séries liées" : " série liée")
                + " à webtoons.com : les séries lues dans le navigateur sont liées toutes seules, sinon "
                + "utilise « ⚡ Alertes » dans la fiche d'une série."
                + (last > 0 ? "\nDernière vérification : "
                + new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date(last)) : "");
    }

    private void showLinkDialog(DbHelper.Series s) {
        EditText input = new EditText(this);
        input.setHint("https://www.webtoons.com/fr/…/list?title_no=…");
        input.setSingleLine(true);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip != null && clip.toString().contains("webtoons.com")) input.setText(clip);
        }
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 3, pad, 0);
        TextView help = new TextView(this);
        help.setText("Ouvre la série sur webtoons.com (ou dans l'appli WEBTOON → Partager → Copier le lien), puis colle le lien ici.");
        help.setTextColor(ContextCompat.getColor(this, R.color.text_soft));
        box.addView(help);
        box.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle("⚡ Alertes pour « " + s.title + " »")
                .setView(box)
                .setPositiveButton("Lier", (d, w) -> {
                    String[] parsed = Detector.parseSeriesLink(input.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, "Lien non reconnu (il doit contenir title_no=…)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    DbHelper.get(this).linkWebtoons(s.title, parsed[0], parsed[1]);
                    NoteWriter.writeAsync(this);
                    boolean on = getSharedPreferences(NoteWriter.PREFS, MODE_PRIVATE)
                            .getBoolean(ChapterCheckWorker.KEY_ALERTS, false);
                    if (on) ChapterCheckWorker.checkNow(this);
                    Toast.makeText(this, on ? "Série liée ✅ Tu seras alerté des nouveaux chapitres"
                            : "Série liée ✅ Active les alertes dans ⚙️ Réglages", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------ Miniatures

    private void showCoverMenu(DbHelper.Series s) {
        String[] items = {
                "🖼 Choisir une image dans la galerie",
                "🔄 Revenir à la miniature automatique",
                "🗑 Supprimer la miniature"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Miniature · " + s.title)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        coverTarget = s.title;
                        pickCover.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
                    } else {
                        Covers.delete(this, s.title); // supprime aussi le verrou "image perso"
                        getSharedPreferences("covers_meta", MODE_PRIVATE).edit()
                                .putLong("try_" + s.title.toLowerCase(Locale.ROOT), w == 1 ? 0 : System.currentTimeMillis())
                                .apply();
                        WidgetProvider.updateAll(this);
                        refresh();
                        Toast.makeText(this, w == 1 ? "La miniature va être recherchée automatiquement"
                                : "Miniature supprimée", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
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

    private void showTagEditor(DbHelper.Series s) {
        java.util.Set<String> all = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Collections.addAll(all, SUGGESTED_TAGS);
        all.addAll(DbHelper.get(this).allTags());
        List<String> current = Titles.splitTags(s.tags);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 3, pad, 0);
        com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this);
        for (String t : all) {
            boolean on = false;
            for (String c : current) if (c.equalsIgnoreCase(t)) on = true;
            group.addView(makeChip(group, t, on));
        }
        box.addView(group);
        EditText custom = new EditText(this);
        custom.setHint("Autre tag (ex : Webtoon du dimanche)");
        custom.setSingleLine(true);
        box.addView(custom);

        new MaterialAlertDialogBuilder(this)
                .setTitle("🏷 Tags · " + s.title)
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < group.getChildCount(); i++) {
                        com.google.android.material.chip.Chip c = (com.google.android.material.chip.Chip) group.getChildAt(i);
                        if (c.isChecked()) sb.append(c.getText()).append(',');
                    }
                    sb.append(custom.getText());
                    DbHelper.get(this).setTags(s.title, sb.toString());
                    NoteWriter.writeAsync(this);
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
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

    private void showMergePicker(DbHelper.Series s) {
        List<String> others = new java.util.ArrayList<>();
        for (DbHelper.Series o : DbHelper.get(this).getSeries()) if (!o.title.equalsIgnoreCase(s.title)) others.add(o.title);
        if (others.isEmpty()) {
            Toast.makeText(this, "Aucune autre série à fusionner", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = others.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Fusionner « " + s.title + " » avec…")
                .setItems(items, (d, w) -> new MaterialAlertDialogBuilder(this)
                        .setMessage("Les chapitres de « " + items[w] + " » seront rangés dans « " + s.title + " ».")
                        .setPositiveButton("Fusionner", (d2, w2) -> {
                            DbHelper.get(this).mergeSeries(items[w], s.title);
                            Covers.merge(this, items[w], s.title);
                            NoteWriter.writeAsync(this);
                            refresh();
                        })
                        .setNegativeButton("Annuler", null)
                        .show())
                .setNegativeButton("Annuler", null)
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

    // ------------------------------------------------------------------ Historique (bottom sheet)

    private void showHistory(DbHelper.Series s) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_history, null);
        sheet.setContentView(v);

        SeriesAdapter.bindCover(v.findViewById(R.id.sAvatar), v.findViewById(R.id.sCover), s.title);
        v.findViewById(R.id.sCoverButton).setOnClickListener(b -> {
            sheet.dismiss();
            showCoverMenu(s);
        });
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

        TextView tagsView = v.findViewById(R.id.sTags);
        tagsView.setText(s.tags.isEmpty() ? "" : "🏷 " + s.tags);
        tagsView.setVisibility(s.tags.isEmpty() ? View.GONE : View.VISIBLE);
        DbHelper.Meta meta = DbHelper.get(this).getMeta(s.title);
        ((TextView) v.findViewById(R.id.sLinkButton)).setText(
                meta != null && meta.titleNo != null ? "⚡ Lié ✓" : "⚡ Alertes");
        v.findViewById(R.id.sResume).setOnClickListener(b -> {
            sheet.dismiss();
            Resume.open(this, s.title);
        });
        v.findViewById(R.id.sTagButton).setOnClickListener(b -> {
            sheet.dismiss();
            showTagEditor(s);
        });
        v.findViewById(R.id.sMergeButton).setOnClickListener(b -> {
            sheet.dismiss();
            showMergePicker(s);
        });
        v.findViewById(R.id.sLinkButton).setOnClickListener(b -> {
            sheet.dismiss();
            showLinkDialog(s);
        });

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
                    Covers.delete(this, s.title);
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
