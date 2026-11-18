package com.lois.webtoontracker;

import android.app.Application;
import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Enregistre tout crash pour l'afficher au prochain lancement (onglet Diagnostic). */
public class App extends Application {

    public static final String PREFS_CRASH = "crash";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveError(this, "Crash", error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public static void saveError(Context ctx, String where, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > 4000) trace = trace.substring(0, 4000);
        ctx.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE).edit()
                .putString("trace", where + " — " + trace)
                .putLong("at", System.currentTimeMillis())
                .putBoolean("seen", !"Crash".equals(where)) // un vrai crash s'affiche au prochain lancement
                .commit();
    }
}
