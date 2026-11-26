package com.lois.webtoontracker;

import static org.junit.Assert.assertNotNull;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/**
 * Ouvre chaque écran avec des données d'essai, vérifie qu'il s'affiche sans erreur et l'enregistre en
 * PNG (build/shots) pour relire le rendu. Téléphone : 412 × 915 dp.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "fr-rFR-w412dp-h915dp-xhdpi")
public class UiShotsTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        ctx = Shots.prepare();
    }

    private MainActivity main() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shots.idle(2500);
        return a;
    }

    @Test
    public void home() throws Exception {
        MainActivity a = main();
        Shots.screen(a, "phone_home");
        Shots.page(a.findViewById(R.id.pageHome), "phone_home_full", 0xFF000000);
    }

    @Test
    public void library() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.navLibrary).performClick();
        Shots.idle(1500);
        Shots.screen(a, "phone_library");
        Shots.page(a.findViewById(R.id.pageLibrary), "phone_library_full", 0xFF000000);
    }

    @Test
    public void discover() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.navDiscover).performClick();
        Shots.idle(6000);   // profil, « Pour toi » (cache), première page AniList (réseau de la CI)
        Shots.screen(a, "phone_discover");
    }

    @Test
    public void discoverSheet() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.navDiscover).performClick();
        Shots.idle(3000);
        org.json.JSONArray fy = new org.json.JSONArray(
                ctx.getSharedPreferences("discover", Context.MODE_PRIVATE).getString("for_you", "[]"));
        a.discoverPage().openMedia(Discover.Media.fromJson(fy.getJSONObject(2)));
        Shots.idle(6000);   // fiche AniList (réseau de la CI)
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("Fiche Découvrir", d);
        Shots.dialog(d, a, "phone_sheet");
    }

    @Test
    public void activity() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.navActivity).performClick();
        Shots.idle(1500);
        Shots.screen(a, "phone_activity");
        Shots.page(a.findViewById(R.id.pageActivity), "phone_activity_full", 0xFF000000);
    }

    @Test
    public void detail() throws Exception {
        Intent i = new Intent(ctx, DetailActivity.class).putExtra(DetailActivity.EXTRA_TITLE, Fixtures.TITLES[0]);
        DetailActivity d = Robolectric.buildActivity(DetailActivity.class, i).setup().get();
        Shots.idle(2500);
        Shots.screen(d, "phone_detail");
        View scroll = d.findViewById(R.id.dScroll);
        if (scroll != null) Shots.page(scroll, "phone_detail_full", 0xFF000000);
    }

    @Test
    public void settings() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.settingsButton).performClick();
        Shots.idle(800);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("Réglages", d);
        Shots.dialog(d, a, "phone_settings");
    }

    @Test
    public void updates() throws Exception {
        MainActivity a = main();
        a.findViewById(R.id.homeBell).performClick();
        Shots.idle(1000);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("Nouveautés", d);
        Shots.dialog(d, a, "phone_updates");
    }
}
