package com.lois.webtoontracker;

import android.content.Context;
import android.net.ConnectivityManager;

/** État de la connexion : les téléchargements lourds attendent le Wi-Fi. */
final class Net {

    private Net() {
    }

    /** true en données mobiles (ou partage de connexion, réseau inconnu) : connexion facturée au volume. */
    static boolean metered(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }
}
