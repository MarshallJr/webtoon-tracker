package com.lois.webtoontracker;

import android.app.Activity;
import android.os.Bundle;

/** Relais invisible du widget : ouvre le chapitre ET déclenche la bannière "tu t'étais arrêté vers…". */
public class ResumeActivity extends Activity {

    public static final String EXTRA_TITLE = "title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) Resume.open(this, title);
        finish();
        overridePendingTransition(0, 0);
    }
}
