package com.lois.webtoontracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Captures sur tablette (paysage, 1280 × 800 dp) : largeur de contenu, grilles, barre. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-land-mdpi")
public class UiShotsTabletTest {

    @Before
    public void setUp() throws Exception {
        Shots.prepare();
    }

    @Test
    public void homeAndLibrary() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shots.idle(2500);
        Shots.screen(a, "tablet_home");
        a.findViewById(R.id.navLibrary).performClick();
        Shots.idle(1500);
        Shots.screen(a, "tablet_library");
        a.findViewById(R.id.navDiscover).performClick();
        Shots.idle(6000);
        Shots.screen(a, "tablet_discover");
    }
}
