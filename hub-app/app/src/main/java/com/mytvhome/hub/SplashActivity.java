package com.mytvhome.hub;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.WebView;

/** The "Mike's TV" start-up logo: a few seconds of animation, then back to whatever was underneath (Home). */
public class SplashActivity extends Activity {

    private static final String SPLASH_URL = MainActivity.ASSET_PREFIX + "splash.html";
    private static final long DURATION_MS = 4200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(false);
        web.setFocusable(false);
        setContentView(web);
        web.loadUrl(SPLASH_URL);
        handler.postDelayed(this::finish, DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
